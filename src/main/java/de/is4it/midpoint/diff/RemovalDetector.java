/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;

/**
 * Finds values midgie deployed previously that the desired tree no longer declares — the third leg of a
 * three-way merge.
 *
 * <p>Diffing desired against live can only ever say "add this" or "change that": a value you deleted from a
 * file simply stops being mentioned, and under {@code merge} the delta comes out empty, so the value lives on
 * unnoticed. Comparing desired against <b>last-applied</b> instead answers the missing question — a value
 * present then and absent now is one you removed on purpose, whereas a value the server holds but midgie
 * never applied is accrued or someone else's and must be left alone.
 *
 * <p>Last-applied comes from version control: {@code git show <lastRev>:<path>} is exactly the fragment as it
 * was when midgie last deployed it. The removal set is then the <em>delete side</em> of an ordinary
 * {@code DiffEngine} run with last-applied in the live slot, so no new diff logic is needed.
 *
 * <p>This works per <b>fragment file</b>, which is what makes it correct for objects assembled from several
 * files. Deleting one branding fragment of {@code SystemConfiguration} yields exactly the values that file
 * contributed — the object itself is never a candidate for deletion (it is often
 * {@code indestructible} anyway), and values a sibling fragment still declares are subtracted.
 */
final class RemovalDetector {

    private static final Logger LOG = LoggerFactory.getLogger(RemovalDetector.class);

    /** One value that was deployed before and is no longer declared. */
    static final class Removal {
        final String objectLabel;   // which object it belongs to
        final String path;          // item path of the value
        final String value;         // rendered value, for the report
        final String sourceFile;    // the fragment that used to declare it

        Removal(String objectLabel, String path, String value, String sourceFile) {
            this.objectLabel = objectLabel;
            this.path = path;
            this.value = value;
            this.sourceFile = sourceFile;
        }

        @Override
        public String toString() {
            return path + (value != null && !value.isBlank() ? " = " + value : "") + " (was in " + sourceFile + ")";
        }
    }

    private final PrismContext prismContext;
    private final GitRevision git;
    private final String lastRevision;

    RemovalDetector(PrismContext prismContext, GitRevision git, String lastRevision) {
        this.prismContext = prismContext;
        this.git = git;
        this.lastRevision = lastRevision;
    }

    /**
     * Values deployed at the recorded revision that {@code units} no longer declare — both from files edited
     * since (a value dropped out) and from files deleted since (everything they declared).
     *
     * @param units the fragments discovered in the tree now
     */
    List<Removal> detect(List<Fragment> units) {
        List<Removal> removals = new ArrayList<>();
        Map<String, Set<String>> stillDeclared = declarationsByObject(units);

        for (Fragment unit : units) {
            String relative = git.repoRelative(unit.sourceFile());
            if (relative == null) {
                continue; // outside the repository: nothing to compare against
            }
            PrismObject<? extends Objectable> lastApplied = parseAt(relative);
            if (lastApplied == null) {
                continue; // new file since the last deploy
            }
            collect(removals, lastApplied, unit.object(), unit.identityLabel(), relative, stillDeclared);
        }

        for (String deleted : deletedFragments(units)) {
            PrismObject<? extends Objectable> lastApplied = parseAt(deleted);
            if (lastApplied == null) {
                continue;
            }
            // The whole fragment is gone: everything it declared is a removal, so diff it against nothing.
            PrismObject<? extends Objectable> empty = emptyLike(lastApplied);
            if (empty == null) {
                continue;
            }
            collect(removals, lastApplied, empty, labelOf(lastApplied), deleted, stillDeclared);
        }

        LOG.debug("detected {} removal(s) against revision {}", removals.size(), lastRevision);
        return removals;
    }

