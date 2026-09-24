/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.prism.path.ItemName;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;

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
 * <p>Last-applied comes from version control: {@code git show <lastRev>:<path>} is exactly the file as it was
 * when midgie last deployed it. The removal set is then the <em>delete side</em> of an ordinary
 * {@code DiffEngine} run with last-applied in the live slot, so no new diff logic is needed.
 *
 * <p>It works per <b>object</b>, on both sides: the union of the files that describe it now against the union
 * of the files that described it then, deleted ones included. That is what makes it correct for objects
 * assembled from several files — deleting one branding fragment of {@code SystemConfiguration} yields exactly
 * the values no surviving file still declares, and a value merely moved between two files of one object never
 * appears at all, because both sides were combined before they were compared. The object itself is never a
 * candidate for deletion (it is often {@code indestructible} anyway); one whose every file has gone is
 * reported as an orphan instead.
 */
final class RemovalDetector {

    private static final Logger LOG = LoggerFactory.getLogger(RemovalDetector.class);

    /**
     * One value that was deployed before and is no longer declared. Carries enough to both report it and —
     * for {@code --prune} — build the delete itself: the owning object's type and identity, the item's
     * definition and path, and the value.
     */
    static final class Removal {
        final String objectLabel;   // which object it belongs to
        final String path;          // item path of the value
        final String value;         // rendered value, for the report
        final String sourceFile;    // the fragment that used to declare it

        final Class<?> objectClass; // owning object's compile-time class
        final String oid;           // its OID, when the fragment carried one
        final String objectName;    // its name, for resolving an OID-less object
        final ItemDefinition<?> definition; // definition of the item the value belongs to
        final ItemPath itemPath;    // the same path, unrendered
        final PrismValue prismValue; // the value itself, for the delete
        final DeployObject unit;    // the object in the tree it belongs to, whose PATCH carries the delete

        Removal(String objectLabel, String path, String value, String sourceFile, Class<?> objectClass,
                String oid, String objectName, ItemDefinition<?> definition, ItemPath itemPath,
                PrismValue prismValue) {
            this(objectLabel, path, value, sourceFile, objectClass, oid, objectName, definition, itemPath,
                    prismValue, null);
        }

        Removal(String objectLabel, String path, String value, String sourceFile, Class<?> objectClass,
                String oid, String objectName, ItemDefinition<?> definition, ItemPath itemPath,
                PrismValue prismValue, DeployObject unit) {
            this.unit = unit;
            this.objectLabel = objectLabel;
            this.path = path;
            this.value = value;
            this.sourceFile = sourceFile;
            this.objectClass = objectClass;
            this.oid = oid;
            this.objectName = objectName;
            this.definition = definition;
            this.itemPath = itemPath;
            this.prismValue = prismValue;
        }

        /** Whether this removal can be turned into a delta (it needs the item's definition). */
        boolean isPrunable() {
            return definition != null && objectClass != null;
        }

        @Override
        public String toString() {
            return path + (value != null && !value.isBlank() ? " = " + value : "") + " (was in " + sourceFile + ")";
        }
    }

    /**
     * Build the {@code ObjectDelta} that deletes {@code removals} from one object. Only ever contains delete
     * operations for values that were in last-applied, which is what makes pruning structurally unable to
     * touch accrued or foreign state.
     */
    static ObjectDelta<? extends Objectable> pruneDelta(PrismContext prismContext, String oid,
            List<Removal> removals) throws SchemaException {
        return pruneDelta(prismContext, oid, removals, null);
    }

    /**
     * As above, with each value to delete taken from {@code live} where it holds a real-value match: a
     * container value recovered from git usually carries no id, and deleting the server's own copy — id
     * included — says which entry is meant rather than leaving midPoint to find it by content.
     */
    @SuppressWarnings({"unchecked", "rawtypes"}) // the delta's item types are matched by construction
    static ObjectDelta<? extends Objectable> pruneDelta(PrismContext prismContext, String oid,
            List<Removal> removals, PrismObject<? extends Objectable> live) throws SchemaException {
        List<ItemDelta> deltas = new ArrayList<>();
        Class<?> objectClass = null;
        for (Removal removal : removals) {
            if (!removal.isPrunable()) {
                continue;
            }
            objectClass = removal.objectClass;
            PrismValue held = (live != null) ? heldValue(live, removal) : null;
            ItemDelta delta = removal.definition.createEmptyDelta(removal.itemPath);
            delta.addValueToDelete((held != null ? held : removal.prismValue).clone());
            deltas.add(delta);
        }
        if (deltas.isEmpty() || objectClass == null) {
            return null;
        }
        return prismContext.deltaFactory().object()
                .createModifyDelta(oid, deltas, (Class<? extends Objectable>) objectClass);
    }

