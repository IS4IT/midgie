/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismProperty;
import com.evolveum.midpoint.util.exception.SchemaException;

/**
 * One logical object to deploy, backed by one or more {@link Fragment}s that share an identity (OID, or
 * type+name). The fragments are kept individually (each is its own apply unit) but treated as one node in
 * the dependency graph. Grouping runs a best-effort consistency check: all fragments must be the same
 * object type, and no single-valued item may be declared with contradicting values across fragments. A
 * multi-valued item declared by more than one fragment is not a contradiction, but — because fragments
 * apply file-by-file and the default is replace — a later PATCH would overwrite the earlier one
 * (last-one-wins), so it is warned about (combine the files, or set a merge policy). The node's outbound
 * dependency edges are the union of its fragments' outbound reference OIDs.
 */
final class DeployObject {

    private final String identityKey;
    private final List<Fragment> fragments;
    private final String typeName;
    private final String name;
    private final String oid;
    private final String identityLabel;
    private final Set<String> outboundOids;

    private DeployObject(String identityKey, List<Fragment> fragments, String typeName, String name,
            String oid, String identityLabel, Set<String> outboundOids) {
        this.identityKey = identityKey;
        this.fragments = fragments;
        this.typeName = typeName;
        this.name = name;
        this.oid = oid;
        this.identityLabel = identityLabel;
        this.outboundOids = outboundOids;
    }

    /** Group fragments by identity into logical objects, running the per-group consistency check. */
    static List<DeployObject> group(List<Fragment> fragments, List<String> warnings) throws SchemaException {
        Map<String, List<Fragment>> byIdentity = new LinkedHashMap<>();
        for (Fragment fragment : fragments) {
            byIdentity.computeIfAbsent(fragment.identityKey(), key -> new ArrayList<>()).add(fragment);
        }
        List<DeployObject> objects = new ArrayList<>();
        for (Map.Entry<String, List<Fragment>> entry : byIdentity.entrySet()) {
            objects.add(build(entry.getKey(), entry.getValue(), warnings));
        }
        return objects;
    }

    private static DeployObject build(String key, List<Fragment> group, List<String> warnings) throws SchemaException {
        group.sort(Comparator.comparing(fragment -> fragment.sourceFile().getPath()));
        Fragment first = group.get(0);

        for (Fragment fragment : group) {
            if (fragment.compileTimeClass() != first.compileTimeClass()) {
                throw new SchemaException("Contradiction for object " + first.identityLabel() + ": "
                        + first.sourceFile() + " is a " + first.typeName() + " but " + fragment.sourceFile()
                        + " is a " + fragment.typeName() + ".");
            }
        }

        Map<String, Object> seen = new LinkedHashMap<>();
        Map<String, File> seenIn = new LinkedHashMap<>();
        Map<String, File> multiSeenIn = new LinkedHashMap<>();
        Set<String> multiWarned = new LinkedHashSet<>();
        Set<String> outbound = new LinkedHashSet<>();
        for (Fragment fragment : group) {
            Map<String, Object> declared = new LinkedHashMap<>();
            collectSingleValued(fragment.object().getValue(), "", declared);
            for (Map.Entry<String, Object> entry : declared.entrySet()) {
                Object previous = seen.get(entry.getKey());
                if (previous != null && !previous.equals(entry.getValue())) {
                    throw new SchemaException("Contradiction for object " + first.identityLabel() + " at '"
                            + entry.getKey() + "': " + seenIn.get(entry.getKey()) + " sets '" + previous
                            + "' but " + fragment.sourceFile() + " sets '" + entry.getValue() + "'.");
                }
                if (previous == null) {
                    seen.put(entry.getKey(), entry.getValue());
                    seenIn.put(entry.getKey(), fragment.sourceFile());
                }
            }

            // A multi-valued item declared by two fragments is not a contradiction, but file-by-file apply
            // does not merge them: under the default (replace) the later PATCH overwrites the earlier one.
            Set<String> multi = new LinkedHashSet<>();
            collectMultiValued(fragment.object().getValue(), "", multi);
            for (String path : multi) {
                File previous = multiSeenIn.putIfAbsent(path, fragment.sourceFile());
                if (previous != null && multiWarned.add(path)) {
                    warnings.add("object " + first.identityLabel() + ": " + previous + " and "
                            + fragment.sourceFile() + " both set the multi-valued '" + path + "' — fragments "
                            + "apply file-by-file, so under the default (replace) a later PATCH overwrites the "
                            + "earlier one (last-one-wins). Put them in one file, or set '" + path
                            + " multivalue=merge' in --policy.");
                }
            }

            outbound.addAll(ReferenceCollector.outboundOids(fragment.object()));
        }
        if (first.oid() != null) {
            outbound.remove(first.oid());
        }
        return new DeployObject(key, group, first.typeName(), first.name(), first.oid(),
                first.identityLabel(), outbound);
    }

    /**
     * Record every single-valued property value under {@code pcv}, descending only through single-valued
     * containers (e.g. {@code extension}). Multi-valued items are handled by {@link #collectMultiValued};
     * operational items (metadata &amp; friends) are ignored so they never look like a contradiction.
     */
    private static void collectSingleValued(PrismContainerValue<?> pcv, String prefix, Map<String, Object> out) {
        for (Item<?, ?> item : pcv.getItems()) {
            ItemDefinition<?> definition = item.getDefinition();
            if (definition == null || definition.isOperational() || !definition.isSingleValue()) {
                continue;
            }
            String path = prefix.isEmpty()
                    ? item.getElementName().getLocalPart()
                    : prefix + "/" + item.getElementName().getLocalPart();
            if (item instanceof PrismProperty) {
                Object value = item.getRealValue();
                if (value != null) {
                    out.put(path, value);
                }
            } else if (item instanceof PrismContainer) {
                for (PrismContainerValue<?> child : ((PrismContainer<?>) item).getValues()) {
                    collectSingleValued(child, path, out);
                }
            }
        }
    }

    /**
     * Record the paths of multi-valued items (containers or properties) that {@code pcv} declares with at
     * least one value, descending only through single-valued containers. Two fragments declaring the same
     * such path clobber under the default replace, which the caller warns about.
     */
    private static void collectMultiValued(PrismContainerValue<?> pcv, String prefix, Set<String> out) {
        for (Item<?, ?> item : pcv.getItems()) {
            ItemDefinition<?> definition = item.getDefinition();
            if (definition == null || definition.isOperational() || item.getValues().isEmpty()) {
                continue;
            }
            String path = prefix.isEmpty()
                    ? item.getElementName().getLocalPart()
                    : prefix + "/" + item.getElementName().getLocalPart();
            if (definition.isMultiValue()) {
                out.add(path);
            } else if (item instanceof PrismContainer) {
                for (PrismContainerValue<?> child : ((PrismContainer<?>) item).getValues()) {
                    collectMultiValued(child, path, out);
                }
            }
        }
    }

    String identityKey() {
        return identityKey;
    }

    List<Fragment> fragments() {
        return fragments;
    }

    String typeName() {
        return typeName;
    }

    String name() {
        return name;
    }

    String oid() {
        return oid;
    }

    String identityLabel() {
        return identityLabel;
    }

    Set<String> outboundOids() {
        return outboundOids;
    }
}
