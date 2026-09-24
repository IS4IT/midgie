/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.ArrayList;
import java.util.List;

import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.prism.equivalence.ParameterizedEquivalenceStrategy;
import com.evolveum.midpoint.prism.path.ItemPath;

import picocli.CommandLine.Option;

/**
 * Shared diff-strategy flags for the {@code diff} and {@code apply} subcommands: {@code --scope},
 * {@code --multivalue} / {@code --path}, and {@code --equivalence}. Mixed into each command with
 * {@code @Mixin}; {@link #resolve()} validates the flags and yields the engine-level strategy set.
 * See {@link DiffEngine} for the semantics.
 */
public final class StrategyOptions {

    @Option(names = "--scope", paramLabel = "present|full", defaultValue = "present",
            description = "present: only diff items present in DESIRED (preserve the rest). full: full replace.")
    String scope;

    @Option(names = "--multivalue", paramLabel = "auto|merge|replace", defaultValue = "auto",
            description = "How to treat live values a multi-valued item holds and the file does not list. "
                    + "merge: keep them. replace: delete them. auto (the default): merge when deploy tracks "
                    + "the tree — it then removes what the tree dropped by itself — else replace. "
                    + "Per-path overrides via --path.")
    String multivalue;

    /** Set when {@code --multivalue} was left on {@code auto}, which the live layer settles per run. */
    private boolean multiValueAuto;

    @Option(names = "--path", paramLabel = "<itemPath>=merge|replace",
            description = "Per-path multi-value strategy override. Repeatable.")
    String[] pathStrategies;

    @Option(names = "--equivalence",
            paramLabel = "auto|real-value|consider-different-ids|natural-keys|ignore-metadata|data|literal",
            defaultValue = "auto",
            description = "Value-equivalence strategy; 'auto' (the default) picks per container. "
                    + "The rest are listed loosest to strictest. See parseEquivalence() for what each considers.")
    String equivalence;

    @Option(names = "--smart-refs", paramLabel = "accept|verify|resolve", defaultValue = "accept",
            description = "How a reference the desired file writes as a search filter is judged against the "
                    + "OID midPoint resolved it to at import. accept: take the live OID as that resolution "
                    + "(comparing filters when the server kept one). verify: confirm it by running the "
                    + "filter against the server. resolve: always send the file's form and let midPoint "
                    + "re-resolve it.")
    String smartRefs;

    @Option(names = "--allow-inplace-merge",
            description = "Proceed even when a merge would edit an accrued (live-only) value in place instead "
                    + "of preserving it (can happen under --equivalence natural-keys). Off by default: the "
                    + "run is refused so accrued state is not silently overwritten.")
    boolean allowInplaceMerge;

    /** Parsed, validated strategy set consumed by {@link DiffEngine}. */
    /** How a filter-written reference is judged against the OID midPoint resolved it to. */
    public enum SmartRefs { ACCEPT, VERIFY, RESOLVE }

    public static final class Resolved {
        final DiffEngine.Scope scope;
        final DiffEngine.EquivalencePlan equivalence;
        final DiffEngine.MultiValueStrategy multiValue;
        final boolean allowInplaceMerge;
        final SmartRefs smartRefs;
        final ApplyRules apply;   // only a --policy file sets these; the CLI has no per-item flag for it
        /**
         * {@code --multivalue auto}, not yet settled: {@link #multiValue} holds {@code replace} as the global
         * default until {@link #settleMultiValue} learns whether this run can track removals.
         */
        final boolean multiValueAuto;

        Resolved(DiffEngine.Scope scope, DiffEngine.EquivalencePlan equivalence,
                DiffEngine.MultiValueStrategy multiValue, boolean allowInplaceMerge) {
            this(scope, equivalence, multiValue, allowInplaceMerge, SmartRefs.ACCEPT);
        }

        Resolved(DiffEngine.Scope scope, DiffEngine.EquivalencePlan equivalence,
                DiffEngine.MultiValueStrategy multiValue, boolean allowInplaceMerge, SmartRefs smartRefs) {
            this(scope, equivalence, multiValue, allowInplaceMerge, smartRefs, ApplyRules.none());
        }

