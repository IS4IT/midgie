/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.path.ItemPath;

/**
 * When midgie may write an item, from the policy's {@code apply} knob — a {@code --policy} file line or one
 * of midgie's built-in rules: {@code always} (the default); {@code on-create} — written in the POST that
 * creates the object, and never touched by a later PATCH; or {@code if-absent} — written whenever the server
 * holds no value for it, and never overwritten or removed, like a midPoint mapping of weak strength. The motivating item for {@code on-create} is
 * {@code TaskType/executionState}: a task is deployed suspended or runnable, and from then on operators and
 * midPoint's task manager own it, so a redeploy must not flip it back. {@code if-absent} differs only once
 * someone clears the item: it is filled in again on the next deploy.
 *
 * <p>Rules are identity-scoped globs like the {@code multivalue} path rules: each is matched against the
 * object's identity ({@code Type/Name}, or {@code oid:X}) followed by the item's local names. A rule covers
 * its path and everything beneath it; where several match, the most specific wins, ties to the earlier line.
 */
final class ApplyRules {

    enum Mode {
        ALWAYS("always"),
        ON_CREATE("on-create"),
        IF_ABSENT("if-absent");

        final String keyword;

        Mode(String keyword) {
            this.keyword = keyword;
        }

        /** The mode a policy value names, or {@code null} if it names none. */
        static Mode byName(String value) {
            for (Mode mode : values()) {
                if (mode.keyword.equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            return null;
        }
    }

    /** One policy line's {@code apply} setting, and whether the line is one of midgie's built-in rules. */
    static final class Rule {
        final GlobPath glob;
        final Mode mode;
        final boolean builtIn;

        Rule(GlobPath glob, Mode mode, boolean builtIn) {
            this.glob = glob;
            this.mode = mode;
            this.builtIn = builtIn;
        }
    }

    /**
     * What {@link #holdBack} took out of a delta: the item paths it left alone, each naming the rule that held
     * it back, or — when the change could not be made without overwriting such an item — the reason the object
     * cannot be patched.
     */
    static final class HoldBack {
        final List<String> paths;
        final String conflict;

        private HoldBack(List<String> paths, String conflict) {
            this.paths = List.copyOf(paths);
            this.conflict = conflict;
        }
    }

    private static final ApplyRules NONE = new ApplyRules(List.of(), List.of());

    private final List<Rule> rules;
    private final List<List<String>> identityPrefixes;

    private ApplyRules(List<Rule> rules, List<List<String>> identityPrefixes) {
        this.rules = List.copyOf(rules);
        this.identityPrefixes = List.copyOf(identityPrefixes);
    }

    /** No rules: every item is written on every run, which is what midgie did before the knob existed. */
    static ApplyRules none() {
        return NONE;
    }

    /** {@code rules} in policy-file order, matched against any of the object's {@code identityPrefixes}. */
    static ApplyRules of(List<Rule> rules, List<List<String>> identityPrefixes) {
        return rules.isEmpty() ? NONE : new ApplyRules(rules, identityPrefixes);
    }

    boolean isEmpty() {
        return rules.isEmpty();
    }

    /** The mode for the item at {@code path}: {@link Mode#ALWAYS} unless a rule says otherwise. */
    Mode modeOf(ItemPath path) {
        return modeAt(localNames(path));
    }

    /**
     * As {@link #modeOf(ItemPath)}, for a path already rendered as text ({@code a/1/b}): container ids and
     * namespace prefixes are dropped, so the rendered form of a delta path matches like the path itself.
     */
    Mode modeOf(String path) {
        List<String> names = new ArrayList<>();
        for (String segment : path.split("/")) {
            String name = segment.strip();
            int close = name.lastIndexOf('}');
            name = (close >= 0) ? name.substring(close + 1) : name.substring(name.indexOf(':') + 1);
            if (!name.isEmpty() && !name.chars().allMatch(Character::isDigit)) {
                names.add(name);
            }
        }
        return modeAt(names);
    }

    /**
     * Take out of {@code delta} every change the policy does not allow on an object that already exists.
     *
     * <p>A change at or beneath an {@code on-create} item is dropped whole. A change at or beneath an
     * {@code if-absent} item is dropped when the server holds a value for that item, and kept (filling it in)
     * when it holds none — so an {@code if-absent} item is never overwritten and never removed.
     *
     * <p>A change <em>above</em> such an item needs more care. Adding a container keeps the container and
     * strips {@code on-create} items from the added value, so a new {@code activation} does not bring an
     * {@code on-create} {@code administrativeStatus} with it; {@code if-absent} items stay, since nothing on the
     * server can be under a container that is only now being added. Replacing a container is the same unless
     * the server already holds a restricted item beneath it: the replace would overwrite that whatever the new
     * value carries, so it is reported as a conflict for the caller to refuse.
     *
     * @param live the object as the server holds it, to tell what is absent and what a replace would overwrite
     */
    HoldBack holdBack(ObjectDelta<? extends Objectable> delta, PrismObject<? extends Objectable> live) {
        List<String> held = new ArrayList<>();
        if (rules.isEmpty() || delta == null || delta.isEmpty()) {
            return new HoldBack(held, null);
        }
        if (rules.stream().allMatch(rule -> rule.mode == Mode.ALWAYS)) {
            return new HoldBack(held, null);
        }

        List<ItemDelta<?, ?>> kept = new ArrayList<>();
        boolean changed = false;
        for (ItemDelta<?, ?> mod : new ArrayList<ItemDelta<?, ?>>(delta.getModifications())) {
            List<String> names = localNames(mod.getPath());
            Mode mode = modeAt(names);
            if (mode == Mode.ON_CREATE || (mode == Mode.IF_ABSENT && holdsValue(live, mod.getPath()))) {
                addOnce(held, label(names));
                changed = true;
                continue;
            }
            if (mode == Mode.IF_ABSENT || !couldReachBelow(names, false)) {
                kept.add(mod);
                continue;
            }
            ItemDelta<?, ?> copy = mod.clone();
            List<String> stripped = new ArrayList<>();
            if (mod.isReplace()) {
                // A replace sets the container to exactly the new value, so whatever the server holds
                // beneath it goes — including a restricted item, whether or not the new value carries one.
                // Stripping the item from the new value cannot prevent that, so refuse the object instead.
                List<String> onServer = new ArrayList<>();
                Item<?, ?> liveItem = (live != null) ? live.findItem(mod.getPath()) : null;
                if (liveItem != null) {
                    collectRestricted(liveItem.getValues(), names, onServer);
                }
                if (!onServer.isEmpty()) {
                    return new HoldBack(held, "replacing " + String.join("/", names) + " would overwrite "
                            + String.join(", ", onServer) + ", which the policy does not let a deploy "
                            + "overwrite");
                }
                stripOnCreate(copy.getValuesToReplace(), names, stripped);
            } else {
                stripOnCreate(copy.getValuesToAdd(), names, stripped);
            }
            if (stripped.isEmpty()) {
                kept.add(mod);
                continue;
            }
            stripped.forEach(path -> addOnce(held, path));
            changed = true;
            // A container value that held nothing but on-create items is left empty; sending it would
            // create an empty container, which is not what the file declared either.
            if (dropEmptyValues(copy)) {
                kept.add(copy);
            }
        }

        if (changed) {
            // Rebuilt rather than edited in place, so the modifications keep their order in the PATCH.
            for (ItemDelta<?, ?> mod : new ArrayList<ItemDelta<?, ?>>(delta.getModifications())) {
                delta.removeModification(mod);
            }
            delta.addModifications(kept);
        }
        return new HoldBack(held, null);
    }

    /**
     * Why {@code delta} cannot be sent as it stands, or {@code null} when it can: it deletes a container value
     * that holds a restricted item on the server, and the object would not keep that item afterwards.
     *
     * <p>{@link #holdBack} guards a <em>replace</em> of such a container, but a list compared by content
     * changes an entry with an add of the new form and a delete of the old, and the delete takes everything
     * the old form holds — an {@code on-create} item stripped from the added value included. Two cases lose
     * the item: the same change also adds a value there (the entry is replaced whole), or the container is
     * single-valued (the object is left without it). A delete of one entry of a multi-valued container, with
     * nothing added in its place, removes that entry as a whole and is left to go through.
     *
     * <p>Judged on the whole PATCH, after the removals of values dropped from the tree have joined it: a
     * removal can supply the delete that pairs with the diff's add.
     *
     * @param live the object as the server holds it, whose values the deletes are resolved against
     */
    String removedWithContainer(ObjectDelta<? extends Objectable> delta, PrismObject<? extends Objectable> live) {
        if (rules.isEmpty() || delta == null || rules.stream().allMatch(rule -> rule.mode == Mode.ALWAYS)) {
            return null;
        }
        for (ItemDelta<?, ?> mod : delta.getModifications()) {
            Collection<? extends PrismValue> deleted = mod.getValuesToDelete();
            if (deleted == null || deleted.isEmpty()) {
                continue;
            }
            List<String> names = localNames(mod.getPath());
            if (!couldReachBelow(names, false)) {
                continue;
            }
            boolean replacedWhole = addsAt(delta, mod.getPath());
            if (!replacedWhole && !isSingleValued(mod)) {
                continue;
            }
            List<String> onServer = new ArrayList<>();
            collectRestricted(liveCounterparts(deleted, live, mod.getPath()), names, onServer);
            if (onServer.isEmpty()) {
                continue;
            }
            String path = String.join("/", names);
            String items = String.join(", ", onServer);
            if (replacedWhole) {
                return "changing a value of " + path + " replaces it whole, which would remove " + items
                        + " — the policy does not let a deploy remove it. Give the entry its container id in the"
                        + " file, so the change is made inside it";
            }
            return "removing " + path + " would remove " + items
                    + ", which the policy does not let a deploy remove";
        }
        return null;
    }

    /** Whether any modification in {@code delta} adds a value at {@code path}. */
    private static boolean addsAt(ObjectDelta<? extends Objectable> delta, ItemPath path) {
        for (ItemDelta<?, ?> mod : delta.getModifications()) {
            Collection<? extends PrismValue> added = mod.getValuesToAdd();
            if (added != null && !added.isEmpty() && mod.getPath().equivalent(path)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSingleValued(ItemDelta<?, ?> mod) {
        return mod.getDefinition() != null && mod.getDefinition().isSingleValue();
    }

    /**
     * The server's own copy of each deleted container value, found by id, so what is judged is what the delete
     * will actually remove; a value without an id, or one the server does not hold, is judged as sent.
     */
    private static List<PrismValue> liveCounterparts(Collection<? extends PrismValue> deleted,
            PrismObject<? extends Objectable> live, ItemPath path) {
        Item<?, ?> liveItem = (live != null) ? live.findItem(path) : null;
        List<PrismValue> values = new ArrayList<>();
        for (PrismValue value : deleted) {
            PrismValue counterpart = null;
            if (liveItem instanceof PrismContainer<?> container && value instanceof PrismContainerValue<?> cv
                    && cv.getId() != null) {
                counterpart = container.findValue(cv.getId());
            }
            values.add((counterpart != null) ? counterpart : value);
        }
        return values;
    }

    /** Whether the server holds at least one value at {@code path}. */
    private static boolean holdsValue(PrismObject<? extends Objectable> live, ItemPath path) {
        Item<?, ?> item = (live != null) ? live.findItem(path) : null;
        return item != null && item.hasAnyValue();
    }

    /**
     * A held-back path as the report shows it, naming the rule that held it back — and saying when that rule
     * is a built-in one, which a run without a {@code --policy} file has no other way to tell.
     */
    String labelOf(ItemPath path) {
        return label(localNames(path));
    }

    private String label(List<String> names) {
        Rule rule = ruleAt(names);
        Mode mode = (rule != null) ? rule.mode : Mode.ALWAYS;
        return String.join("/", names) + " (apply=" + mode.keyword
                + ((rule != null && rule.builtIn) ? ", built-in" : "") + ")";
    }

    /**
     * Drop the container values stripping left empty. Returns whether the change still has anything to send;
     * a replace left with no value is dropped whole rather than sent as a clear.
     */
    private static boolean dropEmptyValues(ItemDelta<?, ?> mod) {
        boolean replace = mod.isReplace();
        Collection<? extends PrismValue> values = replace ? mod.getValuesToReplace() : mod.getValuesToAdd();
        for (PrismValue value : new ArrayList<PrismValue>(values)) {
            if (value instanceof PrismContainerValue<?> container && container.hasNoItems()) {
                if (replace) {
                    mod.removeValueToReplace(value);
                } else {
                    mod.removeValueToAdd(value);
                }
            }
        }
        if (replace) {
            // An empty replace means "clear the item", so it has to be judged on its values, not isEmpty().
            Collection<? extends PrismValue> remaining = mod.getValuesToReplace();
            return remaining != null && !remaining.isEmpty();
        }
        return !mod.isEmpty();   // an add may still carry values to delete
    }

    /** Remove {@code on-create} items from added container values, recording each path removed. */
    private void stripOnCreate(Collection<? extends PrismValue> values, List<String> prefix,
            List<String> stripped) {
        if (values == null) {
            return;
        }
        for (PrismValue value : values) {
            if (value instanceof PrismContainerValue<?> container) {
                stripOnCreate(container, prefix, stripped);
            }
        }
    }

    private void stripOnCreate(PrismContainerValue<?> container, List<String> prefix, List<String> stripped) {
        for (Item<?, ?> item : new ArrayList<Item<?, ?>>(container.getItems())) {
            List<String> names = child(prefix, item);
            if (modeAt(names) == Mode.ON_CREATE) {
                container.remove(item);
                addOnce(stripped, label(names));
            } else if (item instanceof PrismContainer<?> && couldReachBelow(names, true)) {
                stripOnCreate(item.getValues(), names, stripped);
            }
        }
    }

    /** Find items under a restricting rule inside container values, without changing them. */
    private void collectRestricted(Collection<? extends PrismValue> values, List<String> prefix,
            List<String> found) {
        if (values == null) {
            return;
        }
        for (PrismValue value : values) {
            if (!(value instanceof PrismContainerValue<?> container)) {
                continue;
            }
            for (Item<?, ?> item : container.getItems()) {
                List<String> names = child(prefix, item);
                Mode mode = modeAt(names);
                if (mode != Mode.ALWAYS && item.hasAnyValue()) {
                    addOnce(found, String.join("/", names));
                } else if (item instanceof PrismContainer<?> && couldReachBelow(names, false)) {
                    collectRestricted(item.getValues(), names, found);
                }
            }
        }
    }

    /** The mode for the item at {@code itemNames}: the most specific rule matching it or an ancestor. */
    private Mode modeAt(List<String> itemNames) {
        Rule rule = ruleAt(itemNames);
        return (rule != null) ? rule.mode : Mode.ALWAYS;
    }

    /** The rule deciding the item at {@code itemNames}, or {@code null} when none does. */
    private Rule ruleAt(List<String> itemNames) {
        Rule best = null;
        for (int depth = 1; depth <= itemNames.size(); depth++) {
            List<String> path = itemNames.subList(0, depth);
            for (Rule rule : rules) {
                if (matchesWithIdentity(rule.glob, path)
                        && (best == null || rule.glob.compareSpecificity(best.glob) > 0)) {
                    best = rule;
                }
            }
        }
        return best;
    }

    /**
     * Whether a restricting rule could match something strictly beneath {@code itemNames} — only an
     * {@code on-create} one when {@code onCreateOnly}, since only those are stripped from added values.
     */
    private boolean couldReachBelow(List<String> itemNames, boolean onCreateOnly) {
        for (Rule rule : rules) {
            if (rule.mode == Mode.ALWAYS || (onCreateOnly && rule.mode != Mode.ON_CREATE)) {
                continue;
            }
            for (List<String> prefix : identityPrefixes) {
                List<String> candidate = new ArrayList<>(prefix);
                candidate.addAll(itemNames);
                if (rule.glob.size() > candidate.size() || rule.glob.containsGlobstar()) {
                    if (rule.glob.matchesPrefix(candidate)) {
                        return true;
                    }
                }
            }
        }
        return false;
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

    private static List<String> child(List<String> prefix, Item<?, ?> item) {
        List<String> names = new ArrayList<>(prefix);
        names.add(item.getElementName().getLocalPart());
        return names;
    }

    /** An item path as its bare local names, ids stripped — the form the rules match on. */
    private static List<String> localNames(ItemPath path) {
        List<String> names = new ArrayList<>();
        for (Object segment : path.removeIds().getSegments()) {
            if (ItemPath.isName(segment)) {
                names.add(ItemPath.toName(segment).getLocalPart());
            }
        }
        return names;
    }

    private static void addOnce(List<String> list, String value) {
        if (!list.contains(value)) {
            list.add(value);
        }
    }
}
