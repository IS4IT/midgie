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
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the REST client against a stub HTTP server (JDK {@code com.sun.net.httpserver}, no
 * extra dependency). Covers the fiddly bits that tend to regress: URL construction, basic auth, the
 * PATCH method + body, 404 mapping, and the POST-create OID from Location.
 */
class MidpointRestClientTest {

    private HttpServer server;
    private MidpointRestClient client;
    private URI host;

    // captured from the last request
    private volatile String lastMethod;
    private volatile String lastPath;
    private volatile String lastAuth;
    private volatile String lastAccept;
    private volatile String lastContentType;
    private volatile String lastBody;

    // stub behaviour for the retry/timeout tests
    private final AtomicInteger failuresRemaining = new AtomicInteger(0); // /users/flaky returns 503 this many times
    private final AtomicInteger flakyRequests = new AtomicInteger(0);     // total hits on /users/flaky

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/midpoint/ws/rest/users", this::handle);
        server.start();
        host = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/midpoint");
        client = new MidpointRestClient(host, "administrator", "s3cret", false);
        failuresRemaining.set(0);
        flakyRequests.set(0);
    }

    private MidpointRestClient clientWith(MidpointRestClient.Config config) {
        return new MidpointRestClient(host, "administrator", "s3cret", config);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void getReturnsBodyAndSendsBasicAuth() throws Exception {
        String body = client.getObject("users", "aaa");

        assertEquals("<user><name>from-server</name></user>", body);
        assertEquals("GET", lastMethod);
        assertEquals("/midpoint/ws/rest/users/aaa", lastPath, "type + oid must append to /ws/rest");
        assertEquals("application/xml", lastAccept);
        // administrator:s3cret base64
        assertEquals("Basic YWRtaW5pc3RyYXRvcjpzM2NyZXQ=", lastAuth);
    }

    @Test
    void patchSendsBodyWithPatchMethod() throws Exception {
        client.patchObject("users", "aaa", "<objectModification/>");

        assertEquals("PATCH", lastMethod);
        assertEquals("/midpoint/ws/rest/users/aaa", lastPath);
        assertEquals("application/xml", lastContentType);
        assertEquals("<objectModification/>", lastBody);
    }

    @Test
    void notFoundIsMappedToRestExceptionWithFlag() {
        MidpointRestClient.RestException ex = assertThrows(
                MidpointRestClient.RestException.class, () -> client.getObject("users", "missing"));
        assertEquals(404, ex.statusCode());
        assertTrue(ex.isNotFound());
    }

    @Test
    void unauthorizedAndForbiddenCarryActionableHints() {
        MidpointRestClient.RestException unauthorized = assertThrows(
                MidpointRestClient.RestException.class, () -> client.getObject("users", "unauth"));
        assertEquals(401, unauthorized.statusCode());
        assertTrue(unauthorized.getMessage().contains("authentication failed"), unauthorized.getMessage());

        MidpointRestClient.RestException forbidden = assertThrows(
                MidpointRestClient.RestException.class, () -> client.getObject("users", "forbidden"));
        assertEquals(403, forbidden.statusCode());
        assertTrue(forbidden.getMessage().contains("not authorized"), forbidden.getMessage());
    }

    @Test
    void connectionRefusedGivesAClearMessage() {
        MidpointRestClient dead = new MidpointRestClient(
                java.net.URI.create("http://127.0.0.1:1/midpoint"), "u", "p", false);
        java.io.IOException ex = assertThrows(java.io.IOException.class, () -> dead.getObject("users", "x"));
        assertTrue(ex.getMessage().contains("cannot connect"), ex.getMessage());
    }

    @Test
    void createReturnsOidFromLocationHeader() throws Exception {
        String oid = client.createObject("users", "<user/>");

        assertEquals("POST", lastMethod);
        assertEquals("/midpoint/ws/rest/users", lastPath);
        assertEquals("new-oid-123", oid, "OID must be taken from the Location header tail");
    }

    @Test
    void deleteSendsDeleteMethod() throws Exception {
        client.deleteObject("users", "aaa");

        assertEquals("DELETE", lastMethod);
        assertEquals("/midpoint/ws/rest/users/aaa", lastPath);
    }

    @Test
    void retriesIdempotentGetOnTransientFailure() throws Exception {
        failuresRemaining.set(2); // first two GETs -> 503, third -> 200
        MidpointRestClient retrying = clientWith(
                new MidpointRestClient.Config().maxRetries(3).retryBaseDelay(Duration.ofMillis(2)));

        String body = retrying.getObject("users", "flaky");

        assertTrue(body.contains("recovered"), body);
        assertEquals(3, flakyRequests.get(), "one initial GET + two retries");
    }

    @Test
    void failsWhenRetriesAreExhausted() {
        failuresRemaining.set(5);
        MidpointRestClient retrying = clientWith(
                new MidpointRestClient.Config().maxRetries(2).retryBaseDelay(Duration.ofMillis(2)));

        MidpointRestClient.RestException ex = assertThrows(
                MidpointRestClient.RestException.class, () -> retrying.getObject("users", "flaky"));
        assertEquals(503, ex.statusCode());
        assertEquals(3, flakyRequests.get(), "one initial GET + two retries, then gives up");
    }

    @Test
    void writesAreNotRetried() {
        failuresRemaining.set(5);
        MidpointRestClient retrying = clientWith(
                new MidpointRestClient.Config().maxRetries(3).retryBaseDelay(Duration.ofMillis(2)));

        assertThrows(MidpointRestClient.RestException.class,
                () -> retrying.patchObject("users", "flaky", "<objectModification/>"));
        assertEquals(1, flakyRequests.get(), "a PATCH must not be retried");
    }

    @Test
    void requestTimeoutIsHonoured() {
        MidpointRestClient impatient = clientWith(
                new MidpointRestClient.Config().requestTimeout(Duration.ofMillis(300)));

        IOException ex = assertThrows(IOException.class, () -> impatient.getObject("users", "slow"));
        assertTrue(ex.getMessage().contains("timed out"), ex.getMessage());
    }

    @Test
    void cacertLoadsAValidCaCertificate() {
        assertDoesNotThrow(() -> clientWith(new MidpointRestClient.Config()
                .caCert(new java.io.File("src/test/resources/tls/test-ca.crt"))));
    }

    @Test
    void cacertRejectsAnUnreadableOrNonCertFile() {
        assertThrows(IllegalStateException.class, () -> clientWith(new MidpointRestClient.Config()
                .caCert(new java.io.File("/no/such/cacert.pem"))));
    }

    // --- stub server ---

    private void handle(HttpExchange exchange) throws IOException {
        lastMethod = exchange.getRequestMethod();
        lastPath = exchange.getRequestURI().getPath();
        lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
        lastAccept = exchange.getRequestHeaders().getFirst("Accept");
        lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
        lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        if (lastPath.endsWith("/users/flaky")) {
            flakyRequests.incrementAndGet();
            if (failuresRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                respond(exchange, 503, "service unavailable");
            } else {
                respond(exchange, 200, "<user><name>recovered</name></user>");
            }
        } else if (lastPath.endsWith("/users/slow")) {
            sleep(2000);
            respond(exchange, 200, "<user/>");
        } else if (lastPath.endsWith("/users/missing")) {
            respond(exchange, 404, "not found");
        } else if (lastPath.endsWith("/users/unauth")) {
            respond(exchange, 401, "unauthorized");
        } else if (lastPath.endsWith("/users/forbidden")) {
            respond(exchange, 403, "forbidden");
        } else if ("POST".equals(lastMethod) && lastPath.endsWith("/users")) {
            exchange.getResponseHeaders().add("Location",
                    "http://127.0.0.1/midpoint/ws/rest/users/new-oid-123");
            respond(exchange, 201, "");
        } else if ("PATCH".equals(lastMethod) || "DELETE".equals(lastMethod)) {
            respond(exchange, 204, "");
        } else {
            respond(exchange, 200, "<user><name>from-server</name></user>");
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