    /**
     * The value {@code object} holds that is the same, by real value, as the one {@code removal} names — or
     * {@code null} when it holds none. This is the ownership test: a removal only ever deletes a value the
     * object still has in exactly the form midgie deployed it.
     */
    static PrismValue heldValue(PrismObject<? extends Objectable> object, Removal removal) {
        Item<?, ?> item = object.findItem(removal.itemPath.removeIds());
        if (item == null) {
            return null;
        }
        for (PrismValue value : item.getValues()) {
            if (EquivalenceStrategy.REAL_VALUE.equals(value, removal.prismValue)) {
                return value;
            }
        }
        return null;
    }

    /** An object that left the tree entirely: reported, never taken apart value by value. */
    static final class Orphan {
        final String objectLabel;
        final String sourceFile;

        Orphan(String objectLabel, String sourceFile) {
            this.objectLabel = objectLabel;
            this.sourceFile = sourceFile;
        }
    }

    private final PrismContext prismContext;
    private final GitRevision git;
    private final String lastRevision;
    private final List<Orphan> orphaned = new ArrayList<>();

    RemovalDetector(PrismContext prismContext, GitRevision git, String lastRevision) {
        this.prismContext = prismContext;
        this.git = git;
        this.lastRevision = lastRevision;
    }

    /**
     * Values deployed at the recorded revision that {@code units} no longer declare — whether a value was
     * dropped from a file that is still there, or a whole file was deleted.
     *
     * <p>Both sides are <b>whole objects</b>: what the object's files say now, against what the same object's
     * files said at the recorded revision — including files that have since been deleted. That is what makes
     * the awkward cases fall out instead of needing compensation. A value moved from one file of an object to
     * another was never removed and never appears; a deleted fragment contributes exactly the values no
     * surviving file still declares, because the two sides are unions rather than file pairs.
     *
     * @param units the objects discovered in the tree now
     */
    List<Removal> detect(List<DeployObject> units) {
        List<Removal> removals = new ArrayList<>();
        List<LastApplied> lastApplied = lastAppliedObjects(units);

        for (DeployObject unit : units) {
            LastApplied previous = claim(lastApplied, identityKeysOf(unit.object()));
            if (previous == null) {
                continue; // an object that did not exist at the recorded revision
            }
            PrismObject<? extends Objectable> before = previous.union();
            if (before == null) {
                continue;
            }
            collect(removals, before, unit, identityKeyOf(before), previous);
        }

        for (LastApplied previous : lastApplied) {
            if (previous.claimed) {
                continue;
            }
            // Nothing in the tree declares this object any more. Its values are not individually "removed" —
            // the whole object left, and taking it apart value by value would strip its name and leave a
            // mangled object behind. Deleting objects is deliberately out of scope, so say so and leave it.
            PrismObject<? extends Objectable> before = previous.union();
            if (before != null) {
                orphaned.add(new Orphan(identityKeyOf(before), previous.sourceLabel()));
            }
        }

        LOG.debug("detected {} removal(s) and {} orphaned object(s) against revision {}",
                removals.size(), orphaned.size(), lastRevision);
        return removals;
    }

    /**
     * One object as it stood at the recorded revision: the files that described it then, and their union.
     *
     * <p>Kept as a group rather than a single object because the tree's own layout applies to history too —
     * an object assembled from three files was three files then as well, and comparing the current object
     * against any one of them would report the other two's contributions as removed.
     */
    private final class LastApplied {

        private final List<PrismObject<? extends Objectable>> objects = new ArrayList<>();
        private final List<String> paths = new ArrayList<>();
        private final Set<String> keys = new LinkedHashSet<>();
        private boolean claimed;
        private boolean united;
        private PrismObject<? extends Objectable> union;

