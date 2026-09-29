/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import javax.xml.namespace.QName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismReference;
import com.evolveum.midpoint.prism.PrismReferenceValue;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.util.exception.SchemaException;

/**
 * Smart references inside list entries that the diff compares as whole values.
 *
 * <p>{@link DiffEngine} leaves a smart reference alone when its difference is a delta on the reference itself:
 * the server holds the OID its filter resolved to, the file holds the filter. That covers a reference at the top
 * level, inside a single-valued container, and inside a list entry the diff pairs (by id or natural key). It
 * does not cover an entry of a list compared by content — an {@code inducement} or {@code assignment} without
 * an id, whose {@code targetRef} or {@code construction/resourceRef} names its target by filter. There the whole
 * entry differs, the diff deletes the resolved entry and adds one whose reference has no OID, and every deploy
 * did it again.
 *
 * <p>So before the diff, each such entry is tried against the live entries of its list: with its filter-only
 * references replaced by the live references they resolved to — judged by the same {@link
 * DiffEngine.SmartReferenceCheck} — does it equal one of them? If exactly one, the desired copy takes those
 * live references and the entry is in sync. If several, the pairing is ambiguous (the server kept no filter to
 * compare, and the rest of the entries are identical), and it is left as a change with a note; {@code
 * --smart-refs verify} tells them apart.
 *
 * <p>References are followed through single-valued containers ({@code construction/resourceRef}). One inside a
 * list nested in the entry is not substituted; such an entry stays a change, which is sent with {@code
 * reevaluateSearchFilters} (see {@link DeltaOutput#carriesSmartReference}).
 */
final class NestedSmartReferences {

    private static final Logger LOG = LoggerFactory.getLogger(NestedSmartReferences.class);

    private NestedSmartReferences() {
    }

    /**
     * The desired object to diff, the list paths whose entries took a resolved reference, and a note per
     * ambiguous entry.
     */
    record Resolved(PrismObject<? extends Objectable> desired, List<String> paths, List<String> notes) {
    }

    static Resolved resolve(PrismObject<? extends Objectable> desired, PrismObject<? extends Objectable> live,
            DiffEngine.SmartReferenceCheck check) {
        if (live == null || !hasFilterOnlyReference(desired.getValue())) {
            return new Resolved(desired, List.of(), List.of());
        }
        PrismObject<? extends Objectable> copy = desired.clone();
        List<String> paths = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        walk(copy.getValue(), live.getValue(), ItemPath.EMPTY_PATH, check, paths, notes);
        return paths.isEmpty() ? new Resolved(desired, List.of(), List.copyOf(notes))
                : new Resolved(copy, List.copyOf(paths), List.copyOf(notes));
    }

    private static void walk(PrismContainerValue<?> desired, PrismContainerValue<?> live, ItemPath prefix,
            DiffEngine.SmartReferenceCheck check, List<String> paths, List<String> notes) {
        for (Item<?, ?> item : desired.getItems()) {
            if (!(item instanceof PrismContainer<?> desiredContainer)) {
                continue;
            }
            ItemDefinition<?> definition = desiredContainer.getDefinition();
            if (definition == null || definition.isOperational()) {
                continue;
            }
            PrismContainer<?> liveContainer = containerAt(live, item.getElementName());
            if (liveContainer == null || liveContainer.getValues().isEmpty()) {
                continue;
            }
            ItemPath path = prefix.append(item.getElementName());
            if (definition.isSingleValue()) {
                for (PrismContainerValue<?> value : desiredContainer.getValues()) {
                    walk(value, liveContainer.getValues().get(0), path, check, paths, notes);
                }
                continue;
            }
            resolveEntries(desiredContainer, liveContainer, path, check, paths, notes);
        }
    }

