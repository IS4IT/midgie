/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.LinkedHashSet;
import java.util.Set;

import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismReferenceValue;
import com.evolveum.midpoint.prism.Visitor;

/**
 * Collects every outbound reference OID from a parsed object — the raw material for the {@code deploy}
 * dependency graph. Walks the whole object subtree with a prism {@link Visitor} and picks up each
 * {@link PrismReferenceValue} that carries an OID (this is the same idiom midPoint's own
 * {@code ModelImplUtils.resolveReferences} uses). The object's own OID is dropped, so a self-reference
 * never produces a self-edge.
 */
final class ReferenceCollector {

    private ReferenceCollector() {
    }

    /** The OIDs of all references pointing out of {@code object}, minus the object's own OID. */
    @SuppressWarnings({"rawtypes", "unchecked"}) // prism's accept() takes a raw Visitor, as midPoint uses it
    static Set<String> outboundOids(PrismObject<?> object) {
        Set<String> oids = new LinkedHashSet<>();
        Visitor visitor = visitable -> {
            if (visitable instanceof PrismReferenceValue reference && reference.getOid() != null) {
                oids.add(reference.getOid());
            }
        };
        object.accept(visitor);
        if (object.getOid() != null) {
            oids.remove(object.getOid());
        }
        return oids;
    }
}
