/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code init --write-policy} bases its suggestions on: the multi-valued containers that have a schema
 * natural key <em>and</em> actually carry it.
 *
 * <p>The "actually carry it" half is the point. Many midPoint types declare a natural key that real
 * configuration never fills in — {@code assignment} and {@code inducement} declare {@code identifier} — and
 * suggesting natural-key matching there is bad advice: prism pairs unrelated values and the accrued-value
 * guard refuses the result. So a declared-but-unused key must not produce a suggestion.
 */
class NaturalKeysTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";

    private static PrismContext prismContext;

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @Test
    void aDeclaredButUnfilledKeyIsNotSuggested() throws Exception {
        // AssignmentType declares naturalKey=identifier; an ordinary assignment carries none.
        PrismObject<?> user = prismContext.parseObject(
                "<user xmlns=\"" + NS + "\" xmlns:c=\"" + NS + "\" oid=\"22222222-2222-2222-2222-222222222222\">\n"
                        + "  <name>jack</name>\n"
                        + "  <assignment><targetRef oid=\"role-1\" type=\"c:RoleType\"/></assignment>\n"
                        + "</user>");

        assertFalse(NaturalKeys.keyedContainers(user).containsKey("assignment"),
                "an assignment without <identifier> must not be suggested: " + NaturalKeys.keyedContainers(user));
    }

    @Test
    void aFilledKeyIsSuggestedWithItsConstituents() throws Exception {
        PrismObject<?> user = prismContext.parseObject(
                "<user xmlns=\"" + NS + "\" xmlns:c=\"" + NS + "\" oid=\"22222222-2222-2222-2222-222222222222\">\n"
                        + "  <name>jack</name>\n"
                        + "  <assignment>\n"
                        + "    <identifier>holderType</identifier>\n"
                        + "    <targetRef oid=\"role-1\" type=\"c:RoleType\"/>\n"
                        + "  </assignment>\n"
                        + "</user>");

        Map<String, String> keyed = NaturalKeys.keyedContainers(user);
        assertTrue(keyed.containsKey("assignment"), "a filled-in key is worth suggesting: " + keyed);
        assertTrue(keyed.get("assignment").contains("identifier"), keyed.get("assignment"));
    }

    @Test
    void nestedKeyedContainersAreFoundByTheirFullPath() throws Exception {
        // The case the whole feature exists for: a GUI panel nested inside a detail page, both keyed.
        PrismObject<?> config = prismContext.parseObject(
                "<systemConfiguration xmlns=\"" + NS + "\" xmlns:c=\"" + NS + "\""
                        + " oid=\"00000000-0000-0000-0000-000000000001\">\n"
                        + "  <name>SystemConfiguration</name>\n"
                        + "  <adminGuiConfiguration><objectDetails>\n"
                        + "    <objectDetailsPage>\n"
                        + "      <type>c:UserType</type>\n"
                        + "      <panel><identifier>my-panel</identifier></panel>\n"
                        + "    </objectDetailsPage>\n"
                        + "  </objectDetails></adminGuiConfiguration>\n"
                        + "</systemConfiguration>");

        Map<String, String> keyed = NaturalKeys.keyedContainers(config);
        assertTrue(keyed.containsKey("adminGuiConfiguration/objectDetails/objectDetailsPage"), keyed.toString());
        assertTrue(keyed.containsKey("adminGuiConfiguration/objectDetails/objectDetailsPage/panel"),
                "the nested panel is reported at its full path: " + keyed);
        assertTrue(keyed.get("adminGuiConfiguration/objectDetails/objectDetailsPage/panel").contains("identifier"),
                keyed.toString());
    }

    @Test
    void anObjectWithNoKeyedContainersSuggestsNothing() throws Exception {
        PrismObject<?> role = prismContext.parseObject(
                "<role xmlns=\"" + NS + "\" oid=\"11111111-1111-1111-1111-111111111111\">\n"
                        + "  <name>Plain</name>\n  <description>no containers here</description>\n</role>");

        assertTrue(NaturalKeys.keyedContainers(role).isEmpty(),
                "nothing to suggest: " + NaturalKeys.keyedContainers(role));
    }
}
