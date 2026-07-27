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
     * Every keyed multi-valued container the object declares <em>and fills the key in for</em>, as
     * {@code path -> key names}, in document order. These are the places where
     * {@code equivalence=natural-keys} plus {@code multivalue=merge} lets individual entries be managed.
     *
     * <p>The "fills it in" part is what keeps this honest. Plenty of types declare a natural key that real
     * configuration never carries — an {@code assignment} or {@code inducement} rarely has an
     * {@code identifier} — and there natural-key matching does not help: prism pairs unrelated values and the
     * accrued-value guard rightly refuses the result. Suggesting it for those would be actively bad advice.
     */
    static Map<String, String> keyedContainers(PrismObject<?> object) {
        Map<String, String> found = new LinkedHashMap<>();
        walk(object.getValue(), ItemPath.EMPTY_PATH, found);
        return found;
    }

    private static void walk(PrismContainerValue<?> value, ItemPath prefix, Map<String, String> found) {
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
                NaturalKeyDefinition key = of(container);
                if (key != null && anyCarries(key, container)) {
                    found.put(pathText(path), names(container));
                }
            }
            for (PrismContainerValue<?> child : container.getValues()) {
                walk(child, path, found);
            }
        }
    }

    /** Whether at least one of the container's values actually carries the key. */
    private static boolean anyCarries(NaturalKeyDefinition key, PrismContainer<?> container) {
        for (PrismContainerValue<?> value : container.getValues()) {
            if (carries(key, value)) {
                return true;
            }
        }
        return false;
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
