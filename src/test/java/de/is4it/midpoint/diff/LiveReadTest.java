/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;
import com.evolveum.midpoint.xml.ns._public.common.common_3.LookupTableType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LiveLookup} and {@link VersionSupport} against a stub server, so they are tested where the docker rig
 * is not up — CI among them.
 */
class LiveReadTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String API = "http://midpoint.evolveum.com/xml/ns/public/common/api-types-3";
    private static final String TABLE = "12121212-0000-0000-0000-000000000001";
    private static final String ROLE = "12121212-0000-0000-0000-000000000002";

    private static PrismContext prismContext;

    private HttpServer server;
    private MidpointRestClient client;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile String nodeVersion = "4.10.5";
    private volatile int nodeStatus = 200;

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/midpoint/ws/rest", this::handle);
        server.start();
        client = new MidpointRestClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/midpoint"), "u", "p", false);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void anObjectFoundIncompleteIsReadAgainWithWhatWasLeftOut() throws Exception {
        // A lookup table's rows come back as <row incomplete="true"/> unless asked for; diffed as written, the
        // server would read as holding no rows at all.
        List<PrismObject<? extends Objectable>> found = LiveLookup.byName(prismContext, client,
                LookupTableType.class, "lookupTables", "midgie-state");

        assertEquals(1, found.size());
        assertEquals(1, ((LookupTableType) found.get(0).asObjectable()).getRow().size(), "the rows are there");
        assertTrue(requests.contains("GET /midpoint/ws/rest/lookupTables/" + TABLE + "?include=row"),
                requests.toString());
    }

    @Test
    void namesByOidIsOneSearchAndNoReReads() throws Exception {
        Map<String, String> names = LiveLookup.namesByOid(prismContext, client, RoleType.class, "roles",
                List.of(ROLE));

        assertEquals(Map.of(ROLE, "Manager"), names);
        assertEquals(List.of("POST /midpoint/ws/rest/roles/search"), requests);
    }

    @Test
    void theServerVersionIsReadFromTheCurrentNode() {
        nodeVersion = "4.10.7";

        assertEquals("4.10.7", VersionSupport.liveVersion(prismContext, client));
    }

    @Test
    void aNewerPatchAndAnotherLineAreWarnedAboutAndAnUnreadableVersionIsSaid() {
        // The bundled version may be in any of midPoint's forms — 4.10.5, 4.11, 4.11-RC2, 4.11.0-SNAPSHOT — so
        // the server versions are made from its line and patch, not from its text.
        String bundled = VersionSupport.compileTimeVersion();
        String line = VersionSupport.majorMinor(bundled);
        Integer patch = VersionSupport.patchOf(bundled);
        assertNotNull(patch, "the bundled version must name its patch or none at all, got: " + bundled);
        nodeVersion = line + "." + (patch + 1);
        assertTrue(stderrOf(() -> VersionSupport.warnIfMismatch(prismContext, client))
                .contains("is a newer patch release"));

        String[] numbers = line.split("\\.");
        nodeVersion = numbers[0] + "." + (Integer.parseInt(numbers[1]) + 1) + ".0";
        assertTrue(stderrOf(() -> VersionSupport.warnIfMismatch(prismContext, client))
                .contains("differs from the bundled schema version"));

        nodeVersion = bundled;
        assertEquals("", stderrOf(() -> VersionSupport.warnIfMismatch(prismContext, client)));

        // A least-privilege account has no read on NodeType; the check not running is said, not hidden.
        nodeStatus = 403;
        assertNull(VersionSupport.liveVersion(prismContext, client));
        assertTrue(stderrOf(() -> VersionSupport.warnIfMismatch(prismContext, client))
                .contains("could not read the server version"));
    }

    // --- helpers ---

    private static String stderrOf(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setErr(original);
        }
        return err.toString(StandardCharsets.UTF_8);
    }

    private void handle(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        requests.add(exchange.getRequestMethod() + " " + path + (query != null ? "?" + query : ""));
        String body;
        int status = 200;
        if (path.endsWith("/nodes/current")) {
            status = nodeStatus;
            body = "<node xmlns=\"" + NS + "\" oid=\"00000000-0000-0000-0000-0000000000aa\"><name>n</name>"
                    + "<build><version>" + nodeVersion + "</version></build></node>";
        } else if (path.endsWith("/lookupTables/search")) {
            body = list("<object xsi:type=\"c:LookupTableType\" oid=\"" + TABLE + "\"><c:name>midgie-state</c:name>"
                    + "<c:row incomplete=\"true\"/></object>");
        } else if (path.endsWith("/lookupTables/" + TABLE)) {
            body = "<lookupTable xmlns=\"" + NS + "\" oid=\"" + TABLE + "\"><name>midgie-state</name>"
                    + "<row id=\"1\"><key>k</key><value>v</value></row></lookupTable>";
        } else if (path.endsWith("/roles/search")) {
            body = list("<object xsi:type=\"c:RoleType\" oid=\"" + ROLE + "\"><c:name>Manager</c:name></object>");
        } else {
            status = 404;
            body = "";
        }
        byte[] bytes = (status == 200 ? body : "denied").getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String list(String objects) {
        return "<objectList xmlns=\"" + API + "\" xmlns:c=\"" + NS + "\""
                + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" + objects + "</objectList>";
    }
}