        Resolved(DiffEngine.Scope scope, DiffEngine.EquivalencePlan equivalence,
                DiffEngine.MultiValueStrategy multiValue, boolean allowInplaceMerge, SmartRefs smartRefs,
                ApplyRules apply) {
            this(scope, equivalence, multiValue, allowInplaceMerge, smartRefs, apply, false);
        }

        Resolved(DiffEngine.Scope scope, DiffEngine.EquivalencePlan equivalence,
                DiffEngine.MultiValueStrategy multiValue, boolean allowInplaceMerge, SmartRefs smartRefs,
                ApplyRules apply, boolean multiValueAuto) {
            this.multiValueAuto = multiValueAuto;
            this.scope = scope;
            this.equivalence = equivalence;
            this.multiValue = multiValue;
            this.allowInplaceMerge = allowInplaceMerge;
            this.smartRefs = smartRefs;
            this.apply = apply;
        }

        /**
         * Settle {@code --multivalue auto}: {@code merge} when this run tracks removals, {@code replace} when it
         * cannot. Merge alone cannot express a removal or an edit of an entry without a natural key — the old
         * value is kept beside the new one — so it is only the default where the three-way comparison against
         * the last deployed revision removes what the tree dropped. A choice made on the command line stays.
         */
        Resolved settleMultiValue(boolean tracked) {
            if (!multiValueAuto) {
                return this;
            }
            return new Resolved(scope, equivalence, DiffEngine.MultiValueStrategy.of(tracked, multiValue.pathRules()),
                    allowInplaceMerge, smartRefs, apply, false);
        }

        /**
         * The offline half of the judgement. {@code verify} needs a server, so the live layer replaces this
         * with a searching check; here it behaves as {@code accept}, which is the correct fallback for a
         * mode that cannot run offline anyway.
         */
        DiffEngine.SmartReferenceCheck smartReferenceCheck() {
            return (smartRefs == SmartRefs.RESOLVE)
                    ? DiffEngine.SmartReferenceCheck.NEVER
                    : DiffEngine.SmartReferenceCheck.ACCEPT;
        }
    }

    /** Validate every strategy flag. On bad input prints a message to stderr and returns {@code null}. */
    public Resolved resolve() {
        DiffEngine.Scope scopeValue = parseScope();
        if (scopeValue == null) {
            return null;
        }
        DiffEngine.EquivalencePlan strategy = parseEquivalence();
        if (strategy == null) {
            return null;
        }
        DiffEngine.MultiValueStrategy multiValue = parseMultiValue();
        if (multiValue == null) {
            return null;
        }
        // A blanket "merge + non-real-value" warning used to fire here, but it cried wolf: an id-considering
        // strategy only harms accrued state when the diff actually pairs a live-only value and edits it in
        // place. DiffEngine now detects that precisely per delta (DiffResult.accruedInPlaceEditPaths); the
        // command layer refuses those unless --allow-inplace-merge is set.
        SmartRefs smartRefsValue = smartRefsByName(smartRefs);
        if (smartRefsValue == null) {
            System.err.println("midgie: --smart-refs must be 'accept', 'verify' or 'resolve', got: " + smartRefs);
            return null;
        }
        return new Resolved(scopeValue, strategy, multiValue, allowInplaceMerge, smartRefsValue, ApplyRules.none(),
                multiValueAuto);
    }

    private DiffEngine.Scope parseScope() {
        DiffEngine.Scope value = scopeByName(scope);
        if (value == null) {
            System.err.println("midgie: --scope must be 'present' or 'full', got: " + scope);
        }
        return value;
    }

    /** Map an {@code accept|verify|resolve} name, or {@code null} if unrecognised. Shared with {@link Policy}. */
    static SmartRefs smartRefsByName(String value) {
        if (value == null || value.isBlank()) {
            return SmartRefs.ACCEPT; // unset means the declared default, not a bad value
        }
        switch (value.toLowerCase()) {
            case "accept":
                return SmartRefs.ACCEPT;
            case "verify":
                return SmartRefs.VERIFY;
            case "resolve":
                return SmartRefs.RESOLVE;
            default:
                return null;
        }
    }

