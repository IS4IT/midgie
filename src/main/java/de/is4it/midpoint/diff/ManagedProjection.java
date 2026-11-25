/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.prism.key.NaturalKeyDefinition;
import com.evolveum.midpoint.prism.path.ItemName;

/**
 * What a managed file has to become so that it says what the server says about the items it declares: the edits
 * {@code pull} makes to the tree's own files.
 *
 * <p>The file keeps its shape. An item it declares takes the server's value; one the server no longer holds goes;
 * nothing is added — what the server holds beyond the file goes to the remainder, as before. In detail:
 * <ul>
 * <li>a single-valued item, and one without a definition, is <b>replaced</b> by the server's value when the two
 * differ, and <b>deleted</b> when the server holds none;</li>
 * <li>a single-valued container is followed into, and so is a list entry paired with the server's by natural key;
 * a container value whose every item goes is deleted whole rather than left empty;</li>
 * <li>a list entry paired by container id alone, which cannot be followed into, is <b>replaced</b> whole when it
 * differs; an entry the server has no counterpart for is <b>deleted</b>;</li>
 * <li>a value of a multi-valued property or reference that the server does not hold is <b>deleted</b>.</li>
 * </ul>
 *
 * <p>Left alone, whatever the server says: items the server owns or does not send (operational, protected,
 * incomplete), items the policy marks {@code apply} other than {@code always} — an {@code on-create} value is what
 * the object is created with, not what it holds now — and references the file writes as a filter, which only a
 * deploy can judge.
 */
final class ManagedProjection {

    private ManagedProjection() {
    }

    /** One step from a container value to a value of one of its items: the item, and which of its values. */
    record Step(QName item, int index) {
    }

    enum Kind { REPLACE, DELETE }

    /**
     * One change to a file: the value at {@code path} is replaced by {@code value}, or deleted.
     *
     * @param label the item's path, for messages
     */
    record Edit(List<Step> path, Kind kind, PrismValue value, String label) {
    }

    /**
     * What pull leaves alone in a managed file.
     *
     * @param apply      the object's apply rules; anything but {@code always} is left alone
     * @param incomplete the paths the server did not send ({@link Exclusions.Stripped#incompletePaths})
     */
    record Skip(ApplyRules apply, Set<String> incomplete) {
    }

    /**
     * The edits that bring {@code file} — one managed file's object — in line with {@code live}, which carries
     * nothing {@link Exclusions} takes out. Empty when the file already says what the server says.
     */
    static List<Edit> edits(PrismObject<? extends Objectable> file, PrismObject<? extends Objectable> live,
            Skip skip) {
        List<Edit> edits = new ArrayList<>();
        project(file.getValue(), live.getValue(), List.of(), "", skip, edits);
        return edits;
    }

    /**
     * Collect the edits for {@code file} against {@code live}.
     *
     * @return whether every item of {@code file} is deleted, so that its caller deletes the value itself
     */
    private static boolean project(PrismContainerValue<?> file, PrismContainerValue<?> live, List<Step> prefix,
            String prefixText, Skip skip, List<Edit> edits) {
        boolean allDeleted = !file.getItems().isEmpty();
        for (Item<?, ?> item : file.getItems()) {
            QName name = item.getElementName();
            String path = prefixText.isEmpty() ? name.getLocalPart() : prefixText + "/" + name.getLocalPart();
            if (leftAlone(item, path, skip)) {
                allDeleted = false;
                continue;
            }
            Item<?, ?> counterpart = (live != null) ? live.findItem(ItemName.fromQName(name)) : null;
            if (counterpart == null || counterpart.hasNoValues()) {
                for (int i = 0; i < item.getValues().size(); i++) {
                    edits.add(new Edit(append(prefix, name, i), Kind.DELETE, null, path));
                }
                continue;
            }
            allDeleted = false;
            ItemDefinition<?> definition = item.getDefinition();
            boolean known = definition != null && !definition.isDynamic() && !ObjectSplitter.isRaw(item)
                    && !ObjectSplitter.isRaw(counterpart);
            if (known && item instanceof PrismContainer<?> container
                    && counterpart instanceof PrismContainer<?> liveContainer) {
                if (definition.isSingleValue()) {
                    if (container.getValues().isEmpty() || liveContainer.getValues().isEmpty()) {
                        continue;
                    }
                    projectValue(container.getValues().get(0), liveContainer.getValues().get(0),
                            append(prefix, name, 0), path, skip, edits);
                } else {
                    projectEntries(container, liveContainer, prefix, path, skip, edits);
                }
            } else if (ObjectSplitter.smartReference(item)) {
                continue; // a filter the deploy resolves; the server's OID is its answer, not a change
            } else if (known && definition.isMultiValue()) {
                for (int i = 0; i < item.getValues().size(); i++) {
                    if (!ObjectSplitter.containsEqual(counterpart.getValues(), item.getValues().get(i))) {
                        edits.add(new Edit(append(prefix, name, i), Kind.DELETE, null, path));
                    }
                }
            } else if (!ObjectSplitter.sameValues(item, counterpart)) {
                if (item.getValues().size() == 1 && counterpart.getValues().size() == 1) {
                    edits.add(new Edit(append(prefix, name, 0), Kind.REPLACE, counterpart.getValues().get(0), path));
                } else {
                    // Several values of an item whose cardinality is unknown: which file value would become which
                    // server value cannot be told.
                    throw new IllegalStateException(path + " holds " + item.getValues().size() + " value(s) in the "
                            + "file and " + counterpart.getValues().size() + " on the server, and has no definition to "
                            + "pair them by; edit it by hand");
                }
            }
        }
        return allDeleted;
    }

