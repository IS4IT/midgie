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
import com.evolveum.midpoint.prism.PrismContainer;
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
 * when midgie last deployed it. The removal set is what last-applied holds and desired no longer does,
 * compared item by item: container values of a multi-valued container are paired the way the diff pairs them
 * (by container id, else by natural key), so a value that only lost one nested item yields a removal of that
 * item, never of the whole value (see {@link #collect}).
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
        /**
         * The keyed container values the value lies inside, outermost first, as last-applied held them; empty
         * for a value reached through single-valued containers only. {@link #itemPath} is then id-free and
         * {@link #livePath} finds the server's own copy of each value.
         */
        final List<Step> steps;

        Removal(String objectLabel, String path, String value, String sourceFile, Class<?> objectClass,
                String oid, String objectName, ItemDefinition<?> definition, ItemPath itemPath,
                PrismValue prismValue) {
            this(objectLabel, path, value, sourceFile, objectClass, oid, objectName, definition, itemPath,
                    prismValue, null);
        }

        Removal(String objectLabel, String path, String value, String sourceFile, Class<?> objectClass,
                String oid, String objectName, ItemDefinition<?> definition, ItemPath itemPath,
                PrismValue prismValue, DeployObject unit) {
            this(objectLabel, path, value, sourceFile, objectClass, oid, objectName, definition, itemPath,
                    prismValue, unit, List.of());
        }

        Removal(String objectLabel, String path, String value, String sourceFile, Class<?> objectClass,
                String oid, String objectName, ItemDefinition<?> definition, ItemPath itemPath,
                PrismValue prismValue, DeployObject unit, List<Step> steps) {
            this.steps = List.copyOf(steps);
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

        /** Whether the value lies inside a value of a multi-valued container, matched by id or natural key. */
        boolean isNested() {
            return !steps.isEmpty();
        }

        /**
         * The path of this removal's item in {@code object}, container ids included, or {@code null} when the
         * object no longer holds one of the container values it lies inside.
         *
         * <p>Last-applied comes from git, where a container value usually carries no id, while a delete has to
         * name the server's value — and a removal of {@code lifecycleState} inside one mapping must never turn
         * into a removal of the mapping. So each step is found again in {@code object} the way the diff pairs
         * values: by container id, else by natural key ({@link NaturalKeys#match}).
         */
        ItemPath livePath(PrismObject<? extends Objectable> object) {
            if (steps.isEmpty()) {
                return itemPath;
            }
            PrismContainerValue<?> parent = object.getValue();
            List<Object> segments = new ArrayList<>();
            for (Step step : steps) {
                Item<?, ?> item = parent.findItem(step.containerPath());
                if (!(item instanceof PrismContainer<?> container)) {
                    return null;
                }
                PrismContainerValue<?> match = NaturalKeys.match(container, step.value(), step.value().getId());
                if (match == null || match.getId() == null) {
                    return null; // a value without an id cannot be addressed by a delete path
                }
                segments.addAll(step.containerPath().getSegments());
                segments.add(match.getId());
                parent = match;
            }
            segments.addAll(relativePath().getSegments());
            return ItemPath.create(segments.toArray());
        }

        /** The item's path below the innermost step (the whole path when there is none). */
        private ItemPath relativePath() {
            int skip = 0;
            for (Step step : steps) {
                skip += step.containerPath().size();
            }
            return itemPath.rest(skip);
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
     * One container value of a multi-valued container that a nested removal lies inside.
     *
     * @param containerPath the container's id-free path, relative to the previous step's value (or to the
     *                      object), possibly through single-valued containers
     * @param value         the value as last-applied held it; what is matched against the server's copy
     * @param label         how the report names it, e.g. {@code mapping[name=Given name]}
     */
    record Step(ItemPath containerPath, PrismContainerValue<?> value, String label) {
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
            // A nested removal can only be addressed through the server's own container ids.
            ItemPath path = (live != null) ? removal.livePath(live) : (removal.isNested() ? null : removal.itemPath);
            if (path == null) {
                continue;
            }
            objectClass = removal.objectClass;
            PrismValue held = (live != null) ? heldValue(live, removal) : null;
            ItemDelta delta = removal.definition.createEmptyDelta(path);
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
        Item<?, ?> item = itemAt(object, removal);
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

    /**
     * Whether {@code object} holds a value of a single-valued item where {@code removal} names one it does not
     * hold — the item was changed on the server after midgie deployed it. Asked only once {@link #heldValue}
     * came back empty, to word the note that says the removal is left alone.
     */
    static boolean holdsOtherValue(PrismObject<? extends Objectable> object, Removal removal) {
        if (removal.definition == null || !removal.definition.isSingleValue()) {
            return false;
        }
        Item<?, ?> item = itemAt(object, removal);
        return item != null && !item.hasNoValues();
    }

    /** The item {@code removal} names in {@code object}, or {@code null}. */
    private static Item<?, ?> itemAt(PrismObject<? extends Objectable> object, Removal removal) {
        ItemPath path = removal.isNested() ? removal.livePath(object) : removal.itemPath.removeIds();
        return (path != null) ? object.findItem(path) : null;
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

    /**
     * Add the values present in {@code lastApplied} but absent from {@code desired}, item by item.
     *
     * <p>This used to be the delete side of a {@code real-value} diff with last-applied in the live slot. That
     * matches a value of a multi-valued container by content, so a {@code mapping} that only lost its
     * {@code lifecycleState} came out as "the whole old mapping was removed". Harmless while the two-way diff
     * rewrote the mapping in place anyway — the projection in {@code Reconciler.judgeRemovals} then no longer
     * found the old form — but a tracked diff now leaves undeclared nested items alone, so the live mapping can
     * still equal the old one exactly, and the removal would have deleted the <em>entire</em> mapping.
     *
     * <p>So the walk pairs container values the way the diff does: a value of a multi-valued container is
     * matched in desired by container id, else by natural key ({@link NaturalKeys#match}); a matched pair is
     * compared inside, and what the value lost is removed item by item, addressed to the server's copy of the
     * value ({@link Removal#livePath}). A value with no counterpart — a mapping dropped entirely, or a list
     * value without a key that no desired value equals by content — is removed whole, exactly as before.
     * Single-valued containers are always compared inside, as prism does; a single-valued property desired
     * still declares is a replace in the diff and never a removal; everything else loses the values desired
     * no longer holds, by real value.
     */
    private void collect(List<Removal> removals, PrismObject<? extends Objectable> lastApplied,
            DeployObject unit, String objectLabel, LastApplied previous) {
        Target target = new Target(removals, lastApplied, unit, objectLabel, previous);
        try {
            walk(target, unit.object().getValue(), lastApplied.getValue(), List.of(), ItemPath.EMPTY_PATH,
                    ItemPath.EMPTY_PATH);
        } catch (RuntimeException e) {
            LOG.trace("could not compare {} against revision {}", previous.sourceLabel(), lastRevision, e);
        }
    }

    /** Where one object's removals go, and what each of them has to say about the object. */
    private record Target(List<Removal> removals, PrismObject<? extends Objectable> lastApplied,
            DeployObject unit, String objectLabel, LastApplied previous) {
    }

    /**
     * Compare one desired container value against the last-applied value it is paired with.
     *
     * @param steps    the keyed container values crossed so far
     * @param fullPath the id-free path of {@code applied} from the object root
     * @param relative the id-free path of {@code applied} below the innermost step
     */
    private void walk(Target target, PrismContainerValue<?> desired, PrismContainerValue<?> applied,
            List<Step> steps, ItemPath fullPath, ItemPath relative) {
        for (Item<?, ?> appliedItem : applied.getItems()) {
            ItemDefinition<?> definition = appliedItem.getDefinition();
            if ((definition != null && definition.isOperational()) || appliedItem.hasNoValues()) {
                continue; // metadata and the like: never deployed config, never a removal
            }
            ItemName name = appliedItem.getElementName();
            ItemPath itemPath = fullPath.append(name);
            ItemPath itemRelative = relative.append(name);
            Item<?, ?> desiredItem = (desired != null) ? desired.findItem(name) : null;
            if (desiredItem == null || desiredItem.hasNoValues()) {
                for (PrismValue value : appliedItem.getValues()) {
                    add(target, appliedItem, itemPath, value, steps);
                }
                continue;
            }
            if (appliedItem instanceof PrismContainer<?> appliedContainer
                    && desiredItem instanceof PrismContainer<?> desiredContainer) {
                boolean singleValued = definition != null ? definition.isSingleValue()
                        : appliedContainer.getValues().size() == 1 && desiredContainer.getValues().size() == 1;
                if (singleValued) {
                    walk(target, desiredContainer.getValues().get(0), appliedContainer.getValues().get(0), steps,
                            itemPath, itemRelative);
                    continue;
                }
                for (PrismContainerValue<?> value : appliedContainer.getValues()) {
                    if (containsByRealValue(desiredContainer, value)) {
                        continue;
                    }
                    PrismContainerValue<?> match = NaturalKeys.match(desiredContainer, value, value.getId());
                    if (match == null) {
                        add(target, appliedItem, itemPath, value, steps); // dropped whole, as before
                        continue;
                    }
                    List<Step> inner = new ArrayList<>(steps);
                    inner.add(new Step(itemRelative, value, label(itemPath, appliedContainer, value)));
                    walk(target, match, value, inner, itemPath, ItemPath.EMPTY_PATH);
                }
                continue;
            }
            if (definition != null && definition.isSingleValue()
                    && !(definition instanceof com.evolveum.midpoint.prism.PrismReferenceDefinition)) {
                continue; // a changed single-valued property is a replace in the diff, not a removal
            }
            for (PrismValue value : appliedItem.getValues()) {
                if (!containsByRealValue(desiredItem, value)) {
                    add(target, appliedItem, itemPath, value, steps);
                }
            }
        }
    }

    private static boolean containsByRealValue(Item<?, ?> item, PrismValue value) {
        for (PrismValue candidate : item.getValues()) {
            if (EquivalenceStrategy.REAL_VALUE.equals(candidate, value)) {
                return true;
            }
        }
        return false;
    }

    private void add(Target target, Item<?, ?> item, ItemPath itemPath, PrismValue value, List<Step> steps) {
        ItemDefinition<?> definition = item.getDefinition();
        String path = pathText(itemPath, steps);
        if (isUndeletable(itemPath, definition)) {
            LOG.trace("{} is required and is never a removal candidate ({})", path, target.previous().sourceLabel());
            return;
        }
        PrismObject<? extends Objectable> lastApplied = target.lastApplied();
        String objectName = (lastApplied.getName() != null) ? lastApplied.getName().getOrig() : null;
        String sourceFile = target.previous().sourceOf(itemPath, value);
        target.removals().add(new Removal(target.objectLabel(), path, render(value), sourceFile,
                lastApplied.getCompileTimeClass(), lastApplied.getOid(), objectName, definition, itemPath,
                value.clone(), target.unit(), steps));
    }

    /**
     * The path as the report shows it: each keyed value crossed named by its key, e.g.
     * {@code mapping[name=Given name]/lifecycleState}, since a container id from git would mean nothing to the
     * operator and usually is not there.
     */
    private static String pathText(ItemPath itemPath, List<Step> steps) {
        if (steps.isEmpty()) {
            return itemPath.toString();
        }
        StringBuilder text = new StringBuilder();
        int consumed = 0;
        for (Step step : steps) {
            if (text.length() > 0) {
                text.append('/');
            }
            text.append(step.label());
            consumed += step.containerPath().size();
        }
        ItemPath rest = itemPath.rest(consumed);
        for (Object segment : rest.getSegments()) {
            if (ItemPath.isName(segment)) {
                text.append('/').append(ItemPath.toName(segment).getLocalPart());
            }
        }
        return text.toString();
    }

    /** {@code mapping[name=Given name]}, or {@code inducement[3]} for a value paired by container id. */
    private static String label(ItemPath itemPath, PrismContainer<?> container, PrismContainerValue<?> value) {
        List<String> names = new ArrayList<>();
        for (Object segment : itemPath.getSegments()) {
            if (ItemPath.isName(segment)) {
                names.add(ItemPath.toName(segment).getLocalPart());
            }
        }
        String local = names.isEmpty() ? "?" : names.get(names.size() - 1);
        com.evolveum.midpoint.prism.key.NaturalKeyDefinition key = NaturalKeys.of(container);
        ItemDefinition<?> definition = container.getDefinition();
        List<javax.xml.namespace.QName> constituents =
                (definition != null) ? definition.getNaturalKeyConstituents() : null;
        if (key != null && constituents != null && !constituents.isEmpty() && NaturalKeys.carries(key, value)) {
            List<String> parts = new ArrayList<>();
            for (javax.xml.namespace.QName constituent : constituents) {
                Item<?, ?> item = value.findItem(ItemName.fromQName(constituent));
                Object real = (item != null && item.getValues().size() == 1) ? item.getRealValue() : null;
                parts.add(constituent.getLocalPart() + "=" + (real != null ? abbreviate(String.valueOf(real)) : "?"));
            }
            return local + "[" + String.join(", ", parts) + "]";
        }
        return local + "[" + value.getId() + "]";
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
