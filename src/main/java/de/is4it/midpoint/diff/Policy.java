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
            Set.of("scope", "multivalue", "equivalence", "allow-inplace-merge", "smart-refs");
    private static final Set<String> OBJECT_ONLY_KNOBS =
            Set.of("scope", "equivalence", "allow-inplace-merge", "smart-refs");

    private static final class Rule {
        final GlobPath selector;
        final boolean itemLevel;
        final Map<String, String> settings;
        final int order;
        final String source;    // the line as written, for the "matched nothing" report
        boolean matched;

        Rule(GlobPath selector, boolean itemLevel, Map<String, String> settings, int order, String source) {
            this.selector = selector;
            this.itemLevel = itemLevel;
            this.settings = settings;
            this.order = order;
            this.source = source;
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
        String selectorText;
        String settingsText;
        if (line.startsWith("\"")) {
            // A quoted selector, for a name holding a character the bare form cannot express: '=' would
            // otherwise start the settings, and '/' would read as another path segment. midPoint names do
            // contain both (an org named 'ou=people'), and 'init --write-policy' emits names verbatim, so
            // without quoting midgie could generate a policy file midgie then refuses to load.
            int close = line.indexOf('"', 1);
            if (close < 0) {
                throw new IOException("policy line " + lineNumber + " has an unterminated quoted selector: "
                        + line);
            }
            selectorText = line.substring(1, close).strip();
            settingsText = line.substring(close + 1).strip();
        } else {
            String[] tokens = line.split("\\s+");
            int firstSetting = -1;
            for (int i = 0; i < tokens.length; i++) {
                // Only a known knob starts the settings: an object name may contain '=' too, and treating
                // any '=' token as a setting made 'OrgType/ou=people scope=full' fail with a message about
                // the line's shape rather than about the name.
                int eq = tokens[i].indexOf('=');
                if (eq > 0 && KNOWN_KNOBS.contains(tokens[i].substring(0, eq).toLowerCase())) {
                    firstSetting = i;
                    break;
                }
            }
            if (firstSetting <= 0) {
                throw new IOException("policy line " + lineNumber + " must be '<selector> <key=value>…' with "
                        + "a known knob (scope, multivalue, equivalence, allow-inplace-merge); quote the "
                        + "selector if the object name contains '=' or '/': " + line);
            }
            // The selector is everything before the first setting, so an object name may contain spaces.
            selectorText = String.join(" ", Arrays.copyOfRange(tokens, 0, firstSetting)).strip();
            settingsText = String.join(" ", Arrays.copyOfRange(tokens, firstSetting, tokens.length)).strip();
        }
        // A trailing ':' (a readability separator) is dropped.
        if (selectorText.endsWith(":")) {
            selectorText = selectorText.substring(0, selectorText.length() - 1).strip();
        }
        String[] settingTokens = settingsText.isEmpty() ? new String[0] : settingsText.split("\\s+");
        if (settingTokens.length == 0) {
            throw new IOException("policy line " + lineNumber + " has a selector but no settings: " + line);
        }
        // The leading Type/Name segments name an object, so 'prefix:' stripping must not reach them.
        GlobPath selector = GlobPath.parse(selectorText, identityDepth(selectorText));
        if (selectorText.isEmpty() || selector.isEmpty()) {
            throw new IOException("policy line " + lineNumber + " has an empty selector: " + line);
        }
        boolean itemLevel = isItemLevel(selectorText, selector);

        Map<String, String> settings = new LinkedHashMap<>();
        for (String token : settingTokens) {
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
        return new Rule(selector, itemLevel, settings, order, line.strip());
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
            case "smart-refs":
                return StrategyOptions.smartRefsByName(value) != null;
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
        StrategyOptions.SmartRefs smartRefs = baseline.smartRefs;
        boolean globalMerge = baseline.multiValue.isGlobalMerge();
        Rule scopeRule = null;
        Rule equivalenceRule = null;
        Rule allowInplaceRule = null;
        Rule smartRefsRule = null;
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
                    if (couldMatchIdentity(rule.selector, identity)) {
                        rule.matched = true;
                    }
                }
                continue;
            }
            if (!matchesIdentity(rule.selector, identity)) {
                continue;
            }
            rule.matched = true;
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
            if (rule.settings.containsKey("smart-refs")
                    && (smartRefsRule == null || moreSpecific(rule, smartRefsRule))) {
                smartRefs = StrategyOptions.smartRefsByName(rule.settings.get("smart-refs"));
                smartRefsRule = rule;
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
        return new StrategyOptions.Resolved(scope, equivalence, multiValue, allowInplace, smartRefs);
    }

    /**
     * Policy lines that were never offered to any object in this run.
     *
     * <p>Worth saying out loud: a typo'd type, a renamed object or a mangled selector resolves silently to
     * the CLI baseline, so a {@code multivalue=merge} rule written to protect accrued values quietly becomes
     * replace and the deploy deletes them. Nothing in the output distinguished that from a rule that
     * applied.
     */
    List<String> unmatchedRules() {
        List<String> unmatched = new ArrayList<>();
        for (Rule rule : rules) {
            if (!rule.matched) {
                unmatched.add(rule.source);
            }
        }
        return unmatched;
    }

    /** Whether an item-level selector's leading identity segments can apply to this object at all. */
    private static boolean couldMatchIdentity(GlobPath selector, List<List<String>> identity) {
        for (List<String> prefix : identity) {
            if (selector.matchesPrefix(prefix)) {
                return true;
            }
        }
        return false;
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