        void add(PrismObject<? extends Objectable> object, String path) {
            objects.add(object);
            paths.add(path);
            keys.addAll(identityKeysOf(object));
        }

        /** The combined object, or {@code null} when its files contradict each other past combining. */
        PrismObject<? extends Objectable> union() {
            if (united) {
                return union;
            }
            united = true;
            union = combine();
            return union;
        }

        private PrismObject<? extends Objectable> combine() {
            // The OID is an attribute rather than an item, so it does not travel through the union: a file
            // that names the object without repeating its OID would otherwise produce an unaddressable
            // "before" side, and every value it declared would read as a removal on an object nothing could
            // then be pruned from.
            String oid = null;
            for (PrismObject<? extends Objectable> object : objects) {
                if (object.getOid() != null && !object.getOid().isBlank()) {
                    oid = object.getOid();
                    break;
                }
            }
            List<Fragment> fragments = new ArrayList<>();
            for (int i = 0; i < objects.size(); i++) {
                PrismObject<? extends Objectable> object = objects.get(i);
                if (oid != null && (object.getOid() == null || object.getOid().isBlank())) {
                    object.setOid(oid);
                }
                fragments.add(Fragment.of(object, new File(paths.get(i))));
            }
            try {
                return FragmentUnion.of(fragments);
            } catch (Exception e) {
                // Not fatal: history that cannot be combined only costs this object's detection, and
                // failing the deploy over what a past revision looked like would be the wrong trade.
                LOG.trace("could not combine {} at revision {}", paths, lastRevision, e);
                return null;
            }
        }

        String sourceLabel() {
            return (paths.size() == 1) ? paths.get(0) : paths.get(0) + " (+" + (paths.size() - 1) + " more)";
        }

        /**
         * Which of this object's files declared {@code value} at {@code path} when it was last deployed.
         *
         * <p>The comparison is per object now, but the report is not: "was in role-extra.xml" is what tells
         * an operator reviewing a prune where the value came from, and naming the whole file set instead
         * would lose exactly the part they act on. Values are compared with {@code REAL_VALUE} — the same
         * test the diff uses — never by rendered text.
         */
        String sourceOf(ItemPath path, PrismValue value) {
            ItemPath idFree = path.removeIds();
            for (int i = 0; i < objects.size(); i++) {
                List<PrismValue> declared = new ArrayList<>();
                try {
                    valuesAt(objects.get(i).getValue(), idFree, declared);
                } catch (Exception e) {
                    LOG.trace("could not read {} from {} at revision {}", idFree, paths.get(i), lastRevision, e);
                    continue;
                }
                for (PrismValue candidate : declared) {
                    if (EquivalenceStrategy.REAL_VALUE.equals(candidate, value)) {
                        return paths.get(i);
                    }
                }
            }
            return sourceLabel(); // a value no single file can be credited with: name them all
        }
    }

    /**
     * Collect every value held at {@code path} (which carries no container ids), descending into <em>all</em>
     * values of every container along the way — a multi-valued container on the path must contribute each of
     * its values, not just the first.
     */
    private static void valuesAt(PrismContainerValue<?> parent, ItemPath path, List<PrismValue> out) {
        ItemName first = path.firstName();
        if (first == null) {
            return;
        }
        Item<?, ?> item = parent.findItem(first);
        if (item == null) {
            return;
        }
        ItemPath rest = path.rest();
        if (ItemPath.isEmpty(rest)) {
            out.addAll(item.getValues());
            return;
        }
        for (PrismValue value : item.getValues()) {
            if (value instanceof PrismContainerValue<?> container) {
                valuesAt(container, rest, out);
            }
        }
    }

