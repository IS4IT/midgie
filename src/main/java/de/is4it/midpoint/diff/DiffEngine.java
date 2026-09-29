/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.Set;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.namespace.QName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismReferenceValue;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.prism.equivalence.ParameterizedEquivalenceStrategy;
import com.evolveum.midpoint.prism.key.NaturalKeyDefinition;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.prism.xml.ns._public.query_3.SearchFilterType;
import com.evolveum.prism.xml.ns._public.types_3.ProtectedStringType;

/**
 * The offline diff core: parse two midPoint objects and compute the minimal {@link ObjectDelta}
 * that reconciles the live object to the desired one. Deliberately free of any REST/HTTP
 * dependency — the live-fetch and apply layers are thin adapters on top of this (see DESIGN.md).
 *
 * <h2>Direction matters</h2>
 * The emitted delta is the body a REST {@code PATCH} consumes, and that PATCH is applied to the
 * <em>live</em> object on the server to bring it into the desired state. So the delta must transform
 * <b>live &rarr; desired</b>. prism's contract is {@code before.diff(after)} = the delta that, applied
 * to {@code before}, yields {@code after} (proven by midPoint's {@code TestParseDiffPatch}: after
 * {@code userBefore.diff(userAfter)} then {@code delta.applyTo(userBefore)}, {@code userBefore}
 * equals {@code userAfter}). Therefore the core call is {@code live.diff(desired)}.
 *
 * <h2>Two knobs</h2>
 * {@link Scope} decides whether unmanaged (desired-absent) items are left alone or removed;
 * {@link MultiValueStrategy} decides whether multi-valued containers are replaced wholesale or merged
 * by key. Both operate on the raw prism delta before it is converted to XML.
 */
public final class DiffEngine {

    private static final Logger LOG = LoggerFactory.getLogger(DiffEngine.class);

    /**
     * Which item deltas to keep.
     *
     * <ul>
     *   <li>{@link #PRESENT} — only emit deltas for items that appear in the desired object. Items
     *       absent from the desired object are left untouched (their accumulated runtime state is
     *       preserved). This is the config-promotion default.</li>
     *   <li>{@link #FULL} — full-replace semantics: also emit the deltas that remove items present
     *       in the live object but missing from the desired one.</li>
     * </ul>
     */
    public enum Scope { PRESENT, FULL }

    /**
     * Decides whether a live reference is midPoint's resolution of a desired <b>smart reference</b>.
     *
     * <p>A smart reference names its target by search filter instead of OID, so that a file stays portable
     * across instances where the target's OID differs — {@code connectorRef} is the everyday case. midPoint
     * resolves the filter <em>at import</em> and stores the OID; the desired file still says "filter, no
     * OID". Diffed literally that reads as a change, and the resulting PATCH strips the OID and breaks the
     * object, because a modify does not re-resolve filters unless asked to.
     *
     * <p>Whether a given live OID really is that resolution is not always answerable offline — the server
     * may drop the filter once it has resolved it. So the engine makes the structural judgement (this is a
     * filter-only reference on one side and an OID on the other) and defers the rest here, which also keeps
     * the REST-using variant out of the engine.
     */
    @FunctionalInterface
    public interface SmartReferenceCheck {

        boolean isResolutionOf(PrismReferenceValue live, PrismReferenceValue desired);

        /**
         * Offline judgement, the default. When the server kept the filter, compare it: an equal filter means
         * the same smart reference and the OID is its resolution, while a different one is a real change the
         * operator made. When the filter is gone the question cannot be settled from the two objects, and
         * this accepts — a smart reference is used far more often to initialise a reference than to re-point
         * one, and {@code --smart-refs verify} settles it against the server for those who need certainty.
         */
        SmartReferenceCheck ACCEPT = (live, desired) -> {
            SearchFilterType liveFilter = live.getFilter();
            return liveFilter == null || liveFilter.equals(desired.getFilter());
        };

        /** Never suppress: send the desired form and let midPoint re-resolve it ({@code --smart-refs resolve}). */
        SmartReferenceCheck NEVER = (live, desired) -> false;
    }

    /**
     * Which equivalence to diff under: either a strategy the operator named outright, or {@link #auto()},
     * which picks one <em>per subtree</em> of each object.
     *
     * <p>Auto exists because prism takes a single equivalence for a whole-object diff, while the right
     * answer differs within one object. {@code natural-keys} pairs an entry of a multi-valued container by
     * its schema-declared {@code a:naturalKey} and edits it in place, preserving its container id and
     * metadata — a real improvement over {@code real-value}, which cannot pair an id-free desired value and
     * so replaces the entry wholesale. But prism's {@code DefaultNaturalKeyDefinitionImpl.valuesMatch}
     * treats a key constituent absent from <em>both</em> values as matching, so on the many types that
     * declare a key real configuration never fills in ({@code assignment} and {@code inducement} declare
     * {@code identifier}) it pairs unrelated values positionally and edits one into the other.
     *
     * <p>So auto diffs the object under both strategies and keeps each item delta from the pass its path
     * belongs to (see {@code reconcileAuto}). Only {@code real-value} and {@code natural-keys} are ever
     * chosen: the other presets either make no difference for an id-free desired file
     * ({@code consider-different-ids}) or consider operational data and metadata, which against a live
     * server never diffs empty and would break the exit-code drift contract ({@code data}, {@code literal})
     * or re-surface resolved smart references ({@code ignore-metadata}).
     */
    public static final class EquivalencePlan {

        private static final EquivalencePlan AUTO = new EquivalencePlan(null);

        private final ParameterizedEquivalenceStrategy fixed;

        private EquivalencePlan(ParameterizedEquivalenceStrategy fixed) {
            this.fixed = fixed;
        }

        /** Diff everything under the one strategy the operator named. */
        public static EquivalencePlan of(ParameterizedEquivalenceStrategy strategy) {
            return new EquivalencePlan(strategy);
        }

        /** Decide per subtree, per object. */
        public static EquivalencePlan auto() {
            return AUTO;
        }

        public boolean isAuto() {
            return fixed == null;
        }

        /** The named strategy; {@code null} under {@link #auto()}. */
        public ParameterizedEquivalenceStrategy fixedStrategy() {
            return fixed;
        }
    }

    private final PrismContext prismContext;

    public DiffEngine(PrismContext prismContext) {
        this.prismContext = prismContext;
    }

    /**
     * Compute the reconciling delta between two object files. Convenience overload that parses both
     * files and delegates to {@link #diff(PrismObject, PrismObject, ParameterizedEquivalenceStrategy,
     * Scope, MultiValueStrategy)}.
     */
    public DiffResult diff(File desiredFile, File liveFile, ParameterizedEquivalenceStrategy strategy,
            Scope scope, MultiValueStrategy multiValue) throws SchemaException, IOException {
        return diff(desiredFile, liveFile, strategy, scope, multiValue, SmartReferenceCheck.NEVER);
    }

    /** As above, with the smart-reference judgement (see {@link SmartReferenceCheck}). */
    public DiffResult diff(File desiredFile, File liveFile, ParameterizedEquivalenceStrategy strategy,
            Scope scope, MultiValueStrategy multiValue, SmartReferenceCheck smartRefs)
            throws SchemaException, IOException {
        return diff(desiredFile, liveFile, EquivalencePlan.of(strategy), scope, multiValue, smartRefs);
    }

    /** As above, taking an {@link EquivalencePlan} rather than one fixed strategy. */
    public DiffResult diff(File desiredFile, File liveFile, EquivalencePlan equivalence,
            Scope scope, MultiValueStrategy multiValue, SmartReferenceCheck smartRefs)
            throws SchemaException, IOException {
        return diff(prismContext.parseObject(desiredFile), prismContext.parseObject(liveFile),
                equivalence, scope, multiValue, smartRefs);
    }

    /**
     * Compute the reconciling delta between two already-parsed objects. Used directly by the live modes,
     * where the live object arrives as XML from REST rather than a file.
     *
     * @param desired    the desired-state object (source of truth for managed items)
     * @param live       the current/live object
     * @param strategy   equivalence strategy controlling metadata/operational sensitivity (used when no
     *                   merge is active; merge is designed for real-value content matching)
     * @param scope      {@link Scope#PRESENT} to preserve unmanaged items, {@link Scope#FULL} for full replace
     * @param multiValue how multi-valued containers are treated (replace vs merge, with per-path overrides)
     * @return the delta plus any paths where merge fell back to replace
     */
    public DiffResult diff(PrismObject<? extends Objectable> desired, PrismObject<? extends Objectable> live,
            ParameterizedEquivalenceStrategy strategy, Scope scope, MultiValueStrategy multiValue)
            throws SchemaException {
        return diff(desired, live, strategy, scope, multiValue, SmartReferenceCheck.NEVER);
    }

    /**
     * As {@link #diff(PrismObject, PrismObject, ParameterizedEquivalenceStrategy, Scope, MultiValueStrategy)},
     * with {@code smartRefs} deciding whether a live OID counts as midPoint's resolution of a desired
     * filter-only reference. The two file-to-file callers compare inputs the server never touched, so they
     * use the overload above and never suppress.
     */
    public DiffResult diff(PrismObject<? extends Objectable> desired, PrismObject<? extends Objectable> live,
            ParameterizedEquivalenceStrategy strategy, Scope scope, MultiValueStrategy multiValue,
            SmartReferenceCheck smartRefs) throws SchemaException {
        return diff(desired, live, EquivalencePlan.of(strategy), scope, multiValue, smartRefs);
    }

