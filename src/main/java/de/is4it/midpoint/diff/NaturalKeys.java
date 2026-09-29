/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.key.NaturalKeyDefinition;
import com.evolveum.midpoint.prism.path.ItemPath;

/**
 * The schema's {@code a:naturalKey} declarations — what lets midgie match one entry of a multi-valued
 * container against the live one instead of treating the container as an all-or-nothing whole.
 *
 * <p>midPoint declares these for around a hundred containers ({@code panel} by {@code identifier},
 * {@code objectDetailsPage} by {@code type}, and so on). They are the difference between "update this one
 * panel" and "replace every panel with the one I listed".
 */
final class NaturalKeys {

    private NaturalKeys() {
    }

    /** The container's declared natural key, or {@code null} when it has none. */
    static NaturalKeyDefinition of(PrismContainer<?> container) {
        ItemDefinition<?> definition = (container != null) ? container.getDefinition() : null;
        if (definition == null) {
            return null;
        }
        try {
            return definition.getNaturalKeyInstance();
        } catch (RuntimeException e) {
            return null; // a definition that does not support natural keys
        }
    }

    /**
     * Whether {@code value} actually carries <em>all</em> of the key's constituents. A key absent from both
     * sides compares vacuously equal and would match everything, so "declared" is not enough — it has to be
     * filled in.
     *
     * <p>All of them, because {@code getConstituents} returns only the ones present in the value: a value
     * carrying half a composite key would otherwise pass and then match on that half alone. midPoint's
     * most-edited keyed container is exactly composite — {@code ResourceObjectTypeDefinitionType} is keyed
     * by {@code kind + intent}, and omitting {@code <intent>} in a hand-written {@code schemaHandling}
     * fragment is routine.
     */
    static boolean carries(NaturalKeyDefinition key, PrismContainerValue<?> value) {
        try {
            ItemDefinition<?> definition = (value != null) ? value.getDefinition() : null;
            List<QName> declared = (definition != null) ? definition.getNaturalKeyConstituents() : null;
            if (declared == null || declared.isEmpty()) {
                return false;
            }
            Collection<Item<?, ?>> constituents = key.getConstituents(value);
            if (constituents == null || constituents.size() != declared.size()) {
                return false;
            }
            for (Item<?, ?> constituent : constituents) {
                if (constituent == null || !constituent.hasAnyValue()) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The value of {@code container} that {@code candidate} refers to — matched <b>by container id</b>, or,
     * when that finds nothing, <b>by the schema's natural key</b>.
     *
     * <p>The one pairing rule, shared by everything that has to decide "are these two container values the
     * same entry?": the diff's accrued-value guard asking whether desired declares the value being edited,
     * and {@link FragmentUnion} asking whether two files describe the same entry of a list. Both need the
     * same answer, and a second implementation of it would drift in the direction that silently combines or
     * silently duplicates.
     *
     * <p>{@code candidateId} is passed separately because the callers know it differently: the union reads it
     * off the value, while the diff takes it from a delta path segment and may hold no value at all.
     *
     * <p>An id match is not taken when both values fill in the key and it names different entries: the id was
     * numbered on another server (see {@link ContainerIds}), and the key is the better evidence.
     *
     * @return the matching value, or {@code null} when the container holds no counterpart
     */
    static PrismContainerValue<?> match(PrismContainer<?> container, PrismContainerValue<?> candidate,
            Long candidateId) {
        if (container == null) {
            return null;
        }
        NaturalKeyDefinition key = of(container);
        if (candidateId != null) {
            for (PrismContainerValue<?> value : container.getValues()) {
                if (candidateId.equals(value.getId())) {
                    boolean keysDiffer = key != null && candidate != null && carries(key, candidate)
                            && carries(key, value) && !key.valuesMatch(candidate, value);
                    if (!keysDiffer) {
                        return value;
                    }
                    break;
                }
            }
        }
        // Both sides must carry the key, else the comparison is vacuous: a key absent from both values (an
        // assignment with no <identifier>) would "match" everything, combining unrelated entries.
        if (candidate == null || key == null || !carries(key, candidate)) {
            return null;
        }
        for (PrismContainerValue<?> value : container.getValues()) {
            if (carries(key, value) && key.valuesMatch(candidate, value)) {
                return value;
            }
        }
        return null;
    }

    /** The key's constituent names, for a message: {@code "identifier"}, {@code "type + connectorRef"}. */
    static String names(PrismContainer<?> container) {
        ItemDefinition<?> definition = (container != null) ? container.getDefinition() : null;
        List<QName> constituents = (definition != null) ? definition.getNaturalKeyConstituents() : null;
        if (constituents == null || constituents.isEmpty()) {
            return "natural key";
        }
        List<String> names = new ArrayList<>();
        for (QName name : constituents) {
            names.add(name.getLocalPart());
        }
        return String.join(" + ", names);
    }

    /**
     * Whether <em>every</em> value of the container carries the full key — the test
     * {@code --equivalence auto} needs.
     *
     * <p>One key-less value among keyed siblings is enough to do harm: prism's
     * {@code DefaultNaturalKeyDefinitionImpl.valuesMatch} treats a constituent that is absent from
     * <em>both</em> values as matching, so that one value would pair with whichever live value also
     * lacks the key, and be edited into it.
     */
    private static boolean allCarry(NaturalKeyDefinition key, PrismContainer<?> container) {
        for (PrismContainerValue<?> value : container.getValues()) {
            if (!carries(key, value)) {
                return false;
            }
        }
        return true;
    }

    /**
     * One decision of the {@code --equivalence auto} walk: a multi-valued container of the desired object,
     * and whether natural-key pairing is safe throughout the subtree it roots.
     *
     * @param path           the container's id-free item path
     * @param naturalKeySafe whether the subtree may be diffed under {@code natural-keys}
     * @param reason         why not, naming the offending container and its key; {@code null} when safe
     */
    record AutoBucket(ItemPath path, boolean naturalKeySafe, String reason) {
    }

    /**
     * Split {@code object} into the subtrees that {@code --equivalence auto} may diff under
     * {@code natural-keys} and those it must diff under {@code real-value}.
     *
     * <p>The cut points are the <b>multi-valued containers reachable through single-valued ones only</b>.
     * They are the only safe places to switch equivalence, because the two strategies express a change
     * <em>inside</em> a multi-valued container at different depths: {@code natural-keys} pairs the entry and
     * emits {@code objectDetailsPage/3/panel}, while {@code real-value} cannot pair it and emits a
     * whole-value add/delete at {@code objectDetailsPage}. Cutting below such a container would let one pass
     * express a change the other pass expressed at an ancestor, losing or duplicating it. A single-valued
     * container is safe to cut below: prism always dives into one (its
     * {@code PrismContainerValueImpl.representsSameValue} returns true immediately under {@code lax}).
     *
     * <p>A cut is safe for natural keys only when <em>no</em> container in its whole subtree declares a key
     * that its values fail to fill in — which is what keeps {@code assignment} and friends out, with no
     * hardcoded deny list: they declare {@code identifier}, real configuration omits it, and so the subtree
     * is rejected on its own evidence.
     */
    static List<AutoBucket> autoBuckets(PrismObject<?> object) {
        List<AutoBucket> buckets = new ArrayList<>();
        collectBuckets(object.getValue(), ItemPath.EMPTY_PATH, buckets);
        return buckets;
    }

    private static void collectBuckets(PrismContainerValue<?> value, ItemPath prefix, List<AutoBucket> buckets) {
        for (Item<?, ?> item : value.getItems()) {
            if (!(item instanceof PrismContainer<?> container)) {
                continue;
            }
            ItemDefinition<?> definition = container.getDefinition();
            if (definition == null || definition.isOperational() || container.getValues().isEmpty()) {
                continue;
            }
            ItemPath path = prefix.append(item.getElementName());
            if (definition.isMultiValue()) {
                String unsafe = firstUnfilledKey(container, path);
                buckets.add(new AutoBucket(path, unsafe == null, unsafe));
            } else {
                for (PrismContainerValue<?> child : container.getValues()) {
                    collectBuckets(child, path, buckets);
                }
            }
        }
    }

    /**
     * The first container at or below {@code container} that declares a natural key not every one of its
     * values fills in, as a ready-to-print reason; {@code null} when the whole subtree is safe.
     */
    private static String firstUnfilledKey(PrismContainer<?> container, ItemPath path) {
        ItemDefinition<?> definition = container.getDefinition();
        if (definition != null && definition.isMultiValue()) {
            NaturalKeyDefinition key = of(container);
            if (key != null && !allCarry(key, container)) {
                return pathText(path) + " does not fill in its key (" + names(container) + ")";
            }
        }
        for (PrismContainerValue<?> value : container.getValues()) {
            for (Item<?, ?> item : value.getItems()) {
                if (!(item instanceof PrismContainer<?> child)) {
                    continue;
                }
                ItemDefinition<?> childDefinition = child.getDefinition();
                if (childDefinition == null || childDefinition.isOperational() || child.getValues().isEmpty()) {
                    continue;
                }
                String unsafe = firstUnfilledKey(child, path.append(item.getElementName()));
                if (unsafe != null) {
                    return unsafe;
                }
            }
        }
        return null;
    }

    private static String pathText(ItemPath path) {
        List<String> names = new ArrayList<>();
        for (Object segment : path.getSegments()) {
            if (ItemPath.isName(segment)) {
                names.add(ItemPath.toName(segment).getLocalPart());
            }
        }
        return String.join("/", names);
    }

    /**
     * The entries {@code after} holds more often than {@code before} does: values of one multi-valued container
     * that carry the same fully filled-in natural key, each as
     * {@code schemaHandling/objectType[kind=GENERIC, intent=myitTask] ×2}.
     *
     * <p>A natural key names one entry, so a list holding it twice is what a change looks like when it failed to
     * pair an entry with its live copy and added it beside it, or when the tree declares the entry twice.
     * midPoint rejects some of these outright — a resource with two object types of one kind and intent no longer
     * loads its schema — and quietly uses one copy of the others.
     *
     * <p>Only what the change introduces counts. A duplicate the server already held is not this change's doing,
     * and refusing on it would block every deploy of the object until someone removed it by hand. So each group
     * {@code after} holds is looked up in {@code before} at the same container — found by its path with the
     * container ids of the values on the way — and counted there with the key's own {@code valuesMatch}. A
     * container inside a value the change adds (no id yet) is new by definition. That also makes one inside an
     * id-less value of a hand-written {@code current} file count as new — a limit of comparing two files, not of
     * a live run, where every value the server holds has its id.
     */
    static List<String> introducedDuplicates(PrismObject<?> before, PrismObject<?> after) {
        if (after == null) {
            return List.of();
        }
        List<String> introduced = new ArrayList<>();
        for (Duplicate duplicate : duplicatesIn(after)) {
            if (duplicate.count() > heldBefore(before, duplicate)) {
                introduced.add(duplicate.label() + " ×" + duplicate.count());
            }
        }
        return introduced;
    }

    /** How many values carrying {@code duplicate}'s key the same container holds in {@code before}. */
    private static int heldBefore(PrismObject<?> before, Duplicate duplicate) {
        if (before == null || duplicate.where() == null) {
            return 0;
        }
        Item<?, ?> item = before.findItem(duplicate.where());
        if (!(item instanceof PrismContainer<?> container)) {
            return 0;
        }
        int held = 0;
        for (PrismContainerValue<?> value : container.getValues()) {
            if (carries(duplicate.key(), value) && duplicate.key().valuesMatch(duplicate.sample(), value)) {
                held++;
            }
        }
        return held;
    }

    /** Why a change {@link #introducedDuplicates} flags is refused, and what to do about it. */
    static String duplicateRefusal(List<String> duplicates) {
        return "refusing a change that would leave " + String.join(", ", duplicates) + " — values sharing one "
                + "natural key, which is meant to name a single entry. Either the tree declares that entry twice, "
                + "or its values were compared by content and the entry was not paired with its live copy: -v "
                + "names each list compared that way and the key inside it that is not filled in. Fill that key in, "
                + "or put the list on multivalue=replace with a policy rule.";
    }

    /**
     * One group of values sharing a key.
     *
     * @param where  the container's path, container ids included, to find it again in another object;
     *               {@code null} inside a value that has no container id
     * @param key    the container's natural key
     * @param sample one of the values, to match the others against
     * @param label  the id-free path plus the key, for the message
     * @param count  how many values carry the key
     */
    private record Duplicate(ItemPath where, NaturalKeyDefinition key, PrismContainerValue<?> sample, String label,
            int count) {
    }

    private static List<Duplicate> duplicatesIn(PrismObject<?> object) {
        List<Duplicate> found = new ArrayList<>();
        collectDuplicates(object.getValue(), new ArrayList<>(), "", found);
        return found;
    }

    /** {@code idPath} is {@code null} once the walk is inside a list value that has no container id. */
    private static void collectDuplicates(PrismContainerValue<?> value, List<Object> idPath, String namePath,
            List<Duplicate> found) {
        for (Item<?, ?> item : value.getItems()) {
            if (!(item instanceof PrismContainer<?> container)) {
                continue;
            }
            ItemDefinition<?> definition = container.getDefinition();
            if (definition == null || definition.isOperational() || container.getValues().isEmpty()) {
                continue;
            }
            List<Object> path = (idPath != null) ? appended(idPath, item.getElementName()) : null;
            String local = item.getElementName().getLocalPart();
            String names = namePath.isEmpty() ? local : namePath + "/" + local;
            boolean multiValue = definition.isMultiValue();
            if (multiValue) {
                NaturalKeyDefinition key = of(container);
                if (key != null) {
                    groupByKey(container, key, path, names, found);
                }
            }
            for (PrismContainerValue<?> child : container.getValues()) {
                // A single-valued container is unambiguous without an id; a value of a list is not.
                List<Object> childPath = (!multiValue || path == null) ? path
                        : (child.getId() != null) ? appended(path, child.getId()) : null;
                collectDuplicates(child, childPath, names, found);
            }
        }
    }

    private static List<Object> appended(List<Object> path, Object segment) {
        List<Object> longer = new ArrayList<>(path);
        longer.add(segment);
        return longer;
    }

    private static void groupByKey(PrismContainer<?> container, NaturalKeyDefinition key, List<Object> path,
            String names, List<Duplicate> found) {
        List<PrismContainerValue<?>> values = new ArrayList<>(container.getValues());
        boolean[] grouped = new boolean[values.size()];
        for (int i = 0; i < values.size(); i++) {
            if (grouped[i] || !carries(key, values.get(i))) {
                continue;
            }
            int count = 1;
            for (int j = i + 1; j < values.size(); j++) {
                if (!grouped[j] && carries(key, values.get(j)) && key.valuesMatch(values.get(i), values.get(j))) {
                    grouped[j] = true;
                    count++;
                }
            }
            if (count > 1) {
                ItemPath where = (path != null) ? ItemPath.create(path.toArray()) : null;
                String label = names + "[" + keyText(container, values.get(i)) + "]";
                found.add(new Duplicate(where, key, values.get(i), label, count));
            }
        }
    }

    /** The key's constituents and their values, {@code kind=GENERIC, intent=myitTask}. */
    private static String keyText(PrismContainer<?> container, PrismContainerValue<?> value) {
        ItemDefinition<?> definition = container.getDefinition();
        List<QName> constituents = (definition != null) ? definition.getNaturalKeyConstituents() : null;
        if (constituents == null || constituents.isEmpty()) {
            return "?";
        }
        List<String> parts = new ArrayList<>();
        for (QName constituent : constituents) {
            Item<?, ?> item = value.findItem(ItemPath.create(constituent));
            Object real = (item != null && item.getValues().size() == 1) ? item.getRealValue() : null;
            String text = (real != null) ? String.valueOf(real) : "?";
            parts.add(constituent.getLocalPart() + "=" + (text.length() > 80 ? text.substring(0, 80) + "…" : text));
        }
        return String.join(", ", parts);
    }
}