    /** Map a {@code present|full} name to a scope, or {@code null} if unrecognised. Shared with {@link Policy}. */
    static DiffEngine.Scope scopeByName(String value) {
        switch (value.toLowerCase()) {
            case "present":
                return DiffEngine.Scope.PRESENT;
            case "full":
                return DiffEngine.Scope.FULL;
            default:
                return null;
        }
    }

    /**
     * Map the {@code --equivalence} value to a prism {@link EquivalenceStrategy}. Each strategy answers
     * "are these two values the same?", which drives both what counts as drift and how multi-valued items
     * are matched. Presets, loosest to strictest (see
     * {@code com.evolveum.midpoint.prism.equivalence.ParameterizedEquivalenceStrategy}):
     *
     * <pre>
     *   CLI value               prism preset                                    ignores / considers
     *   ----------------------  ----------------------------------------------  --------------------------------
     *   auto (default)          per container: REAL_VALUE or the natural-key     see DiffEngine.EquivalencePlan
     *                           preset below, from whether the key is filled
     *   real-value              REAL_VALUE                                      ignores container ids, metadata,
     *                                                                           operational, element names, ns
     *                                                                           prefixes, ref filters/options
     *   consider-different-ids  REAL_VALUE_CONSIDER_DIFFERENT_IDS               as real-value, but PCV ids must
     *                                                                           not contradict
     *   natural-keys            REAL_VALUE_CONSIDER_DIFFERENT_IDS_NATURAL_KEYS  as consider-different-ids, plus
     *                                                                           container natural keys (@Experimental)
     *   ignore-metadata         IGNORE_METADATA                                ignores metadata/operational/ids;
     *                                                                           keeps element names + ref filters
     *   data                    DATA                                           parsed data incl. metadata + exact
     *                                                                           ids; ignores prefixes only
     *   literal                 LITERAL                                        as data, but ns prefixes matter too
     * </pre>
     *
     * The container-id knob is the one that changes multi-valued behaviour: id-agnostic {@code real-value}
     * yields clean whole-value add/delete, while the id-considering variants ({@code consider-different-ids},
     * {@code natural-keys}) pair values and edit them in place. That is why merge prefers {@code real-value}
     * and only warns for the rest. {@code natural-keys} is prism-{@code @Experimental} (the pair-and-edit
     * matching is the merge footgun described in {@link DiffEngine}); it is exposed for exploration against
     * a real server and prints an experimental note when selected.
     *
     * <p>{@code auto} is the default and is deliberately absent from the strictness ordering: it is not a
     * strategy of its own but a decision taken per multi-valued container, between {@code real-value} and
     * {@code natural-keys}, from whether the schema's key is actually filled in. See
     * {@link DiffEngine.EquivalencePlan} for why those two are the only candidates.
     */
    private DiffEngine.EquivalencePlan parseEquivalence() {
        DiffEngine.EquivalencePlan plan = equivalencePlanByName(equivalence);
        if (plan == null) {
            System.err.println("midgie: --equivalence must be "
                    + "auto|real-value|consider-different-ids|natural-keys|ignore-metadata|data|literal, got: "
                    + equivalence);
            return null;
        }
        // Only for an explicit choice: under auto the note would fire on most objects and say nothing the
        // operator can act on, since auto only picks natural-keys where the key is filled in and pairing is
        // therefore well-defined. What auto chose is reported per object instead.
        if (plan.fixedStrategy() == EquivalenceStrategy.REAL_VALUE_CONSIDER_DIFFERENT_IDS_NATURAL_KEYS) {
            System.err.println("midgie: note: --equivalence natural-keys "
                    + "(REAL_VALUE_CONSIDER_DIFFERENT_IDS_NATURAL_KEYS) is prism-experimental; "
                    + "container-value matching by natural key may behave unexpectedly.");
        }
        return plan;
    }

    /**
     * Map an {@code --equivalence} name to a plan — {@code auto}, or one fixed prism strategy — or
     * {@code null} if unrecognised. Shared with {@link Policy}, which validates against it.
     */
    static DiffEngine.EquivalencePlan equivalencePlanByName(String value) {
        if ("auto".equalsIgnoreCase(value)) {
            return DiffEngine.EquivalencePlan.auto();
        }
        ParameterizedEquivalenceStrategy strategy = equivalenceByName(value);
        return (strategy != null) ? DiffEngine.EquivalencePlan.of(strategy) : null;
    }

