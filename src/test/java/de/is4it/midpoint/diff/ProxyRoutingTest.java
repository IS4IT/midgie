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

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The REST client through a real (if small) forward proxy: that requests actually go via the proxy, that a
 * bypassed host does not, and that Basic credentials reach a proxy which demands them.
 *
 * <p>These run over plain HTTP, where the proxy sees an absolute-form request line. The {@code CONNECT}
 * tunnel an https server needs is exercised in {@link ServerCertificatesTest}, which has TLS to hand.
 */
class ProxyRoutingTest {

    private HttpServer server;
    private URI host;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/midpoint/ws/rest/users", exchange -> {
            byte[] body = "<user><name>through-the-proxy</name></user>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/xml");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        host = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/midpoint");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private MidpointRestClient clientThrough(ProxyConfig proxy) {
        return new MidpointRestClient(host, "administrator", "s3cret",
                new MidpointRestClient.Config().proxy(proxy).requestTimeout(Duration.ofSeconds(10)));
    }

    /** The server is on loopback, so proxying it at all means clearing the built-in loopback exemption. */
    private ProxyConfig proxyAt(StubProxy proxy, String noProxy) {
        return ProxyConfig.resolve(proxy.uri().toString(), noProxy, host, name -> null);
    }

    @Test
    void requestsGoThroughTheProxy() throws Exception {
        try (StubProxy proxy = StubProxy.open()) {
            String body = clientThrough(proxyAt(proxy, "")).getObject("users", "aaa");

            assertEquals("<user><name>through-the-proxy</name></user>", body);
            assertEquals(1, proxy.requests().size(), "the proxy should have seen exactly one request");
            assertTrue(proxy.requests().get(0).startsWith("GET http://127.0.0.1:"),
                    "a proxied request carries the absolute URL: " + proxy.requests().get(0));
        }
    }

    @Test
    void loopbackBypassesTheProxyByDefault() throws Exception {
        try (StubProxy proxy = StubProxy.open()) {
            String body = clientThrough(proxyAt(proxy, null)).getObject("users", "aaa");

            assertEquals("<user><name>through-the-proxy</name></user>", body);
            assertTrue(proxy.requests().isEmpty(),
                    "with no bypass list given, a loopback server must not be sent to the proxy");
        }
    }

    @Test
    void anExplicitBypassEntryKeepsAHostDirect() throws Exception {
        try (StubProxy proxy = StubProxy.open()) {
            assertEquals("<user><name>through-the-proxy</name></user>",
                    clientThrough(proxyAt(proxy, "127.0.0.1")).getObject("users", "aaa"));
            assertTrue(proxy.requests().isEmpty(), "a bypassed host must not reach the proxy");
        }
    }

    @Test
    void credentialsAreSentToAProxyThatDemandsThem() throws Exception {
        try (StubProxy proxy = StubProxy.requiringAuth("alice", "s3cret")) {
            ProxyConfig config = proxyAt(proxy, "").withCredentials("alice", "s3cret");

            assertEquals("<user><name>through-the-proxy</name></user>",
                    clientThrough(config).getObject("users", "aaa"));
            assertEquals(1, proxy.countAuthenticated(), "the retry after 407 must carry the credentials");
        }
    }

    @Test
    void aRejectedProxyPasswordSaysSoAndNamesTheProxy() throws Exception {
        try (StubProxy proxy = StubProxy.requiringAuth("alice", "s3cret")) {
            ProxyConfig config = proxyAt(proxy, "").withCredentials("alice", "wrong");

            IOException failure = assertThrows(IOException.class,
                    () -> clientThrough(config).getObject("users", "aaa"));
            assertTrue(failure.getMessage().contains("proxy"), failure.getMessage());
            assertTrue(failure.getMessage().contains("alice"), failure.getMessage());
        }
    }

    @Test
    void anUnauthenticatedRunAgainstAnAuthenticatingProxyIsExplained() throws Exception {
        try (StubProxy proxy = StubProxy.requiringAuth("alice", "s3cret")) {
            IOException failure = assertThrows(IOException.class,
                    () -> clientThrough(proxyAt(proxy, "")).getObject("users", "aaa"));
            assertTrue(failure.getMessage().contains("--proxy-user"),
                    "a bare 407 should name the flag that fixes it: " + failure.getMessage());
        }
    }

    /**
     * The one that guards the JDK assumption the whole feature rests on.
     *
     * <p>An https server behind an authenticating proxy is reached with {@code CONNECT}, and the JDK refuses
     * to put Basic credentials on a tunnel unless {@code jdk.http.auth.tunneling.disabledSchemes} has been
     * cleared before {@code java.net.http} loads. If a future JDK stops honouring that property, or renames
     * it, this test fails and everything else still passes — which is exactly the signal wanted, because in
     * production the symptom is a 407 that no amount of correct configuration can fix.
     *
     * <p>The property comes from surefire here rather than from {@link MidpointRestClient#latchProxyTunnelAuth}:
     * one JVM runs every test class, and by the time this one starts, another has already used the HTTP
     * client and fixed the value. {@link #theLatchClearsTheSchemesTheJdkDisables} covers the latch itself.
     */
    @Test
    void httpsThroughAnAuthenticatingProxyTunnelsWithCredentials() throws Exception {
        assumeTrue(RigTls.available(),
                "no rig certificates at docker/tls — run docker/tls/gen-certs.sh to exercise the handshake");
        assumeTrue("".equals(System.getProperty("jdk.http.auth.tunneling.disabledSchemes")),
                "run through surefire, which clears the property before the JVM starts");

        HttpsServer secure = RigTls.server();
        secure.createContext("/midpoint/ws/rest/users", exchange -> {
            byte[] body = "<user><name>tunnelled</name></user>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/xml");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        secure.start();
        try (StubProxy proxy = StubProxy.requiringAuth("alice", "s3cret")) {
            URI secureHost = URI.create("https://127.0.0.1:" + secure.getAddress().getPort() + "/midpoint");
            ProxyConfig config = ProxyConfig.resolve(proxy.uri().toString(), "", secureHost, name -> null)
                    .withCredentials("alice", "s3cret");
            MidpointRestClient client = new MidpointRestClient(secureHost, "administrator", "s3cret",
                    new MidpointRestClient.Config().proxy(config).caCert(RigTls.caCert())
                            .requestTimeout(Duration.ofSeconds(10)));

            assertEquals("<user><name>tunnelled</name></user>", client.getObject("users", "aaa"));
            assertTrue(proxy.requests().get(0).startsWith("CONNECT 127.0.0.1:"), proxy.requests().toString());
            assertEquals(1, proxy.countAuthenticated(),
                    "the CONNECT is retried with credentials once the proxy asks: " + proxy.requests());
        } finally {
            secure.stop(0);
        }
    }

    /**
     * The latch sets the property the test above depends on.
     *
     * <p>Split from it because the two failure modes are different questions: whether midgie asks for
     * tunnelled authentication, and whether the JDK still honours the asking. Verified separately that
     * setting this from {@code main} — before anything touches {@code java.net.http} — does take effect,
     * which is the arrangement {@code Main} relies on and no in-process test can reproduce twice.
     */
    @Test
    void theLatchClearsTheSchemesTheJdkDisables() {
        MidpointRestClient.latchProxyTunnelAuth(true);
        assertEquals("", System.getProperty("jdk.http.auth.tunneling.disabledSchemes"),
                "an empty list disables no scheme, which is what allows Basic");
    }
}
