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

    @Option(names = "--multivalue", paramLabel = "replace|merge", defaultValue = "replace",
            description = "How to treat multi-valued containers. Per-path overrides via --path.")
    String multivalue;

    @Option(names = "--path", paramLabel = "<itemPath>=merge|replace",
            description = "Per-path multi-value strategy override. Repeatable.")
    String[] pathStrategies;

    @Option(names = "--equivalence",
            paramLabel = "real-value|consider-different-ids|natural-keys|ignore-metadata|data|literal",
            defaultValue = "real-value",
            description = "Value-equivalence strategy, loosest to strictest. See parseEquivalence() for what each considers.")
    String equivalence;

    @Option(names = "--allow-inplace-merge",
            description = "Proceed even when a merge would edit an accrued (live-only) value in place instead "
                    + "of preserving it (can happen under --equivalence natural-keys). Off by default: the "
                    + "run is refused so accrued state is not silently overwritten.")
    boolean allowInplaceMerge;

    /** Parsed, validated strategy set consumed by {@link DiffEngine}. */
    public static final class Resolved {
        final DiffEngine.Scope scope;
        final ParameterizedEquivalenceStrategy equivalence;
        final DiffEngine.MultiValueStrategy multiValue;
        final boolean allowInplaceMerge;

        Resolved(DiffEngine.Scope scope, ParameterizedEquivalenceStrategy equivalence,
                DiffEngine.MultiValueStrategy multiValue, boolean allowInplaceMerge) {
            this.scope = scope;
            this.equivalence = equivalence;
            this.multiValue = multiValue;
            this.allowInplaceMerge = allowInplaceMerge;
        }
    }

    /** Validate every strategy flag. On bad input prints a message to stderr and returns {@code null}. */
    public Resolved resolve() {
        DiffEngine.Scope scopeValue = parseScope();
        if (scopeValue == null) {
            return null;
        }
        ParameterizedEquivalenceStrategy strategy = parseEquivalence();
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
        return new Resolved(scopeValue, strategy, multiValue, allowInplaceMerge);
    }

    private DiffEngine.Scope parseScope() {
        DiffEngine.Scope value = scopeByName(scope);
        if (value == null) {
            System.err.println("midgie: --scope must be 'present' or 'full', got: " + scope);
        }
        return value;
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
     *   real-value (default)    REAL_VALUE                                      ignores container ids, metadata,
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
     */
    private ParameterizedEquivalenceStrategy parseEquivalence() {
        ParameterizedEquivalenceStrategy strategy = equivalenceByName(equivalence);
        if (strategy == null) {
            System.err.println("midgie: --equivalence must be "
                    + "real-value|consider-different-ids|natural-keys|ignore-metadata|data|literal, got: "
                    + equivalence);
            return null;
        }
        if (strategy == EquivalenceStrategy.REAL_VALUE_CONSIDER_DIFFERENT_IDS_NATURAL_KEYS) {
            System.err.println("midgie: note: --equivalence natural-keys "
                    + "(REAL_VALUE_CONSIDER_DIFFERENT_IDS_NATURAL_KEYS) is prism-experimental; "
                    + "container-value matching by natural key may behave unexpectedly.");
        }
        return strategy;
    }

    /** Map an {@code --equivalence} name to a prism strategy, or {@code null} if unrecognised. Shared with {@link Policy}. */
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
        switch (multivalue.toLowerCase()) {
            case "replace":
                globalMerge = false;
                break;
            case "merge":
                globalMerge = true;
                break;
            default:
                System.err.println("midgie: --multivalue must be 'replace' or 'merge', got: " + multivalue);
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