    private static void resolveEntries(PrismContainer<?> desired, PrismContainer<?> live, ItemPath path,
            DiffEngine.SmartReferenceCheck check, List<String> paths, List<String> notes) {
        // A live entry named by a desired id is prism's pairing, not this one's to hand out.
        Set<PrismContainerValue<?>> taken = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PrismContainerValue<?> value : desired.getValues()) {
            PrismContainerValue<?> byId = (value.getId() != null) ? live.findValue(value.getId()) : null;
            if (byId != null) {
                taken.add(byId);
            }
        }
        for (PrismContainerValue<?> value : desired.getValues()) {
            PrismContainerValue<?> byId = (value.getId() != null) ? live.findValue(value.getId()) : null;
            if (byId != null) {
                walk(value, byId, path, check, paths, notes); // paired by id: its own references diff directly
                continue;
            }
            if (!hasFilterOnlyReference(value)) {
                PrismContainerValue<?> byKey = NaturalKeys.match(live, value, null);
                if (byKey != null) {
                    walk(value, byKey, path, check, paths, notes);
                }
                continue;
            }
            List<PrismContainerValue<?>> candidates = new ArrayList<>();
            for (PrismContainerValue<?> liveValue : live.getValues()) {
                if (taken.contains(liveValue)) {
                    continue;
                }
                PrismContainerValue<?> trial = value.clone();
                if (substitute(trial, liveValue, check, "", new ArrayList<>())
                        && EquivalenceStrategy.REAL_VALUE.equals(trial, liveValue)) {
                    candidates.add(liveValue);
                }
            }
            String text = pathText(path);
            if (candidates.size() == 1) {
                List<String> replaced = new ArrayList<>();
                if (substitute(value, candidates.get(0), check, text, replaced)) {
                    taken.add(candidates.get(0));
                    replaced.stream().filter(p -> !paths.contains(p)).forEach(paths::add);
                }
            } else if (candidates.size() > 1) {
                notes.add(text + ": an entry whose reference names its target by filter matches "
                        + candidates.size() + " entries here once resolved, and the server kept no filter to tell"
                        + " them apart, so it is left as a change; --smart-refs verify resolves the filter to"
                        + " decide");
            }
        }
    }

    /**
     * Replace each filter-only reference in {@code desired} — directly in it or through single-valued
     * containers — by the value of the same reference in {@code live} that {@code check} accepts as its
     * resolution. Returns whether anything was replaced; each replaced reference's path, under {@code prefix},
     * goes to {@code replacedPaths}.
     */
    private static boolean substitute(PrismContainerValue<?> desired, PrismContainerValue<?> live,
            DiffEngine.SmartReferenceCheck check, String prefix, List<String> replacedPaths) {
        boolean replaced = false;
        for (Item<?, ?> item : desired.getItems()) {
            Item<?, ?> liveItem = live.findItem(ItemPath.create(item.getElementName()));
            String here = (prefix.isEmpty() ? "" : prefix + "/") + item.getElementName().getLocalPart();
            if (item instanceof PrismReference reference && liveItem instanceof PrismReference liveReference) {
                if (substituteValues(reference, liveReference, check)) {
                    replacedPaths.add(here);
                    replaced = true;
                }
            } else if (item instanceof PrismContainer<?> container && liveItem instanceof PrismContainer<?> liveContainer
                    && container.getDefinition() != null && container.getDefinition().isSingleValue()
                    && !container.getValues().isEmpty() && !liveContainer.getValues().isEmpty()) {
                replaced |= substitute(container.getValues().get(0), liveContainer.getValues().get(0), check,
                        here, replacedPaths);
            }
        }
        return replaced;
    }

    private static boolean substituteValues(PrismReference desired, PrismReference live,
            DiffEngine.SmartReferenceCheck check) {
        List<PrismReferenceValue> liveValues = new ArrayList<>(live.getValues());
        Set<PrismReferenceValue> takenLive = Collections.newSetFromMap(new IdentityHashMap<>());
        List<PrismReferenceValue> result = new ArrayList<>();
        boolean replaced = false;
        for (PrismReferenceValue value : desired.getValues()) {
            PrismReferenceValue match = DiffEngine.matchingResolution(value, liveValues, takenLive, check);
            if (match != null) {
                takenLive.add(match);
                result.add(match.clone());
                replaced = true;
            } else {
                result.add(value.clone());
            }
        }
        if (!replaced) {
            return false;
        }
        try {
            desired.clear();
            for (PrismReferenceValue value : result) {
                desired.add(value);
            }
            return true;
        } catch (SchemaException e) {
            // Falls back to the plain diff, which sends the entry with reevaluateSearchFilters.
            LOG.debug("could not take the resolved reference for {}", desired.getElementName(), e);
            return false;
        }
    }

    /** Whether {@code value} holds a reference by filter alone anywhere inside it. */
    static boolean hasFilterOnlyReference(PrismContainerValue<?> value) {
        for (Item<?, ?> item : value.getItems()) {
            if (item instanceof PrismReference reference) {
                for (PrismReferenceValue referenceValue : reference.getValues()) {
                    if (referenceValue.getOid() == null && referenceValue.getFilter() != null) {
                        return true;
                    }
                }
            } else if (item instanceof PrismContainer<?> container) {
                for (PrismContainerValue<?> child : container.getValues()) {
                    if (hasFilterOnlyReference(child)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Whether {@code value} — a reference, or a container value holding one anywhere — sets one by filter. */
    static boolean setsReferenceByFilter(PrismValue value) {
        if (value instanceof PrismReferenceValue reference) {
            return reference.getFilter() != null;
        }
        if (value instanceof PrismContainerValue<?> container) {
            for (Item<?, ?> item : container.getItems()) {
                for (PrismValue child : item.getValues()) {
                    if (setsReferenceByFilter(child)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static PrismContainer<?> containerAt(PrismContainerValue<?> parent, QName name) {
        Item<?, ?> item = parent.findItem(ItemPath.create(name));
        return (item instanceof PrismContainer<?> container) ? container : null;
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
}
