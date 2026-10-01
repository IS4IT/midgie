/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The OIDs an object points at, which order a deploy. */
class ReferenceCollectorTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String SELF = "55555555-0000-0000-0000-000000000000";
    private static final String INDUCED = "55555555-0000-0000-0000-000000000001";
    private static final String MEMBER_OF = "55555555-0000-0000-0000-000000000002";
    private static final String ARCHETYPE = "55555555-0000-0000-0000-000000000003";
    private static final String CREATOR = "55555555-0000-0000-0000-000000000004";

    private static PrismContext prismContext;

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @Test
    void referencesMidPointMaintainsDoNotOrderTheDeploy() throws Exception {
        // An object exported from a server carries roleMembershipRef, archetypeRef and metadata; read as
        // dependencies they ordered the role after everything it had been given and reported cycles.
        String role = "<role xmlns=\"" + NS + "\" oid=\"" + SELF + "\"><name>R</name>"
                + "<metadata><creatorRef oid=\"" + CREATOR + "\" type=\"UserType\"/></metadata>"
                + "<inducement><targetRef oid=\"" + INDUCED + "\" type=\"RoleType\"/></inducement>"
                + "<roleMembershipRef oid=\"" + MEMBER_OF + "\" type=\"RoleType\"/>"
                + "<archetypeRef oid=\"" + ARCHETYPE + "\" type=\"ArchetypeType\"/>"
                + "<roleMembershipRef oid=\"" + SELF + "\" type=\"RoleType\"/></role>";

        Set<String> oids = ReferenceCollector.outboundOids(prismContext.parseObject(role));

        assertEquals(Set.of(INDUCED), oids);
    }
}
