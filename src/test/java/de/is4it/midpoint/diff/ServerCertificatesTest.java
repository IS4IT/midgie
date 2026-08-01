/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@code init --write-cacert}'s certificate fetch against a real TLS handshake, using the rig's own
 * server certificate and its CA. The point of the feature is to see a certificate that is <em>not</em>
 * trusted yet, so the interesting property is that the handshake completes at all against a CA no JDK ships.
 */
class ServerCertificatesTest {

    private static final Path SERVER_CERT = Path.of("docker/tls/server.crt");
    private static final Path SERVER_KEY = Path.of("docker/tls/server.key");

    private HttpsServer server;
    private URI url;

    @BeforeEach
    void startServer() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers(), null, null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/midpoint", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        url = URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/midpoint");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void theChainIsFetchedFromAServerNothingTrustsYet() throws Exception {
        List<X509Certificate> chain = ServerCertificates.fetch(url, Duration.ofSeconds(10));

        assertEquals(1, chain.size(), "the rig's server sends its own certificate");
        assertEquals(certificate(SERVER_CERT), chain.get(0),
                "and it is the one on disk, byte for byte");
    }

    @Test
    void aLoneCertificateIsItsOwnAnchor() throws Exception {
        List<X509Certificate> chain = ServerCertificates.fetch(url, Duration.ofSeconds(10));

        // Trusting the leaf pins one certificate and breaks at its next renewal, so anchors() normally drops
        // it in favour of the issuers — but when the server sends nothing else there is nothing else to take.
        assertEquals(chain, ServerCertificates.anchors(chain));
    }

    @Test
    void issuersAreTheAnchorsWhenTheServerSendsAChain() throws Exception {
        X509Certificate leaf = certificate(SERVER_CERT);
        X509Certificate ca = certificate(Path.of("docker/tls/ca.crt"));

        List<X509Certificate> anchors = ServerCertificates.anchors(List.of(leaf, ca));

        assertEquals(List.of(ca), anchors, "the leaf is dropped; its issuer is what keeps working");
    }

    @Test
    void thePemItWritesParsesBackAsTheSameCertificate() throws Exception {
        X509Certificate ca = certificate(Path.of("docker/tls/ca.crt"));

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
        String fingerprint = ServerCertificates.fingerprint(certificate(Path.of("docker/tls/ca.crt")));

        assertEquals(32, fingerprint.split(":").length, fingerprint);
        assertTrue(fingerprint.matches("([0-9A-F]{2}:){31}[0-9A-F]{2}"), fingerprint);
    }

    @Test
    void aPlainHttpUrlIsRefused() {
        Exception error = assertThrows(Exception.class,
                () -> ServerCertificates.fetch(URI.create("http://127.0.0.1:1/midpoint"), Duration.ofSeconds(1)));
        assertTrue(error.getMessage().contains("https"), error.getMessage());
    }

    // --- fixtures ---

    private static X509Certificate certificate(Path pem) throws Exception {
        try (var in = Files.newInputStream(pem)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    /** The rig's PEM certificate and PKCS#8 key, as the key material for the stub server. */
    private static javax.net.ssl.KeyManager[] keyManagers() throws Exception {
        String pem = Files.readString(SERVER_KEY, StandardCharsets.UTF_8)
                .replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));

        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("server", key, new char[0], new java.security.cert.Certificate[] {certificate(SERVER_CERT)});

        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(store, new char[0]);
        return factory.getKeyManagers();
    }
}
