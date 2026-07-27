/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.evolveum.midpoint.prism.equivalence.ParameterizedEquivalenceStrategy;

/**
 * Per-object / per-item diff-strategy overrides for {@code deploy}, loaded from a central policy file
 * ({@code --policy}). Each non-blank, non-{@code #} line is {@code <selector> [:] <key=value>…}: the selector
 * is a single glob over {@code Type/Name/item/subitem…} (or {@code oid:<oid>/item…}) with {@code *} (exactly
 * one segment) and {@code **} (zero or more segments) at any level.
 *
 * <p>A selector that stops at the object identity sets object-level knobs ({@code scope}, {@code equivalence},
 * {@code allow-inplace-merge}, and a default {@code multivalue}); a selector that reaches an item sets
 * {@code multivalue} for that container (an object-only knob on an item-path selector is rejected). When
 * several rules set the same knob for the same target, the <b>most-specific</b> selector wins (more literal
 * segments, then fewer {@code **}, then a longer literal prefix; ties fall to the earlier line). The CLI flags
 * are the baseline every matching knob is layered onto. The object XML stays pure midPoint XML — strategy
 * lives here, never embedded in the objects.
 */
final class Policy {

    private static final Logger LOG = LoggerFactory.getLogger(Policy.class);

    private static final Set<String> KNOWN_KNOBS =
            Set.of("scope", "multivalue", "equivalence", "allow-inplace-merge");
    private static final Set<String> OBJECT_ONLY_KNOBS =
            Set.of("scope", "equivalence", "allow-inplace-merge");

    private static final class Rule {
        final GlobPath selector;
        final boolean itemLevel;
        final Map<String, String> settings;
        final int order;

        Rule(GlobPath selector, boolean itemLevel, Map<String, String> settings, int order) {
            this.selector = selector;
            this.itemLevel = itemLevel;
            this.settings = settings;
            this.order = order;
        }
    }

    private final List<Rule> rules;

    private Policy(List<Rule> rules) {
        this.rules = rules;
    }

    /** Parse and validate the policy file. Throws with the line number on a malformed rule. */
    static Policy load(File file) throws IOException {
        List<Rule> rules = new ArrayList<>();
        int lineNumber = 0;
        int order = 0;
        for (String raw : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            lineNumber++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            rules.add(parseLine(line, lineNumber, order++));
        }
        if (rules.isEmpty()) {
            // A comment-only file is legitimate: 'init --write-policy' writes exactly that for a tree with
            // nothing to tune, and rejecting it would break the very command line init tells you to run.
            LOG.debug("policy file {} has no rules; every object keeps the CLI strategy", file);
        }
        return new Policy(rules);
    }

