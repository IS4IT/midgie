/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * The TLS rig's key material, for tests that need a real handshake against a CA no JDK ships.
 *
 * <p>{@code docker/tls/gen-certs.sh} generates it and {@code .gitignore} keeps it out of the repository, so
 * every test built on this skips where it is absent — CI, a fresh clone — rather than failing for the absence
 * of something no commit contains. The server certificate carries {@code IP Address:127.0.0.1} among its
 * subject alternative names, which is what lets a client verify it properly instead of needing
 * {@code --insecure}.
 */
final class RigTls {

    static final Path SERVER_CERT = Path.of("docker/tls/server.crt");
    static final Path SERVER_KEY = Path.of("docker/tls/server.key");
    static final Path CA_CERT = Path.of("docker/tls/ca.crt");

    private RigTls() {
    }

    static boolean available() {
        return Files.exists(SERVER_KEY) && Files.exists(SERVER_CERT) && Files.exists(CA_CERT);
    }

    static File caCert() {
        return CA_CERT.toFile();
    }

    static X509Certificate certificate(Path pem) throws Exception {
        try (var in = Files.newInputStream(pem)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    /** An https server on an ephemeral loopback port, presenting the rig's certificate. */
    static HttpsServer server() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        return server;
    }

    /** The rig's PEM certificate and PKCS#8 key, as key material for a stub server. */
    static KeyManager[] keyManagers() throws Exception {
        String pem = Files.readString(SERVER_KEY, StandardCharsets.UTF_8)
                .replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));

        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("server", key, new char[0], new Certificate[] {certificate(SERVER_CERT)});

        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(store, new char[0]);
        return factory.getKeyManagers();
    }
}
