package net.haeger.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.equivalence.ParameterizedEquivalenceStrategy;
import com.evolveum.midpoint.prism.path.ItemName;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.util.exception.SchemaException;

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
     * Compute the reconciling delta between two object files.
     *
     * @param desiredFile the desired-state object XML (source of truth for managed items)
     * @param liveFile    the current/live object XML
     * @param strategy    equivalence strategy controlling metadata/operational sensitivity (used when
     *                    no merge is active; merge forces key-aware container matching)
     * @param scope       {@link Scope#PRESENT} to preserve unmanaged items, {@link Scope#FULL} for full replace
     * @param multiValue  how multi-valued containers are treated (replace vs merge, with per-path overrides)
     * @return the delta plus any paths where merge fell back to replace
     */
    public DiffResult diff(File desiredFile, File liveFile, ParameterizedEquivalenceStrategy strategy,
            Scope scope, MultiValueStrategy multiValue) throws SchemaException, IOException {

        PrismObject<? extends Objectable> desired = prismContext.parseObject(desiredFile);
        PrismObject<? extends Objectable> live = prismContext.parseObject(liveFile);

        Class<?> desiredType = desired.getCompileTimeClass();
        Class<?> liveType = live.getCompileTimeClass();
        if (desiredType != null && liveType != null && !desiredType.equals(liveType)) {
            throw new SchemaException("Cannot diff objects of different types: live is "
                    + liveType.getSimpleName() + ", desired is " + desiredType.getSimpleName());
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
        return new DiffResult(delta, mergeFallbacks);
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

    /** The outcome of a diff: the delta plus any paths where merge had to fall back to replace. */
    public static final class DiffResult {

        private final ObjectDelta<? extends Objectable> delta;
        private final List<String> mergeFallbackPaths;

        DiffResult(ObjectDelta<? extends Objectable> delta, List<String> mergeFallbackPaths) {
            this.delta = delta;
            this.mergeFallbackPaths = List.copyOf(mergeFallbackPaths);
        }

        public ObjectDelta<? extends Objectable> delta() {
            return delta;
        }

        /** Paths of merge-requested items that could only be expressed as a replace (see {@code applyMerge}). */
        public List<String> mergeFallbackPaths() {
            return mergeFallbackPaths;
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
