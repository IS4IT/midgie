/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.equivalence.ParameterizedEquivalenceStrategy;
import com.evolveum.midpoint.prism.path.ItemName;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.util.exception.SchemaException;
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
        return diff(prismContext.parseObject(desiredFile), prismContext.parseObject(liveFile),
                strategy, scope, multiValue);
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

        // before.diff(after) => delta that, applied to `before`, yields `after`. before = live,
        // after = desired, so the delta reconciles the live object to the desired state. REAL_VALUE
        // matches container/property values by content (ignoring PCV ids), which yields clean whole-value
        // add/delete deltas — exactly what merge needs (see applyMerge). The command layer defaults merge
        // to real-value and only warns for other strategies; under an id-considering strategy merge may
        // see paired/edited values instead of clean add/delete, so applyMerge has less to strip.
        ObjectDelta<? extends Objectable> delta = reconcile(live, desired, strategy);

        List<String> mergeFallbacks = multiValue.isMergeActive() ? applyMerge(delta, multiValue) : List.of();

        if (scope == Scope.PRESENT) {
            retainItemsPresentIn(delta, desired);
        }

        // Protected/encrypted values (ProtectedStringType) re-encrypt on every fetch, so the ciphertext
        // differs each time even when the cleartext is identical. Diffing them yields spurious deltas and
        // would leak the ciphertext into the emitted PATCH, so drop those item deltas entirely and report
        // the paths. midPoint owns password rotation; a genuine password change must go through its own
        // credential API, not a blind PATCH of stale ciphertext.
        List<String> protectedSkipped = stripProtectedValues(delta);

        // Merge promises accrued (live-only) values survive. Under an id/natural-key-considering strategy,
        // prism can pair a live-only value with a desired value and edit it in place (a deep delta merge
        // cannot express as a union), which silently overwrites the accrued value. Report those paths so
        // the command layer can refuse by default. Empty for a clean real-value union of accrued values.
        List<String> accruedInPlaceEdits = multiValue.isMergeActive()
                ? findAccruedInPlaceEdits(delta, desired, multiValue) : List.of();

        LOG.debug("diff {} (scope={}, {}) → {} modification(s); mergeFallback={}, protectedSkipped={}, "
                + "accruedInPlace={}", desiredType != null ? desiredType.getSimpleName() : "?", scope,
                multiValue.isMergeActive() ? "merge" : "replace", delta.getModifications().size(),
                mergeFallbacks, protectedSkipped, accruedInPlaceEdits);
        return new DiffResult(delta, mergeFallbacks, protectedSkipped, accruedInPlaceEdits);
    }

    private static <O extends Objectable> ObjectDelta<O> reconcile(
            PrismObject<O> live, PrismObject<?> desired, ParameterizedEquivalenceStrategy strategy) {
        @SuppressWarnings("unchecked") // the two objects were verified to share a compile-time class
        PrismObject<O> desiredTyped = (PrismObject<O>) desired;
        return live.diff(desiredTyped, strategy);
    }

    /**
     * Drop item deltas whose top-level item does not appear in the desired object. With a live&rarr;desired
     * delta these are exactly the deltas that would remove live-only items, so dropping them leaves
     * unmanaged items alone.
     */
    private static void retainItemsPresentIn(ObjectDelta<?> delta, PrismObject<?> desired) {
        List<ItemDelta<?, ?>> toRemove = new ArrayList<>();
        for (ItemDelta<?, ?> mod : delta.getModifications()) {
            ItemName top = mod.getPath().firstName();
            if (top == null || desired.findItem(ItemPath.create(top)) == null) {
                toRemove.add(mod);
            }
        }
        toRemove.forEach(delta::removeModification);
    }

    /**
     * Drop item deltas that touch a {@link ProtectedStringType} value and return their paths. An item is
     * protected either by its schema definition (type is {@code ProtectedStringType}, e.g.
     * {@code credentials/password/value}) or by carrying a protected real value directly (a fallback for
     * when no definition is attached). Nested protected values inside a larger added/deleted container
     * (e.g. a password buried in an added assignment) are not detected here; those are rare and would need
     * a deep value walk — call this out in the docs rather than pretend it is handled.
     */
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
     * Find modifications that edit an accrued (live-only) container value in place under a merge-designated
     * container. When merge is combined with an id- or natural-key-considering strategy, prism may pair a
     * live-only value with a desired value and emit a deep in-place edit — e.g. {@code assignment/2/targetRef}
     * — instead of a clean whole-value add/delete. {@code applyMerge} operates on the container path and
     * never sees such a deep edit, so the accrued value is silently overwritten rather than preserved.
     *
     * <p>The signature is a path that descends into a merge container via a container-id segment (name path
     * before the first id = the container; the id = the value being edited) whose id is <em>not</em> present
     * in the desired object. An in-place edit of a value that <em>is</em> in desired is a legitimate managed
     * update (the user's desired state carries that value), so it is not reported.
     *
     * @return the paths of accrued in-place edits — empty for a clean real-value union
     */
    private static List<String> findAccruedInPlaceEdits(ObjectDelta<?> delta, PrismObject<?> desired,
            MultiValueStrategy multiValue) {
        List<String> flagged = new ArrayList<>();
        for (ItemDelta<?, ?> mod : delta.getModifications()) {
            List<QName> containerNames = new ArrayList<>();
            Long valueId = null;
            for (Object segment : mod.getPath().getSegments()) {
                if (ItemPath.isId(segment)) {
                    valueId = ItemPath.toId(segment);
                    break;
                }
                if (!ItemPath.isName(segment)) {
                    break;
                }
                containerNames.add(ItemPath.toName(segment));
            }
            if (valueId == null || containerNames.isEmpty()) {
                continue; // no container-id segment: a clean whole-value add/delete, not a paired in-place edit
            }
            ItemPath containerPath = ItemPath.create(containerNames.toArray());
            if (multiValue.isMerge(containerPath) && !desiredHasContainerValue(desired, containerPath, valueId)) {
                flagged.add(mod.getPath().toString());
            }
        }
        return flagged;
    }

    /** Whether the desired object carries a container value with {@code valueId} at {@code containerPath}. */
    private static boolean desiredHasContainerValue(PrismObject<?> desired, ItemPath containerPath, Long valueId) {
        Item<?, ?> item = desired.findItem(containerPath);
        if (!(item instanceof PrismContainer<?> container)) {
            return false; // the whole container is absent from desired, so the value is live-only (accrued)
        }
        for (PrismContainerValue<?> value : container.getValues()) {
            if (valueId.equals(value.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Turn the add/delete deltas of merge-designated paths into merge (union) deltas: keep the additions
     * but stop deleting live-only values, so accrued runtime state survives. Because the diff runs under
     * a real-value strategy, values are matched by content — a value that appears as a delete is a
     * genuine live-only value with no desired counterpart, so dropping the delete simply preserves it
     * (no duplication: a desired value already present in live would have matched and not diffed).
     *
     * <p>Merge is union semantics. To change a managed value in place, keep that path on {@code replace};
     * under merge a content change reads as "old value gone, new value added", so the old value is
     * preserved <em>and</em> the new one is added.
     *
     * <p>An item that diffs to a whole-value {@code replace} (single-valued items, or a wholesale
     * container replace) has no per-value deletes to drop, so merge cannot apply; its path is returned so
     * the caller can note that it fell back to replace.
     *
     * @return the paths of merge-requested items that could only be expressed as a replace
     */
    private static List<String> applyMerge(ObjectDelta<?> delta, MultiValueStrategy multiValue) {
        List<String> fallbacks = new ArrayList<>();
        List<ItemDelta<?, ?>> emptied = new ArrayList<>();

        for (ItemDelta<?, ?> mod : delta.getModifications()) {
            if (!multiValue.isMerge(mod.getPath())) {
                continue;
            }
            if (mod.getValuesToReplace() != null) {
                fallbacks.add(mod.getPath().toString());
                continue;
            }
            if (mod.getValuesToDelete() != null && !mod.getValuesToDelete().isEmpty()) {
                mod.clearValuesToDelete(); // preserve every live-only value (union by real value)
                if (mod.isEmpty()) {
                    emptied.add(mod);
                }
            }
        }
        emptied.forEach(delta::removeModification);
        return fallbacks;
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

        DiffResult(ObjectDelta<? extends Objectable> delta, List<String> mergeFallbackPaths,
                List<String> protectedSkippedPaths, List<String> accruedInPlaceEditPaths) {
            this.delta = delta;
            this.mergeFallbackPaths = List.copyOf(mergeFallbackPaths);
            this.protectedSkippedPaths = List.copyOf(protectedSkippedPaths);
            this.accruedInPlaceEditPaths = List.copyOf(accruedInPlaceEditPaths);
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

        /** A per-path override: this item path uses merge (or replace) regardless of the global default. */
        public static final class PathRule {
            private final ItemPath path;
            private final boolean merge;

            public PathRule(ItemPath path, boolean merge) {
                this.path = path;
                this.merge = merge;
            }
        }

        private final boolean globalMerge;
        private final List<PathRule> overrides;

        private MultiValueStrategy(boolean globalMerge, List<PathRule> overrides) {
            this.globalMerge = globalMerge;
            this.overrides = List.copyOf(overrides);
        }

        /** Everything replaces — the default, and identical to the pre-merge behaviour. */
        public static MultiValueStrategy replaceAll() {
            return new MultiValueStrategy(false, List.of());
        }

        public static MultiValueStrategy of(boolean globalMerge, List<PathRule> overrides) {
            return new MultiValueStrategy(globalMerge, overrides);
        }

        /** True if merge applies anywhere — which is when key-aware container matching is needed. */
        public boolean isMergeActive() {
            if (globalMerge) {
                return true;
            }
            for (PathRule rule : overrides) {
                if (rule.merge) {
                    return true;
                }
            }
            return false;
        }

        /** Whether the container at {@code containerPath} should merge. Per-path overrides win. */
        public boolean isMerge(ItemPath containerPath) {
            ItemPath normalized = containerPath.removeIds();
            for (PathRule rule : overrides) {
                if (rule.path.removeIds().equivalent(normalized)) {
                    return rule.merge;
                }
            }
            return globalMerge;
        }
    }
}
