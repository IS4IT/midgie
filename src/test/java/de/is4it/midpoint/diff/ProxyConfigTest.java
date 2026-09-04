/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.net.Proxy;
import java.net.URI;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Resolution, bypass matching and the refusals — all of it offline, with the environment handed in. */
class ProxyConfigTest {

    private static final URI HTTPS = URI.create("https://mp.example.com/midpoint");
    private static final URI HTTP = URI.create("http://mp.example.com/midpoint");

    private static UnaryOperator<String> env(String... pairs) {
        Map<String, String> values = new java.util.HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1]);
        }
        return values::get;
    }

    private static ProxyConfig resolve(String option, URI target, String... env) {
        return ProxyConfig.resolve(option, null, target, env(env));
    }

    @Test
    void nothingConfiguredMeansNoProxy() {
        assertNull(resolve(null, HTTPS));
    }

    @Test
    void optionBeatsEnvironment() {
        ProxyConfig proxy = resolve("http://chosen:3128", HTTPS, "https_proxy", "http://ignored:8080");
        assertEquals("chosen", proxy.address().getHostString());
        assertEquals(3128, proxy.address().getPort());
    }

    @Test
    void httpsTargetPrefersHttpsProxyVariable() {
        ProxyConfig proxy = resolve(null, HTTPS, "https_proxy", "http://tls:3128", "http_proxy", "http://plain:3128");
        assertEquals("tls", proxy.address().getHostString());
    }

    @Test
    void httpTargetIgnoresTheHttpsProxyVariable() {
        assertNull(resolve(null, HTTP, "https_proxy", "http://tls:3128"));
        assertEquals("plain", resolve(null, HTTP, "http_proxy", "http://plain:3128").address().getHostString());
    }

    @Test
    void httpsTargetDoesNotFallBackToTheHttpProxyVariable() {
        // curl, git, Go and Python all keep the two apart, so a site that sets only http_proxy means it.
        // Falling back would send an https server through a proxy the git in the same image would not use.
        assertNull(resolve(null, HTTPS, "http_proxy", "http://plain:3128"));
        assertNull(resolve(null, HTTPS, "HTTP_PROXY", "http://plain:3128"));
    }

    @Test
    void lowercaseVariableWinsOverUppercase() {
        ProxyConfig proxy = resolve(null, HTTPS, "https_proxy", "http://lower:3128", "HTTPS_PROXY", "http://upper:3128");
        assertEquals("lower", proxy.address().getHostString());
        assertEquals("lower", ProxyConfig.resolve(null, null, HTTP,
                env("http_proxy", "http://lower:3128", "HTTP_PROXY", "http://upper:3128"))
                .address().getHostString());
        assertEquals("upper", resolve(null, HTTPS, "HTTPS_PROXY", "http://upper:3128").address().getHostString(),
                "the uppercase spelling alone is still honoured");
    }

    @Test
    void theBypassListTakesEitherSpellingToo() {
        for (String variable : new String[] {"no_proxy", "NO_PROXY"}) {
            ProxyConfig proxy = ProxyConfig.resolve("http://proxy.corp:3128", null, HTTPS,
                    env(variable, ".example.com"));
            assertTrue(proxy.bypasses("mp.example.com"), variable + " must be read");
            assertFalse(proxy.bypasses("localhost"), variable + " replaces the loopback default");
        }
    }

    @Test
    void bareHostAndPortIsAccepted() {
        ProxyConfig proxy = resolve("proxy.corp:3128", HTTPS);
        assertEquals("proxy.corp", proxy.address().getHostString());
        assertEquals(3128, proxy.address().getPort());
    }

    @Test
    void noPortMeansTheSchemeDefault() {
        assertEquals(80, resolve("http://proxy.corp", HTTPS).address().getPort());
    }

    @Test
    void socksAndHttpsProxiesAreRefusedWithAReason() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> resolve("socks5://proxy.corp:1080", HTTPS)).getMessage().contains("SOCKS"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> resolve("https://proxy.corp:3128", HTTPS)).getMessage().contains("CONNECT"));
    }

    @Test
    void credentialsAreTakenFromTheEnvironmentUrl() {
        ProxyConfig proxy = resolve(null, HTTPS, "https_proxy", "http://alice:s3cret@proxy.corp:3128");
        assertTrue(proxy.hasCredentials());
        assertEquals("alice", proxy.user());
        assertEquals("s3cret", proxy.password());
    }

    @Test
    void credentialsOnTheCommandLineAreStrippedAndNotUsed() {
        // The same rule --url follows: an option is visible in ps, so what it carries is dropped, not honoured.
        ProxyConfig proxy = resolve("http://alice:s3cret@proxy.corp:3128", HTTPS);
        assertFalse(proxy.hasCredentials());
        assertNull(proxy.user());
        assertFalse(proxy.describe().contains("s3cret"));
    }

    @Test
    void describeNeverShowsThePassword() {
        ProxyConfig proxy = resolve(null, HTTPS, "https_proxy", "http://alice:s3cret@proxy.corp:3128");
        assertTrue(proxy.describe().contains("alice"));
        assertFalse(proxy.describe().contains("s3cret"));
    }

    @Test
    void loopbackBypassesByDefault() {
        ProxyConfig proxy = resolve("http://proxy.corp:3128", HTTPS);
        assertTrue(proxy.bypasses("localhost"));
        assertTrue(proxy.bypasses("127.0.0.1"));
        assertTrue(proxy.bypasses("127.13.2.9"));
        assertTrue(proxy.bypasses("::1"));
        assertFalse(proxy.bypasses("mp.example.com"));
    }

    @Test
    void anExplicitListReplacesTheLoopbackDefault() {
        ProxyConfig proxy = ProxyConfig.resolve("http://proxy.corp:3128", "", HTTPS, env());
        assertFalse(proxy.bypasses("localhost"), "an empty --no-proxy proxies everything, loopback included");
    }

    @Test
    void bypassEntrySemantics() {
        ProxyConfig proxy = ProxyConfig.resolve("http://proxy.corp:3128",
                "mp.example.com,.corp.example,internal,another.host:8443", HTTPS, env());
        assertTrue(proxy.bypasses("mp.example.com"));
        assertTrue(proxy.bypasses("MP.EXAMPLE.COM"), "matching is case-insensitive");
        assertTrue(proxy.bypasses("mp.example.com."), "a trailing root dot is not a different host");
        assertTrue(proxy.bypasses("host.corp.example"), "a leading dot covers subdomains");
        assertFalse(proxy.bypasses("corp.example"), "...but not the domain itself");
        assertTrue(proxy.bypasses("internal"));
        assertTrue(proxy.bypasses("mp.internal"), "a bare name also covers what sits under it");
        assertTrue(proxy.bypasses("another.host"), "a port on an entry is accepted and ignored");
        assertFalse(proxy.bypasses("elsewhere.example"));
    }

    @Test
    void starBypassesEverything() {
        ProxyConfig proxy = ProxyConfig.resolve("http://proxy.corp:3128", "*", HTTPS, env());
        assertTrue(proxy.bypasses("anything.at.all"));
    }

    @Test
    void noProxyComesFromTheEnvironmentToo() {
        ProxyConfig proxy = ProxyConfig.resolve(null, null, HTTPS,
                env("https_proxy", "http://proxy.corp:3128", "no_proxy", ".example.com"));
        assertTrue(proxy.bypasses("mp.example.com"));
        assertFalse(proxy.bypasses("mp.elsewhere.org"));
    }

    @Test
    void selectorSendsBypassedHostsDirect() {
        ProxyConfig proxy = ProxyConfig.resolve("http://proxy.corp:3128", "mp.internal", HTTPS, env());
        assertEquals(Proxy.NO_PROXY, proxy.selector().select(URI.create("https://mp.internal/x")).get(0));
        assertEquals(Proxy.Type.HTTP,
                proxy.selector().select(URI.create("https://mp.example.com/x")).get(0).type());
    }

    @Test
    void theAuthenticatorAnswersTheProxyAndNothingElse() throws Exception {
        ProxyConfig proxy = resolve(null, HTTPS, "https_proxy", "http://alice:s3cret@proxy.corp:3128");
        java.net.Authenticator authenticator = proxy.authenticator();
        assertNotNull(authenticator);

        assertEquals("alice", ask(authenticator, java.net.Authenticator.RequestorType.PROXY).getUserName());
        assertNull(ask(authenticator, java.net.Authenticator.RequestorType.SERVER),
                "a 401 from midPoint must never be answered with the proxy password");
    }

    @Test
    void noCredentialsMeansNoAuthenticator() {
        assertNull(resolve("http://proxy.corp:3128", HTTPS).authenticator());
    }

    /** Drive {@link java.net.Authenticator} through its only public entry point. */
    private static java.net.PasswordAuthentication ask(java.net.Authenticator authenticator,
            java.net.Authenticator.RequestorType type) throws Exception {
        return java.net.Authenticator.requestPasswordAuthentication(authenticator, "proxy.corp", null, 3128,
                "http", "realm", "basic", new java.net.URL("http://proxy.corp:3128"), type);
    }
}