    /**
     * Every object the tree described at the recorded revision: the files that are still there (read as they
     * were then) plus the ones deleted since, grouped by identity.
     */
    private List<LastApplied> lastAppliedObjects(List<DeployObject> units) {
        Map<String, LastApplied> byKey = new LinkedHashMap<>();
        List<LastApplied> groups = new ArrayList<>();
        for (String path : lastAppliedPaths(units)) {
            PrismObject<? extends Objectable> object = parseAt(path);
            if (object == null) {
                continue; // a file that did not exist yet, or cannot be read as an object
            }
            List<String> keys = identityKeysOf(object);
            LastApplied group = null;
            for (String key : keys) {
                group = byKey.get(key);
                if (group != null) {
                    break;
                }
            }
            if (group == null) {
                group = new LastApplied();
                groups.add(group);
            }
            group.add(object, path);
            // Under every form it identifies itself by, since two files of one object need not agree on
            // which one they use.
            for (String key : group.keys) {
                byKey.put(key, group);
            }
        }
        return groups;
    }

    /** The last-applied object sharing any identity form with {@code keys}, marked as claimed. */
    private static LastApplied claim(List<LastApplied> lastApplied, List<String> keys) {
        for (LastApplied candidate : lastApplied) {
            for (String key : keys) {
                if (candidate.keys.contains(key)) {
                    candidate.claimed = true;
                    return candidate;
                }
            }
        }
        return null;
    }

    /** Repository-relative paths that described objects at the recorded revision: today's plus the deleted. */
    private List<String> lastAppliedPaths(List<DeployObject> units) {
        List<String> paths = new ArrayList<>();
        Set<String> present = new LinkedHashSet<>();
        for (DeployObject unit : units) {
            for (File file : unit.sourceFiles()) {
                String relative = git.repoRelative(file);
                if (relative != null && present.add(relative)) {
                    paths.add(relative);
                }
            }
        }
        for (String deleted : git.deletedSince(lastRevision)) {
            // A path both "deleted" and present again is not a removal.
            if (present.add(deleted)) {
                paths.add(deleted);
            }
        }
        return paths;
    }

    /** Objects whose every fragment left the tree; reported so the operator can retire them deliberately. */
    List<Orphan> orphaned() {
        return orphaned;
    }

    /** Add the values present in {@code lastApplied} but absent from {@code desired}. */
    private void collect(List<Removal> removals, PrismObject<? extends Objectable> lastApplied,
            DeployObject unit, String objectLabel, LastApplied previous) {
        PrismObject<? extends Objectable> desired = unit.object();
        DiffEngine.DiffResult result;
        try {
            // last-applied in the live slot: the delete side is "was deployed, no longer declared".
            result = new DiffEngine(prismContext).diff(desired, lastApplied, EquivalenceStrategy.REAL_VALUE,
                    DiffEngine.Scope.FULL, DiffEngine.MultiValueStrategy.replaceAll());
        } catch (Exception e) {
            LOG.trace("could not compare {} against revision {}", previous.sourceLabel(), lastRevision, e);
            return;
        }
        Class<?> objectClass = lastApplied.getCompileTimeClass();
        String oid = lastApplied.getOid();
        String objectName = (lastApplied.getName() != null) ? lastApplied.getName().getOrig() : null;
        for (ItemDelta<?, ?> mod : result.delta().getModifications()) {
            if (mod.getValuesToDelete() == null) {
                continue;
            }
            String path = mod.getPath().toString();
            if (isUndeletable(mod.getPath(), mod.getDefinition())) {
                LOG.trace("{} is required and is never a removal candidate ({})", path,
                        previous.sourceLabel());
                continue;
            }
            for (PrismValue value : mod.getValuesToDelete()) {
                String sourceFile = previous.sourceOf(mod.getPath(), value);
                removals.add(new Removal(objectLabel, path, render(value), sourceFile, objectClass, oid,
                        objectName, mod.getDefinition(), mod.getPath(), value, unit));
            }
        }
    }

    /**
     * Items that must never be proposed for deletion, however the fragments are laid out. An object's
     * {@code name} is the case that bites: when a deleted fragment carried {@code <name>} and the surviving
     * sibling identifies the object by OID alone — a legal and common layout — dismantling the deleted
     * fragment would put {@code delete name} in the prune delta. midPoint's repository rejects that, failing
     * the object's whole prune group with an obscure constraint error, and anything that did get through
     * would leave the object nameless.
     */
    private static boolean isUndeletable(ItemPath path, ItemDefinition<?> definition) {
        ItemPath idFree = path.removeIds();
        if (idFree.size() == 1 && ObjectType.F_NAME.equivalent(idFree.firstName())) {
            return true;
        }
        return definition != null && definition.isMandatory();
    }