    /** Map an {@code --equivalence} name to a prism strategy, or {@code null} if unrecognised ({@code auto} included). */
    static ParameterizedEquivalenceStrategy equivalenceByName(String value) {
        switch (value.toLowerCase()) {
            case "real-value":
                return EquivalenceStrategy.REAL_VALUE;
            case "consider-different-ids":
                return EquivalenceStrategy.REAL_VALUE_CONSIDER_DIFFERENT_IDS;
            case "natural-keys":
                return EquivalenceStrategy.REAL_VALUE_CONSIDER_DIFFERENT_IDS_NATURAL_KEYS;
            case "ignore-metadata":
                return EquivalenceStrategy.IGNORE_METADATA;
            case "data":
                return EquivalenceStrategy.DATA;
            case "literal":
                return EquivalenceStrategy.LITERAL;
            default:
                return null;
        }
    }

    /**
     * The shared refusal message for accrued in-place edits (see
     * {@link DiffEngine.DiffResult#accruedInPlaceEditPaths()}). Printed by both {@code diff} and {@code apply}
     * before they emit or write a delta that would overwrite accrued state.
     */
    static String accruedInPlaceRefusal(List<String> paths) {
        return "midgie: refusing to merge: it would edit accrued (live-only) value(s) in place instead "
                + "of preserving them, silently overwriting them at " + String.join(", ", paths)
                + ". Use --equivalence real-value for a clean union, or --allow-inplace-merge to proceed anyway.";
    }

    /** Parse {@code --multivalue} plus any {@code --path <itemPath>=merge|replace} overrides. Null on error. */
    private DiffEngine.MultiValueStrategy parseMultiValue() {
        boolean globalMerge;
        multiValueAuto = false;
        switch (multivalue.toLowerCase()) {
            case "auto":
                // Unsettled until the live layer knows whether removals are tracked; replace until then, which
                // is also what every run that never asks (an offline diff) gets.
                multiValueAuto = true;
                globalMerge = false;
                break;
            case "replace":
                globalMerge = false;
                break;
            case "merge":
                globalMerge = true;
                break;
            default:
                System.err.println("midgie: --multivalue must be 'auto', 'merge' or 'replace', got: " + multivalue);
                return null;
        }

        List<DiffEngine.MultiValueStrategy.PathRule> overrides = new ArrayList<>();
        if (pathStrategies != null) {
            for (String spec : pathStrategies) {
                int eq = spec.lastIndexOf('=');
                if (eq <= 0 || eq == spec.length() - 1) {
                    System.err.println("midgie: --path must be <itemPath>=merge|replace, got: " + spec);
                    return null;
                }
                String pathText = spec.substring(0, eq).trim();
                String strategyText = spec.substring(eq + 1).trim().toLowerCase();

                boolean merge;
                if ("merge".equals(strategyText)) {
                    merge = true;
                } else if ("replace".equals(strategyText)) {
                    merge = false;
                } else {
                    System.err.println("midgie: --path strategy must be merge|replace, got: " + strategyText);
                    return null;
                }

                ItemPath path = parseItemPath(pathText);
                if (path.isEmpty()) {
                    System.err.println("midgie: --path has an empty itemPath in: " + spec);
                    return null;
                }
                overrides.add(new DiffEngine.MultiValueStrategy.PathRule(path, merge));
            }
        }
        return DiffEngine.MultiValueStrategy.of(globalMerge, overrides);
    }

    /**
     * Build an item path from a {@code /}-separated name list without a PrismContext (flags are parsed
     * before the context exists). Any {@code prefix:} is dropped — {@link DiffEngine.MultiValueStrategy}
     * matches names namespace-leniently, so unqualified local names line up with the diff's paths.
     */
    private static ItemPath parseItemPath(String text) {
        List<QName> names = new ArrayList<>();
        for (String segment : text.split("/")) {
            String name = segment.trim();
            if (name.isEmpty()) {
                continue;
            }
            int colon = name.indexOf(':');
            names.add(new QName(colon >= 0 ? name.substring(colon + 1) : name));
        }
        return ItemPath.create(names.toArray());
    }
}
