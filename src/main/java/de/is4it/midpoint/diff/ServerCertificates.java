/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The certificate chain a server presents, fetched for {@code init --write-cacert} — the equivalent of
 * {@code openssl s_client -showcerts}, so the CA that signs a private midPoint can be turned into the PEM
 * that {@code --cacert} takes.
 *
 * <p>The handshake here deliberately trusts whatever it is given: it has to, because the whole point is to
 * see a certificate that is <em>not</em> trusted yet. That makes this trust-on-first-use, and the only thing
 * standing between it and an impostor is the operator checking the fingerprint against what the CA's owner
 * says it should be — which is why the caller prints fingerprints and asks, rather than writing the file
 * outright. Nothing is sent over this connection: it is opened, the handshake is read, and it is closed.
 */
final class ServerCertificates {

    private static final Logger LOG = LoggerFactory.getLogger(ServerCertificates.class);

    private ServerCertificates() {
    }

    /** The chain {@code url}'s host presents, leaf first, as the server sent it. */
    static List<X509Certificate> fetch(URI url, Duration timeout) throws IOException {
        if (!"https".equalsIgnoreCase(url.getScheme())) {
            throw new IOException("--write-cacert needs an https:// URL; " + url + " has no TLS to inspect.");
        }
        String host = url.getHost();
        int port = (url.getPort() > 0) ? url.getPort() : 443;

        Capture capture = new Capture();
        SSLSocketFactorySource factory = new SSLSocketFactorySource(capture);
        LOG.debug("fetching the certificate chain from {}:{}", host, port);
        try (SSLSocket socket = factory.create()) {
            socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            socket.setSoTimeout((int) timeout.toMillis());
            SSLParameters parameters = socket.getSSLParameters();
            // Without SNI a virtual host answers with the wrong certificate — the one thing that would make
            // this write a PEM for a server the operator never asked about. An address literal is the
            // exception: SNI carries names only, and SNIHostName rejects one, so a URL with an IP in it
            // would otherwise fail here rather than simply not offering a name.
            try {
                parameters.setServerNames(List.of(new SNIHostName(host)));
            } catch (IllegalArgumentException e) {
                LOG.debug("no SNI for '{}': {}", host, e.getMessage());
            }
            socket.setSSLParameters(parameters);
            socket.startHandshake();
        }
        if (capture.chain.isEmpty()) {
            throw new IOException("the handshake with " + host + ":" + port + " presented no certificate.");
        }
        LOG.debug("{} presented {} certificate(s)", host, capture.chain.size());
        return capture.chain;
    }

    /**
     * The certificates worth putting in a trust file: the issuers, or a lone self-signed certificate.
     *
     * <p>Trusting the leaf would pin that one server certificate and break at its next renewal — usually 90
     * days away with Let's Encrypt. Trusting its issuers keeps working, which is what an operator means by
     * "trust this server". A self-signed certificate is its own issuer, so there is nothing else to take.
     */
    static List<X509Certificate> anchors(List<X509Certificate> chain) {
        if (chain.size() == 1) {
            return chain;
        }
        return List.copyOf(chain.subList(1, chain.size()));
    }

    /** PEM, the form {@code --cacert} reads. */
    static String toPem(List<X509Certificate> certificates) throws CertificateEncodingException {
        StringBuilder pem = new StringBuilder();
        Base64.Encoder encoder = Base64.getMimeEncoder(64, new byte[] {'\n'});
        for (X509Certificate certificate : certificates) {
            pem.append("# ").append(certificate.getSubjectX500Principal().getName()).append('\n')
                    .append("-----BEGIN CERTIFICATE-----\n")
                    .append(encoder.encodeToString(certificate.getEncoded())).append('\n')
                    .append("-----END CERTIFICATE-----\n");
        }
        return pem.toString();
    }

    /** One line per certificate: who it is, who signed it, how long it lasts, and its fingerprint. */
    static String describe(X509Certificate certificate) {
        return "  subject: " + certificate.getSubjectX500Principal().getName()
                + "\n  issuer:  " + certificate.getIssuerX500Principal().getName()
                + "\n  valid:   " + certificate.getNotBefore().toInstant()
                + " .. " + certificate.getNotAfter().toInstant()
                + "\n  sha256:  " + fingerprint(certificate);
    }

    /** The SHA-256 fingerprint, in the colon-separated form openssl and browsers show. */
    static String fingerprint(X509Certificate certificate) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(certificate.getEncoded());
            StringBuilder hex = new StringBuilder(digest.length * 3);
            for (byte b : digest) {
                if (hex.length() > 0) {
                    hex.append(':');
                }
                hex.append(String.format("%02X", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            return "(cannot be computed: " + e.getMessage() + ")";
        }
    }

    /** Records the chain and accepts it — see the class comment for why that is the point, not a lapse. */
    private static final class Capture implements X509TrustManager {

        private final List<X509Certificate> chain = new ArrayList<>();

        @Override
        public void checkClientTrusted(X509Certificate[] certificates, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] certificates, String authType)
                throws CertificateException {
            if (certificates == null || certificates.length == 0) {
                throw new CertificateException("the server presented no certificate");
            }
            chain.addAll(List.of(certificates));
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    private record SSLSocketFactorySource(Capture capture) {

        SSLSocket create() throws IOException {
            try {
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[] {capture}, null);
                return (SSLSocket) context.getSocketFactory().createSocket();
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                throw new IOException("cannot set up a TLS client: " + e.getMessage(), e);
            }
        }
    }
}