    /**
     * As above, but taking an {@link EquivalencePlan} — which may be {@link EquivalencePlan#auto()}, letting
     * the engine choose the equivalence per subtree of this object. The command layer uses this overload;
     * the internal callers that need one fixed strategy use the one above.
     */
    public DiffResult diff(PrismObject<? extends Objectable> desired, PrismObject<? extends Objectable> live,
            EquivalencePlan equivalence, Scope scope, MultiValueStrategy multiValue,
            SmartReferenceCheck smartRefs) throws SchemaException {
        return diff(desired, live, equivalence, scope, multiValue, smartRefs, false);
    }

    /**
     * As above, and with {@code keepUndeclaredNested} set, live items <em>below</em> the top level that the
     * desired object does not declare are kept rather than deleted (see {@link #keepUndeclaredNested}). Only
     * a tracked deploy under {@code --scope present} asks for that, because only there is last-applied known,
     * so that what the tree deployed and then dropped can still be removed — by the removal side, not here.
     */
    public DiffResult diff(PrismObject<? extends Objectable> desired, PrismObject<? extends Objectable> live,
            EquivalencePlan equivalence, Scope scope, MultiValueStrategy multiValue,
            SmartReferenceCheck smartRefs, boolean keepUndeclaredNested) throws SchemaException {

        Class<?> desiredType = desired.getCompileTimeClass();
        Class<?> liveType = live.getCompileTimeClass();
        if (desiredType != null && liveType != null && !desiredType.equals(liveType)) {
            throw new SchemaException("Cannot diff objects of different types: live is "
                    + liveType.getSimpleName() + ", desired is " + desiredType.getSimpleName());
        }

        // Guard against diffing the wrong object: if both sides carry an OID and they differ, this is
        // almost certainly a mistake (wrong --oid, wrong file), and a delta would rewrite one object into
        // another. A desired object with no OID (an unbound template) is fine — the live OID governs.
        String desiredOid = desired.getOid();
        String liveOid = live.getOid();
        if (desiredOid != null && liveOid != null && !desiredOid.equals(liveOid)) {
            throw new SchemaException("Cannot diff different objects: desired oid " + desiredOid
                    + " does not match live oid " + liveOid + ".");
        }

        // prism pairs list values by container id under every strategy, so ids numbered on another server would
        // pair unrelated entries and edit one into the other. Everything below works on this copy.
        ContainerIds.Trusted trusted = ContainerIds.trustedOnly(desired, live);
        desired = trusted.desired();

        // A smart reference inside a list entry compared by content differs as the whole entry, which the
        // reference-level strip below never sees; such an entry takes the live references it resolved to.
        NestedSmartReferences.Resolved nested = NestedSmartReferences.resolve(desired, live, smartRefs);
        desired = nested.desired();

        // before.diff(after) => delta that, applied to `before`, yields `after`. before = live,
        // after = desired, so the delta reconciles the live object to the desired state. REAL_VALUE
        // matches container/property values by content (ignoring PCV ids), which yields clean whole-value
        // add/delete deltas — exactly what merge needs (see applyMerge). Under an id-considering strategy
        // merge may see paired/edited values instead of clean add/delete, so applyMerge has less to strip;
        // that is true of the natural-keys half of an auto diff as much as of an explicit --equivalence.
        List<String> equivalenceChoices = new ArrayList<>();
        List<String> keyPairingDeclined = new ArrayList<>(trusted.notes());
        keyPairingDeclined.addAll(nested.notes());
        ObjectDelta<? extends Objectable> delta =
                reconcile(live, desired, equivalence, equivalenceChoices, keyPairingDeclined);

        // First, before anything else reads the delta: drop the differences that are not differences at all,
        // only midPoint having resolved a smart reference. Ahead of applyMerge because merge would otherwise
        // strip the delete side of a merge-designated reference path and leave nothing to pair the add with.
        List<String> smartReferences = stripResolvedSmartReferences(delta, smartRefs);
        smartReferences.addAll(nested.paths());

        // Before merge and scope, so that it sees every delete of a nested item and can report what it keeps:
        // applyMerge would otherwise clear the deletes of a multi-valued item inside a declared value, and
        // retainItemsPresentIn drop those along single-valued containers, both without a word.
        List<KeptNested> keptNested = (keepUndeclaredNested && scope == Scope.PRESENT)
                ? keepUndeclaredNested(delta, desired, live) : List.of();

        List<String> mergeFallbacks = multiValue.isMergeActive()
                ? applyMerge(delta, desired, live, multiValue) : List.of();

        if (scope == Scope.PRESENT) {
            retainItemsPresentIn(delta, desired);
        }

        // Protected/encrypted values (ProtectedStringType) re-encrypt on every fetch, so the ciphertext
        // differs each time even when the cleartext is identical. Diffing them yields spurious deltas and
        // would leak the ciphertext into the emitted PATCH, so drop those item deltas entirely and report
        // the paths. midPoint owns password rotation; a genuine password change must go through its own
        // credential API, not a blind PATCH of stale ciphertext.
        List<String> protectedSkipped = stripProtectedValues(delta);

        // An item the server marked incomplete was not sent, and "not sent" is not "empty". LiveLookup
        // re-reads such an object asking for those items; anything still incomplete here is something the
        // server will not hand over (no read permission on it, typically). Diffing it would turn the values
        // midgie cannot see into a deletion, so those item deltas are dropped and reported.
        List<String> incompleteSkipped = stripIncompleteItems(delta, live);

        // Record what each replace is about to overwrite. Run after the strips, so nothing is recorded for a
        // delta that is no longer there.
        recordOldValues(delta, live);

        // The same for the merge fallbacks, which were collected before --scope present and the strips: an
        // item the desired object does not even mention was otherwise reported as "left as replace" while
        // nothing about it is sent.
        mergeFallbacks = stillInDelta(mergeFallbacks, delta);

        // Merge promises accrued (live-only) values survive. Under an id/natural-key-considering strategy,
        // prism can pair a live-only value with a desired value and edit it in place (a deep delta merge
        // cannot express as a union), which silently overwrites the accrued value. Report those paths so
        // the command layer can refuse by default. Empty for a clean real-value union of accrued values.
        List<String> accruedInPlaceEdits = multiValue.isMergeActive()
                ? findAccruedInPlaceEdits(delta, desired, live, multiValue) : List.of();

        // Static input checks: a fragment that omits a natural key, or only partially declares a keyed
        // container on replace, silently destroys live state. Reported before anything is written.
        List<String> inputWarnings = findInputWarnings(desired, live, multiValue);

        LOG.debug("diff {} (scope={}, {}) → {} modification(s), removes {} value(s); mergeFallback={}, "
                + "protectedSkipped={}, accruedInPlace={}, inputWarnings={}, smartRefs={}, equivalence={}",
                desiredType != null ? desiredType.getSimpleName() : "?", scope,
                multiValue.isMergeActive() ? "merge" : "replace", delta.getModifications().size(),
                DeltaOutput.removedValueCount(delta), mergeFallbacks, protectedSkipped, accruedInPlaceEdits,
                inputWarnings.size(), smartReferences, equivalenceChoices);
        return new DiffResult(delta, mergeFallbacks, protectedSkipped, accruedInPlaceEdits, inputWarnings,
                smartReferences, equivalenceChoices, keyPairingDeclined, incompleteSkipped, keptNested);
    }

    /**
     * Live values of an item below the top level that a tracked diff kept instead of deleting, because the
     * desired object does not declare the item there.
     *
     * @param path       the item's path in the live object, container ids included
     *                   ({@code mapping/4/lifecycleState})
     * @param definition the item's definition, when the delta carried one
     * @param values     the live values that the two-way diff would have deleted
     */
    record KeptNested(ItemPath path, ItemDefinition<?> definition, List<PrismValue> values) {
    }

    /** What {@link #declarationAt} found for a nested delta path. */
    private enum Declaration { DECLARED, UNDECLARED, UNKNOWN }

    /**
     * The nested analogue of {@link #retainItemsPresentIn}, for a tracked run: drop every delete of live values
     * of an item <em>below</em> the top level that the desired object does not declare at that path, and
     * return what was kept.
     *
     * <p>A two-way diff reads an item missing from desired as "delete it". At the top level {@code --scope
     * present} already says otherwise; inside a container value the tree declares — one {@code mapping} of a
     * template, paired by its {@code name} — the diff deleted everything the server's copy held beyond the
     * file, whoever had put it there. An administrator who set {@code lifecycleState} on ten mappings got ten
     * deletes. With last-applied known that is no longer necessary: an item the tree deployed and then dropped
     * is found by the removal side ({@link RemovalDetector}) and deleted there, in the form the tree deployed
     * it; one the tree never deployed is someone else's and stays.
     *
     * <p>Only deletes of an item desired lacks <em>entirely</em> at that path are dropped. An item desired
     * declares keeps its delta — a replace of a single-valued item, and the add/delete of a multi-valued one,
     * which is list membership and belongs to merge/replace. So does every top-level delta ({@code --scope
     * present} decides those), including the whole-value add/delete of a container value that {@code auto}'s
     * {@code real-value} half produces for a list without keys: its path names the list, which desired
     * declares.
     *
     * <p>A path through a multi-valued container carries the <em>live</em> value's container id
     * ({@code mapping/4/lifecycleState}); desired, written by hand, usually has none. Each id is therefore
     * mapped back to the desired value the diff paired it with — by container id, else by natural key, the
     * pairing {@link NaturalKeys#match} defines for every other caller. A live value that maps to nothing in
     * desired is left to the accrued-value guard, which exists for exactly that case.
     */
    private static List<KeptNested> keepUndeclaredNested(ObjectDelta<?> delta, PrismObject<?> desired,
            PrismObject<?> live) {
        List<KeptNested> kept = new ArrayList<>();
        List<ItemDelta<?, ?>> emptied = new ArrayList<>();
        for (ItemDelta<?, ?> mod : delta.getModifications()) {
            if (localNames(mod.getPath()).size() < 2) {
                continue; // a top-level item: --scope present's business
            }
            if (mod.getValuesToDelete() == null || mod.getValuesToDelete().isEmpty()) {
                continue; // an add or a replace: desired declares the item, and it is sent as before
            }
            if (declarationAt(desired, live, mod.getPath()) != Declaration.UNDECLARED) {
                continue;
            }
            List<PrismValue> values = new ArrayList<>();
            for (PrismValue value : mod.getValuesToDelete()) {
                values.add(value.clone());
            }
            kept.add(new KeptNested(mod.getPath(), mod.getDefinition(), values));
            mod.clearValuesToDelete();
            if (mod.isEmpty()) {
                emptied.add(mod);
            }
        }
        emptied.forEach(delta::removeModification);
        return kept;
    }

