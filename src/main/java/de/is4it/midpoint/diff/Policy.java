/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Per-object / per-item diff-strategy overrides for {@code deploy}, loaded from a central policy file
 * ({@code --policy}). Each non-blank, non-{@code #} line is {@code <selector> [:] <key=value>…}: the selector
 * is a single glob over {@code Type/Name/item/subitem…} (or {@code oid:<oid>/item…}) with {@code *} (exactly
 * one segment) and {@code **} (zero or more segments) at any level. Each wildcard is a whole segment; a
 * segment that mixes one with other text is rejected at load time rather than read as a literal that matches
 * nothing.
 *
 * <p>A selector that stops at the object identity sets object-level knobs ({@code scope}, {@code equivalence},
 * {@code allow-inplace-merge}, and a default {@code multivalue}); a selector that reaches an item sets
 * {@code multivalue} for that container and {@code apply} for that item ({@code on-create}: written only when
 * the object is created; {@code if-absent}: written only while the server holds no value). An object-only knob
 * on an item-path selector is rejected, as is {@code apply} on an object. When
 * several rules set the same knob for the same target, the <b>most-specific</b> selector wins (more literal
 * segments, then fewer {@code **}, then a longer literal prefix; ties fall to the earlier line). The CLI flags
 * are the baseline every matching knob is layered onto. The object XML stays pure midPoint XML — strategy
 * lives here, never embedded in the objects.
 *
 * <p>Beneath the file sit midgie's built-in rules ({@value #BUILT_IN_RESOURCE}, in the same grammar): the
 * {@code apply=on-create} items midPoint or its operators own once an object exists, such as a task's
 * {@code executionState}. They apply with or without a file, after its lines, so a file line naming the same
 * item wins the tie and overrides one; there is no switch to turn them off as a whole.
 */
final class Policy {

    private static final Logger LOG = LoggerFactory.getLogger(Policy.class);

    /** The built-in rules, next to this class on the classpath. */
    static final String BUILT_IN_RESOURCE = "builtin.policy";

    /** Where a built-in rule says it comes from, in the log and the held-back notes. */
    private static final String BUILT_IN = "built-in";

    /** In the order the messages list them. */
    private static final List<String> KNOB_NAMES =
            List.of("scope", "multivalue", "equivalence", "allow-inplace-merge", "smart-refs", "apply");
    private static final Set<String> KNOWN_KNOBS = Set.copyOf(KNOB_NAMES);
    private static final Set<String> OBJECT_ONLY_KNOBS =
            Set.of("scope", "equivalence", "allow-inplace-merge", "smart-refs");
    private static final Set<String> ITEM_ONLY_KNOBS = Set.of("apply");

    private static final class Rule {
        final GlobPath selector;
        final String selectorText;  // as written, unquoted — what tells a restated built-in apart
        final boolean itemLevel;
        final Map<String, String> settings;
        final int order;
        final int lineNumber;   // so a message can say where to change it, not just what it says
        final String source;    // the line as written, for the "matched nothing" report
        final Origin origin;
        boolean matched;

        Rule(GlobPath selector, String selectorText, boolean itemLevel, Map<String, String> settings, int order,
                int lineNumber, String source, Origin origin) {
            this.selector = selector;
            this.selectorText = selectorText;
            this.itemLevel = itemLevel;
            this.settings = settings;
            this.order = order;
            this.lineNumber = lineNumber;
            this.source = source;
            this.origin = origin;
        }

        boolean builtIn() {
            return origin.builtIn();
        }

        /** What the rule says, independent of how it is spaced or where it is written. */
        String meaning() {
            return selectorText + " " + new TreeMap<>(settings);
        }
    }

    /** Where a rule was read from: the policy file's path, or midgie's built-in rules. */
    private record Origin(String path, boolean builtIn) {
        static final Origin BUILT_IN_RULES = new Origin(BUILT_IN, true);
    }

    private final List<Rule> rules;

    private Policy(List<Rule> rules) {
        this.rules = rules;
    }

    /** How many rules the file holds, built-ins not counted; zero for a file of comments only. */
    int ruleCount() {
        return (int) rules.stream().filter(rule -> !rule.builtIn()).count();
    }

    /** How many built-in rules apply beneath the file's. */
    int builtInCount() {
        return (int) rules.stream().filter(Rule::builtIn).count();
    }

    /** Parse and validate the policy file, without the built-ins. Throws with the line number on a malformed rule. */
    static Policy load(File file) throws IOException {
        if (!file.isFile() || !file.canRead()) {
            // NoSuchFileException carries only the path as its message, so the run failed with a bare
            // 'midgie: midgie.policy' — which reads like the file was the problem rather than its absence.
            throw new IOException("--policy file does not exist or is not readable: " + file);
        }
        List<Rule> rules = parse(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8),
                new Origin(file.getPath(), false), 0);
        if (rules.isEmpty()) {
            // A comment-only file is legitimate — one whose every rule was commented out to try a deploy
            // without them, say — and rejecting it would fail a run over a file that asks for nothing.
            LOG.debug("policy file {} has no rules; every object keeps the CLI strategy", file);
        }
        return new Policy(rules);
    }

    /**
     * The policy a deploy runs on: the file's rules ({@code null}: none), then the built-in ones. Placed after
     * the file's lines, a built-in loses every tie to them — the same reason {@code init --write-policy} puts its
     * block at the end of a file.
     */
    static Policy effective(File file) throws IOException {
        List<Rule> rules = new ArrayList<>((file != null) ? load(file).rules : List.of());
        try {
            rules.addAll(parse(builtInText().lines().toList(), Origin.BUILT_IN_RULES, rules.size()));
        } catch (IOException e) {
            throw new IllegalStateException("midgie's built-in policy does not parse: " + e.getMessage(), e);
        }
        return new Policy(rules);
    }

    /** The built-in rules as midgie ships them, comments included: what {@code init --write-policy} copies. */
    static String builtInText() {
        try (InputStream in = Policy.class.getResourceAsStream(BUILT_IN_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("midgie's built-in policy (" + BUILT_IN_RESOURCE
                        + ") is missing from the build");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read midgie's built-in policy: " + e.getMessage(), e);
        }
    }

    /** The rules of a policy text, numbered from {@code firstOrder} so a later source loses ties to an earlier one. */
    private static List<Rule> parse(List<String> lines, Origin origin, int firstOrder) throws IOException {
        List<Rule> rules = new ArrayList<>();
        int lineNumber = 0;
        int order = firstOrder;
        for (String raw : lines) {
            lineNumber++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            rules.add(parseLine(line, lineNumber, order++, origin));
        }
        return rules;
    }

    /** A run of non-blank characters: a word of a policy line. */
    private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile("\\S+");

    private static Rule parseLine(String line, int lineNumber, int order, Origin origin) throws IOException {
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
            java.util.regex.Matcher token = TOKEN.matcher(line);
            int settingsStart = -1;
            while (token.find()) {
                // Only a known knob starts the settings: an object name may contain '=' too, and treating
                // any '=' token as a setting made 'OrgType/ou=people scope=full' fail with a message about
                // the line's shape rather than about the name.
                String text = token.group();
                int eq = text.indexOf('=');
                if (eq > 0 && KNOWN_KNOBS.contains(text.substring(0, eq).toLowerCase())) {
                    settingsStart = token.start();
                    break;
                }
            }
            if (settingsStart <= 0) {
                throw new IOException("policy line " + lineNumber + " must be '<selector> <key=value>…' with "
                        + "a known knob (" + String.join(", ", KNOB_NAMES) + "); quote the "
                        + "selector if the object name contains '=' or '/': " + line);
            }
            // The selector is everything before the first setting, as written: an object name may contain
            // spaces, two of them in a row included, and rejoining the words with one space named another object.
            selectorText = line.substring(0, settingsStart).strip();
            settingsText = line.substring(settingsStart).strip();
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
        GlobPath selector;
        try {
            selector = GlobPath.parse(selectorText, identityDepth(selectorText));
        } catch (IllegalArgumentException e) {
            throw new IOException("policy line " + lineNumber + " has an invalid selector: " + e.getMessage()
                    + ": " + line);
        }
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
                        + "' (known: " + String.join(", ", KNOB_NAMES) + ")");
            }
            if (itemLevel && OBJECT_ONLY_KNOBS.contains(key)) {
                throw new IOException("policy line " + lineNumber + " sets object-only knob '" + key
                        + "' on an item-path selector — only 'multivalue' and 'apply' are per-item.");
            }
            if (!itemLevel && ITEM_ONLY_KNOBS.contains(key)) {
                // An object has no "on create" of its own to hold back: the whole object is either created
                // or patched, so the knob only means something for an item.
                throw new IOException("policy line " + lineNumber + " sets '" + key + "' on an object — it is "
                        + "per-item; name the item, e.g. 'TaskType/*/executionState apply=on-create'.");
            }
            if (!validValue(key, value)) {
                throw new IOException("policy line " + lineNumber + " has an invalid value for '" + key
                        + "': " + value);
            }
            settings.put(key, value);
        }
        return new Rule(selector, selectorText, itemLevel, settings, order, lineNumber, line.strip(), origin);
    }

    /**
     * A selector is item-level (sets only {@code multivalue} and {@code apply}) when it reaches past the object
     * identity: it contains {@code **}, or it is deeper than the identity (one segment for {@code oid:X}, else
     * two for {@code Type/Name}).
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
                return StrategyOptions.equivalencePlanByName(value) != null;
            case "multivalue":
                return mergeByName(value) != null;
            case "allow-inplace-merge":
                return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
            case "smart-refs":
                return StrategyOptions.smartRefsByName(value) != null;
            case "apply":
                return ApplyRules.Mode.byName(value) != null;
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
        DiffEngine.EquivalencePlan equivalence = baseline.equivalence;
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
        List<ApplyRules.Rule> applyRules = new ArrayList<>();
        List<Rule> itemRulesOffered = new ArrayList<>();

        for (Rule rule : rules) {
            if (rule.itemLevel) {
                String multivalue = rule.settings.get("multivalue");
                if (multivalue != null) {
                    itemRules.add(new DiffEngine.MultiValueStrategy.PathRule(
                            rule.selector, Boolean.TRUE.equals(mergeByName(multivalue)), true));
                }
                String apply = rule.settings.get("apply");
                if (apply != null) {
                    applyRules.add(new ApplyRules.Rule(rule.selector, ApplyRules.Mode.byName(apply), rule.builtIn()));
                }
                if (couldMatchIdentity(rule.selector, identity)) {
                    rule.matched = true;
                    itemRulesOffered.add(rule);
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
                equivalence = StrategyOptions.equivalencePlanByName(rule.settings.get("equivalence"));
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
        logApplied(type, name, oid, scopeRule, equivalenceRule, allowInplaceRule, smartRefsRule, globalMergeRule,
                itemRulesOffered);
        return new StrategyOptions.Resolved(scope, equivalence, multiValue, allowInplace, smartRefs,
                ApplyRules.of(applyRules, identity), false, baseline.tracked);
    }

    /**
     * Say which rule set what for this object, when any did.
     *
     * <p>The only feedback the policy gave was the "matched no object" note, which answers the opposite
     * question: it names rules that reached nothing, and stays silent about the ones that did — so "was my
     * rule applied to this object?" had no answer at any log level. Nothing is logged for an object no rule
     * touched, which is most of a tree.
     *
     * <p>Item rules ({@code apply=}, a per-item {@code multivalue}) are named too, with the identity they were
     * matched against — the type and name as this object was offered, which is what a rule that "matched no
     * object" has to be compared with.
     */
    private void logApplied(String type, String name, String oid, Rule scope, Rule equivalence, Rule allowInplace,
            Rule smartRefs, Rule multivalue, List<Rule> itemRules) {
        if (!LOG.isDebugEnabled()) {
            return;
        }
        // Grouped by rule, since one line commonly sets two or three knobs and repeating its text per knob
        // makes the log line longer than the policy file.
        Map<Rule, List<String>> byRule = new LinkedHashMap<>();
        addApplied(byRule, "scope", scope);
        addApplied(byRule, "equivalence", equivalence);
        addApplied(byRule, "allow-inplace-merge", allowInplace);
        addApplied(byRule, "smart-refs", smartRefs);
        addApplied(byRule, "multivalue", multivalue);
        if (byRule.isEmpty() && itemRules.isEmpty()) {
            return;
        }
        List<String> applied = new ArrayList<>();
        for (Map.Entry<Rule, List<String>> entry : byRule.entrySet()) {
            applied.add(String.join(", ", entry.getValue()) + " (" + at(entry.getKey()) + ")");
        }
        for (Rule rule : itemRules) {
            applied.add(rule.source + " (" + at(rule) + ")");
        }
        LOG.debug("policy: {}/{}{} ← {}", type, name, (oid != null && !oid.isBlank()) ? " [" + oid + "]" : "",
                String.join("; ", applied));
    }

    /** Where a rule lives, in the form an editor jumps to — the answer to "and where do I change it?". */
    private static String at(Rule rule) {
        return rule.builtIn() ? BUILT_IN : rule.origin.path() + ":" + rule.lineNumber;
    }

    private static void addApplied(Map<Rule, List<String>> byRule, String knob, Rule rule) {
        if (rule != null) {
            byRule.computeIfAbsent(rule, key -> new ArrayList<>())
                    .add(knob + "=" + rule.settings.get(knob));
        }
    }

    /**
     * Policy lines that were never offered to any object in this run.
     *
     * <p>Worth saying out loud: a typo'd type, a renamed object or a mangled selector resolves silently to
     * the CLI baseline, so a {@code multivalue=merge} rule written to protect accrued values quietly becomes
     * replace and the deploy deletes them. Nothing in the output distinguished that from a rule that
     * applied.
     *
     * <p>Built-in rules are left out, and so are the file's lines that restate one, as the file
     * {@code init --write-policy} seeds does: they cover every tree, and most trees hold no user or no
     * resource for them to reach — which says nothing about a typo.
     */
    List<String> unmatchedRules() {
        Set<String> builtIns = new HashSet<>();
        for (Rule rule : rules) {
            if (rule.builtIn()) {
                builtIns.add(rule.meaning());
            }
        }
        List<String> unmatched = new ArrayList<>();
        for (Rule rule : rules) {
            if (!rule.matched && !rule.builtIn() && !builtIns.contains(rule.meaning())) {
                unmatched.add(at(rule) + ": " + rule.source);
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
