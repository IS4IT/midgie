/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLContext;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The REST client against a TLS server whose CA no JDK ships: the rig's certificate, issued for {@code mp-tls},
 * {@code localhost} and {@code 127.0.0.1}. Skipped without the rig's generated certificates.
 */
class TlsClientTest {

    private static final String OID = "99999999-0000-0000-0000-000000000001";

    private HttpsServer server;
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        if (!RigTls.available()) {
            return;
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(RigTls.keyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress(0), 0); // every address, so ::1 reaches it too
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/midpoint/ws/rest/users", exchange -> {
            byte[] body = ("<user oid=\"" + OID + "\"><name>u</name></user>").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void anUntrustedServerIsRefusedAndNamesTheFlagsThatFixIt() {
        assumeTrue(server != null, "no rig certificates at docker/tls");
        MidpointRestClient.TransportException e = assertThrows(MidpointRestClient.TransportException.class,
                () -> client("127.0.0.1", new MidpointRestClient.Config()).getObject("users", OID));

        assertTrue(e.getMessage().contains("--cacert") && e.getMessage().contains("--insecure"), e.getMessage());
    }

    @Test
    void cacertTrustsTheServerItIssuedFor() throws Exception {
        assumeTrue(server != null, "no rig certificates at docker/tls");
        String body = client("127.0.0.1", new MidpointRestClient.Config().caCert(RigTls.caCert()))
                .getObject("users", OID);

        assertTrue(body.contains(OID), body);
    }

    @Test
    void cacertStillChecksTheName() {
        // Trusting the CA is not trusting every name: the certificate does not name ::1.
        assumeTrue(server != null, "no rig certificates at docker/tls");
        assertThrows(MidpointRestClient.TransportException.class,
                () -> client("[::1]", new MidpointRestClient.Config().caCert(RigTls.caCert()))
                        .getObject("users", OID));
    }

    @Test
    void insecureTrustsACertificateNoCaVouchesFor() throws Exception {
        // The other half of --insecure, skipping the name check, is a process-wide switch Main latches before
        // any client exists (MidpointRestClient.latchHostnameVerification); set here, it would hold for every
        // test after this one.
        assumeTrue(server != null, "no rig certificates at docker/tls");
        String body = client("127.0.0.1", new MidpointRestClient.Config().insecure(true)).getObject("users", OID);

        assertTrue(body.contains(OID), body);
    }

    private MidpointRestClient client(String host, MidpointRestClient.Config config) {
        return new MidpointRestClient(URI.create("https://" + host + ":" + port + "/midpoint"), "u", "p", config);
    }
}