    /**
     * Whether {@code desired} declares the item a delta path names. Each container-id segment is resolved to
     * the desired value paired with the live value carrying that id; the names after the last one are looked
     * up in that value, through single-valued containers. {@code UNKNOWN} when a live value on the way has no
     * counterpart in desired.
     */
    private static Declaration declarationAt(PrismObject<?> desired, PrismObject<?> live, ItemPath path) {
        PrismContainerValue<?> desiredParent = desired.getValue();
        PrismContainerValue<?> liveParent = (live != null) ? live.getValue() : null;
        List<QName> names = new ArrayList<>();
        for (Object segment : path.getSegments()) {
            if (ItemPath.isName(segment)) {
                names.add(ItemPath.toName(segment));
                continue;
            }
            if (!ItemPath.isId(segment) || names.isEmpty()) {
                return Declaration.UNKNOWN; // an exotic segment: nothing to vouch for either way
            }
            ItemPath containerPath = ItemPath.create(names.toArray());
            names.clear();
            Item<?, ?> desiredItem = desiredParent.findItem(containerPath);
            if (!(desiredItem instanceof PrismContainer<?> desiredContainer) || desiredItem.hasNoValues()) {
                return Declaration.UNDECLARED;
            }
            Long id = ItemPath.toId(segment);
            PrismContainerValue<?> liveValue = containerValueById(liveParent, containerPath, id);
            PrismContainerValue<?> match = NaturalKeys.match(desiredContainer, liveValue, id);
            if (match == null) {
                return Declaration.UNKNOWN;
            }
            desiredParent = match;
            liveParent = liveValue;
        }
        if (names.isEmpty()) {
            return Declaration.UNKNOWN;
        }
        Item<?, ?> item = desiredParent.findItem(ItemPath.create(names.toArray()));
        return (item == null || item.hasNoValues()) ? Declaration.UNDECLARED : Declaration.DECLARED;
    }

    private static <O extends Objectable> ObjectDelta<O> reconcile(PrismObject<O> live, PrismObject<?> desired,
            EquivalencePlan equivalence, List<String> choices, List<String> declined) {
        @SuppressWarnings("unchecked") // the two objects were verified to share a compile-time class
        PrismObject<O> desiredTyped = (PrismObject<O>) desired;
        if (!equivalence.isAuto()) {
            ParameterizedEquivalenceStrategy strategy = equivalence.fixedStrategy();
            if (strategy.isConsideringNaturalKeys()) {
                PrismObject<O> withIds = desiredTyped.clone();
                adoptLiveIds(withIds.getValue(), live.getValue());
                return live.diff(withIds, strategy);
            }
            return live.diff(desiredTyped, strategy);
        }
        return reconcileAuto(live, desiredTyped, choices, declined);
    }

    /**
     * Give each desired container value that pairs with a live one by natural key the live value's container
     * id — on a copy of desired, for the {@code natural-keys} pass only.
     *
     * <p>prism pairs such values (a hand-written {@code mapping} has no id, the server's copy has one) and
     * diffs inside them, but it builds the path of an item only desired holds from the <em>desired</em> value,
     * so adding a {@code lifecycleState} to a mapping came out as {@code mapping/lifecycleState} — no id, a
     * path that names no particular mapping and that prism itself refuses to resolve ("Attempt to get segment
     * without an ID from a multi-valued container"). With the id adopted both sides agree and every path
     * carries the server's id, which is also what the PATCH needs to address the value.
     *
     * <p>Pairing is {@link NaturalKeys#match}'s, id first and then key, each live value taken at most once.
     * Nothing else about desired changes, and the real-value pass never sees the ids: there they would pair
     * values the {@code auto} walk deliberately left to be matched by content.
     */
    private static void adoptLiveIds(PrismContainerValue<?> desired, PrismContainerValue<?> live) {
        if (live == null) {
            return;
        }
        for (Item<?, ?> item : desired.getItems()) {
            if (item instanceof PrismContainer<?> container) {
                ItemDefinition<?> definition = container.getDefinition();
                if (definition != null && !definition.isOperational()) {
                    adoptLiveIds(container, containerAt(live, item.getElementName()));
                }
            }
        }
    }