    /** Follow into one container value; delete it whole when nothing of it would be left. */
    private static void projectValue(PrismContainerValue<?> file, PrismContainerValue<?> live, List<Step> at,
            String path, Skip skip, List<Edit> edits) {
        List<Edit> inside = new ArrayList<>();
        if (project(file, live, at, path, skip, inside)) {
            edits.add(new Edit(at, Kind.DELETE, null, path));
        } else {
            edits.addAll(inside);
        }
    }

    private static void projectEntries(PrismContainer<?> file, PrismContainer<?> live, List<Step> prefix,
            String path, Skip skip, List<Edit> edits) {
        NaturalKeyDefinition key = NaturalKeys.of(file);
        Map<PrismContainerValue<?>, Boolean> taken = new IdentityHashMap<>();
        for (int i = 0; i < file.getValues().size(); i++) {
            PrismContainerValue<?> entry = file.getValues().get(i);
            List<Step> at = append(prefix, file.getElementName(), i);
            PrismContainerValue<?> match = NaturalKeys.match(live, entry, entry.getId());
            if (match == null) {
                match = ObjectSplitter.equalValue(live, entry);
            }
            if (match == null || taken.containsKey(match)) {
                edits.add(new Edit(at, Kind.DELETE, null, path));
                continue;
            }
            taken.put(match, Boolean.TRUE);
            if (key != null && NaturalKeys.carries(key, entry) && NaturalKeys.carries(key, match)) {
                projectValue(entry, match, at, path, skip, edits);
            } else if (!entry.equals(match, EquivalenceStrategy.REAL_VALUE)) {
                edits.add(new Edit(at, Kind.REPLACE, match, path));
            }
        }
    }

    private static boolean leftAlone(Item<?, ?> item, String path, Skip skip) {
        ItemDefinition<?> definition = item.getDefinition();
        if (definition != null && definition.isOperational()) {
            return true;
        }
        if (DiffEngine.isProtectedItem(item)) {
            return true;
        }
        for (String incomplete : skip.incomplete()) {
            if (path.equals(incomplete) || path.startsWith(incomplete + "/")) {
                return true;
            }
        }
        return skip.apply().modeOf(path) != ApplyRules.Mode.ALWAYS;
    }

    private static List<Step> append(List<Step> prefix, QName name, int index) {
        List<Step> path = new ArrayList<>(prefix);
        path.add(new Step(name, index));
        return path;
    }

    /**
     * {@code file} with {@code edits} made: what the edited file must read back as. The values put in are copies
     * without container ids, as the file is written without them.
     */
    static PrismObject<? extends Objectable> applied(PrismObject<? extends Objectable> file, List<Edit> edits) {
        PrismObject<? extends Objectable> copy = file.clone();
        List<Item<?, ?>> items = new ArrayList<>();
        List<PrismValue> targets = new ArrayList<>();
        for (Edit edit : edits) {
            PrismContainerValue<?> container = copy.getValue();
            for (int i = 0; i < edit.path().size() - 1; i++) {
                Step step = edit.path().get(i);
                container = (PrismContainerValue<?>) container.findItem(ItemName.fromQName(step.item()))
                        .getValues().get(step.index());
            }
            Step last = edit.path().get(edit.path().size() - 1);
            Item<?, ?> item = container.findItem(ItemName.fromQName(last.item()));
            items.add(item);
            targets.add(item.getValues().get(last.index()));
        }
        // Resolved first, changed after: a delete shifts the index of every later value of the same item.
        for (int i = 0; i < edits.size(); i++) {
            Item<?, ?> item = items.get(i);
            removeValue(item, targets.get(i));
            if (edits.get(i).kind() == Kind.REPLACE) {
                addValue(item, withoutIds(edits.get(i).value()));
            }
        }
        for (Item<?, ?> item : items) {
            if (item.hasNoValues() && item.getParent() != null) {
                removeItem(item.getParent(), item);
            }
        }
        return copy;
    }

    /** A copy of {@code value} without container ids, as it is written into a file. */
    static PrismValue withoutIds(PrismValue value) {
        PrismValue copy = value.clone();
        if (copy instanceof PrismContainerValue<?> container) {
            container.setId(null);
            dropIds(container);
        }
        return copy;
    }

    private static void dropIds(PrismContainerValue<?> container) {
        for (Item<?, ?> item : container.getItems()) {
            if (item instanceof PrismContainer<?> nested) {
                for (PrismContainerValue<?> value : nested.getValues()) {
                    value.setId(null);
                    dropIds(value);
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // the value's type is the item's by construction
    private static void removeValue(Item<?, ?> item, PrismValue value) {
        ((Item) item).remove(value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // the value's type is the item's by construction
    private static void addValue(Item<?, ?> item, PrismValue value) {
        try {
            ((Item) item).add(value);
        } catch (com.evolveum.midpoint.util.exception.SchemaException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // the item carries its own value/definition types
    private static void removeItem(PrismContainerValue<?> container, Item<?, ?> item) {
        ((PrismContainerValue) container).remove((Item) item);
    }

    /** The edits as text, for a log line. */
    static List<String> labels(List<Edit> edits) {
        List<String> labels = new ArrayList<>();
        for (Edit edit : edits) {
            labels.add(edit.kind().name().toLowerCase() + " " + edit.label());
        }
        return labels;
    }
}
