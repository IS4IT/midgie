/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DiffEngine#liveOnlyValues}: the live values of each multi-valued container the desired object declares
 * that it does not list. It is what {@code merge} keeps and {@code replace} deletes, so it is both what
 * {@code deploy} names as kept and what {@code init --write-policy} bases its list suggestions on.
 */
class LiveOnlyValuesTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";

    private static PrismContext prismContext;

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @Test
    void anEntryWithoutAKeyThatOnlyTheServerHoldsIsCounted() throws Exception {
        // An inducement has no key real config fills in, so it is matched by content: the one the file lists
        // is declared, the one someone added on the server is not.
        PrismObject<?> desired = parse(role(inducement("11111111-1111-1111-1111-111111111111")));
        PrismObject<?> live = parse(role(inducement("11111111-1111-1111-1111-111111111111")
                + inducement("22222222-2222-2222-2222-222222222222")));

        List<DiffEngine.LiveOnly> found = DiffEngine.liveOnlyValues(desired, live);

        assertEquals(1, found.size(), found.toString());
        assertEquals("inducement", found.get(0).pathText());
        assertEquals(1, found.get(0).count());
    }

    @Test
    void aNestedKeyedEntryIsCountedAtItsOwnPath() throws Exception {
        // The page the file declares is matched by its key; the extra panel inside it counts under the panel's
        // path, not as a second page.
        PrismObject<?> desired = parse(config(page("<panel><identifier>mine</identifier></panel>")));
        PrismObject<?> live = parse(config(page("<panel><identifier>mine</identifier></panel>"
                + "<panel><identifier>shipped</identifier></panel>")));

        List<DiffEngine.LiveOnly> found = DiffEngine.liveOnlyValues(desired, live);

        assertEquals(1, found.size(), found.toString());
        assertEquals("adminGuiConfiguration/objectDetails/objectDetailsPage/panel", found.get(0).pathText());
    }

    @Test
    void aListTheTreeFullyDeclaresHasNothingToSay() throws Exception {
        PrismObject<?> desired = parse(role(inducement("11111111-1111-1111-1111-111111111111")));

        assertTrue(DiffEngine.liveOnlyValues(desired, parse(role(inducement("11111111-1111-1111-1111-111111111111"))))
                .isEmpty());
        assertTrue(DiffEngine.liveOnlyValues(desired, null).isEmpty(), "nothing live, nothing to count");
    }

    private static PrismObject<?> parse(String xml) throws Exception {
        return prismContext.parseObject(xml);
    }

    private static String role(String body) {
        return "<role xmlns=\"" + NS + "\" xmlns:c=\"" + NS + "\" oid=\"33333333-3333-3333-3333-333333333333\">\n"
                + "  <name>Example Role</name>\n" + body + "</role>";
    }

    private static String inducement(String targetOid) {
        return "  <inducement><targetRef oid=\"" + targetOid + "\" type=\"c:RoleType\"/></inducement>\n";
    }

    private static String config(String pages) {
        return "<systemConfiguration xmlns=\"" + NS + "\" xmlns:c=\"" + NS + "\""
                + " oid=\"00000000-0000-0000-0000-000000000001\">\n"
                + "  <name>SystemConfiguration</name>\n"
                + "  <adminGuiConfiguration><objectDetails>" + pages + "</objectDetails></adminGuiConfiguration>\n"
                + "</systemConfiguration>";
    }

    private static String page(String panels) {
        return "<objectDetailsPage><type>c:UserType</type>" + panels + "</objectDetailsPage>";
    }
}