    /**
     * Add the values present in {@code lastApplied} but absent from {@code desired}, skipping any a surviving
     * fragment of the same object still declares.
     */
    private void collect(List<Removal> removals, PrismObject<? extends Objectable> lastApplied,
            PrismObject<? extends Objectable> desired, String objectLabel, String sourceFile,
            Map<String, Set<String>> stillDeclared) {
        DiffEngine.DiffResult result;
        try {
            // last-applied in the live slot: the delete side is "was deployed, no longer declared".
            result = new DiffEngine(prismContext).diff(desired, lastApplied, EquivalenceStrategy.REAL_VALUE,
                    DiffEngine.Scope.FULL, DiffEngine.MultiValueStrategy.replaceAll());
        } catch (Exception e) {
            LOG.debug("could not compare {} against revision {}", sourceFile, lastRevision, e);
            return;
        }
        Set<String> survivors = stillDeclared.getOrDefault(objectLabel, Set.of());
        for (ItemDelta<?, ?> mod : result.delta().getModifications()) {
            if (mod.getValuesToDelete() == null) {
                continue;
            }
            String path = mod.getPath().toString();
            for (PrismValue value : mod.getValuesToDelete()) {
                String rendered = render(value);
                if (survivors.contains(declaration(path, rendered))) {
                    LOG.debug("{} at {} still declared by another fragment; not a removal", rendered, path);
                    continue;
                }
                removals.add(new Removal(objectLabel, path, rendered, sourceFile));
            }
        }
    }

    /** Everything the current tree declares, per object, so a sibling fragment's values are not reported. */
    private Map<String, Set<String>> declarationsByObject(List<Fragment> units) {
        Map<String, Set<String>> declared = new LinkedHashMap<>();
        for (Fragment unit : units) {
            Set<String> target = declared.computeIfAbsent(unit.identityLabel(), key -> new LinkedHashSet<>());
            PrismObject<? extends Objectable> empty = emptyLike(unit.object());
            if (empty == null) {
                continue;
            }
            try {
                // Diffing the fragment against an empty object enumerates everything it declares, in exactly
                // the same shape the removal side produces, so the two sets compare directly.
                DiffEngine.DiffResult all = new DiffEngine(prismContext).diff(unit.object(), empty,
                        EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.FULL,
                        DiffEngine.MultiValueStrategy.replaceAll());
                for (ItemDelta<?, ?> mod : all.delta().getModifications()) {
                    addValues(target, mod.getPath().toString(), mod.getValuesToAdd());
                    addValues(target, mod.getPath().toString(), mod.getValuesToReplace());
                }
            } catch (Exception e) {
                LOG.debug("could not enumerate declarations of {}", unit.sourceFile(), e);
            }
        }
        return declared;
    }

    private static void addValues(Set<String> target, String path, java.util.Collection<? extends PrismValue> values) {
        if (values == null) {
            return;
        }
        for (PrismValue value : values) {
            target.add(declaration(path, render(value)));
        }
    }

    /** Repository-relative fragment paths deleted since the recorded revision. */
    private List<String> deletedFragments(List<Fragment> units) {
        List<String> deleted = git.deletedSince(lastRevision);
        Set<String> present = new LinkedHashSet<>();
        for (Fragment unit : units) {
            String relative = git.repoRelative(unit.sourceFile());
            if (relative != null) {
                present.add(relative);
            }
        }
        deleted.removeIf(present::contains); // a path both "deleted" and present again is not a removal
        return deleted;
    }

    private PrismObject<? extends Objectable> parseAt(String repoRelativePath) {
        String content = git.fileAt(lastRevision, repoRelativePath);
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            return prismContext.parseObject(content);
        } catch (Exception e) {
            LOG.debug("could not parse {} at revision {}", repoRelativePath, lastRevision, e);
            return null;
        }
    }

    /** An object of the same type and identity carrying nothing else — the "declares nothing" side. */
    private PrismObject<? extends Objectable> emptyLike(PrismObject<? extends Objectable> template) {
        try {
            PrismObject<? extends Objectable> empty = template.getDefinition().instantiate();
            empty.setOid(template.getOid());
            return empty;
        } catch (Exception e) {
            LOG.debug("could not instantiate an empty {}", template.getCompileTimeClass(), e);
            return null;
        }
    }

    private static String labelOf(PrismObject<? extends Objectable> object) {
        String name = (object.getName() != null) ? object.getName().getOrig() : null;
        String type = (object.getCompileTimeClass() != null)
                ? object.getCompileTimeClass().getSimpleName() : "?";
        return (object.getOid() != null) ? object.getOid() : type + "/" + (name != null ? name : "?");
    }

    private static String declaration(String path, String value) {
        return path + "=" + value;
    }

    private static String render(PrismValue value) {
        Object real = value.getRealValue();
        if (real == null) {
            return "";
        }
        String text = String.valueOf(real);
        return (text.length() > 120) ? text.substring(0, 120) + "…" : text;
    }
}
