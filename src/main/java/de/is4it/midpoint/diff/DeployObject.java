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
import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismProperty;
import com.evolveum.midpoint.util.exception.SchemaException;

/**
 * One logical object to deploy, backed by one or more {@link Fragment}s that share an identity (OID, or
 * type+name). It is <b>the apply unit</b>: the fragments are combined into a single desired object
 * ({@link FragmentUnion}) which is reconciled once, so an object described by several files becomes one
 * PATCH — or one POST — rather than one per file.
 *
 * <p>Grouping runs a best-effort consistency check first: all fragments must be the same object type, and no
 * single-valued item may be declared with contradicting values across fragments. A multi-valued item declared
 * by more than one fragment needs no check at all — combining is exactly what the union does with it, which
 * is why the "last-one-wins" warning this class used to emit is gone: the hazard it warned about cannot
 * happen any more.
 *
 * <p>The node's outbound dependency edges are the union of its fragments' outbound reference OIDs.
 */
final class DeployObject {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DeployObject.class);

    private final String identityKey;
    private final List<Fragment> fragments;
    private final PrismObject<? extends Objectable> object;
    private final String typeName;
    private final String name;
    private final String oid;
    private final String identityLabel;
    private final Set<String> outboundOids;

    private DeployObject(String identityKey, List<Fragment> fragments,
            PrismObject<? extends Objectable> object, String typeName, String name, String oid,
            String identityLabel, Set<String> outboundOids) {
        this.identityKey = identityKey;
        this.fragments = fragments;
        this.object = object;
        this.typeName = typeName;
        this.name = name;
        this.oid = oid;
        this.identityLabel = identityLabel;
        this.outboundOids = outboundOids;
    }

    /**
     * One fragment as its own apply unit, for the deployable schema objects: they are ordered among
     * themselves by {@code xsd:import} rather than by reference, so they never take part in grouping.
     */
    static DeployObject of(Fragment fragment) throws SchemaException {
        return build(fragment.identityKey(), List.of(fragment));
    }

    /** Group fragments by identity into logical objects, running the per-group consistency check. */
    static List<DeployObject> group(List<Fragment> fragments) throws SchemaException {
        // One object is routinely described by a fragment carrying its OID and another carrying only its
        // name. Keying purely on "OID else type/name" files those under different keys, making them two
        // objects: the contradiction check and the clobber warning never run between them, nothing that
        // references the object orders itself before the OID-less half, and at apply time that half is
        // skipped as "no oid". So resolve type/name to a known OID first.
        Map<String, String> oidByTypeAndName = new LinkedHashMap<>();
        for (Fragment fragment : fragments) {
            if (isSet(fragment.oid()) && isSet(fragment.name())) {
                oidByTypeAndName.putIfAbsent(fragment.typeName() + "/" + fragment.name(), fragment.oid());
            }
        }
        Map<String, List<Fragment>> byIdentity = new LinkedHashMap<>();
        for (Fragment fragment : fragments) {
            String key = fragment.identityKey();
            byIdentity.computeIfAbsent(oidByTypeAndName.getOrDefault(key, key), ignored -> new ArrayList<>())
                    .add(fragment);
        }
        List<DeployObject> objects = new ArrayList<>();
        for (Map.Entry<String, List<Fragment>> entry : byIdentity.entrySet()) {
            objects.add(build(entry.getKey(), entry.getValue()));
        }
        return objects;
    }

    private static DeployObject build(String key, List<Fragment> group) throws SchemaException {
        group.sort(Comparator.comparing(fragment -> fragment.sourceFile().getPath()));
        Fragment first = group.get(0);

        // The group's identity is whatever any of its fragments knows, not whatever the first file happens
        // to carry: the alphabetically first fragment may be the one that omits the OID or the name.
        String groupOid = firstSet(group, Fragment::oid);
        String groupName = firstSet(group, Fragment::name);
        for (Fragment fragment : group) {
            fragment.adoptOid(groupOid);
        }

        for (Fragment fragment : group) {
            if (fragment.compileTimeClass() != first.compileTimeClass()) {
                throw new SchemaException("Contradiction for object " + first.identityLabel() + ": "
                        + first.sourceFile() + " is a " + first.typeName() + " but " + fragment.sourceFile()
                        + " is a " + fragment.typeName() + ".");
            }
        }

        Map<String, Object> seen = new LinkedHashMap<>();
        Map<String, File> seenIn = new LinkedHashMap<>();
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

            outbound.addAll(ReferenceCollector.outboundOids(fragment.object()));
        }
        if (groupOid != null) {
            outbound.remove(groupOid);
        }
        // Combined only after the consistency check has had its say: a contradiction is a better message
        // than whatever the union would make of two files that disagree.
        PrismObject<? extends Objectable> object = FragmentUnion.of(group);
        if (group.size() > 1) {
            LOG.debug("object {} is assembled from {} file(s): {}", first.identityLabel(), group.size(),
                    sourceFilesOf(group));
        }
        return new DeployObject(key, group, object, first.typeName(), groupName, groupOid,
                first.identityLabel(), outbound);
    }

    /** The files that describe this object, for the messages that have to name all of them. */
    private static List<File> sourceFilesOf(List<Fragment> group) {
        List<File> files = new ArrayList<>();
        for (Fragment fragment : group) {
            files.add(fragment.sourceFile());
        }
        return files;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstSet(List<Fragment> group,
            java.util.function.Function<Fragment, String> reader) {
        for (Fragment fragment : group) {
            String value = reader.apply(fragment);
            if (isSet(value)) {
                return value;
            }
        }
        return null;
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

    String identityKey() {
        return identityKey;
    }

    List<Fragment> fragments() {
        return fragments;
    }

    /**
     * The desired object to reconcile: every fragment's contribution combined into one. Identical to the
     * single fragment's own object when only one file describes this object.
     */
    PrismObject<? extends Objectable> object() {
        return object;
    }

    /** The files this object is described by, in the order they were read. */
    List<File> sourceFiles() {
        return sourceFilesOf(fragments);
    }

    /**
     * The object's file(s) in one column's worth of text: the file, or the first of them and how many more
     * there are. The full list goes in the apply-order table, which has room for it.
     */
    String sourceLabel() {
        List<File> files = sourceFiles();
        return (files.size() == 1) ? files.get(0).toString()
                : files.get(0) + " (+" + (files.size() - 1) + " more)";
    }

    /** The object's {@code Type/Name} path — the identity form the {@code --policy} selectors also use. */
    String objectPath() {
        return typeName + "/" + (name != null && !name.isBlank() ? name : "?");
    }

    /** The OID for the {@code --debug} identity column, or {@code "-"} when the object has none. */
    String oidLabel() {
        return (oid != null && !oid.isBlank()) ? oid : "-";
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
