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

        Removal(String objectLabel, String path, String value, String sourceFile, Class<?> objectClass,
                String oid, String objectName, ItemDefinition<?> definition, ItemPath itemPath,
                PrismValue prismValue) {
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
    @SuppressWarnings({"unchecked", "rawtypes"}) // the delta's item types are matched by construction
    static ObjectDelta<? extends Objectable> pruneDelta(PrismContext prismContext, String oid,
            List<Removal> removals) throws SchemaException {
        List<ItemDelta> deltas = new ArrayList<>();
        Class<?> objectClass = null;
        for (Removal removal : removals) {
            if (!removal.isPrunable()) {
                continue;
            }
            objectClass = removal.objectClass;
            ItemDelta delta = removal.definition.createEmptyDelta(removal.itemPath);
            delta.addValueToDelete(removal.prismValue.clone());
            deltas.add(delta);
        }
        if (deltas.isEmpty() || objectClass == null) {
            return null;
        }
        return prismContext.deltaFactory().object()
                .createModifyDelta(oid, deltas, (Class<? extends Objectable>) objectClass);
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
     * Values deployed at the recorded revision that {@code units} no longer declare — both from files edited
     * since (a value dropped out) and from files deleted since (everything they declared).
     *
     * @param units the fragments discovered in the tree now
     */
    List<Removal> detect(List<Fragment> units) {
        List<Removal> removals = new ArrayList<>();
        Map<String, List<Fragment>> byIdentity = fragmentsByObject(units);

        for (Fragment unit : units) {
            String relative = git.repoRelative(unit.sourceFile());
            if (relative == null) {
                continue; // outside the repository: nothing to compare against
            }
            PrismObject<? extends Objectable> lastApplied = parseAt(relative);
            if (lastApplied == null) {
                continue; // new file since the last deploy
            }
            collect(removals, lastApplied, unit.object(), identityKeyOf(unit.object()), relative,
                    siblingsOf(byIdentity, unit.object()));
        }

        for (String deleted : deletedFragments(units)) {
            PrismObject<? extends Objectable> lastApplied = parseAt(deleted);
            if (lastApplied == null) {
                continue;
            }
            String label = identityKeyOf(lastApplied);
            List<Fragment> siblings = siblingsOf(byIdentity, lastApplied);
            if (siblings.isEmpty()) {
                // No fragment declares this object any more. Its values are not individually "removed" — the
                // whole object left the tree, and taking it apart value by value would strip its name and
                // leave a mangled object behind. Deleting objects is deliberately out of scope, so say so and
                // leave it alone.
                orphaned.add(new Orphan(label, deleted));
                continue;
            }
            // A sibling still declares the object, so this fragment's own contribution is a real removal.
            PrismObject<? extends Objectable> empty = emptyLike(lastApplied);
            if (empty == null) {
                continue;
            }
            collect(removals, lastApplied, empty, label, deleted, siblings);
        }

        LOG.debug("detected {} removal(s) and {} orphaned object(s) against revision {}",
                removals.size(), orphaned.size(), lastRevision);
        return removals;
    }

    /** Objects whose every fragment left the tree; reported so the operator can retire them deliberately. */
    List<Orphan> orphaned() {
        return orphaned;
    }

    /**
     * Add the values present in {@code lastApplied} but absent from {@code desired}, skipping any a surviving
     * fragment of the same object still declares.
     */
    private void collect(List<Removal> removals, PrismObject<? extends Objectable> lastApplied,
            PrismObject<? extends Objectable> desired, String objectLabel, String sourceFile,
            List<Fragment> siblings) {
        DiffEngine.DiffResult result;
        try {
            // last-applied in the live slot: the delete side is "was deployed, no longer declared".
            result = new DiffEngine(prismContext).diff(desired, lastApplied, EquivalenceStrategy.REAL_VALUE,
                    DiffEngine.Scope.FULL, DiffEngine.MultiValueStrategy.replaceAll());
        } catch (Exception e) {
            LOG.debug("could not compare {} against revision {}", sourceFile, lastRevision, e);
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
                LOG.debug("{} is required and is never a removal candidate ({})", path, sourceFile);
                continue;
            }
            for (PrismValue value : mod.getValuesToDelete()) {
                String rendered = render(value);
                if (stillDeclaredBy(siblings, mod.getPath(), value)) {
                    LOG.debug("{} at {} still declared by another fragment; not a removal", rendered, path);
                    continue;
                }
                removals.add(new Removal(objectLabel, path, rendered, sourceFile, objectClass, oid,
                        objectName, mod.getDefinition(), mod.getPath(), value));
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

    /**
     * The fragments in the tree that describe the same object, indexed under <em>both</em> identity forms.
     * One object is routinely described by a fragment carrying its OID and another carrying only its name;
     * indexing by "OID else Type/Name" alone would file those under different keys, so neither would subtract
     * from the other and a value both declare would be reported as removed — and pruned.
     */
    private static Map<String, List<Fragment>> fragmentsByObject(List<Fragment> units) {
        Map<String, List<Fragment>> byIdentity = new LinkedHashMap<>();
        for (Fragment unit : units) {
            for (String key : identityKeysOf(unit.object())) {
                byIdentity.computeIfAbsent(key, ignored -> new ArrayList<>()).add(unit);
            }
        }
        return byIdentity;
    }

    /** Every fragment that still describes {@code object}, found under any of its identity forms. */
    private static List<Fragment> siblingsOf(Map<String, List<Fragment>> byIdentity,
            PrismObject<? extends Objectable> object) {
        List<Fragment> siblings = new ArrayList<>();
        for (String key : identityKeysOf(object)) {
            for (Fragment candidate : byIdentity.getOrDefault(key, List.of())) {
                if (!siblings.contains(candidate)) {
                    siblings.add(candidate);
                }
            }
        }
        return siblings;
    }

    /**
     * Whether a fragment still in the tree declares this value at this path.
     *
     * <p>Asked per removal, against the fragments themselves, rather than by pre-enumerating what each
     * fragment declares. That pre-enumeration used to diff a fragment against an <em>empty</em> object, which
     * yields whole-container adds at the top level ({@code deploymentInformation}) while the removal side
     * recurses into the container ({@code deploymentInformation/headerColor}) — two shapes that can never
     * compare equal, so no nested value was ever recognised as still declared. Walking the fragment to the
     * removal's own path removes the asymmetry entirely.
     *
     * <p>Values are compared with {@code REAL_VALUE} — the same test the diff itself uses — never by rendered
     * text, which would match values differing past a truncation point and miss ones differing only in
     * bookkeeping.
     */
    private static boolean stillDeclaredBy(List<Fragment> siblings, ItemPath path, PrismValue value) {
        ItemPath idFree = path.removeIds();
        for (Fragment sibling : siblings) {
            List<PrismValue> declared = new ArrayList<>();
            try {
                valuesAt(sibling.object().getValue(), idFree, declared);
            } catch (Exception e) {
                // Unknown beats wrong: a fragment we cannot read might well declare the value, and treating
                // it as "not declared" is what leads --prune to delete something still in the tree.
                LOG.debug("could not read {} from {}; treating the value as still declared", idFree,
                        sibling.sourceFile(), e);
                return true;
            }
            for (PrismValue candidate : declared) {
                if (EquivalenceStrategy.REAL_VALUE.equals(candidate, value)) {
                    return true;
                }
            }
        }
        return false;
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

    /**
     * The key both sides of the survivor subtraction must agree on: the OID when there is one, else
     * {@code Type/Name}. It has to be derived the same way for a fragment in the tree and for one recovered
     * from git, or an OID-less object's surviving declarations would never match what a deleted fragment
     * declared and every one of its values would be reported.
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

    private static String render(PrismValue value) {
        Object real = value.getRealValue();
        if (real == null) {
            return "";
        }
        String text = String.valueOf(real);
        return (text.length() > 120) ? text.substring(0, 120) + "…" : text;
    }
}