    private PrismObject<? extends Objectable> parseAt(String repoRelativePath) {
        String content = git.fileAt(lastRevision, repoRelativePath);
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            return prismContext.parseObject(content);
        } catch (Exception e) {
            LOG.trace("could not parse {} at revision {}", repoRelativePath, lastRevision, e);
            return null;
        }
    }

    /**
     * The key the report and the prune grouping settle on: the OID when there is one, else {@code Type/Name}.
     * It has to be derived the same way for an object in the tree and for one recovered from git, or an
     * OID-less object would never line up with its own history.
     */
    private static String identityKeyOf(PrismObject<? extends Objectable> object) {
        if (object.getOid() != null && !object.getOid().isBlank()) {
            return object.getOid();
        }
        return typeAndName(object);
    }

    /**
     * <em>Every</em> form this object can be identified by — the OID and {@code Type/Name}, whichever it
     * carries. Grouping fragments needs all of them, because two fragments of one object need not identify it
     * the same way; only the report needs to settle on the single key {@link #identityKeyOf} picks.
     */
    private static List<String> identityKeysOf(PrismObject<? extends Objectable> object) {
        List<String> keys = new ArrayList<>(2);
        if (object.getOid() != null && !object.getOid().isBlank()) {
            keys.add(object.getOid());
        }
        if (object.getName() != null && object.getCompileTimeClass() != null) {
            keys.add(typeAndName(object));
        }
        return keys;
    }

    private static String typeAndName(PrismObject<? extends Objectable> object) {
        String name = (object.getName() != null) ? object.getName().getOrig() : "?";
        String type = (object.getCompileTimeClass() != null)
                ? object.getCompileTimeClass().getSimpleName() : "?";
        return type + "/" + name;
    }

    /**
     * A one-line preview of the value, for the report and for the destructive prompt that follows it.
     *
     * <p>A container has no useful {@code getRealValue()}, and prism's debug {@code toString} cut at 120
     * characters gave the operator something like
     * {@code assignment = PCV(null):[PrismReferenceImpl({http://…}targetRef):[PRV(oid=22222222-2…} —
     * truncated mid-token, with the identifying part off the end. Its own items are named instead.
     */
    private static String render(PrismValue value) {
        try {
            return describe(value);
        } catch (RuntimeException e) {
            // A preview is never worth a failed run. This is the report of what is about to be deleted; if
            // one value cannot be summarised, the operator is better served by the path and the count than
            // by an aborted deploy.
            LOG.trace("could not render a removed value", e);
            return "";
        }
    }

    private static String describe(PrismValue value) {
        if (value instanceof PrismContainerValue<?> container) {
            List<String> parts = new ArrayList<>();
            for (Item<?, ?> item : container.getItems()) {
                String name = item.getElementName().getLocalPart();
                // Only a single-valued item has "the" real value. Asking a multi-valued one throws
                // ("Attempt to get single value from a multivalued container panel"), which is how a GUI
                // page holding two panels used to kill the whole run from inside this report — so the count
                // is shown instead, which is what matters when reviewing a deletion anyway.
                int count = item.getValues().size();
                if (count == 1) {
                    Object real = item.getRealValue();
                    if (real != null) {
                        parts.add(name + "=" + abbreviate(String.valueOf(real)));
                    }
                } else if (count > 1) {
                    parts.add(name + " ×" + count);
                }
                if (parts.size() == 4) {
                    parts.add("…");
                    break;
                }
            }
            String id = (container.getId() != null) ? "[" + container.getId() + "] " : "";
            return id + "{" + String.join(", ", parts) + "}";
        }
        Object real = value.getRealValue();
        return (real == null) ? "" : abbreviate(String.valueOf(real));
    }

    /** Shorten a rendered value without cutting mid-token where a word boundary is close by. */
    private static String abbreviate(String text) {
        if (text.length() <= 120) {
            return text;
        }
        int space = text.lastIndexOf(' ', 120);
        return text.substring(0, space > 80 ? space : 120) + "…";
    }
}
