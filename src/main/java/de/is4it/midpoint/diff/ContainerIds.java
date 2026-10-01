/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.ArrayList;
import java.util.List;

import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.prism.key.NaturalKeyDefinition;
import com.evolveum.midpoint.prism.path.ItemPath;

/**
 * Which container ids in a desired object name the same entries on the live one.
 *
 * <p>prism pairs two values of a multi-valued container by their ids whenever both carry one, under every
 * equivalence strategy, {@code real-value} included, and then edits the live value into the desired one. That
 * is right for a file exported from the server it is deployed to. It is wrong for a file exported from another
 * one: ids are numbered per object on each server, so a DEV file's {@code <inducement id="5">} names whatever
 * PROD happens to hold under 5 — often an entry someone added there — and the diff rewrote that entry in place,
 * under {@code merge} too, which promises to keep it.
 *
 * <p>So before the diff, each list's ids are checked against the live list, and the whole list is treated as
 * numbered elsewhere when the server shows any of three contradictions:
 * <ul>
 *   <li>a value with the same id carries a different filled-in natural key;</li>
 *   <li>a desired value is already held on the server under a <em>different</em> id — by key, or by content;</li>
 *   <li>the live value with a desired value's id is declared by another desired value.</li>
 * </ul>
 * midPoint never reuses a container id, so none of these can happen between a server and a file exported from
 * it. The ids of such a list are dropped from a copy of desired, and its values pair by natural key or by
 * content instead, which is what an id-less hand-written file gets anyway.
 *
 * <p>What this cannot catch is one edited entry whose foreign id happens to be taken, with nothing else in the
 * list to show the numbering is foreign: an edit and a collision look the same from the two objects alone.
 */
final class ContainerIds {

    private ContainerIds() {
    }

    /**
     * The desired object to diff, and a note per list whose ids were dropped.
     *
     * @param desired {@code desired} itself when every id checked out, else a copy without the foreign ones
     * @param notes   one message per list, for {@code -v}
     */
    record Trusted(PrismObject<? extends Objectable> desired, List<String> notes) {
    }

    /** {@code desired} with the ids that do not name the same entries on {@code live} removed. */
    static Trusted trustedOnly(PrismObject<? extends Objectable> desired, PrismObject<? extends Objectable> live) {
        if (live == null) {
            return new Trusted(desired, List.of());
        }
        PrismObject<? extends Objectable> copy = desired.clone();
        List<String> notes = new ArrayList<>();
        dropForeign(copy.getValue(), live.getValue(), ItemPath.EMPTY_PATH, notes);
        return notes.isEmpty() ? new Trusted(desired, List.of()) : new Trusted(copy, List.copyOf(notes));
    }

    private static void dropForeign(PrismContainerValue<?> desired, PrismContainerValue<?> live, ItemPath prefix,
            List<String> notes) {
        if (live == null) {
            return;
        }
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
                    dropForeign(value, liveContainer.getValues().get(0), path, notes);
                }
                continue;
            }
            String contradiction = contradiction(desiredContainer, liveContainer);
            if (contradiction != null) {
                for (PrismContainerValue<?> value : desiredContainer.getValues()) {
                    value.setId(null);
                }
                notes.add(pathText(path) + ": the container ids in the file do not match this server's ("
                        + contradiction + "), so its entries are paired by natural key or by content instead");
            }
            for (PrismContainerValue<?> value : desiredContainer.getValues()) {
                dropForeign(value, NaturalKeys.match(liveContainer, value, value.getId()), path, notes);
            }
        }
    }

    /** The first sign that {@code desired}'s ids were numbered on another server, or {@code null}. */
    static String contradiction(PrismContainer<?> desired, PrismContainer<?> live) {
        NaturalKeyDefinition key = NaturalKeys.of(desired);
        for (PrismContainerValue<?> value : desired.getValues()) {
            Long id = value.getId();
            if (id == null) {
                continue;
            }
            PrismContainerValue<?> sameId = live.findValue(id);
            if (sameId != null && keysDiffer(key, value, sameId)) {
                return "entry " + id + " holds a different " + NaturalKeys.names(desired) + " here";
            }
            if (sameId != null && sameEntry(key, value, sameId)) {
                continue;
            }
            for (PrismContainerValue<?> other : live.getValues()) {
                if (other.getId() != null && !other.getId().equals(id) && sameEntry(key, value, other)) {
                    return "the file's entry " + id + " is entry " + other.getId() + " here";
                }
            }
            if (sameId != null) {
                for (PrismContainerValue<?> sibling : desired.getValues()) {
                    if (sibling != value && sameEntry(key, sibling, sameId)) {
                        return "entry " + id + " here is the file's " + describe(sibling);
                    }
                }
            }
        }
        return null;
    }

    /** Whether both values fill in the key and it names different entries. */
    private static boolean keysDiffer(NaturalKeyDefinition key, PrismContainerValue<?> a, PrismContainerValue<?> b) {
        return key != null && NaturalKeys.carries(key, a) && NaturalKeys.carries(key, b) && !key.valuesMatch(a, b);
    }

    /** The same entry: by natural key where both fill it in, else by content. */
    private static boolean sameEntry(NaturalKeyDefinition key, PrismContainerValue<?> a, PrismContainerValue<?> b) {
        if (key != null && NaturalKeys.carries(key, a) && NaturalKeys.carries(key, b)) {
            return key.valuesMatch(a, b);
        }
        return EquivalenceStrategy.REAL_VALUE.equals(a, b);
    }

    private static String describe(PrismContainerValue<?> value) {
        return (value.getId() != null) ? "entry " + value.getId() : "entry without an id";
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
