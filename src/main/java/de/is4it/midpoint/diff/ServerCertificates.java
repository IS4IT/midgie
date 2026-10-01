/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
        return fetch(url, timeout, null);
    }

    /**
     * The chain {@code url}'s host presents, leaf first, reached through {@code proxy} when one applies.
     *
     * <p>This is the one place midgie speaks the proxy protocol itself: {@code java.net.http} handles it
     * everywhere else, but the handshake has to be inspected here, so the tunnel has to be opened by hand
     * with {@code CONNECT} before TLS starts. Note what that yields through an intercepting proxy — the
     * chain captured is the proxy's own, signed by the CA it re-signs with, which is precisely the CA that
     * {@code --cacert} then needs. Fetching it is the right answer, not a mistake to guard against.
     */
    static List<X509Certificate> fetch(URI url, Duration timeout, ProxyConfig proxy) throws IOException {
        if (!"https".equalsIgnoreCase(url.getScheme())) {
            throw new IOException("--write-cacert needs an https:// URL; " + url + " has no TLS to inspect.");
        }
        String host = url.getHost();
        int port = (url.getPort() > 0) ? url.getPort() : 443;
        boolean tunnelled = proxy != null && !proxy.bypasses(host);

        Capture capture = new Capture();
        SSLSocketFactorySource factory = new SSLSocketFactorySource(capture);
        LOG.debug("fetching the certificate chain from {}:{}{}", host, port,
                tunnelled ? " through " + proxy.address() : "");
        try (SSLSocket socket = tunnelled
                ? factory.overTunnel(ProxyTunnel.open(proxy, host, port, timeout), host, port)
                : factory.create()) {
            if (!tunnelled) {
                socket.connect(new InetSocketAddress(host, port), millis(timeout));
            }
            socket.setSoTimeout(millis(timeout));
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

    /**
     * Whether {@code anchors} is a lone server certificate that is not its own issuer: a server sending no
     * chain leaves nothing else to take, and trusting the leaf pins it until its renewal.
     */
    static boolean isPinnedLeaf(List<X509Certificate> anchors) {
        return anchors.size() == 1 && !isSelfSigned(anchors.get(0));
    }

    /** Issued by itself, and signed with its own key. */
    static boolean isSelfSigned(X509Certificate certificate) {
        if (!certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())) {
            return false;
        }
        try {
            certificate.verify(certificate.getPublicKey());
            return true;
        } catch (java.security.GeneralSecurityException e) {
            return false;
        }
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
            return (SSLSocket) factory().createSocket();
        }

        /** TLS layered over an already-connected tunnel; {@code autoClose} shuts the plain socket with it. */
        SSLSocket overTunnel(Socket tunnel, String host, int port) throws IOException {
            return (SSLSocket) factory().createSocket(tunnel, host, port, true);
        }

        private javax.net.ssl.SSLSocketFactory factory() throws IOException {
            try {
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[] {capture}, null);
                return context.getSocketFactory();
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                throw new IOException("cannot set up a TLS client: " + e.getMessage(), e);
            }
        }
    }

    /**
     * A {@code CONNECT} tunnel to the target, opened by hand.
     *
     * <p>Deliberately minimal: the request goes out, the status line decides, and the remaining headers are
     * drained so the socket is positioned exactly at the start of the tunnelled stream when TLS takes over.
     * Anything richer — redirects, other authentication schemes, keep-alive — belongs to the HTTP client that
     * handles every other connection midgie makes.
     */
    private static final class ProxyTunnel {

        private ProxyTunnel() {
        }

        static Socket open(ProxyConfig proxy, String host, int port, Duration timeout) throws IOException {
            String target = host + ":" + port;
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(proxy.address().getHostString(),
                        proxy.address().getPort()), millis(timeout));
                socket.setSoTimeout(millis(timeout));

                StringBuilder request = new StringBuilder()
                        .append("CONNECT ").append(target).append(" HTTP/1.1\r\n")
                        .append("Host: ").append(target).append("\r\n");
                if (proxy.hasCredentials()) {
                    String credentials = proxy.user() + ":" + proxy.password();
                    request.append("Proxy-Authorization: Basic ")
                            .append(Base64.getEncoder().encodeToString(
                                    credentials.getBytes(StandardCharsets.UTF_8)))
                            .append("\r\n");
                }
                request.append("Proxy-Connection: keep-alive\r\n\r\n");
                socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                readResponse(socket, proxy, target);
                LOG.debug("CONNECT {} through {} established", target, proxy.address());
                return socket;
            } catch (IOException e) {
                socket.close();
                throw e;
            }
        }

        /**
         * Read the status line and drain the headers.
         *
         * <p>Byte at a time, and not through a {@code BufferedReader}: a buffered reader would swallow the
         * first bytes of the TLS handshake into its own buffer, where the socket the caller goes on to use
         * can never see them again.
         */
        private static void readResponse(Socket socket, ProxyConfig proxy, String target) throws IOException {
            String statusLine = readLine(socket);
            if (statusLine == null) {
                throw new IOException("the proxy at " + proxy.address() + " closed the connection without "
                        + "answering CONNECT " + target + ".");
            }
            // Bounded: the read timeout limits how long any one line may take, not how many may arrive, so a
            // proxy that never stopped sending headers would otherwise loop here for ever.
            for (int header = 0; header < 100; header++) {
                String line = readLine(socket);
                if (line == null || line.isEmpty()) {
                    break;
                }
                if (header == 99) {
                    throw new IOException("the proxy at " + proxy.address() + " sent an implausible number "
                            + "of headers answering CONNECT " + target + ".");
                }
            }
            String[] parts = statusLine.split(" ", 3);
            int status = (parts.length > 1) ? parseStatus(parts[1]) : -1;
            if (status == 407) {
                throw new IOException("the proxy at " + proxy.address() + " demands authentication"
                        + (proxy.hasCredentials()
                                ? " and rejected the credentials for user '" + proxy.user() + "'."
                                : "; pass --proxy-user with --proxy-password-env/--proxy-password-file."));
            }
            if (status != 200) {
                throw new IOException("the proxy at " + proxy.address() + " refused CONNECT " + target
                        + ": " + statusLine.strip());
            }
        }

        private static int parseStatus(String token) {
            try {
                return Integer.parseInt(token.strip());
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        /** One CRLF-terminated line, or null at end of stream. */
        private static String readLine(Socket socket) throws IOException {
            InputStream in = socket.getInputStream();
            StringBuilder line = new StringBuilder();
            int read;
            while ((read = in.read()) != -1) {
                if (read == '\n') {
                    int end = line.length();
                    if (end > 0 && line.charAt(end - 1) == '\r') {
                        line.setLength(end - 1);
                    }
                    return line.toString();
                }
                line.append((char) read);
                if (line.length() > 8192) {
                    throw new IOException("the proxy sent an implausibly long response line.");
                }
            }
            return line.length() > 0 ? line.toString() : null;
        }
    }

    /** A socket timeout: milliseconds as an {@code int}, which a cast of a long timeout turned negative. */
    static int millis(Duration timeout) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, timeout.toMillis()));
    }
}
