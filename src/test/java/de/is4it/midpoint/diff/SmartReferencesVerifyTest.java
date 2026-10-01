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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismReferenceValue;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectTemplateType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --smart-refs verify}: the desired filter is searched on the server and must select exactly the object
 * the live reference holds.
 */
class SmartReferencesVerifyTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String X = "66666666-0000-0000-0000-00000000000a";
    private static final String Y = "66666666-0000-0000-0000-00000000000b";

    private static PrismContext prismContext;

    private HttpServer server;
    private MidpointRestClient client;
    private final AtomicInteger searches = new AtomicInteger();
    private volatile int status = 200;
    private volatile List<String> found = List.of(X);

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/midpoint/ws/rest/objectTemplates/search", this::search);
        server.start();
        client = new MidpointRestClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/midpoint"), "u", "p", false);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void theOidTheFilterSelectsIsInSyncAndAnotherIsAChange() throws Exception {
        DiffEngine.SmartReferenceCheck check = verify();

        assertTrue(check.isResolutionOf(live(X), desired("A")));
        assertFalse(check.isResolutionOf(live(Y), desired("A")));
    }

    @Test
    void aFilterIsSearchedOnceHoweverManyReferencesCarryIt() throws Exception {
        // Keyed on toString(), the identity of the filter object, equal filters on two references were two
        // searches — and a live value per filter one more each.
        DiffEngine.SmartReferenceCheck check = verify();

        check.isResolutionOf(live(X), desired("A"));
        check.isResolutionOf(live(Y), desired("A"));
        check.isResolutionOf(live(X), desired("A"));

        assertEquals(1, searches.get());
    }

    @Test
    void aFilterSelectingSeveralObjectsIsAChangeAndSaidOnce() throws Exception {
        found = List.of(X, Y);
        DiffEngine.SmartReferenceCheck check = verify();
        PrismReferenceValue x = live(X);
        PrismReferenceValue y = live(Y);
        PrismReferenceValue a = desired("A");

        String err = stderrOf(() -> {
            assertFalse(check.isResolutionOf(x, a));
            assertFalse(check.isResolutionOf(y, a));
        });

        assertEquals(1, err.split("matches 2 object", -1).length - 1, err);
    }

    @Test
    void aSearchTheServerRefusesIsAChangeAndSaysWhy() throws Exception {
        // It was logged at TRACE only: an account without search rights turned every smart reference into a
        // change without a word.
        status = 403;
        DiffEngine.SmartReferenceCheck check = verify();
        PrismReferenceValue x = live(X);
        PrismReferenceValue a = desired("A");
        PrismReferenceValue b = desired("B");

        String err = stderrOf(() -> {
            assertFalse(check.isResolutionOf(x, a));
            assertFalse(check.isResolutionOf(x, b));
        });

        assertTrue(err.contains("--smart-refs verify") && err.contains("403"), err);
        assertEquals(1, err.split("warning:", -1).length - 1, "one warning for one reason: " + err);
    }

    // --- helpers ---

    private DiffEngine.SmartReferenceCheck verify() {
        StrategyOptions.Resolved strategy = new StrategyOptions.Resolved(DiffEngine.Scope.PRESENT,
                DiffEngine.EquivalencePlan.auto(), DiffEngine.MultiValueStrategy.replaceAll(), false,
                StrategyOptions.SmartRefs.VERIFY);
        return SmartReferences.checkFor(strategy, prismContext, client);
    }

    private static PrismReferenceValue live(String oid) throws Exception {
        return includeRef("<includeRef oid=\"" + oid + "\" type=\"ObjectTemplateType\"/>");
    }

    private static PrismReferenceValue desired(String name) throws Exception {
        return includeRef("<includeRef type=\"ObjectTemplateType\"><filter>"
                + "<q:equal xmlns:q=\"http://prism.evolveum.com/xml/ns/public/query-3\"><q:path>name</q:path>"
                + "<q:value>" + name + "</q:value></q:equal></filter></includeRef>");
    }

    private static PrismReferenceValue includeRef(String ref) throws Exception {
        PrismObject<ObjectTemplateType> template = prismContext.parseObject(
                "<objectTemplate xmlns=\"" + NS + "\"><name>T</name>" + ref + "</objectTemplate>");
        return template.findReference(ItemPath.create(ObjectTemplateType.F_INCLUDE_REF)).getValue();
    }

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

    private void search(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        searches.incrementAndGet();
        StringBuilder body = new StringBuilder("<objectList xmlns=\"http://midpoint.evolveum.com/xml/ns/public/"
                + "common/api-types-3\" xmlns:c=\"" + NS + "\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">");
        for (String oid : found) {
            body.append("<object xsi:type=\"c:ObjectTemplateType\" oid=\"").append(oid)
                    .append("\"><c:name>t</c:name></object>");
        }
        body.append("</objectList>");
        byte[] bytes = (status == 200 ? body.toString() : "forbidden").getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
