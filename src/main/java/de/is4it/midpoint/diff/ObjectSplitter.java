/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.ArrayList;
import java.util.List;

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismReferenceValue;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.prism.path.ItemName;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.prism.key.NaturalKeyDefinition;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.prism.xml.ns._public.types_3.PolyStringType;

/**
 * Splits a live object into the part the tree's managed files declare and the rest — the <b>remainder</b>
 * {@code pull} writes. It is the inverse of {@link FragmentUnion}: combining the managed files with the
 * remainder gives the live object back, so a deploy of both under {@code --scope full} has nothing to change.
 *
 * <p>An item no managed file declares goes to the remainder whole. Where a managed file declares an item, the
 * remainder holds only what the file leaves out:
 * <ul>
 * <li>a <b>single-valued container</b> is followed into, so a file declaring one setting of
 * {@code deploymentInformation} leaves the others to the remainder;</li>
 * <li>a <b>multi-valued container</b> pairs its entries the way the union does ({@link NaturalKeys#match}: by
 * container id, else by natural key). A paired entry with a natural key is followed into, and the remainder's
 * part repeats the key so the union pairs it back; a paired entry without one stays whole with the file, as
 * does an entry equal to one the file declares. An entry the file has no counterpart for goes to the
 * remainder;</li>
 * <li>a <b>multi-valued property or reference</b> keeps the values the file does not list in the remainder —
 * unless the file writes a reference as a filter, which only the deploy can pair with an OID;</li>
 * <li>a <b>single-valued item</b>, and an item without a definition, belongs to the file outright.</li>
 * </ul>
 *
 * <p>Where the server and a managed file disagree about something the file declares, the file's side is not
 * touched here; the difference is returned as drift, to report. One disagreement cannot be settled by either
 * side: a list where the file holds entries the server lacks <em>and</em> the server holds entries the file
 * lacks, with no key to pair them — typically one keyless entry edited on the server. Both would end up in the
 * combined object. That is returned as a conflict, and the caller writes nothing for the object.
 */
final class ObjectSplitter {

    private ObjectSplitter() {
    }

    /**
     * The remainder, and where the managed files and the server disagree.
     *
     * @param remainder the live object minus what the managed files declare; carries the OID and the name
     * @param drift     items the managed files declare differently from the server
     * @param conflicts lists whose entries cannot be paired between file and server (see the class comment)
     */
    record Split(PrismObject<? extends Objectable> remainder, List<String> drift, List<String> conflicts) {