    private static Rule parseLine(String line, int lineNumber, int order) throws IOException {
        String[] tokens = line.split("\\s+");
        int firstSetting = -1;
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].indexOf('=') > 0) { // the first key=value token starts the settings
                firstSetting = i;
                break;
            }
        }
        if (firstSetting <= 0) {
            throw new IOException("policy line " + lineNumber + " must be '<selector> <key=value>…': " + line);
        }
        // The selector is everything before the first setting, so an object name may contain spaces. A
        // trailing ':' (a readability separator) is dropped.
        String selectorText = String.join(" ", Arrays.copyOfRange(tokens, 0, firstSetting)).strip();
        if (selectorText.endsWith(":")) {
            selectorText = selectorText.substring(0, selectorText.length() - 1).strip();
        }
        // The leading Type/Name segments name an object, so 'prefix:' stripping must not reach them.
        GlobPath selector = GlobPath.parse(selectorText, identityDepth(selectorText));
        if (selectorText.isEmpty() || selector.isEmpty()) {
            throw new IOException("policy line " + lineNumber + " has an empty selector: " + line);
        }
        boolean itemLevel = isItemLevel(selectorText, selector);

        Map<String, String> settings = new LinkedHashMap<>();
        for (int i = firstSetting; i < tokens.length; i++) {
            String token = tokens[i];
            int eq = token.indexOf('=');
            if (eq <= 0 || eq == token.length() - 1) {
                throw new IOException("policy line " + lineNumber + " setting must be key=value: " + token);
            }
            String key = token.substring(0, eq).toLowerCase();
            String value = token.substring(eq + 1);
            if (!KNOWN_KNOBS.contains(key)) {
                throw new IOException("policy line " + lineNumber + " unknown knob '" + key
                        + "' (known: scope, multivalue, equivalence, allow-inplace-merge)");
            }
            if (itemLevel && OBJECT_ONLY_KNOBS.contains(key)) {
                throw new IOException("policy line " + lineNumber + " sets object-only knob '" + key
                        + "' on an item-path selector — only 'multivalue' is per-item.");
            }
            if (!validValue(key, value)) {
                throw new IOException("policy line " + lineNumber + " has an invalid value for '" + key
                        + "': " + value);
            }
            settings.put(key, value);
        }
        return new Rule(selector, itemLevel, settings, order);
    }

    /**
     * A selector is item-level (can only set {@code multivalue}) when it reaches past the object identity: it
     * contains {@code **}, or it is deeper than the identity (one segment for {@code oid:X}, else two for
     * {@code Type/Name}).
     */
    private static boolean isItemLevel(String selectorText, GlobPath selector) {
        return selector.containsGlobstar() || selector.size() > identityDepth(selectorText);
    }

    /** How many leading segments identify the object: one for {@code oid:X}, else {@code Type/Name}. */
    private static int identityDepth(String selectorText) {
        return selectorText.startsWith("oid:") ? 1 : 2;
    }

    private static boolean validValue(String key, String value) {
        switch (key) {
            case "scope":
                return StrategyOptions.scopeByName(value) != null;
            case "equivalence":
                return StrategyOptions.equivalenceByName(value) != null;
            case "multivalue":
                return mergeByName(value) != null;
            case "allow-inplace-merge":
                return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
            default:
                return false;
        }
    }

    private static Boolean mergeByName(String value) {
        if ("merge".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("replace".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * The effective strategy for one object: {@code baseline} (the CLI flags) with every matching policy knob
     * layered on, most-specific-wins per knob. Item-level {@code multivalue} rules become identity-scoped glob
     * {@link DiffEngine.MultiValueStrategy.PathRule}s resolved per container at diff time.
     */
    StrategyOptions.Resolved resolveFor(String type, String name, String oid, StrategyOptions.Resolved baseline) {
        List<List<String>> identity = new ArrayList<>();
        identity.add(List.of(type != null ? type : "", name != null ? name : ""));
        if (oid != null && !oid.isBlank()) {
            identity.add(List.of("oid:" + oid));
        }

        DiffEngine.Scope scope = baseline.scope;
        ParameterizedEquivalenceStrategy equivalence = baseline.equivalence;
        boolean allowInplace = baseline.allowInplaceMerge;
        boolean globalMerge = baseline.multiValue.isGlobalMerge();
        Rule scopeRule = null;
        Rule equivalenceRule = null;
        Rule allowInplaceRule = null;
        Rule globalMergeRule = null;

        // Policy rules first, CLI --path rules after. MultiValueStrategy.isMerge breaks a specificity tie by
        // list position, so seeding the baseline ahead of the policy made an equally specific policy rule
        // lose to the flag it was written to override — inverting the documented "the CLI is the baseline"
        // layering, and replacing values the policy asked to merge.
        List<DiffEngine.MultiValueStrategy.PathRule> itemRules = new ArrayList<>();

        for (Rule rule : rules) {
            if (rule.itemLevel) {
                String multivalue = rule.settings.get("multivalue");
                if (multivalue != null) {
                    itemRules.add(new DiffEngine.MultiValueStrategy.PathRule(
                            rule.selector, Boolean.TRUE.equals(mergeByName(multivalue)), true));
                }
                continue;
            }
            if (!matchesIdentity(rule.selector, identity)) {
                continue;
            }
            if (rule.settings.containsKey("scope") && (scopeRule == null || moreSpecific(rule, scopeRule))) {
                scope = StrategyOptions.scopeByName(rule.settings.get("scope"));
                scopeRule = rule;
            }
            if (rule.settings.containsKey("equivalence")
                    && (equivalenceRule == null || moreSpecific(rule, equivalenceRule))) {
                equivalence = StrategyOptions.equivalenceByName(rule.settings.get("equivalence"));
                equivalenceRule = rule;
            }
            if (rule.settings.containsKey("allow-inplace-merge")
                    && (allowInplaceRule == null || moreSpecific(rule, allowInplaceRule))) {
                allowInplace = Boolean.parseBoolean(rule.settings.get("allow-inplace-merge"));
                allowInplaceRule = rule;
            }
            if (rule.settings.containsKey("multivalue")
                    && (globalMergeRule == null || moreSpecific(rule, globalMergeRule))) {
                globalMerge = Boolean.TRUE.equals(mergeByName(rule.settings.get("multivalue")));
                globalMergeRule = rule;
            }
        }

        itemRules.addAll(baseline.multiValue.pathRules());
        DiffEngine.MultiValueStrategy multiValue =
                DiffEngine.MultiValueStrategy.of(globalMerge, itemRules, identity);
        return new StrategyOptions.Resolved(scope, equivalence, multiValue, allowInplace);
    }

    private static boolean matchesIdentity(GlobPath selector, List<List<String>> identity) {
        for (List<String> candidate : identity) {
            if (selector.matches(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** Most-specific-wins: higher selector specificity, or (tie) the earlier line. */
    private static boolean moreSpecific(Rule candidate, Rule current) {
        int bySelector = candidate.selector.compareSpecificity(current.selector);
        return (bySelector != 0) ? bySelector > 0 : candidate.order < current.order;
    }
}
