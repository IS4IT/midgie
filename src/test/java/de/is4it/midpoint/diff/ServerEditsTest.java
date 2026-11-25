/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Telling a deploy's own changes from ones that would undo an edit made on the server: the server's state counts
 * as known when the tree has been through it since its last deploy.
 */
class ServerEditsTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String OID = "77777777-0000-0000-0000-00000000dddd";

    private static PrismContext prismContext;

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @Test
    void aChangeOnlyTheTreeMadeIsTheTrees() throws Exception {
        ServerEdits.Finding finding = judgeOne(user("<locality>Hamburg</locality>"),
                user("<locality>Berlin</locality>"), user("<locality>Berlin</locality>"));

        assertEquals(ServerEdits.Kind.YOURS, finding.kind());
        assertEquals("locality: your change — server \"Berlin\", last deployed \"Berlin\" → tree \"Hamburg\"",
                finding.describe());
    }

    @Test
    void aChangeOnlyTheServerMadeWouldBeUndone() throws Exception {
        ServerEdits.Finding finding = judgeOne(user("<locality>Berlin</locality>"),
                user("<locality>Leipzig</locality>"), user("<locality>Berlin</locality>"));

        assertEquals(ServerEdits.Kind.SERVER_EDIT, finding.kind());
        assertEquals("\"Leipzig\"", finding.server());
        assertEquals("\"Berlin\"", finding.lastDeployed());
    }

    @Test
    void bothSidesMoved() throws Exception {
        ServerEdits.Finding finding = judgeOne(user("<locality>Hamburg</locality>"),
                user("<locality>Leipzig</locality>"), user("<locality>Berlin</locality>"));

        assertEquals(ServerEdits.Kind.BOTH, finding.kind());
    }

    @Test
    void aValueTheTreeHeldSinceTheLastDeployIsKnown() throws Exception {
        // Pulled from the server, committed, then edited: the pulled value is a state the tree went through.
        ServerEdits.Finding finding = judgeOne(user("<locality>Hamburg</locality>"),
                user("<locality>Leipzig</locality>"), user("<locality>Berlin</locality>"),
                user("<locality>Hamburg</locality>"), user("<locality>Leipzig</locality>"));

        assertEquals(ServerEdits.Kind.YOURS, finding.kind());
    }

    @Test
    void aValueTheTreeHeldOnlyBeforeTheLastDeployIsNot() throws Exception {
        // The tree went Leipzig, then Berlin, and deployed Berlin; the server is back on Leipzig. Leipzig is in the
        // history, but not since the last deploy, so it is the server's edit, not a state the tree is moving on from.
        ServerEdits.Finding finding = judgeOne(user("<locality>Berlin</locality>"),
                user("<locality>Leipzig</locality>"), user("<locality>Berlin</locality>"),
                user("<locality>Berlin</locality>"));

        assertEquals(ServerEdits.Kind.SERVER_EDIT, finding.kind());
    }

    @Test
    void withoutALastDeployTheServersValueIsUntraced() throws Exception {
        ServerEdits.Finding finding = judgeOne(user("<telephoneNumber>+49 30 1234</telephoneNumber>"),
                user("<telephoneNumber>+49 341 5678</telephoneNumber>"), null,
                user("<telephoneNumber>+49 30 1234</telephoneNumber>"));

        assertEquals(ServerEdits.Kind.UNTRACED, finding.kind());
        assertNull(finding.lastDeployed());
    }

    @Test
    void aValueAddedOnTheServerThatTheDeployWouldDeleteIsTheServers() throws Exception {
        ServerEdits.Finding finding = judgeOne(user("<organization>Leipzig</organization>"),
                user("<organization>Leipzig</organization><organization>Dresden</organization>"),
                user("<organization>Leipzig</organization>"));

        assertEquals(ServerEdits.Kind.SERVER_EDIT, finding.kind());
    }

    @Test
    void aValueTheTreeDroppedIsTheTreesToRemove() throws Exception {
        ServerEdits.Finding finding = judgeOne(user("<organization>Leipzig</organization>"),
                user("<organization>Leipzig</organization><organization>Dresden</organization>"),
                user("<organization>Leipzig</organization><organization>Dresden</organization>"));

        assertEquals(ServerEdits.Kind.YOURS, finding.kind());
    }

    // --- helpers ---

    /**
     * The one finding for deploying {@code tree} onto {@code server}, given what the last deploy wrote and the
     * versions the tree has been through since, newest first.
     */
    private static ServerEdits.Finding judgeOne(String tree, String server, String lastDeployed, String... window)
            throws Exception {
        PrismObject<? extends Objectable> live = prismContext.parseObject(server);
        DiffEngine.DiffResult result = new DiffEngine(prismContext).diff(prismContext.parseObject(tree), live,
                DiffEngine.EquivalencePlan.auto(), DiffEngine.Scope.FULL, DiffEngine.MultiValueStrategy.replaceAll(),
                DiffEngine.SmartReferenceCheck.NEVER);
        List<PrismObject<? extends Objectable>> versions = new java.util.ArrayList<>();
        for (String version : window) {
            versions.add(prismContext.parseObject(version));
        }
        List<ServerEdits.Finding> findings = ServerEdits.judge(result.delta(), live, new ServerEdits.Versions(
                (lastDeployed != null) ? prismContext.parseObject(lastDeployed) : null, versions::iterator));
        assertEquals(1, findings.size(), findings.toString());
        return findings.get(0);
    }

    private static String user(String body) {
        return "<user xmlns=\"" + NS + "\" oid=\"" + OID + "\"><name>jdoe</name>" + body + "</user>";
    }
}
