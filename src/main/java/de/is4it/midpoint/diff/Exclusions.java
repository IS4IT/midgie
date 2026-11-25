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
import com.evolveum.midpoint.prism.PrismValue;

/**
 * What of a live object never goes into a tree file, and so is never part of what {@code pull} writes: the
 * items midgie does not deploy either.
 *
 * <ul>
 * <li><b>operational items</b> and <b>value metadata</b> — the server's own bookkeeping, rewritten on every
 * change, and meaningless on any other server;</li>
 * <li><b>protected values</b> — the ciphertext re-encrypts on every read, and deploy never writes it;</li>
 * <li><b>incomplete items</b> — the server did not send them, so there is nothing to write down;</li>
 * <li>items the policy marks <b>{@code apply=never}</b> — the server owns them outright;</li>
 * <li>the object's <b>version</b>, and <b>container ids</b> ({@link #dropIds}, once the ids have served to
 * pair the live entries with the tree's) — numbered by this server, and a file carrying them would pair entries
 * by number on another one.</li>
 * </ul>
 *
 * <p>Deploy leaves each of these alone for its own reasons (the diff skips protected and incomplete items,
 * {@code never} holds an item back, operational items are not compared), so a file written without them is
 * still complete for the deploy that reads it back: under {@code --scope full} nothing excluded here is
 * deleted.
 */
final class Exclusions {

    private Exclusions() {
    }

    /** A live object with every excluded item taken out, and the paths that were worth naming. */
    record Stripped(PrismObject<? extends Objectable> object, List<String> neverPaths, List<String> protectedPaths,
            List<String> incompletePaths) {
    }

    /**
     * A copy of {@code live} without anything excluded. A container value emptied by that is pruned, so one that
     * held nothing but metadata does not survive as an empty element; one the server holds empty stays.
     */
    static Stripped strip(PrismObject<? extends Objectable> live, ApplyRules apply) {
        PrismObject<? extends Objectable> copy = live.clone();
        copy.setVersion(null);
        List<String> neverPaths = apply.stripNever(copy);
        List<String> protectedPaths = new ArrayList<>();
        List<String> incompletePaths = new ArrayList<>();
        clean(copy.getValue(), "", protectedPaths, incompletePaths);
        copy.getValue().deleteValueMetadata();
        return new Stripped(copy, neverPaths, protectedPaths, incompletePaths);
    }

    /** Take every container id out of {@code object}. */
    static void dropIds(PrismObject<? extends Objectable> object) {
        dropIds(object.getValue());
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

    private static void clean(PrismContainerValue<?> container, String prefix, List<String> protectedPaths,
            List<String> incompletePaths) {
        List<Item<?, ?>> drop = new ArrayList<>();
        for (Item<?, ?> item : container.getItems()) {
            String path = prefix.isEmpty()
                    ? item.getElementName().getLocalPart()
                    : prefix + "/" + item.getElementName().getLocalPart();
            ItemDefinition<?> definition = item.getDefinition();
            if (definition != null && definition.isOperational()) {
                drop.add(item);
                continue;
            }
            if (item.isIncomplete()) {
                incompletePaths.add(path);
                drop.add(item);
                continue;
            }
            if (DiffEngine.isProtectedItem(item)) {
                protectedPaths.add(path);
                drop.add(item);
                continue;
            }
            for (PrismValue value : item.getValues()) {
                value.deleteValueMetadata();
            }
            if (item instanceof PrismContainer<?> nested && !nested.getValues().isEmpty()) {
                List<PrismContainerValue<?>> emptied = new ArrayList<>();
                for (PrismContainerValue<?> value : nested.getValues()) {
                    // An empty value the server holds is content — a task's <work><cleanup/></work> says which
                    // work it does — so only a value emptied by taking things out goes.
                    if (value.isEmpty()) {
                        continue;
                    }
                    clean(value, path, protectedPaths, incompletePaths);
                    if (value.isEmpty()) {
                        emptied.add(value);
                    }
                }
                for (PrismContainerValue<?> value : emptied) {
                    removeValue(nested, value);
                }
                if (nested.hasNoValues()) {
                    drop.add(item);
                }
            }
        }
        for (Item<?, ?> item : drop) {
            removeItem(container, item);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // the value's type is the item's by construction
    private static void removeValue(Item<?, ?> item, PrismValue value) {
        ((Item) item).remove(value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // the item carries its own value/definition types
    private static void removeItem(PrismContainerValue<?> container, Item<?, ?> item) {
        ((PrismContainerValue) container).remove((Item) item);
    }
}
