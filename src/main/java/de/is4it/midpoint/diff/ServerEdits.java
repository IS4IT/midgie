/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismPropertyValue;
import com.evolveum.midpoint.prism.PrismReferenceValue;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.prism.path.ItemName;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.prism.polystring.PolyString;
import com.evolveum.prism.xml.ns._public.types_3.PolyStringType;

/**
 * For each change a deploy would make, whether it moves the server on to what the tree says, or undoes something
 * done on the server that the tree has never seen.
 *
 * <p>A change is <b>the tree's own</b> when the server holds a state of the item that the tree has been through
 * since the last deploy: what that deploy wrote, or what a commit since then said. Anything else was done on the
 * server — in the GUI, by another copy of the tree, by a deploy from a version this one does not contain — and
 * the change would undo it. "Since the last deploy" matters: a yes/no item has held both values somewhere in a
 * long history, so "ever" would excuse every edit.
 *
 * <p>Judged per value, on the delta itself: a value the change removes must have been held by some version, a
 * value it adds must have been absent from some version. So a server that dropped a list entry the tree still
 * lists is caught when the deploy would add it back, and an entry added on the server when the deploy would
 * remove it. The comparison is by real value, so formatting never counts.
 */
final class ServerEdits {

    private ServerEdits() {
    }

    /** Who moved, for one change. */
    enum Kind {
        /** the server holds a state the tree has been through: the change is the tree's own */
        YOURS("your change"),
        /** the server changed and the tree did not: the change undoes the server's edit */
        SERVER_EDIT("undoes a change made on the server"),
        /** both moved since the last deploy */
        BOTH("changed on the server and in the tree"),
        /** the server holds something the tree never had, and there is no last deploy to tell who moved */
        UNTRACED("the server's value is not in your tree's history");

        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /** One change, with the item's three states as text: on the server, as last deployed, in the tree. */
    record Finding(String path, Kind kind, String server, String lastDeployed, String tree) {

        String describe() {
            return path + ": " + kind.label + " — server " + server
                    + (lastDeployed != null ? ", last deployed " + lastDeployed : "") + " → tree " + tree;
        }
    }

    /**
     * The versions of an object the tree has been through since its last deploy.
     *
     * @param lastDeployed what the last deploy wrote, or {@code null} when that is unknown or not in this tree's
     *                     history
     * @param window       the object as each commit since then had it, newest first; read on demand
     */
    record Versions(PrismObject<? extends Objectable> lastDeployed,
            Supplier<Iterator<PrismObject<? extends Objectable>>> window) {
    }

    /** One finding per modification of {@code delta}, against {@code live} as the server holds it. */
    static List<Finding> judge(ObjectDelta<? extends Objectable> delta, PrismObject<? extends Objectable> live,
            Versions versions) {
        List<Finding> findings = new ArrayList<>();
        List<PrismObject<? extends Objectable>> read = new ArrayList<>();
        Iterator<PrismObject<? extends Objectable>> window = null;
        for (ItemDelta<?, ?> mod : delta.getModifications()) {
            ItemPath path = mod.getPath();
            List<PrismValue> server = valuesAt(live, live, path);
            List<PrismValue> removed = new ArrayList<>();
            List<PrismValue> added = new ArrayList<>();
            List<PrismValue> tree = new ArrayList<>(server);
            if (mod.getValuesToReplace() != null) {
                tree = new ArrayList<>(mod.getValuesToReplace());
                for (PrismValue value : server) {
                    if (!contains(tree, value)) {
                        removed.add(value);
                    }
                }
                for (PrismValue value : tree) {
                    if (!contains(server, value)) {
                        added.add(value);
                    }
                }
            } else {
                for (PrismValue value : nonNull(mod.getValuesToDelete())) {
                    removed.add(value);
                    tree.removeIf(held -> held.equals(value, EquivalenceStrategy.REAL_VALUE));
                }
                for (PrismValue value : nonNull(mod.getValuesToAdd())) {
                    added.add(value);
                    tree.add(value);
                }
            }
            List<PrismValue> deployed = (versions.lastDeployed() != null)
                    ? valuesAt(versions.lastDeployed(), live, path) : null;
            boolean known = deployed != null && explains(deployed, removed, added);
            int index = 0;
            while (!known) {
                if (index == read.size()) {
                    if (window == null) {
                        window = versions.window().get();
                    }
                    if (!window.hasNext()) {
                        break;
                    }
                    read.add(window.next());
                }
                known = explains(valuesAt(read.get(index), live, path), removed, added);
                index++;
            }
            Kind kind;
            if (known) {
                kind = Kind.YOURS;
            } else if (deployed == null) {
                kind = Kind.UNTRACED;
            } else {
                kind = sameValues(tree, deployed) ? Kind.SERVER_EDIT : Kind.BOTH;
            }
            findings.add(new Finding(PathText.of(path), kind, render(server),
                    (deployed != null) ? render(deployed) : null, render(tree)));
        }
        return findings;
    }

