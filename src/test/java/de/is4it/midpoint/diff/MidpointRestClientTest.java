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
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private volatile String lastQuery;

    // stub behaviour for the retry/timeout tests
    private final AtomicInteger failuresRemaining = new AtomicInteger(0); // /users/flaky returns 503 this many times
    private final AtomicInteger flakyRequests = new AtomicInteger(0);     // total hits on /users/flaky
    private final AtomicInteger dropsRemaining = new AtomicInteger(0);    // /orgs drops the connection this often

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/midpoint/ws/rest/users", this::handle);
        server.createContext("/midpoint/ws/rest/roles", this::handle);
        server.createContext("/midpoint/ws/rest/orgs", exchange -> {
            // Closed without a response while drops remain: the client sees a connection that ended part way.
            if (dropsRemaining.getAndDecrement() > 0) {
                exchange.close();
                return;
            }
            byte[] body = "<org><name>back</name></org>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        host = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/midpoint");
        client = new MidpointRestClient(host, "administrator", "s3cret", false);
        failuresRemaining.set(0);
        flakyRequests.set(0);
    }

    private MidpointRestClient clientWith(MidpointRestClient.Config config) {
        return new MidpointRestClient(host, "administrator", "s3cret", config);
    }

    /** A UUID standing for a stub behaviour, since the client refuses anything but a UUID as an OID. */
    private static String oid(String tag) {
        return java.util.UUID.nameUUIDFromBytes(tag.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static final java.util.Map<String, String> TAG_BY_OID = new java.util.HashMap<>();
    static {
        for (String tag : new String[] {"aaa", "missing", "unauth", "forbidden", "redirect", "x", "flaky", "any", "u1", "partial", "partial-with-object", "slow"}) {
            TAG_BY_OID.put(oid(tag), tag);
        }
    }

    @Test
    void passwordValuesAreMaskedInWhatIsLogged() {
        String body = "<t:value><t:clearValue>hunter2</t:clearValue></t:value>"
                + "<encryptedData><cipherData><cipherValue>\nQUJD\n</cipherValue></cipherData></encryptedData>"
                + "<t:hashedData><t:digestValue>ZGln</t:digestValue></t:hashedData><description>kept</description>";

        String masked = MidpointRestClient.masked(body);

        assertFalse(masked.contains("hunter2") || masked.contains("QUJD") || masked.contains("ZGln"), masked);
        assertTrue(masked.contains("<t:clearValue>***</t:clearValue>") && masked.contains("<cipherValue>***</cipherValue>"),
                masked);
        assertTrue(masked.contains("<description>kept</description>"), "anything else is left as it is: " + masked);
    }

    @Test
    void anOidThatIsNotAUuidIsRefusedBeforeAnythingIsSent() {
        // Built by concatenation, an OID like this one addressed another collection and added a query option.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> client.deleteObject("users", "../roles/x?options=raw"));
        assertTrue(ex.getMessage().contains("not a midPoint OID"), ex.getMessage());
        assertNull(lastMethod, "no request may reach the server");
        assertThrows(IllegalArgumentException.class, () -> client.getObject("users", "has space"));
        assertThrows(IllegalArgumentException.class, () -> client.listObjects("users/../roles"));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void getReturnsBodyAndSendsBasicAuth() throws Exception {
        String body = client.getObject("users", oid("aaa"));

        assertEquals("<user><name>from-server</name></user>", body);
        assertEquals("GET", lastMethod);
        assertEquals("/midpoint/ws/rest/users/" + oid("aaa"), lastPath, "type + oid must append to /ws/rest");
        assertEquals("application/xml", lastAccept);
        // administrator:s3cret base64
        assertEquals("Basic YWRtaW5pc3RyYXRvcjpzM2NyZXQ=", lastAuth);
    }

    @Test
    void patchSendsBodyWithPatchMethod() throws Exception {
        client.patchObject("users", oid("aaa"), "<objectModification/>");

        assertEquals("PATCH", lastMethod);
        assertEquals("/midpoint/ws/rest/users/" + oid("aaa"), lastPath);
        assertEquals("application/xml", lastContentType);
        assertEquals("<objectModification/>", lastBody);
    }

    @Test
    void notFoundIsMappedToRestExceptionWithFlag() {
        MidpointRestClient.RestException ex = assertThrows(
                MidpointRestClient.RestException.class, () -> client.getObject("users", oid("missing")));
        assertEquals(404, ex.statusCode());
        assertTrue(ex.isNotFound());
    }

    @Test
    void unauthorizedAndForbiddenCarryActionableHints() {
        MidpointRestClient.RestException unauthorized = assertThrows(
                MidpointRestClient.RestException.class, () -> client.getObject("users", oid("unauth")));
        assertEquals(401, unauthorized.statusCode());
        assertTrue(unauthorized.getMessage().contains("authentication failed"), unauthorized.getMessage());

        MidpointRestClient.RestException forbidden = assertThrows(
                MidpointRestClient.RestException.class, () -> client.getObject("users", oid("forbidden")));
        assertEquals(403, forbidden.statusCode());
        assertTrue(forbidden.getMessage().contains("not authorized"), forbidden.getMessage());
    }

    @Test
    void redirectIsReportedWithLocationAndAHint() {
        MidpointRestClient.RestException ex = assertThrows(
                MidpointRestClient.RestException.class, () -> client.getObject("users", oid("redirect")));
        assertEquals(302, ex.statusCode());
        assertTrue(ex.getMessage().contains("redirected"), ex.getMessage());
        assertTrue(ex.getMessage().contains("/midpoint/"), ex.getMessage());   // the Location header
        assertTrue(ex.getMessage().contains("context path"), ex.getMessage()); // the actionable hint
    }

    @Test
    void connectionRefusedGivesAClearMessage() {
        MidpointRestClient dead = new MidpointRestClient(
                java.net.URI.create("http://127.0.0.1:1/midpoint"), "u", "p", false);
        java.io.IOException ex = assertThrows(java.io.IOException.class, () -> dead.getObject("users", oid("x")));
        assertTrue(ex.getMessage().contains("cannot connect"), ex.getMessage());
    }

    @Test
    void anUnresolvableHostIsReportedAsDnsRatherThanAsAServerProblem() {
        // The JDK reports a DNS failure as a ConnectException whose own message is null, so the generic
        // wording came out as "cannot connect to … (is the server up and the URL correct? null)" and sent
        // the operator to check a server that was never contacted. '.invalid' is reserved by RFC 2606 and
        // never resolves.
        MidpointRestClient unresolvable = new MidpointRestClient(
                java.net.URI.create("https://midgie-no-such-host.invalid/midpoint"), "u", "p", false);

        java.io.IOException ex =
                assertThrows(java.io.IOException.class, () -> unresolvable.getObject("users", oid("x")));

        assertTrue(ex.getMessage().contains("cannot resolve host"), ex.getMessage());
        assertTrue(ex.getMessage().contains("midgie-no-such-host.invalid"), ex.getMessage());
        assertFalse(ex.getMessage().contains("null"), "no null where a diagnosis belongs: " + ex.getMessage());
    }

    @Test
    void createReturnsOidFromLocationHeader() throws Exception {
        String oid = client.createObject("users", "<user/>");

        assertEquals("POST", lastMethod);
        assertEquals("/midpoint/ws/rest/users", lastPath);
        assertEquals("new-oid-123", oid, "OID must be taken from the Location header tail");
    }

    @Test
    void searchPostsQueryToSearchEndpoint() throws Exception {
        String body = client.searchObjects("users", "<query><filter/></query>");

        assertEquals("POST", lastMethod);
        assertEquals("/midpoint/ws/rest/users/search", lastPath, "search is POST {type}/search");
        assertEquals("application/xml", lastContentType);
        assertEquals("<query><filter/></query>", lastBody);
        assertTrue(body.contains("found-oid"), body);
    }

    @Test
    void deleteSendsDeleteMethod() throws Exception {
        client.deleteObject("users", oid("aaa"));

        assertEquals("DELETE", lastMethod);
        assertEquals("/midpoint/ws/rest/users/" + oid("aaa"), lastPath);
    }

    @Test
    void retriesIdempotentGetOnTransientFailure() throws Exception {
        failuresRemaining.set(2); // first two GETs -> 503, third -> 200
        MidpointRestClient retrying = clientWith(
                new MidpointRestClient.Config().maxRetries(3).retryBaseDelay(Duration.ofMillis(2)));

        String body = retrying.getObject("users", oid("flaky"));

        assertTrue(body.contains("recovered"), body);
        assertEquals(3, flakyRequests.get(), "one initial GET + two retries");
    }

    @Test
    void aReadWhoseConnectionDropsIsRetriedAndOtherwiseNamed() throws Exception {
        // Only a refused connection or a timeout was retried; a connection that ended part way failed the read at
        // once, as a bare IOException that did not say which request it ended.
        dropsRemaining.set(2);
        MidpointRestClient retrying = clientWith(
                new MidpointRestClient.Config().maxRetries(5).retryBaseDelay(Duration.ofMillis(2)));
        assertTrue(retrying.getObject("orgs", oid("o")).contains("back"));

        dropsRemaining.set(100);
        MidpointRestClient.TransportException e = assertThrows(MidpointRestClient.TransportException.class,
                () -> client.getObject("orgs", oid("o")));
        assertTrue(e.getMessage().startsWith("GET orgs/") && e.getMessage().contains("connection"), e.getMessage());
    }

    @Test
    void onlyTheJdksOwnProxyAuthenticationFailuresReadAsOne() {
        IOException upToJdk25 = new IOException("too many authentication attempts. Limit: 3");
        IOException jdk26 = new IOException("x", new NullPointerException(
                "Cannot invoke \"ExchangeImpl.cancel()\" because \"this.exchImpl\" is null"));
        IOException unrelated = new IOException("exchImpl mentioned in passing");

        assertTrue(MidpointRestClient.isProxyAuthFailure(upToJdk25, false));
        assertTrue(MidpointRestClient.isProxyAuthFailure(jdk26, true));
        assertFalse(MidpointRestClient.isProxyAuthFailure(jdk26, false), "no tunnel, no CONNECT to authenticate");
        assertFalse(MidpointRestClient.isProxyAuthFailure(unrelated, true));
    }

    @Test
    void failsWhenRetriesAreExhausted() {
        failuresRemaining.set(5);
        MidpointRestClient retrying = clientWith(
                new MidpointRestClient.Config().maxRetries(2).retryBaseDelay(Duration.ofMillis(2)));

        MidpointRestClient.RestException ex = assertThrows(
                MidpointRestClient.RestException.class, () -> retrying.getObject("users", oid("flaky")));
        assertEquals(503, ex.statusCode());
        assertEquals(3, flakyRequests.get(), "one initial GET + two retries, then gives up");
    }

    @Test
    void theObjectSearchIsRetriedBecauseItIsTheReadEverythingUses() {
        // Every object lookup is a POST {type}/search, so keying retries on "is it a GET" left the tool's
        // primary read uncovered: a midPoint that is still starting failed the very first search however
        // high --retries was set.
        failuresRemaining.set(2);
        MidpointRestClient retrying = clientWith(
                new MidpointRestClient.Config().maxRetries(3).retryBaseDelay(Duration.ofMillis(2)));

        String body = assertDoesNotThrow(() -> retrying.searchObjects("roles", "<query/>"));
        assertTrue(body.contains("found-oid"), body);
        assertEquals(3, flakyRequests.get(), "two 503s ridden out, then the answer");
    }

    @Test
    void credentialsEmbeddedInTheUrlAreStrippedFromEverythingPrinted() {
        // java.net.http accepts a userinfo URI and never authenticates with it, so it is pure leak: the
        // connection error below is printed to stderr regardless of --quiet, straight into a CI log.
        URI withCredentials = URI.create("http://mpuser:s3cr3t-pw@127.0.0.1:1/midpoint");
        MidpointRestClient client = new MidpointRestClient(withCredentials, "administrator", "s3cret",
                new MidpointRestClient.Config().maxRetries(0));

        IOException ex = assertThrows(IOException.class, () -> client.getObject("users", oid("any")));
        assertFalse(ex.getMessage().contains("s3cr3t-pw"), "the password must not reach the message: "
                + ex.getMessage());
        assertFalse(ex.getMessage().contains("mpuser"), ex.getMessage());
        assertTrue(ex.getMessage().contains("127.0.0.1:1"), "but the host must still be named: "
                + ex.getMessage());
    }

    @Test
    void aPatchSettingAReferenceByFilterAsksMidpointToResolveIt() throws Exception {
        // A modify does not resolve a search filter on its own, so without this option midPoint stores the
        // reference unresolved and the object is left pointing at nothing.
        client.patchObject("users", oid("u1"), "<objectModification/>", true);
        assertEquals("/midpoint/ws/rest/users/" + oid("u1"), lastPath);
        assertEquals("options=reevaluateSearchFilters", lastQuery);

        client.patchObject("users", oid("u1"), "<objectModification/>", false);
        assertNull(lastQuery, "an ordinary patch keeps the plain path: " + lastQuery);
    }

    @Test
    void aPartialErrorOnAWriteIsNotReportedAsSuccess() {
        // midPoint answers 250 (partial error) and 240 (handled error) for an operation that did not fully
        // succeed. Both are 2xx, so "is it 2xx" reads them as plain success and a PATCH that half-worked was
        // reported as applied.
        MidpointRestClient.RestException ex = assertThrows(MidpointRestClient.RestException.class,
                () -> client.patchObject("users", oid("partial"), "<objectModification/>"));

        assertEquals(250, ex.statusCode());
        assertTrue(ex.getMessage().contains("partial error"), ex.getMessage());
        assertTrue(ex.getMessage().contains("something did not finish"),
                "midPoint's own message must survive: " + ex.getMessage());
    }

    @Test
    void aPartialErrorOnAReadKeepsTheObjectButSaysSo() throws Exception {
        // A resource whose schema midPoint cannot complete answers 250 to every read while remaining
        // deployable — verified on a live server, where its GET returns 250 and its PATCH returns 204. So a
        // read that still carries its payload must not be refused, or such a resource could never be
        // reconciled at all.
        PrintStream originalErr = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String body;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            body = client.getObject("users", oid("partial-with-object"));
        } finally {
            System.setErr(originalErr);
        }

        assertTrue(body.contains("still-usable"), "the payload is returned: " + body);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("partial error"),
                "and the operator is told: " + err);
    }

    @Test
    void aPartialErrorWhoseBodyIsOnlyTheResultFails() {
        // Some endpoints answer 240/250 with the OperationResult instead of the payload. There is then
        // nothing to parse, so a read fails too rather than handing the caller a result it will misread.
        MidpointRestClient.RestException ex = assertThrows(MidpointRestClient.RestException.class,
                () -> client.getObject("users", oid("partial")));
        assertEquals(250, ex.statusCode());
    }

    @Test
    void writesAreNotRetried() {
        failuresRemaining.set(5);
        MidpointRestClient retrying = clientWith(
                new MidpointRestClient.Config().maxRetries(3).retryBaseDelay(Duration.ofMillis(2)));

        assertThrows(MidpointRestClient.RestException.class,
                () -> retrying.patchObject("users", oid("flaky"), "<objectModification/>"));
        assertEquals(1, flakyRequests.get(), "a PATCH must not be retried");
    }

    @Test
    void requestTimeoutIsHonoured() {
        MidpointRestClient impatient = clientWith(
                new MidpointRestClient.Config().requestTimeout(Duration.ofMillis(300)));

        IOException ex = assertThrows(IOException.class, () -> impatient.getObject("users", oid("slow")));
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
        // The client only sends UUID OIDs; the stub's behaviours are named, so map one back to the other.
        String last = lastPath.substring(lastPath.lastIndexOf('/') + 1);
        String logical = lastPath.substring(0, lastPath.lastIndexOf('/') + 1) + TAG_BY_OID.getOrDefault(last, last);
        lastQuery = exchange.getRequestURI().getQuery();
        lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
        lastAccept = exchange.getRequestHeaders().getFirst("Accept");
        lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
        lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        if (logical.endsWith("/users/partial")) {
            // The shape midPoint uses when it sends the OperationResult rather than the payload.
            respond(exchange, 250, "<t:object xmlns:t=\"http://prism.evolveum.com/xml/ns/public/types-3\""
                    + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
                    + " xsi:type=\"c:OperationResultType\""
                    + " xmlns:c=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\">"
                    + "<status>partial_error</status><message>something did not finish</message></t:object>");
        } else if (logical.endsWith("/users/partial-with-object")) {
            // And the shape it uses when the payload still comes back (search does this).
            respond(exchange, 250, "<user xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\">"
                    + "<name>still-usable</name></user>");
        } else if (logical.endsWith("/users/flaky")) {
            flakyRequests.incrementAndGet();
            if (failuresRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                respond(exchange, 503, "service unavailable");
            } else {
                respond(exchange, 200, "<user><name>recovered</name></user>");
            }
        } else if (logical.endsWith("/users/slow")) {
            sleep(2000);
            respond(exchange, 200, "<user/>");
        } else if (logical.endsWith("/users/missing")) {
            respond(exchange, 404, "not found");
        } else if (logical.endsWith("/users/unauth")) {
            respond(exchange, 401, "unauthorized");
        } else if (logical.endsWith("/users/forbidden")) {
            respond(exchange, 403, "forbidden");
        } else if (logical.endsWith("/users/redirect")) {
            exchange.getResponseHeaders().add("Location", "/midpoint/");
            respond(exchange, 302, "");
        } else if ("POST".equals(lastMethod) && logical.endsWith("/roles/search")) {
            flakyRequests.incrementAndGet();
            if (failuresRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                respond(exchange, 503, "service unavailable");
            } else {
                respond(exchange, 200, "<objectList><object oid=\"found-oid\"/></objectList>");
            }
        } else if ("POST".equals(lastMethod) && logical.endsWith("/users/search")) {
            respond(exchange, 200, "<objectList><object oid=\"found-oid\"/></objectList>");
        } else if ("POST".equals(lastMethod) && logical.endsWith("/users")) {
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