    private static void adoptLiveIds(PrismContainer<?> desired, PrismContainer<?> live) {
        if (live == null || desired.getValues().isEmpty()) {
            return;
        }
        ItemDefinition<?> definition = desired.getDefinition();
        if (definition != null && definition.isSingleValue()) {
            if (!live.getValues().isEmpty()) {
                adoptLiveIds(desired.getValues().get(0), live.getValues().get(0));
            }
            return;
        }
        Set<PrismContainerValue<?>> taken = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PrismContainerValue<?> value : desired.getValues()) {
            PrismContainerValue<?> match = NaturalKeys.match(live, value, value.getId());
            if (match == null || taken.contains(match)) {
                continue;
            }
            taken.add(match);
            if (value.getId() == null && match.getId() != null) {
                value.setId(match.getId());
            }
            adoptLiveIds(value, match);
        }
    }

    /** {@link #adoptLiveIds} for the multi-valued container at {@code path}, reached through single values. */
    private static void adoptLiveIdsAt(PrismObject<?> desired, PrismObject<?> live, ItemPath path) {
        Item<?, ?> desiredItem = desired.findItem(path);
        Item<?, ?> liveItem = live.findItem(path);
        if (desiredItem instanceof PrismContainer<?> desiredContainer
                && liveItem instanceof PrismContainer<?> liveContainer) {
            adoptLiveIds(desiredContainer, liveContainer);
        }
    }

    /**
     * The {@code --equivalence auto} diff: {@code natural-keys} where the schema's keys are filled in and
     * pairing is therefore safe, {@code real-value} everywhere else, within one object.
     *
     * <p>prism takes one equivalence per whole-object diff, so the object is diffed <b>twice</b> and each
     * item delta is kept from the pass its path belongs to. That is sound because the item deltas of an
     * {@code ObjectModificationType} are independent, and because the buckets never cut into a multi-valued
     * container (see {@link NaturalKeys#autoBuckets}), so the two passes never express the same change at
     * paths that land in different buckets.
     *
     * <p>Diffing twice is only needed when the object genuinely splits. With nothing safe to pair this is a
     * single {@code real-value} pass — byte-identical to the old default — and with nothing to protect it is
     * a single {@code natural-keys} pass.
     *
     * <p>{@code choices} receives one entry per container, for the debug log; {@code declined} only the
     * containers left to {@code real-value}, each as a sentence naming the reason, for the note {@code -v}
     * prints.
     */
    private static <O extends Objectable> ObjectDelta<O> reconcileAuto(
            PrismObject<O> live, PrismObject<O> desired, List<String> choices, List<String> declined) {

        List<List<String>> naturalKeyPaths = new ArrayList<>();
        PrismObject<O> withIds = desired.clone();
        int total = 0;
        for (NaturalKeys.AutoBucket bucket : NaturalKeys.autoBuckets(desired)) {
            total++;
            if (bucket.naturalKeySafe()) {
                naturalKeyPaths.add(localNames(bucket.path()));
                adoptLiveIdsAt(withIds, live, bucket.path());
                choices.add(pathText(bucket.path()) + "=natural-keys");
            } else {
                choices.add(pathText(bucket.path()) + "=real-value (" + bucket.reason() + ")");
                declined.add(pathText(bucket.path()) + " is compared as whole values, not paired by natural key: "
                        + bucket.reason() + ", so a change inside one of its entries replaces that entry whole");
            }
        }

        if (naturalKeyPaths.isEmpty()) {
            return live.diff(desired, EquivalenceStrategy.REAL_VALUE);
        }
        if (naturalKeyPaths.size() == total) {
            return live.diff(withIds, EquivalenceStrategy.REAL_VALUE_CONSIDER_DIFFERENT_IDS_NATURAL_KEYS);
        }

        ObjectDelta<O> natural =
                live.diff(withIds, EquivalenceStrategy.REAL_VALUE_CONSIDER_DIFFERENT_IDS_NATURAL_KEYS);
        ObjectDelta<O> real = live.diff(desired, EquivalenceStrategy.REAL_VALUE);
        retainBucket(natural, naturalKeyPaths, true);
        retainBucket(real, naturalKeyPaths, false);
        // Copy first: getModifications() hands back the delta's own live collection.
        natural.addModifications(new ArrayList<>(real.getModifications()));
        return natural;
    }

    /**
     * Drop every item delta that does not belong to this pass. A modification belongs to the natural-keys
     * bucket when one of {@code naturalKeyPaths} is a prefix of its (id-free) path; everything else — plain
     * properties, single-valued containers, and the multi-valued containers auto rejected — belongs to the
     * real-value bucket, where the two strategies agree anyway.
     */
    private static void retainBucket(ObjectDelta<?> delta, List<List<String>> naturalKeyPaths, boolean keepInside) {
        List<ItemDelta<?, ?>> toRemove = new ArrayList<>();
        for (ItemDelta<?, ?> mod : delta.getModifications()) {
            if (isUnder(localNames(mod.getPath()), naturalKeyPaths) != keepInside) {
                toRemove.add(mod);
            }
        }
        toRemove.forEach(delta::removeModification);
    }

    private static boolean isUnder(List<String> path, List<List<String>> prefixes) {
        for (List<String> prefix : prefixes) {
            if (path.size() >= prefix.size() && path.subList(0, prefix.size()).equals(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** An item path as its bare local names, ids stripped — the form the path buckets and rules match on. */
    private static List<String> localNames(ItemPath path) {
        List<String> names = new ArrayList<>();
        for (Object segment : path.removeIds().getSegments()) {
            if (ItemPath.isName(segment)) {
                names.add(ItemPath.toName(segment).getLocalPart());
            }
        }
        return names;
    }

    /** An item path as {@code a/b/c}, for a message. */
    private static String pathText(ItemPath path) {
        return String.join("/", localNames(path));
    }

    /**
     * Drop item deltas whose target item does not appear in the desired object, matched at the <b>full
     * path</b> (not just the top-level item). With a live&rarr;desired delta these are exactly the deltas
     * that would remove live-only items, so dropping them leaves unmanaged items alone.
     *
     * <p>Matching the full path (rather than only the first segment) is what lets two fragments of one
     * object each fill in a <em>different child</em> of the same single-valued container without clobbering
     * each other. If file A sets {@code deploymentInformation/headerColor} and file B sets
     * {@code deploymentInformation/name}, B's diff against a live object that already carries A's
     * {@code headerColor} yields a {@code delete deploymentInformation/headerColor} — but {@code headerColor}
     * is absent from B's desired object, so under PRESENT it is a live-only item and the delete is dropped.
     * A change to an item the fragment <em>does</em> declare (e.g. {@code deploymentInformation/name}) is
     * found at its full path and kept.
     */
    private static void retainItemsPresentIn(ObjectDelta<?> delta, PrismObject<?> desired) {
        List<ItemDelta<?, ?>> toRemove = new ArrayList<>();
        for (ItemDelta<?, ?> mod : delta.getModifications()) {
            // Test the delta's item at its id-free prefix — the path truncated at the first container-id
            // segment (or the whole path when it has none). An id-free path names an exact item, so two
            // fragments can each fill a different child of one single-valued container without deleting the
            // other's child, and single-valued mid-levels (e.g. internals/tracing) are protected at their
            // own depth. Protection can only be as fine as the first MULTI-valued container: a delta that
            // reaches into one (internals/tracing/profile/[2]/…) carries that value's live-side id, which
            // need not exist in desired, so it is judged at the container (internals/tracing/profile) —
            // testing the id would wrongly drop a managed change under a re-keyed value and would rob the
            // accrued-in-place-edit detection of the deltas it inspects.
            ItemPath checkPath = idFreePrefix(mod.getPath());
            if (checkPath.isEmpty() || desired.findItem(checkPath) == null) {
                toRemove.add(mod);
            }
        }
        toRemove.forEach(delta::removeModification);
    }

    /** The path truncated at the first container-id segment (the whole path when it has none). */
    private static ItemPath idFreePrefix(ItemPath path) {
        List<Object> segments = new ArrayList<>();
        for (Object segment : path.getSegments()) {
            if (ItemPath.isId(segment)) {
                break;
            }
            segments.add(segment);
        }
        return ItemPath.create(segments.toArray());
    }

    /**
     * Drop item deltas that touch a {@link ProtectedStringType} value and return their paths. An item is
     * protected either by its schema definition (type is {@code ProtectedStringType}, e.g.
     * {@code credentials/password/value}) or by carrying a protected real value directly (a fallback for
     * when no definition is attached). Nested protected values inside a larger added/deleted container
     * (e.g. a password buried in an added assignment) are not detected here; those are rare and would need
     * a deep value walk — call this out in the docs rather than pretend it is handled.
     */
    /**
     * Drop the deltas that only say "midPoint resolved this smart reference", and report their paths.
     *
     * <p>The desired file names the target by filter and carries no OID; the live object carries the OID the
     * server resolved that filter to (and {@code relation="org:default"}). prism sees two unequal reference
     * values, so it emits an add of the filter-only form paired with a delete of the resolved one. Sending
     * that strips the OID, and a modify does not re-resolve filters unless explicitly asked to, so the
     * object is left pointing at nothing.
     *
     * <p>Everything here keys on the prism <em>value kind</em>, never on an element name: only a
     * {@link PrismReferenceValue} has {@code getFilter()}, so the many unrelated {@code SearchFilterType}
     * fields in the schema — an object collection's filter, a GUI view's, a policy constraint's — are
     * property values and cannot reach this pass.
     */
    private static List<String> stripResolvedSmartReferences(ObjectDelta<?> delta, SmartReferenceCheck check) {
        List<String> paths = new ArrayList<>();
        List<ItemDelta<?, ?>> emptied = new ArrayList<>();
        for (ItemDelta<?, ?> mod : delta.getModifications()) {
            List<PrismReferenceValue> desiredValues = referenceValues(mod.getValuesToAdd());
            List<PrismReferenceValue> liveValues = referenceValues(mod.getValuesToDelete());
            if (desiredValues.isEmpty() || liveValues.isEmpty()) {
                continue;
            }
            // Identity, not equality: two reference values can be equal by content and must still be
            // consumed separately.
            Set<PrismReferenceValue> takenLive = Collections.newSetFromMap(new IdentityHashMap<>());
            List<PrismReferenceValue> resolvedDesired = new ArrayList<>();
            for (PrismReferenceValue desiredValue : desiredValues) {
                PrismReferenceValue match = matchingResolution(desiredValue, liveValues, takenLive, check);
                if (match != null) {
                    takenLive.add(match);
                    resolvedDesired.add(desiredValue);
                }
            }
            if (resolvedDesired.isEmpty()) {
                continue;
            }
            resolvedDesired.forEach(mod::removeValueToAdd);
            takenLive.forEach(mod::removeValueToDelete);
            paths.add(mod.getPath().toString());
            if (mod.isEmpty()) {
                emptied.add(mod);
            }
        }
        emptied.forEach(delta::removeModification);
        return paths;
    }

    /** The live value that is {@code desired}'s resolution, or {@code null} when none of them is. */
    static PrismReferenceValue matchingResolution(PrismReferenceValue desired,
            List<PrismReferenceValue> liveValues, Set<PrismReferenceValue> taken, SmartReferenceCheck check) {
        if (desired.getOid() != null || desired.getFilter() == null) {
            return null; // not a smart reference: it names its target outright, or names nothing
        }
        for (PrismReferenceValue live : liveValues) {
            if (taken.contains(live) || live.getOid() == null) {
                continue;
            }
            // A relation or target type spelled out in desired and contradicted by live is a real change,
            // whatever the filter says; desired leaving them out is the normal shape.
            if (desired.getRelation() != null && !desired.getRelation().equals(live.getRelation())) {
                continue;
            }
            if (desired.getTargetType() != null && !desired.getTargetType().equals(live.getTargetType())) {
                continue;
            }
            if (check.isResolutionOf(live, desired)) {
                return live;
            }
        }
        return null;
    }

    private static List<PrismReferenceValue> referenceValues(Collection<?> values) {
        List<PrismReferenceValue> references = new ArrayList<>();
        for (Object value : (values != null) ? values : List.of()) {
            if (value instanceof PrismReferenceValue reference) {
                references.add(reference);
            }
        }
        return references;
    }

    /**
     * Drop item deltas that touch an item the live object reports as {@code incomplete}, and return their
     * paths. midPoint does not return some items unless asked ({@code LookupTableType/row}, a
     * {@code jpegPhoto}); {@link LiveLookup} re-reads the object for those, so anything still marked here is
     * one the server would not give up. Its values are unknown, not absent — emitting a delta for it would
     * write over content midgie never saw.
     */
    private static List<String> stripIncompleteItems(ObjectDelta<?> delta, PrismObject<?> live) {
        List<List<String>> incomplete = new ArrayList<>();
        collectIncomplete(live.getValue(), List.of(), incomplete);
        if (incomplete.isEmpty()) {
            return List.of();
        }
        List<String> skipped = new ArrayList<>();
        List<ItemDelta<?, ?>> toRemove = new ArrayList<>();
        for (ItemDelta<?, ?> mod : delta.getModifications()) {
            if (isUnder(localNames(mod.getPath()), incomplete)) {
                toRemove.add(mod);
                skipped.add(mod.getPath().toString());
            }
        }
        toRemove.forEach(delta::removeModification);
        return skipped;
    }

    private static void collectIncomplete(PrismContainerValue<?> container, List<String> prefix,
            List<List<String>> found) {
        for (Item<?, ?> item : container.getItems()) {
            List<String> path = new ArrayList<>(prefix);
            path.add(item.getElementName().getLocalPart());
            if (item.isIncomplete()) {
                found.add(path);
                continue;
            }
            if (item instanceof PrismContainer<?> nested) {
                for (PrismContainerValue<?> value : nested.getValues()) {
                    collectIncomplete(value, path, found);
                }
            }
        }
    }

    private static List<String> stripProtectedValues(ObjectDelta<?> delta) {
        List<String> skipped = new ArrayList<>();
        List<ItemDelta<?, ?>> toRemove = new ArrayList<>();
        for (ItemDelta<?, ?> mod : delta.getModifications()) {
            if (isProtected(mod)) {
                toRemove.add(mod);
                skipped.add(mod.getPath().toString());
            }
        }
        toRemove.forEach(delta::removeModification);
        return skipped;
    }

    private static boolean isProtected(ItemDelta<?, ?> mod) {
        ItemDefinition<?> definition = mod.getDefinition();
        if (definition != null && ProtectedStringType.COMPLEX_TYPE.equals(definition.getTypeName())) {
            return true;
        }
        return hasProtectedValue(mod.getValuesToReplace())
                || hasProtectedValue(mod.getValuesToAdd())
                || hasProtectedValue(mod.getValuesToDelete());
    }

    private static boolean hasProtectedValue(Collection<? extends PrismValue> values) {
        if (values == null) {
            return false;
        }
        for (PrismValue value : values) {
            if (value.getRealValue() instanceof ProtectedStringType) {
                return true;
            }
        }
        return false;
    }

    /**
     * Record, on every <b>replace</b> item delta, the live values it is about to overwrite.
     *
     * <p>prism computes the old value and then throws it away. {@code ItemImpl.diffInternal} builds a clean
     * add/delete pair, and {@code PrismPropertyImpl.fixupDelta} — overridden for single-valued
     * <em>properties</em> alone — calls {@code delta.clear()} and rewrites it as a bare replace, without
     * copying the old value into {@code estimatedOldValues}. So the delta says where to go but not where it
     * came from. Multi-valued items and references keep their delete side and need nothing here.
     *
     * <p>Two things depend on putting it back. A saved plan becomes reviewable — a reader sees what is being
     * overwritten, not just the new value — and {@code ObjectDelta.createReverseDelta()} becomes correct: it
     * reverses a replace by substituting {@code estimatedOldValues}, and with none present it falls back to
     * an <em>empty</em> replace, so an undo would silently clear the item instead of restoring it.
     *
     * <p>The value is carried by {@code ItemDeltaType}'s {@code estimatedOldValue} element, which
     * {@code DeltaConvertor} serializes and parses back on its own, so the plan files round-trip unchanged.
     * It is informational to midPoint: the repository write path does not read it (midPoint's own delta
     * visualizer does, which is what renders "old &rarr; new" in the GUI).
     *
     * <p>A protected value is never recorded. The ciphertext re-encrypts on every fetch and has no business
     * in a file on disk; {@link #stripProtectedValues} has already dropped whole protected deltas, and this
     * guards the case where the delta's own definition does not say protected but the live item's does.
     */
    private static void recordOldValues(ObjectDelta<?> delta, PrismObject<?> live) {
        if (live == null) {
            return;
        }
        for (ItemDelta<?, ?> mod : delta.getModifications()) {
            if (mod.getValuesToReplace() == null || mod.getEstimatedOldValues() != null) {
                continue;
            }
            // The delta came out of live.diff(desired), so any container-id segment in the path is a
            // live-side id and resolves against the live object directly.
            Item<?, ?> liveItem = live.findItem(mod.getPath());
            if (liveItem == null || liveItem.hasNoValues() || isProtectedItem(liveItem)) {
                continue;
            }
            List<PrismValue> oldValues = new ArrayList<>();
            for (PrismValue value : liveItem.getValues()) {
                oldValues.add(value.clone());
            }
            setEstimatedOldValues(mod, oldValues);
        }
    }

    /** {@link ItemDelta#setEstimatedOldValues} is typed on the delta's value type, which is a wildcard here. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void setEstimatedOldValues(ItemDelta<?, ?> mod, List<PrismValue> values) {
        ((ItemDelta) mod).setEstimatedOldValues(values);
    }

    private static boolean isProtectedItem(Item<?, ?> item) {
        ItemDefinition<?> definition = item.getDefinition();
        if (definition != null && ProtectedStringType.COMPLEX_TYPE.equals(definition.getTypeName())) {
            return true;
        }
        return hasProtectedValue(item.getValues());
    }

    /**
     * Find modifications that edit an accrued (live-only) container value in place under a merge-designated
     * container. When merge is combined with an id- or natural-key-considering strategy, prism may pair a
     * live-only value with a desired value and emit a deep in-place edit — e.g. {@code assignment/2/targetRef}
     * — instead of a clean whole-value add/delete. {@code applyMerge} operates on the container path and
     * never sees such a deep edit, so the accrued value is silently overwritten rather than preserved.
     *
     * <p>The signature is a path that descends into a merge container via a container-id segment (name path
     * before the first id = the container; the id = the value being edited) whose value the desired object
     * does <em>not</em> declare. An in-place edit of a value that <em>is</em> declared in desired is a
     * legitimate managed update (the user's desired state carries that value), so it is not reported — see
     * {@link #desiredDeclaresValueAt}, which matches by container id <em>or</em> by the schema's natural key.
     *
     * @return the paths of accrued in-place edits — empty for a clean real-value union
     */
    private static List<String> findAccruedInPlaceEdits(ObjectDelta<?> delta, PrismObject<?> desired,
            PrismObject<?> live, MultiValueStrategy multiValue) {
        List<String> flagged = new ArrayList<>();
        for (ItemDelta<?, ?> mod : delta.getModifications()) {
            if (accruedEditLevel(mod.getPath(), desired, live, multiValue) != null) {
                flagged.add(mod.getPath().toString());
            }
        }
        return flagged;
    }

    /**
     * The path prefix at which this delta edits an accrued value inside a merged container, or {@code null}
     * when it does not.
     *
     * <p><b>Every</b> container the path descends into is examined, not just the outermost one. A delta path
     * can cross several multi-valued containers ({@code adminGuiConfiguration/objectDetails/
     * objectDetailsPage/1/panel/10/display/label}), and merge can be requested at any of them — a
     * {@code --path} rule or a {@code --policy} selector naming the inner container is explicitly supported,
     * and {@link #applyMerge} honours it at the full id-free path. Testing only the outermost level meant
     * such a rule dropped that container's deletes without ever arming this refusal.
     *
     * <p>Each merged level is judged at <em>its own</em> depth: the question is whether desired declares the
     * value being edited <em>at that level</em>. A level on {@code replace} is skipped, because editing a
     * value in place is replace's own semantics and flagging it would refuse a perfectly ordinary update.
     */
    private static ItemPath accruedEditLevel(ItemPath path, PrismObject<?> desired, PrismObject<?> live,
            MultiValueStrategy multiValue) {
        List<?> segments = path.getSegments();
        List<QName> containerNames = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            Object segment = segments.get(i);
            if (ItemPath.isName(segment)) {
                containerNames.add(ItemPath.toName(segment));
                continue;
            }
            if (!ItemPath.isId(segment) || containerNames.isEmpty()) {
                continue;
            }
            // A container-id segment: the names before it name the container, the id names the value edited.
            if (!multiValue.isMerge(ItemPath.create(containerNames.toArray()))) {
                continue;
            }
            ItemPath throughThisValue = ItemPath.create(segments.subList(0, i + 1).toArray());
            if (!desiredDeclaresValueAt(desired, live, throughThisValue)) {
                return throughThisValue;
            }
        }
        return null;
    }

    /**
     * Whether the desired object declares every container value the delta path descends through — i.e. the
     * edit lands inside managed state rather than on an accrued (live-only) value.
     *
     * <p>Each container-id segment of the path is resolved against the desired object one level at a time:
     * the value is matched <b>by container id</b>, or — when that fails — <b>by the schema's natural key</b>
     * ({@code a:naturalKey}, e.g. {@code panel} keyed by {@code identifier}, {@code objectDetailsPage} by
     * {@code type}), comparing the live value at that id against the values desired declares. The key match
     * is what makes an id-less desired fragment work: a hand-written fragment carries no container ids, so
     * under a natural-key strategy prism pairs its values with the live ones and emits a deep in-place edit
     * — which is exactly the intended managed update, not an accrued value being overwritten.
     */
    private static boolean desiredDeclaresValueAt(PrismObject<?> desired, PrismObject<?> live, ItemPath path) {
        PrismContainerValue<?> desiredParent = desired.getValue();
        PrismContainerValue<?> liveParent = (live != null) ? live.getValue() : null;
        List<QName> names = new ArrayList<>();
        for (Object segment : path.getSegments()) {
            if (ItemPath.isName(segment)) {
                names.add(ItemPath.toName(segment));
                continue;
            }
            if (!ItemPath.isId(segment)) {
                return false; // an exotic segment (e.g. a reference deref): not something we can vouch for
            }
            ItemPath containerPath = ItemPath.create(names.toArray());
            names.clear();
            if (containerPath.isEmpty()) {
                return false;
            }
            Item<?, ?> desiredItem = desiredParent.findItem(containerPath);
            if (!(desiredItem instanceof PrismContainer<?> desiredContainer)) {
                return false; // desired does not declare this container at all -> the value is live-only
            }
            PrismContainerValue<?> liveValue =
                    containerValueById(liveParent, containerPath, ItemPath.toId(segment));
            PrismContainerValue<?> match =
                    matchingDesiredValue(desiredContainer, liveValue, ItemPath.toId(segment));
            if (match == null) {
                return false;
            }
            desiredParent = match;
            liveParent = liveValue;
        }
        return true;
    }

    /**
     * The desired container value matching {@code valueId} — by container id, else by natural key.
     *
     * <p>The same pairing {@link FragmentUnion} uses to decide whether two files describe one entry, which is
     * why it lives in {@link NaturalKeys}: "is this the same entry of the list?" has to be answered
     * identically wherever it is asked, or a value combined on the way in would be flagged as accrued on the
     * way out.
     */
    private static PrismContainerValue<?> matchingDesiredValue(PrismContainer<?> desiredContainer,
            PrismContainerValue<?> liveValue, Long valueId) {
        return NaturalKeys.match(desiredContainer, liveValue, valueId);
    }

    /** Whether {@code value} actually carries the natural key's constituent item(s) — a discriminating key. */
    private static boolean carriesKey(NaturalKeyDefinition naturalKey, PrismContainerValue<?> value) {
        return NaturalKeys.carries(naturalKey, value);
    }

    /** The container value carrying {@code valueId} at {@code containerPath} under {@code parent}, or null. */
    private static PrismContainerValue<?> containerValueById(PrismContainerValue<?> parent,
            ItemPath containerPath, Long valueId) {
        if (parent == null) {
            return null;
        }
        Item<?, ?> item = parent.findItem(containerPath);
        if (!(item instanceof PrismContainer<?> container)) {
            return null;
        }
        for (PrismContainerValue<?> value : container.getValues()) {
            if (valueId.equals(value.getId())) {
                return value;
            }
        }
        return null;
    }

    /**
     * Static sanity checks on the <em>input</em>, reported before anything is written. Walks the desired
     * object against the live one, pairing keyed container values, and flags the two ways a partial fragment
     * silently destroys live state:
     *
     * <ul>
     *   <li><b>a missing natural key</b> — a value of a keyed multi-valued container that does not carry its
     *       key ({@code a:naturalKey}) cannot be matched to a live value, so it degrades to a whole-value
     *       add/delete instead of an update;</li>
     *   <li><b>a partially declared container</b> — desired lists only some of a keyed container's live
     *       values while that path is on {@code replace}, so the unlisted ones are deleted. Names
     *       {@code multivalue=merge} as the fix.</li>
     * </ul>
     *
     * Only containers with a declared natural key are checked, which keeps this precise: those are exactly
     * the containers a fragment is expected to address by key.
     */
    private static List<String> findInputWarnings(PrismObject<?> desired, PrismObject<?> live,
            MultiValueStrategy multiValue) {
        List<String> warnings = new ArrayList<>();
        checkContainers(desired.getValue(), (live != null) ? live.getValue() : null,
                ItemPath.EMPTY_PATH, multiValue, warnings);
        return warnings;
    }

    private static void checkContainers(PrismContainerValue<?> desiredValue, PrismContainerValue<?> liveValue,
            ItemPath prefix, MultiValueStrategy multiValue, List<String> warnings) {
        for (Item<?, ?> item : desiredValue.getItems()) {
            if (!(item instanceof PrismContainer<?> desiredContainer)) {
                continue;
            }
            ItemDefinition<?> definition = desiredContainer.getDefinition();
            if (definition == null || definition.isOperational()) {
                continue;
            }
            ItemPath path = prefix.append(item.getElementName());
            PrismContainer<?> liveContainer = containerAt(liveValue, item.getElementName());

            if (definition.isMultiValue()) {
                NaturalKeyDefinition naturalKey = naturalKeyOf(desiredContainer);
                if (naturalKey != null) {
                    checkKeyedContainer(desiredContainer, liveContainer, naturalKey, path, multiValue, warnings);
                }
                for (PrismContainerValue<?> child : desiredContainer.getValues()) {
                    checkContainers(child, matchingLiveValue(liveContainer, child, naturalKey),
                            path, multiValue, warnings);
                }
            } else {
                PrismContainerValue<?> liveChild = (liveContainer != null && !liveContainer.getValues().isEmpty())
                        ? liveContainer.getValues().iterator().next() : null;
                for (PrismContainerValue<?> child : desiredContainer.getValues()) {
                    checkContainers(child, liveChild, path, multiValue, warnings);
                }
            }
        }
    }

    private static void checkKeyedContainer(PrismContainer<?> desiredContainer, PrismContainer<?> liveContainer,
            NaturalKeyDefinition naturalKey, ItemPath path, MultiValueStrategy multiValue, List<String> warnings) {
        if (liveContainer == null) {
            return; // nothing live to be matched against or destroyed
        }
        // Only complain about a missing key when the key is actually in play — i.e. the live values use it.
        // Plenty of containers declare a natural key that real config never fills in (an `assignment` or
        // `inducement` rarely carries an <identifier>); warning on those would be pure noise.
        boolean keyInPlay = false;
        for (PrismContainerValue<?> live : liveContainer.getValues()) {
            if (carriesKey(naturalKey, live)) {
                keyInPlay = true;
                break;
            }
        }
        if (keyInPlay) {
            int index = 0;
            for (PrismContainerValue<?> value : desiredContainer.getValues()) {
                index++;
                if (!carriesKey(naturalKey, value)) {
                    warnings.add("'" + path + "' value #" + index + " does not carry its natural key ("
                            + keyNames(desiredContainer) + "), so it cannot be matched to a live value; it will "
                            + "be treated as a whole-value change instead of an update of the matching entry.");
                }
            }
        }

        if (multiValue.isMerge(path)) {
            return; // merge keeps live-only values, so a partial declaration is intended there
        }
        int unmatched = 0;
        for (PrismContainerValue<?> live : liveContainer.getValues()) {
            if (!declaredInDesired(desiredContainer, live, naturalKey)) {
                unmatched++;
            }
        }
        if (unmatched > 0) {
            // Both numbers count live values. Reporting the *desired* count against the live total mixed two
            // denominators and could read as arithmetically impossible ("declares 1 of 2 ... the other 2 will
            // be DELETED"), which understates what is about to go: an operator reasonably concludes one entry
            // is being kept.
            int matched = liveContainer.getValues().size() - unmatched;
            // What to advise depends on whether the key is usable here. Naming equivalence=natural-keys
            // unconditionally was fine when real-value was the default; under auto it is bad advice for
            // exactly the containers auto declines — pinning it would widen natural-key pairing to the whole
            // object and mispair the values whose key nothing fills in, which is what auto exists to avoid.
            boolean keyUsable = keyInPlay;
            for (PrismContainerValue<?> value : desiredContainer.getValues()) {
                keyUsable = keyUsable && carriesKey(naturalKey, value);
            }
            warnings.add("'" + path + "': desired matches " + matched + " of "
                    + liveContainer.getValues().size() + " live value(s), and this path is on "
                    + "multivalue=replace, so the other " + unmatched + " will be DELETED. Set '" + path
                    + " multivalue=merge' to update just the entries you list."
                    + (keyUsable ? "" : " Their natural key (" + keyNames(desiredContainer)
                            + ") is not filled in on every value, so they can only be matched by content — "
                            + "fill it in to have them updated individually."));
        }
    }

    /**
     * Whether desired declares {@code live} — by container id, by natural key, or by plain content
     * equivalence. Content is the generous fallback that keeps this check quiet for keyless-in-practice
     * containers, where a fragment simply restates the values it manages verbatim.
     */
    /** Live values of one multi-valued container that the desired object does not declare. */
    record LiveOnly(ItemPath path, String pathText, int count) {
    }

    /**
     * Per multi-valued container the desired object declares, how many values {@code live} holds that it does
     * not — by container id, natural key, or content, the same test the partial-declaration warning uses.
     * Under {@code replace} these are what a PATCH deletes; under {@code merge} they are what it keeps. Either
     * way they are the entries the tree does not own, which is what makes them worth naming: an entry someone
     * added on the server is invisible to a merge otherwise. Nested containers are followed into the matching
     * live value, so a panel added inside a declared page counts under the panel's own path.
     */
    static List<LiveOnly> liveOnlyValues(PrismObject<? extends Objectable> desired,
            PrismObject<? extends Objectable> live) {
        Map<String, LiveOnly> found = new LinkedHashMap<>();
        if (live != null) {
            // With the diff's own view of which ids are this server's, or a foreign id would count as declared.
            PrismObject<? extends Objectable> pairable = ContainerIds.trustedOnly(desired, live).desired();
            collectLiveOnly(pairable.getValue(), live.getValue(), ItemPath.EMPTY_PATH, found);
        }
        return new ArrayList<>(found.values());
    }

    private static void collectLiveOnly(PrismContainerValue<?> desiredValue, PrismContainerValue<?> liveValue,
            ItemPath prefix, Map<String, LiveOnly> found) {
        if (liveValue == null) {
            return;
        }
        for (Item<?, ?> item : desiredValue.getItems()) {
            if (!(item instanceof PrismContainer<?> desiredContainer)) {
                continue;
            }
            ItemDefinition<?> definition = desiredContainer.getDefinition();
            if (definition == null || definition.isOperational()) {
                continue;
            }
            ItemPath path = prefix.append(item.getElementName());
            PrismContainer<?> liveContainer = containerAt(liveValue, item.getElementName());
            if (definition.isMultiValue()) {
                NaturalKeyDefinition naturalKey = naturalKeyOf(desiredContainer);
                if (liveContainer != null) {
                    int undeclared = 0;
                    for (PrismContainerValue<?> live : liveContainer.getValues()) {
                        if (!declaredInDesired(desiredContainer, live, naturalKey)) {
                            undeclared++;
                        }
                    }
                    if (undeclared > 0) {
                        String text = String.join("/", localNames(path));
                        LiveOnly previous = found.get(text);
                        found.put(text, new LiveOnly(path, text,
                                undeclared + (previous != null ? previous.count() : 0)));
                    }
                }
                for (PrismContainerValue<?> child : desiredContainer.getValues()) {
                    collectLiveOnly(child, matchingLiveValue(liveContainer, child, naturalKey), path, found);
                }
            } else {
                PrismContainerValue<?> liveChild = (liveContainer != null && !liveContainer.getValues().isEmpty())
                        ? liveContainer.getValues().iterator().next() : null;
                for (PrismContainerValue<?> child : desiredContainer.getValues()) {
                    collectLiveOnly(child, liveChild, path, found);
                }
            }
        }
    }

    private static boolean declaredInDesired(PrismContainer<?> desiredContainer, PrismContainerValue<?> live,
            NaturalKeyDefinition naturalKey) {
        boolean keyed = naturalKey != null && carriesKey(naturalKey, live);
        for (PrismContainerValue<?> value : desiredContainer.getValues()) {
            if (live.getId() != null && live.getId().equals(value.getId())) {
                return true;
            }
            if (keyed && carriesKey(naturalKey, value) && naturalKey.valuesMatch(live, value)) {
                return true;
            }
            if (EquivalenceStrategy.REAL_VALUE.equals(live, value)) {
                return true;
            }
        }
        return false;
    }

    /** The live container value matching {@code desiredValue} by container id, else by natural key. */
    private static PrismContainerValue<?> matchingLiveValue(PrismContainer<?> liveContainer,
            PrismContainerValue<?> desiredValue, NaturalKeyDefinition naturalKey) {
        if (liveContainer == null) {
            return null;
        }
        for (PrismContainerValue<?> live : liveContainer.getValues()) {
            if (live.getId() != null && live.getId().equals(desiredValue.getId())) {
                return live;
            }
        }
        if (naturalKey == null || !carriesKey(naturalKey, desiredValue)) {
            return null;
        }
        for (PrismContainerValue<?> live : liveContainer.getValues()) {
            if (carriesKey(naturalKey, live) && naturalKey.valuesMatch(live, desiredValue)) {
                return live;
            }
        }
        return null;
    }

    private static PrismContainer<?> containerAt(PrismContainerValue<?> parent, QName name) {
        if (parent == null) {
            return null;
        }
        Item<?, ?> item = parent.findItem(ItemPath.create(name));
        return (item instanceof PrismContainer<?> container) ? container : null;
    }

    /** The local names of the container's natural-key constituents, for the warning message. */
    private static String keyNames(PrismContainer<?> container) {
        return NaturalKeys.names(container);
    }

    /** The container's schema-declared natural key ({@code a:naturalKey}), or null when it has none. */
    private static NaturalKeyDefinition naturalKeyOf(PrismContainer<?> container) {
        return NaturalKeys.of(container);
    }

    /** The paths of {@code paths} that the delta still modifies. */
    private static List<String> stillInDelta(List<String> paths, ObjectDelta<?> delta) {
        if (paths.isEmpty()) {
            return paths;
        }
        Set<String> remaining = new java.util.HashSet<>();
        for (ItemDelta<?, ?> mod : delta.getModifications()) {
            remaining.add(mod.getPath().toString());
        }
        return paths.stream().filter(remaining::contains).toList();
    }

    /**
     * Turn the add/delete deltas of merge-designated paths into merge (union) deltas: keep the additions
     * but stop deleting live-only values, so accrued runtime state survives. Because the diff runs under
     * a real-value strategy, values are matched by content — a value that appears as a delete has no desired
     * counterpart by content, so dropping the delete simply preserves it (no duplication: a desired value
     * already present in live would have matched and not diffed).
     *
     * <p>Merge is union semantics. To change a managed value in place, keep that path on {@code replace};
     * under merge a content change of a value without a key reads as "old value gone, new value added", so
     * the old value is preserved <em>and</em> the new one is added.
     *
     * <p><b>Except the old form of an entry desired declares.</b> Where a container's values are compared by
     * content — {@code auto}'s {@code real-value} half, or {@code --equivalence real-value} — an entry desired
     * changed is not paired with its live copy either: it diffs to the same add-new, delete-old. But when the
     * live value carries a container id or a natural key that a desired value also carries, it is not a value
     * beyond the tree; it is that entry's old form. Dropping its delete left both on the server — two
     * {@code schemaHandling/objectType} values with one {@code kind + intent}, which midPoint rejects as a
     * duplicate definition — while the removal detector, which pairs by key, only deleted the items the tree
     * had dropped from inside it. So that delete is kept. The pairing is {@link NaturalKeys#match}'s, the one
     * the removal detector and the fragment union use, so all three agree on which entry is which.
     *
     * <p>A <b>single-valued</b> item can never merge: union semantics have no meaning where only one value
     * fits. Whether it can merge is decided on the item's <em>cardinality</em>, not on the shape of its
     * delta, because the two do not agree. prism emits a whole-value replace only for single-valued
     * <em>properties</em> — {@code fixupDelta} is overridden in {@code PrismPropertyImpl} alone — while a
     * changed single-valued <em>reference</em> ({@code connectorRef}, {@code resourceRef}, a nested
     * {@code targetRef}) diffs to delete-old plus add-new. Dropping that delete would leave an add on an
     * item that holds one value, which midPoint rejects outright ("Attempt to put more than one value to
     * single-valued item") and which {@code --plan} cannot even apply to its own local projection.
     *
     * <p>The same goes for a wholesale container replace, which has no per-value deletes to drop. Either
     * way the path is returned so the caller can note that merge fell back to replace.
     *
     * @return the paths of merge-requested items that could only be expressed as a replace
     */
    private static List<String> applyMerge(ObjectDelta<?> delta, PrismObject<?> desired, PrismObject<?> live,
            MultiValueStrategy multiValue) {
        List<String> fallbacks = new ArrayList<>();
        List<ItemDelta<?, ?>> emptied = new ArrayList<>();

        for (ItemDelta<?, ?> mod : delta.getModifications()) {
            if (!multiValue.isMerge(mod.getPath())) {
                continue;
            }
            ItemDefinition<?> definition = mod.getDefinition();
            if (definition != null && definition.isSingleValue()) {
                // Left as it is, delete side included — but not reported: a single-valued item has one value,
                // so there is nothing for merge to keep and nothing it gave up. Reporting it named every
                // changed field of every object once merge became the default on a tracked tree.
                continue;
            }
            if (mod.getValuesToReplace() != null) {
                // Kept for definition-less deltas, where cardinality cannot be read.
                fallbacks.add(mod.getPath().toString());
                continue;
            }
            if (mod.getValuesToDelete() != null && !mod.getValuesToDelete().isEmpty()) {
                PrismContainer<?> declared = desiredContainerAt(desired, live, mod.getPath());
                List<PrismValue> liveOnly = new ArrayList<>();
                for (PrismValue value : mod.getValuesToDelete()) {
                    if (!(value instanceof PrismContainerValue<?> containerValue)
                            || NaturalKeys.match(declared, containerValue, containerValue.getId()) == null) {
                        liveOnly.add(value);
                    }
                }
                liveOnly.forEach(mod::removeValueToDelete); // preserve every live-only value (union by real value)
                if (mod.getValuesToDelete().isEmpty()) {
                    // An empty set is still a delete: it would go out as a <delete> with no value beside the add.
                    mod.clearValuesToDelete();
                }
                if (mod.isEmpty()) {
                    emptied.add(mod);
                }
            }
        }
        emptied.forEach(delta::removeModification);
        return fallbacks;
    }

    /**
     * The desired container a delta path names, or {@code null} when desired does not declare it. Each
     * container-id segment is followed to the desired value paired with the live value carrying that id, by
     * {@link NaturalKeys#match}; a live value on the way without a counterpart in desired also yields
     * {@code null}, since nothing below it is declared.
     */
    private static PrismContainer<?> desiredContainerAt(PrismObject<?> desired, PrismObject<?> live,
            ItemPath path) {
        PrismContainerValue<?> desiredParent = desired.getValue();
        PrismContainerValue<?> liveParent = (live != null) ? live.getValue() : null;
        List<QName> names = new ArrayList<>();
        for (Object segment : path.getSegments()) {
            if (ItemPath.isName(segment)) {
                names.add(ItemPath.toName(segment));
                continue;
            }
            if (!ItemPath.isId(segment) || names.isEmpty()) {
                return null;
            }
            ItemPath containerPath = ItemPath.create(names.toArray());
            names.clear();
            Item<?, ?> desiredItem = desiredParent.findItem(containerPath);
            if (!(desiredItem instanceof PrismContainer<?> desiredContainer)) {
                return null;
            }
            Long id = ItemPath.toId(segment);
            PrismContainerValue<?> liveValue = containerValueById(liveParent, containerPath, id);
            PrismContainerValue<?> match = NaturalKeys.match(desiredContainer, liveValue, id);
            if (match == null) {
                return null;
            }
            desiredParent = match;
            liveParent = liveValue;
        }
        if (names.isEmpty()) {
            return null;
        }
        Item<?, ?> item = desiredParent.findItem(ItemPath.create(names.toArray()));
        return (item instanceof PrismContainer<?> container) ? container : null;
    }

    /**
     * {@code current} with {@code delta} applied locally, on a copy, or {@code null} when prism cannot apply
     * it. What the object would look like after the PATCH, for the checks that have to judge the result rather
     * than the delta: the duplicate-key refusal ({@link NaturalKeys#introducedDuplicates}) and what merge keeps.
     */
    @SuppressWarnings({"unchecked", "rawtypes"}) // applyTo is typed by the object class the delta was built for
    static PrismObject<? extends Objectable> project(PrismObject<? extends Objectable> current,
            ObjectDelta<? extends Objectable> delta) {
        PrismObject<? extends Objectable> after = current.clone();
        try {
            if (!delta.isEmpty()) {
                ((ObjectDelta) delta).applyTo((PrismObject) after);
            }
            return after;
        } catch (Exception e) {
            LOG.trace("could not project the change onto {}", current.getOid(), e);
            return null;
        }
    }

    /**
     * The outcome of a diff: the delta plus any paths where merge had to fall back to replace, any
     * protected/encrypted paths that were skipped, and any accrued values a merge would edit in place.
     */
    public static final class DiffResult {

        private final ObjectDelta<? extends Objectable> delta;
        private final List<String> mergeFallbackPaths;
        private final List<String> protectedSkippedPaths;
        private final List<String> accruedInPlaceEditPaths;
        private final List<String> inputWarnings;
        private final List<String> smartReferencePaths;
        private final List<String> equivalenceChoices;
        private final List<String> keyPairingDeclined;
        private final List<String> incompleteSkippedPaths;
        private final List<KeptNested> keptNested;

        DiffResult(ObjectDelta<? extends Objectable> delta, List<String> mergeFallbackPaths,
                List<String> protectedSkippedPaths, List<String> accruedInPlaceEditPaths,
                List<String> inputWarnings, List<String> smartReferencePaths,
                List<String> equivalenceChoices, List<String> keyPairingDeclined,
                List<String> incompleteSkippedPaths, List<KeptNested> keptNested) {
            this.keptNested = List.copyOf(keptNested);
            this.delta = delta;
            this.mergeFallbackPaths = List.copyOf(mergeFallbackPaths);
            this.protectedSkippedPaths = List.copyOf(protectedSkippedPaths);
            this.accruedInPlaceEditPaths = List.copyOf(accruedInPlaceEditPaths);
            this.inputWarnings = List.copyOf(inputWarnings);
            this.smartReferencePaths = List.copyOf(smartReferencePaths);
            this.equivalenceChoices = List.copyOf(equivalenceChoices);
            this.keyPairingDeclined = List.copyOf(keyPairingDeclined);
            this.incompleteSkippedPaths = List.copyOf(incompleteSkippedPaths);
        }

        /**
         * Paths the live object reported as {@code incomplete} — the server did not send them, so they were
         * left out of the delta rather than diffed as empty (see {@code stripIncompleteItems}).
         */
        public List<String> incompleteSkippedPaths() {
            return incompleteSkippedPaths;
        }

        /**
         * Live values below the top level that a tracked diff kept because desired does not declare their item
         * (see {@code keepUndeclaredNested}); empty on any other run.
         */
        List<KeptNested> keptNested() {
            return keptNested;
        }

        /**
         * What {@code --equivalence auto} decided for this object, one entry per multi-valued container it
         * considered, each naming the chosen strategy and — where it declined {@code natural-keys} — why.
         * Empty when the operator named a strategy outright.
         */
        public List<String> equivalenceChoices() {
            return equivalenceChoices;
        }

        /**
         * How entries of multi-valued containers were paired where it is not the obvious way, one complete message
         * each: the containers {@code --equivalence auto} compared by content instead of pairing their entries by
         * natural key, naming the container below whose key is not filled in (a change inside such an entry
         * replaces the entry whole), and the containers whose ids in the file were numbered on another server and
         * so were not used for pairing (see {@link ContainerIds}).
         */
        public List<String> keyPairingDeclined() {
            return keyPairingDeclined;
        }

        /**
         * Paths where the live object holds midPoint's resolution of a smart reference the desired file
         * writes as a filter, and the difference was therefore left alone (see
         * {@code stripResolvedSmartReferences}).
         */
        public List<String> smartReferencePaths() {
            return smartReferencePaths;
        }

        /**
         * Advisories about the desired input itself — a value missing its natural key, or a keyed container
         * only partially declared while on replace (see {@code findInputWarnings}). Complete messages, ready
         * to print; empty when the input is unambiguous.
         */
        public List<String> inputWarnings() {
            return inputWarnings;
        }

        /** How many values this delta removes outright — the blast radius. */
        public int removedValueCount() {
            return DeltaOutput.removedValueCount(delta);
        }

        /** A loud warning naming what the delta removes, or {@code null} when it removes nothing. */
        public String removalWarning() {
            return DeltaOutput.removalWarning(delta);
        }

        public ObjectDelta<? extends Objectable> delta() {
            return delta;
        }

        /** Paths of merge-requested items that could only be expressed as a replace (see {@code applyMerge}). */
        public List<String> mergeFallbackPaths() {
            return mergeFallbackPaths;
        }

        /** Paths of protected/encrypted values that were dropped from the delta (see {@code stripProtectedValues}). */
        public List<String> protectedSkippedPaths() {
            return protectedSkippedPaths;
        }

        /**
         * Paths where a merge would edit an accrued (live-only) value in place rather than preserve it
         * (see {@code findAccruedInPlaceEdits}). Non-empty only under an id/natural-key-considering strategy.
         */
        public List<String> accruedInPlaceEditPaths() {
            return accruedInPlaceEditPaths;
        }

        public boolean hasDrift() {
            return !delta.isEmpty();
        }
    }

    /**
     * Multi-valued container handling: a global default plus repeatable per-path overrides.
     *
     * <ul>
     *   <li><b>replace</b> — desired container values are authoritative (live-only values are removed).</li>
     *   <li><b>merge</b> — union by key: add desired values, keep live-only ones.</li>
     * </ul>
     */
    public static final class MultiValueStrategy {

        /**
         * A per-path override: containers whose path matches {@code glob} use merge (or replace) regardless of
         * the global default. A plain rule (e.g. from {@code --path}) matches its glob against the container's
         * item path; an identity-scoped rule (from the deploy {@link Policy}) matches against the object's
         * identity prefix followed by the container path, so a selector can pin a container within a type/name.
         */
        public static final class PathRule {
            private final GlobPath glob;
            private final boolean merge;
            private final boolean identityScoped;

            /** An exact per-path rule (e.g. from {@code --path assignment=merge}); matches the item path alone. */
            public PathRule(ItemPath path, boolean merge) {
                this(GlobPath.ofItemPath(path), merge, false);
            }

            PathRule(GlobPath glob, boolean merge, boolean identityScoped) {
                this.glob = glob;
                this.merge = merge;
                this.identityScoped = identityScoped;
            }
        }

        private final boolean globalMerge;
        private final List<PathRule> rules;
        private final List<List<String>> identityPrefixes;

        private MultiValueStrategy(boolean globalMerge, List<PathRule> rules, List<List<String>> identityPrefixes) {
            this.globalMerge = globalMerge;
            this.rules = List.copyOf(rules);
            this.identityPrefixes = List.copyOf(identityPrefixes);
        }

        /** Everything replaces — the default, and identical to the pre-merge behaviour. */
        public static MultiValueStrategy replaceAll() {
            return new MultiValueStrategy(false, List.of(), List.of());
        }

        public static MultiValueStrategy of(boolean globalMerge, List<PathRule> rules) {
            return new MultiValueStrategy(globalMerge, rules, List.of());
        }

        /** As {@link #of(boolean, List)} but with identity prefixes for identity-scoped ({@link Policy}) rules. */
        static MultiValueStrategy of(boolean globalMerge, List<PathRule> rules, List<List<String>> identityPrefixes) {
            return new MultiValueStrategy(globalMerge, rules, identityPrefixes);
        }

        boolean isGlobalMerge() {
            return globalMerge;
        }

        List<PathRule> pathRules() {
            return rules;
        }

        /** True if merge applies anywhere — which is when key-aware container matching is needed. */
        public boolean isMergeActive() {
            if (globalMerge) {
                return true;
            }
            for (PathRule rule : rules) {
                if (rule.merge) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Whether the container at {@code containerPath} should merge. The most-specific matching rule wins,
         * resolved in two tiers: a matching identity-scoped rule (from the {@code --policy} file) first, and
         * only if none matches, the per-path CLI rules.
         *
         * <p>The tiers exist because the two kinds of glob are not comparable. An identity-scoped selector
         * carries one or two extra {@code Type}/{@code Name} segments that a {@code --path} glob has none of,
         * so ranking them together on literal count and prefix length let a deep {@code --path} outrank the
         * central policy — while the documented layering is the other way round: the CLI flags are the
         * baseline, and the policy is what varies it per object.
         */
        public boolean isMerge(ItemPath containerPath) {
            List<String> itemNames = localNames(containerPath);
            PathRule best = mostSpecific(itemNames, true);
            if (best == null) {
                best = mostSpecific(itemNames, false);
            }
            return (best != null) ? best.merge : globalMerge;
        }

        private PathRule mostSpecific(List<String> itemNames, boolean identityScoped) {
            PathRule best = null;
            for (PathRule rule : rules) {
                if (rule.identityScoped != identityScoped) {
                    continue;
                }
                boolean matched = identityScoped
                        ? matchesWithIdentity(rule.glob, itemNames)
                        : rule.glob.matches(itemNames);
                if (matched && (best == null || rule.glob.compareSpecificity(best.glob) > 0)) {
                    best = rule;
                }
            }
            return best;
        }

        private boolean matchesWithIdentity(GlobPath glob, List<String> itemNames) {
            for (List<String> prefix : identityPrefixes) {
                List<String> candidate = new ArrayList<>(prefix);
                candidate.addAll(itemNames);
                if (glob.matches(candidate)) {
                    return true;
                }
            }
            return false;
        }
    }
}