    /**
     * Whether a version of the item, as {@code held}, accounts for the server's state: it held every value the
     * change removes, and lacked every value the change adds.
     */
    private static boolean explains(List<PrismValue> held, List<PrismValue> removed, List<PrismValue> added) {
        for (PrismValue value : removed) {
            if (!contains(held, value)) {
                return false;
            }
        }
        for (PrismValue value : added) {
            if (contains(held, value)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The values {@code object} holds at {@code path}, a path of the live object: a container id in it names a
     * live list entry, which is looked for in {@code object} by natural key, else by content. An entry
     * {@code object} has no counterpart for holds nothing.
     */
    static List<PrismValue> valuesAt(PrismObject<? extends Objectable> object, PrismObject<? extends Objectable> live,
            ItemPath path) {
        PrismContainerValue<?> mine = object.getValue();
        PrismContainerValue<?> theirs = live.getValue();
        Item<?, ?> item = null;
        Item<?, ?> liveItem = null;
        for (Object segment : path.getSegments()) {
            if (ItemPath.isId(segment)) {
                Long id = ItemPath.toId(segment);
                if (!(item instanceof PrismContainer<?> container) || !(liveItem instanceof PrismContainer<?> liveList)) {
                    return List.of();
                }
                PrismContainerValue<?> liveEntry = liveList.findValue(id);
                if (liveEntry == null) {
                    return List.of();
                }
                PrismContainerValue<?> entry;
                if (object == live) {
                    entry = liveEntry;
                } else if (container.getDefinition() != null && container.getDefinition().isSingleValue()) {
                    entry = container.getValues().isEmpty() ? null : container.getValues().get(0);
                } else {
                    entry = counterpart(container, liveEntry);
                }
                if (entry == null) {
                    return List.of();
                }
                mine = entry;
                theirs = liveEntry;
                item = null;
                liveItem = null;
                continue;
            }
            if (!ItemPath.isName(segment)) {
                return List.of();
            }
            if (item != null) {
                // A step through a single-valued container: its one value.
                if (!(item instanceof PrismContainer<?> container) || container.getValues().isEmpty()) {
                    return List.of();
                }
                mine = container.getValues().get(0);
                theirs = (liveItem instanceof PrismContainer<?> liveContainer && !liveContainer.getValues().isEmpty())
                        ? liveContainer.getValues().get(0) : null;
            }
            ItemName name = ItemName.fromQName(ItemPath.toName(segment));
            item = mine.findItem(name);
            liveItem = (theirs != null) ? theirs.findItem(name) : null;
            if (item == null) {
                return List.of();
            }
        }
        return (item != null) ? new ArrayList<>(item.getValues()) : List.of();
    }

    private static PrismContainerValue<?> counterpart(PrismContainer<?> container, PrismContainerValue<?> liveEntry) {
        PrismContainerValue<?> match = NaturalKeys.match(container, liveEntry, null);
        return (match != null) ? match : ObjectSplitter.equalValue(container, liveEntry);
    }

    private static boolean contains(Collection<? extends PrismValue> values, PrismValue candidate) {
        for (PrismValue value : values) {
            if (value.equals(candidate, EquivalenceStrategy.REAL_VALUE)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameValues(List<PrismValue> one, List<PrismValue> other) {
        if (one.size() != other.size()) {
            return false;
        }
        for (PrismValue value : one) {
            if (!contains(other, value)) {
                return false;
            }
        }
        return true;
    }

    private static <T> Collection<T> nonNull(Collection<T> values) {
        return (values != null) ? values : List.of();
    }

    /** The values as one short phrase: {@code "Leipzig"}, {@code "a", "b"}, {@code (none)}. */
    static String render(List<PrismValue> values) {
        if (values.isEmpty()) {
            return "(none)";
        }
        List<String> parts = new ArrayList<>();
        for (PrismValue value : values) {
            parts.add(render(value));
        }
        String text = String.join(", ", parts);
        return (text.length() > 80) ? text.substring(0, 77) + "…" : text;
    }

    private static String render(PrismValue value) {
        if (value instanceof PrismReferenceValue reference) {
            return (reference.getOid() != null) ? reference.getOid() : "(a filter)";
        }
        if (value instanceof PrismContainerValue<?>) {
            return "(an entry)";
        }
        Object real = (value instanceof PrismPropertyValue<?> property) ? property.getRealValue() : null;
        if (real instanceof PolyString poly) {
            real = poly.getOrig();
        } else if (real instanceof PolyStringType poly) {
            real = poly.getOrig();
        }
        String text = String.valueOf(real);
        int newline = text.indexOf('\n');
        if (newline >= 0) {
            text = text.substring(0, newline) + " …";
        }
        return "\"" + text + "\"";
    }
}
