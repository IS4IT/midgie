/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;
import com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectModificationType;
import com.evolveum.prism.xml.ns._public.types_3.ItemDeltaType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PATCH {@link SyncState#apply} builds from a set of row changes: a row added whole, updated and removed by
 * its container id, duplicate keys, and rows that moved on the server after the run read the table.
 */
class SyncStateTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String OID = "00000000-0000-0000-0000-00000000f00d";

    private static PrismContext prismContext;

    private HttpServer server;
    private MidpointRestClient client;
    private volatile String rows = "";
    private final AtomicInteger failNextPatches = new AtomicInteger();
    private final List<String> patches = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void initPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/midpoint/ws/rest/lookupTables", this::handle);
        server.start();
        client = new MidpointRestClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/midpoint"), "u", "p", false);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void aNewKeyIsAddedWholeAndAKnownOneUpdatedByItsId() throws Exception {
        rows = row(4, "repo/roles/a.xml", "old", "a") + row(9, "repo/roles/b.xml", "same", "b");
        SyncState.Changes changes = new SyncState.Changes();
        changes.set("repo/roles/a.xml", "new", "a");
        changes.set("repo/roles/b.xml", "same", "b");
        changes.set("repo/roles/c.xml", "fresh", "c");

        SyncState.apply(prismContext, client, table(), changes);

        assertEquals(List.of("replace row/4/value", "add row"), deltas(only(patches)));
    }

    @Test
    void aChangedLabelIsReplacedAndADeletedKeyRemovedById() throws Exception {
        rows = row(4, "k1", "v", "old label") + row(5, "k2", "v", null);
        SyncState.Changes changes = new SyncState.Changes();
        changes.set("k1", "v", "new label");
        changes.delete("k2");
        changes.delete("never-there");

        SyncState.apply(prismContext, client, table(), changes);

        String body = only(patches);
        assertEquals(List.of("replace row/4/label", "delete row"), deltas(body));
        assertTrue(body.matches("(?s).*id=\"5\".*"), body);
    }

    @Test
    void nothingIsSentWhenTheTableAlreadySaysIt() throws Exception {
        rows = row(4, "k", "v", "l");
        SyncState.Changes changes = new SyncState.Changes();
        changes.set("k", "v", "l");

        SyncState.apply(prismContext, client, table(), changes);

        assertTrue(patches.isEmpty(), patches.toString());
    }

    @Test
    void aKeyHeldTwiceIsWrittenToTheFirstRowAndTheOtherRemoved() throws Exception {
        // Two first deploys of one file at the same time each added its row. FileTracking reads the first, so
        // that is the one written; the second goes.
        rows = row(4, "k", "first", null) + row(8, "k", "second", null);
        SyncState.Changes changes = new SyncState.Changes();
        changes.set("k", "third", null);

        SyncState.apply(prismContext, client, table(), changes);

        String body = only(patches);
        assertEquals(List.of("replace row/4/value", "delete row"), deltas(body));
        assertTrue(body.matches("(?s).*id=\"8\".*"), body);
    }

    @Test
    void rowsThatMovedSinceTheRunReadTheTableAreAddressedAsTheyAreNow() throws Exception {
        // The run read the table with the key at id 4; another deploy has since removed it and added it again as
        // id 12. Addressed as 4, the whole PATCH failed and no row of this run was recorded.
        rows = row(4, "k", "old", null);
        PrismObject<? extends Objectable> readAtStart = table();
        rows = row(12, "k", "old", null);
        SyncState.Changes changes = new SyncState.Changes();
        changes.set("k", "new", null);

        SyncState.apply(prismContext, client, readAtStart, changes);

        assertEquals(List.of("replace row/12/value"), deltas(only(patches)));
    }

    @Test
    void aWriteThatFailsIsTriedOnceMoreOnAFreshRead() throws Exception {
        rows = row(4, "k", "old", null);
        failNextPatches.set(1);
        SyncState.Changes changes = new SyncState.Changes();
        changes.set("k", "new", null);

        SyncState.apply(prismContext, client, table(), changes);

        assertEquals(1, patches.size(), "the second attempt landed");
    }

    // --- helpers ---

    private static String row(long id, String key, String value, String label) {
        return "<row id=\"" + id + "\"><key>" + key + "</key><value>" + value + "</value>"
                + (label != null ? "<label>" + label + "</label>" : "") + "</row>";
    }

    private String tableXml() {
        return "<lookupTable xmlns=\"" + NS + "\" oid=\"" + OID + "\" version=\"1\"><name>"
                + SyncState.TABLE_NAME + "</name>" + rows + "</lookupTable>";
    }

    private PrismObject<? extends Objectable> table() throws Exception {
        return prismContext.parseObject(tableXml());
    }

    private static String only(List<String> bodies) {
        assertEquals(1, bodies.size(), bodies.toString());
        return bodies.get(0);
    }

    /** Each item delta of a PATCH body as "{@code <modification> <path>}", ids as path segments. */
    private static List<String> deltas(String body) throws Exception {
        ObjectModificationType modification = prismContext.parserFor(body).xml()
                .parseRealValue(ObjectModificationType.class);
        List<String> described = new ArrayList<>();
        for (ItemDeltaType delta : modification.getItemDelta()) {
            List<String> segments = new ArrayList<>();
            for (Object segment : delta.getPath().getItemPath().getSegments()) {
                segments.add(ItemPath.isName(segment) ? ItemPath.toName(segment).getLocalPart()
                        : String.valueOf(ItemPath.toId(segment)));
            }
            described.add(delta.getModificationType().value() + " " + String.join("/", segments));
        }
        return described;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if ("GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 200, tableXml());
        } else if ("PATCH".equals(exchange.getRequestMethod())) {
            if (failNextPatches.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                respond(exchange, 500, "conflict");
                return;
            }
            patches.add(body);
            respond(exchange, 204, "");
        } else {
            respond(exchange, 405, "");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
