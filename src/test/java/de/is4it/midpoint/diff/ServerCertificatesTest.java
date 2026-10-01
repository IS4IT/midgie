/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;

import com.sun.net.httpserver.HttpsServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@code init --write-cacert}'s certificate fetch against a real TLS handshake, using the rig's own
 * server certificate and its CA. The point of the feature is to see a certificate that is <em>not</em>
 * trusted yet, so the interesting property is that the handshake completes at all against a CA no JDK ships.
 */
class ServerCertificatesTest {

    /** Committed, so the parts that need only a certificate run anywhere. */
    private static final Path TEST_CA = Path.of("src/test/resources/tls/test-ca.crt");

    /** The rig's own certificate — see {@link RigTls} for why the handshake tests skip without it. */
    private static final Path SERVER_CERT = RigTls.SERVER_CERT;

    private HttpsServer server;
    private URI url;

    @BeforeEach
    void startServer() throws Exception {
        if (!RigTls.available()) {
            return;   // only the handshake tests need it; the rest work off the committed certificate
        }
        server = RigTls.server();
        server.createContext("/midpoint", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        url = URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/midpoint");
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void theChainIsFetchedFromAServerNothingTrustsYet() throws Exception {
        assumeTrue(server != null,
                "no rig certificates at docker/tls — run docker/tls/gen-certs.sh to exercise the handshake");
        List<X509Certificate> chain = ServerCertificates.fetch(url, Duration.ofSeconds(10));

        assertEquals(1, chain.size(), "the rig's server sends its own certificate");
        assertEquals(certificate(SERVER_CERT), chain.get(0),
                "and it is the one on disk, byte for byte");
    }

    @Test
    void aLoneCertificateIsItsOwnAnchor() throws Exception {
        assumeTrue(server != null,
                "no rig certificates at docker/tls — run docker/tls/gen-certs.sh to exercise the handshake");
        List<X509Certificate> chain = ServerCertificates.fetch(url, Duration.ofSeconds(10));

        // Trusting the leaf pins one certificate and breaks at its next renewal, so anchors() normally drops
        // it in favour of the issuers — but when the server sends nothing else there is nothing else to take.
        assertEquals(chain, ServerCertificates.anchors(chain));
    }

    @Test
    void aLoneCertificateIssuedByACaIsSaidToBePinned() throws Exception {
        // The rig's server sends no chain. Its certificate is all there is to take, and trusting it pins it until
        // the renewal — which used to go unsaid.
        assertFalse(ServerCertificates.isPinnedLeaf(List.of(certificate(TEST_CA))), "a CA is its own issuer");
        assumeTrue(Files.exists(SERVER_CERT), "no rig certificates at docker/tls");
        assertTrue(ServerCertificates.isPinnedLeaf(List.of(certificate(SERVER_CERT))));
    }

    @Test
    void issuersAreTheAnchorsWhenTheServerSendsAChain() throws Exception {
        X509Certificate ca = certificate(TEST_CA);
        X509Certificate leaf = ca;   // stands in for a leaf: anchors() slices the chain, it does not verify

        List<X509Certificate> anchors = ServerCertificates.anchors(List.of(leaf, ca));

        assertEquals(List.of(ca), anchors, "the leaf is dropped; its issuer is what keeps working");
    }

    @Test
    void thePemItWritesParsesBackAsTheSameCertificate() throws Exception {
        X509Certificate ca = certificate(TEST_CA);

        String pem = ServerCertificates.toPem(List.of(ca));
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate parsed = (X509Certificate) factory.generateCertificate(
                new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));

        assertEquals(ca, parsed, "what --cacert reads back has to be what was fetched");
        assertTrue(pem.contains("-----BEGIN CERTIFICATE-----"), pem);
        assertTrue(pem.contains(ca.getSubjectX500Principal().getName()), "the subject is named in a comment");
    }

    @Test
    void theFingerprintIsTheOneOpensslWouldPrint() throws Exception {
        // openssl x509 -noout -fingerprint -sha256: uppercase hex, colon-separated, 32 bytes.
        String fingerprint = ServerCertificates.fingerprint(certificate(TEST_CA));

        assertEquals(32, fingerprint.split(":").length, fingerprint);
        assertTrue(fingerprint.matches("([0-9A-F]{2}:){31}[0-9A-F]{2}"), fingerprint);
    }

    @Test
    void theChainIsFetchedThroughACONNECTTunnel() throws Exception {
        assumeTrue(server != null,
                "no rig certificates at docker/tls — run docker/tls/gen-certs.sh to exercise the handshake");
        try (StubProxy proxy = StubProxy.open()) {
            List<X509Certificate> chain =
                    ServerCertificates.fetch(url, Duration.ofSeconds(10), through(proxy));

            assertEquals(certificate(SERVER_CERT), chain.get(0),
                    "the handshake has to complete through the tunnel, not just open it");
            assertTrue(proxy.requests().get(0).startsWith("CONNECT 127.0.0.1:"),
                    "the tunnel is opened with CONNECT host:port — " + proxy.requests().get(0));
        }
    }

    @Test
    void aTunnelIsAuthenticatedWhenTheProxyDemandsIt() throws Exception {
        assumeTrue(server != null,
                "no rig certificates at docker/tls — run docker/tls/gen-certs.sh to exercise the handshake");
        try (StubProxy proxy = StubProxy.requiringAuth("alice", "s3cret")) {
            ProxyConfig authenticated = through(proxy).withCredentials("alice", "s3cret");

            assertEquals(certificate(SERVER_CERT),
                    ServerCertificates.fetch(url, Duration.ofSeconds(10), authenticated).get(0));
            // Unlike the HTTP client, this handshake sends the credentials up front rather than waiting to be
            // asked: there is one exchange to get wrong, and a 407 would cost a second connection for nothing.
            assertEquals(1, proxy.countAuthenticated());
        }
    }

    @Test
    void aTunnelThatIsRefusedNamesTheProxyAndTheFlag() throws Exception {
        assumeTrue(server != null,
                "no rig certificates at docker/tls — run docker/tls/gen-certs.sh to exercise the handshake");
        try (StubProxy proxy = StubProxy.requiringAuth("alice", "s3cret")) {
            Exception error = assertThrows(Exception.class,
                    () -> ServerCertificates.fetch(url, Duration.ofSeconds(10), through(proxy)));

            assertTrue(error.getMessage().contains("--proxy-user"), error.getMessage());
        }
    }

    @Test
    void aBypassedHostIsNotTunnelled() throws Exception {
        assumeTrue(server != null,
                "no rig certificates at docker/tls — run docker/tls/gen-certs.sh to exercise the handshake");
        try (StubProxy proxy = StubProxy.open()) {
            ProxyConfig bypassing =
                    ProxyConfig.resolve(proxy.uri().toString(), null, url, name -> null);   // loopback default

            assertEquals(certificate(SERVER_CERT),
                    ServerCertificates.fetch(url, Duration.ofSeconds(10), bypassing).get(0));
            assertTrue(proxy.requests().isEmpty(), "a bypassed host is reached directly");
        }
    }

    @Test
    void aPlainHttpUrlIsRefused() {
        Exception error = assertThrows(Exception.class,
                () -> ServerCertificates.fetch(URI.create("http://127.0.0.1:1/midpoint"), Duration.ofSeconds(1)));
        assertTrue(error.getMessage().contains("https"), error.getMessage());
    }

    // --- fixtures ---

    /** The stub proxy, with the loopback exemption cleared so the rig's own address is actually tunnelled. */
    private ProxyConfig through(StubProxy proxy) {
        return ProxyConfig.resolve(proxy.uri().toString(), "", url, name -> null);
    }

    private static X509Certificate certificate(Path pem) throws Exception {
        try (var in = Files.newInputStream(pem)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

}
