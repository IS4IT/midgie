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

import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;
import com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectListType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline tests for the reconcile decision table, driving {@link Reconciler} through the real REST client
 * against a stub {@code com.sun.net.httpserver}. Existence is checked with a {@code POST {type}/search}
 * (an absent object is an empty result, not a 404), so the stub answers search with a controllable
 * {@code ObjectListType}. The name-search path ({@code --match-by-name}) is exercised end-to-end against
 * the live rig instead (see {@code LiveIntegrationTest}).
 */
class ReconcilerTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String NS_API = "http://midpoint.evolveum.com/xml/ns/public/common/api_types_3";
    private static final String OID = "11111111-0000-0000-0f00-000000000001";

    private static PrismContext prismContext;

    private HttpServer server;
    private URI host;
    private volatile String searchResponse; // the ObjectListType the "server" returns for a search

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/midpoint/ws/rest/roles", this::handle);
        server.start();
        host = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/midpoint");
        searchResponse = objectList(); // absent by default
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void presentButDifferentPlansAPatch() throws Exception {
        searchResponse = objectList(role(OID, "Manager", "old"));
        Reconciler.Outcome outcome = plan(false, false, role(OID, "Manager", "new"));
        assertEquals(Reconciler.Action.PATCH, outcome.action, outcome.detail);
        assertTrue(outcome.changes > 0);
    }

    @Test
    void presentAndEqualIsInSync() throws Exception {
        searchResponse = objectList(role(OID, "Manager", "same"));
        Reconciler.Outcome outcome = plan(false, false, role(OID, "Manager", "same"));
        assertEquals(Reconciler.Action.IN_SYNC, outcome.action, outcome.detail);
    }

    @Test
    void absentWithCreateIfAbsentPlansACreate() throws Exception {
        Reconciler.Outcome outcome = plan(true, false, role(OID, "Manager", "x"));
        assertEquals(Reconciler.Action.CREATE, outcome.action);
    }

    @Test
    void absentWithoutCreateIfAbsentFails() throws Exception {
        Reconciler.Outcome outcome = plan(false, false, role(OID, "Manager", "x"));
        assertEquals(Reconciler.Action.FAIL, outcome.action);
        assertTrue(outcome.detail.contains("create-if-absent"), outcome.detail);
    }

    @Test
    void oidlessDefaultsToSkip() throws Exception {
        Reconciler.Outcome outcome = plan(false, false, role(null, "Manager", "x"));
        assertEquals(Reconciler.Action.SKIP, outcome.action);
        assertTrue(outcome.detail.contains("no oid"), outcome.detail);
    }

    @Test
    void oidlessWithCreateIfAbsentPlansACreate() throws Exception {
        Reconciler.Outcome outcome = plan(true, false, role(null, "Manager", "x"));
        assertEquals(Reconciler.Action.CREATE, outcome.action);
    }

    // --- helpers ---

    private Reconciler.Outcome plan(boolean createIfAbsent, boolean matchByName, String xml) throws Exception {
        MidpointRestClient client = new MidpointRestClient(host, "administrator", "s3cret", false);
        Reconciler reconciler = new Reconciler(prismContext, client, createIfAbsent, matchByName);
        return reconciler.plan(desired(xml), strategy());
    }

    private static StrategyOptions.Resolved strategy() {
        StrategyOptions options = new StrategyOptions();
        options.scope = "present";
        options.multivalue = "replace";
        options.equivalence = "real-value";
        return options.resolve();
    }

    private static PrismObject<? extends Objectable> desired(String xml) throws Exception {
        return prismContext.parseObject(xml);
    }

    private static String role(String oid, String name, String description) {
        return "<role xmlns=\"" + NS + "\"" + (oid != null ? " oid=\"" + oid + "\"" : "") + ">"
                + "<name>" + name + "</name>"
                + (description != null ? "<description>" + description + "</description>" : "")
                + "</role>";
    }

    /** Build the {@code ObjectListType} XML a search would return for the given roles. */
    private static String objectList(String... roleXmls) throws Exception {
        ObjectListType list = new ObjectListType();
        for (String roleXml : roleXmls) {
            list.getObject().add((ObjectType) prismContext.parseObject(roleXml).asObjectable());
        }
        return prismContext.xmlSerializer().serializeRealValue(list, new QName(NS_API, "objectList"));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if ("POST".equals(method) && path.endsWith("/search")) {
            respond(exchange, 200, searchResponse);
        } else if ("POST".equals(method) && path.endsWith("/roles")) {
            exchange.getResponseHeaders().add("Location",
                    "http://127.0.0.1/midpoint/ws/rest/roles/created-oid");
            respond(exchange, 201, "");
        } else {
            respond(exchange, 204, ""); // PATCH
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (status == 204 || bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