        /** Whether the remainder says anything beyond the object's identity. */
        boolean hasContent() {
            for (Item<?, ?> item : remainder.getValue().getItems()) {
                if (!ObjectType.F_NAME.equals(item.getElementName())) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Split {@code live} — already without anything {@link Exclusions} takes out — against {@code managed}, the
     * union of the object's managed files, or {@code null} when the tree has none.
     */
    static Split split(PrismObject<? extends Objectable> live, PrismObject<? extends Objectable> managed)
            throws SchemaException {
        PrismObject<? extends Objectable> remainder = live.clone();
        List<String> drift = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        if (managed != null) {
            // With the diff's own view of which ids are this server's, or a foreign id would pair two entries.
            PrismObject<? extends Objectable> pairable = ContainerIds.trustedOnly(managed, live).desired();
            subtract(remainder.getValue(), pairable.getValue(), "", drift, conflicts);
            // The name stays in every remainder file, so that the file says which object it is.
            Item<?, ?> name = live.getValue().findItem(ObjectType.F_NAME);
            if (name != null && remainder.getValue().findItem(ObjectType.F_NAME) == null) {
                addItem(remainder.getValue(), name.clone());
            }
        }
        Exclusions.dropIds(remainder);
        return new Split(remainder, drift, conflicts);
    }

    /** Take out of {@code remainder} (a copy of a live value) what {@code managed} declares. */
    private static void subtract(PrismContainerValue<?> remainder, PrismContainerValue<?> managed, String prefix,
            List<String> drift, List<String> conflicts) throws SchemaException {
        for (Item<?, ?> item : new ArrayList<>(remainder.getItems())) {
            Item<?, ?> declared = managed.findItem(ItemName.fromQName(item.getElementName()));
            if (declared == null) {
                continue;
            }
            String path = prefix.isEmpty()
                    ? item.getElementName().getLocalPart()
                    : prefix + "/" + item.getElementName().getLocalPart();
            ItemDefinition<?> definition = item.getDefinition();
            boolean known = definition != null && !definition.isDynamic() && !isRaw(item) && !isRaw(declared);
            if (known && item instanceof PrismContainer<?> container
                    && declared instanceof PrismContainer<?> declaredContainer) {
                if (definition.isSingleValue()) {
                    subtractSingle(container, declaredContainer, path, drift, conflicts);
                } else {
                    subtractEntries(container, declaredContainer, path, drift, conflicts);
                }
            } else if (known && definition.isMultiValue()) {
                subtractValues(item, declared, path, drift);
            } else {
                if (!sameValues(item, declared) && !smartReference(declared)) {
                    drift.add(path);
                }
                removeItem(remainder, item);
                continue;
            }
            if (item.hasNoValues()) {
                removeItem(remainder, item);
            }
        }
    }

    private static void subtractSingle(PrismContainer<?> container, PrismContainer<?> declared, String path,
            List<String> drift, List<String> conflicts) throws SchemaException {
        if (container.getValues().isEmpty() || declared.getValues().isEmpty()) {
            return;
        }
        PrismContainerValue<?> value = container.getValues().get(0);
        subtract(value, declared.getValues().get(0), path, drift, conflicts);
        if (value.isEmpty()) {
            removeValue(container, value);
        }
    }

    /** Pair the entries of a multi-valued container with the file's, keep in the remainder what has no pair. */
    private static void subtractEntries(PrismContainer<?> container, PrismContainer<?> declared, String path,
            List<String> drift, List<String> conflicts) throws SchemaException {
        NaturalKeyDefinition key = NaturalKeys.of(declared);
        List<PrismContainerValue<?>> paired = new ArrayList<>();
        int liveOnly = 0;
        for (PrismContainerValue<?> value : new ArrayList<>(container.getValues())) {
            PrismContainerValue<?> match = NaturalKeys.match(declared, value, value.getId());
            if (match == null) {
                match = equalValue(declared, value);
                if (match != null) {
                    paired.add(match);
                    removeValue(container, value);
                } else {
                    liveOnly++;
                }
                continue;
            }
            paired.add(match);
            if (key != null && NaturalKeys.carries(key, value) && NaturalKeys.carries(key, match)) {
                List<Item<?, ?>> keyItems = keyItems(key, value);
                subtract(value, match, path, drift, conflicts);
                if (value.isEmpty()) {
                    removeValue(container, value);
                } else {
                    // The file declared the key, so subtracting took it out; the union needs it to pair this
                    // part with the file's entry.
                    for (Item<?, ?> keyItem : keyItems) {
                        if (value.findItem(ItemName.fromQName(keyItem.getElementName())) == null) {
                            addItem(value, keyItem);
                        }
                    }
                }
            } else {
                // Paired by container id alone: nothing to repeat in the remainder that would pair it back, so
                // the entry is the file's whole.
                if (!value.equals(match, EquivalenceStrategy.REAL_VALUE)) {
                    drift.add(path);
                }
                removeValue(container, value);
            }
        }
        int fileOnly = 0;
        for (PrismContainerValue<?> value : declared.getValues()) {
            if (!containsIdentity(paired, value)) {
                fileOnly++;
            }
        }
        if (fileOnly > 0 && liveOnly > 0) {
            conflicts.add(path + ": " + fileOnly + " entr" + (fileOnly == 1 ? "y" : "ies") + " of the file and "
                    + liveOnly + " of the server have no counterpart on the other side, and nothing pairs them"
                    + ((key == null) ? " (the list has no natural key)" : " (the key is not filled in on both)"));
        } else if (fileOnly > 0) {
            drift.add(path);
        }
    }

    /** Keep the values of a multi-valued property or reference the file does not list. */
    private static void subtractValues(Item<?, ?> item, Item<?, ?> declared, String path, List<String> drift) {
        if (smartReference(declared)) {
            // A reference written as a filter pairs with its OID only in the deploy's own judgement; any value
            // left here would be a second one beside the one the filter resolves to.
            if (!item.getValues().isEmpty()) {
                item.clear();
            }
            return;
        }
        List<PrismValue> live = new ArrayList<>(item.getValues());
        for (PrismValue value : live) {
            if (containsEqual(declared.getValues(), value)) {
                removeValue(item, value);
            }
        }
        for (PrismValue value : declared.getValues()) {
            if (!containsEqual(live, value)) {
                drift.add(path);
                break;
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // the value's type is the item's by construction
    private static void removeValue(Item<?, ?> item, PrismValue value) {
        ((Item) item).remove(value);
    }

    /** Whether any value of a reference item is written as a filter alone, with no OID. */
    static boolean smartReference(Item<?, ?> item) {
        for (PrismValue value : item.getValues()) {
            if (value instanceof PrismReferenceValue reference && reference.getOid() == null
                    && reference.getFilter() != null) {
                return true;
            }
        }
        return false;
    }

    static boolean isRaw(Item<?, ?> item) {
        for (PrismValue value : item.getValues()) {
            if (value.isRaw()) {
                return true;
            }
        }
        return false;
    }

    static boolean sameValues(Item<?, ?> one, Item<?, ?> other) {
        if (one.getValues().size() != other.getValues().size()) {
            return false;
        }
        for (PrismValue value : one.getValues()) {
            if (!containsEqual(other.getValues(), value) && !samePolyString(value, other)) {
                return false;
            }
        }
        return true;
    }

    /** A {@code name} read from a file and from the server may differ only in the normalized form prism adds. */
    private static boolean samePolyString(PrismValue value, Item<?, ?> other) {
        if (other.getValues().size() != 1) {
            return false;
        }
        Object mine = value.getRealValue();
        Object theirs = other.getValues().get(0).getRealValue();
        String left = origOf(mine);
        return left != null && left.equals(origOf(theirs));
    }

    private static String origOf(Object real) {
        if (real instanceof com.evolveum.midpoint.prism.polystring.PolyString poly) {
            return poly.getOrig();
        }
        if (real instanceof PolyStringType poly) {
            return poly.getOrig();
        }
        return null;
    }

    static boolean containsEqual(List<? extends PrismValue> values, PrismValue candidate) {
        for (PrismValue value : values) {
            if (value.equals(candidate, EquivalenceStrategy.REAL_VALUE)) {
                return true;
            }
        }
        return false;
    }

    static PrismContainerValue<?> equalValue(PrismContainer<?> container, PrismContainerValue<?> candidate) {
        for (PrismContainerValue<?> value : container.getValues()) {
            if (value.equals(candidate, EquivalenceStrategy.REAL_VALUE)) {
                return value;
            }
        }
        return null;
    }

    private static boolean containsIdentity(List<PrismContainerValue<?>> values, PrismContainerValue<?> candidate) {
        for (PrismContainerValue<?> value : values) {
            if (value == candidate) {
                return true;
            }
        }
        return false;
    }

    /** Copies of the items making up {@code value}'s natural key. */
    private static List<Item<?, ?>> keyItems(NaturalKeyDefinition key, PrismContainerValue<?> value) {
        List<Item<?, ?>> items = new ArrayList<>();
        for (Item<?, ?> constituent : key.getConstituents(value)) {
            if (constituent != null) {
                items.add(constituent.clone());
            }
        }
        return items;
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // the item carries its own value/definition types
    private static void addItem(PrismContainerValue<?> target, Item<?, ?> item) throws SchemaException {
        ((PrismContainerValue) target).add(item);
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // the item carries its own value/definition types
    private static void removeItem(PrismContainerValue<?> container, Item<?, ?> item) {
        ((PrismContainerValue) container).remove((Item) item);
    }
}
