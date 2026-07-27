/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal midPoint REST client for the live modes ({@code diff --url}, {@code apply}). This is the
 * only HTTP-touching class — the diff engine stays REST-free (see DESIGN.md layering). Uses the JDK
 * {@code java.net.http} client, so no extra dependency.
 *
 * <p>midPoint REST lives under {@code {host}/ws/rest}; objects are addressed as
 * {@code {type}/{oid}} where {@code type} is the collection name from
 * {@code ObjectTypes.getRestTypeFromClass} (e.g. {@code users}, {@code roles}). Bodies are XML.
 */
public final class MidpointRestClient {

    private static final Logger LOG = LoggerFactory.getLogger(MidpointRestClient.class);

    /** Thrown for any non-success HTTP status; carries the code so callers can special-case 404. */
    public static final class RestException extends IOException {
        private final int statusCode;

        RestException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }

        public boolean isNotFound() {
            return statusCode == 404;
        }
    }

    /**
     * The connection never came up, or gave up waiting: no HTTP status, nothing to classify by code.
     *
     * <p>A type rather than a message prefix, because callers do act on it — {@code SchemaOptions} stays
     * quiet about a failed schema read when the object fetch is about to report the same transport problem.
     * Matching on the message text made rewording an error message a silent behaviour change.
     */
    public static final class TransportException extends IOException {
        TransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final Duration RETRY_MAX_DELAY = Duration.ofSeconds(30);

    /**
     * Whether hostname verification has been switched off for this process, or {@code null} while undecided.
     *
     * <p>It is a single process-wide decision, not a per-client one: the JDK reads
     * {@code jdk.internal.httpclient.disableHostnameVerification} exactly once, into a {@code static final},
     * on first use of {@code java.net.http}. Setting it from whichever client happened to be built first
     * meant the flag worked only by accident of call order — a client built after any earlier request would
     * silently keep verification on, and every client built after an {@code --insecure} one would silently
     * have it off. {@link #latchHostnameVerification} makes the decision once, before any client exists.
     */
    private static Boolean hostnameVerificationDisabled;

    /**
     * Fix the process-wide hostname-verification decision. Called from {@code Main} before any client is
     * built; the first call wins and later ones are ignored, so the constructor can only ever observe it.
     */
    static synchronized void latchHostnameVerification(boolean disabled) {
        if (hostnameVerificationDisabled != null) {
            return;
        }
        hostnameVerificationDisabled = disabled;
        if (disabled) {
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        }
        LOG.debug("hostname verification {} for this process", disabled ? "disabled" : "enabled");
    }

    /** Timeout / retry / TLS knobs for the client (fluent setters; sensible defaults). */
    public static final class Config {
        private boolean insecure;
        private File caCert;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private int maxRetries;
        private Duration retryBaseDelay = Duration.ofSeconds(1);

        public Config insecure(boolean value) {
            this.insecure = value;
            return this;
        }

        public Config caCert(File value) {
            this.caCert = value;
            return this;
        }

        public Config connectTimeout(Duration value) {
            this.connectTimeout = value;
            return this;
        }

        public Config requestTimeout(Duration value) {
            this.requestTimeout = value;
            return this;
        }

        public Config maxRetries(int value) {
            this.maxRetries = Math.max(0, value);
            return this;
        }

        public Config retryBaseDelay(Duration value) {
            this.retryBaseDelay = value;
            return this;
        }
    }

    private final URI restBase;
    private final String authHeader;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final Duration retryBaseDelay;

    /**
     * Convenience constructor with default timeouts and no retries.
     *
     * @param host     the midPoint application root, e.g. {@code http://localhost:12080/midpoint}
     *                 (a trailing {@code /ws/rest} is accepted and not duplicated)
     * @param user     REST user (e.g. {@code administrator})
     * @param password REST password
     * @param insecure trust any TLS certificate (for self-signed https servers)
     */
    public MidpointRestClient(URI host, String user, String password, boolean insecure) {
        this(host, user, password, new Config().insecure(insecure));
    }

    public MidpointRestClient(URI host, String user, String password, Config config) {
        this.restBase = restBaseOf(host);
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.requestTimeout = config.requestTimeout;
        this.maxRetries = config.maxRetries;
        this.retryBaseDelay = config.retryBaseDelay;

        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(config.connectTimeout);
        if (config.insecure) {
            // "insecure" must mean insecure: trust any cert AND skip hostname verification. The latter is
            // only honoured by java.net.http via a system property the JDK reads once — see the latch.
            latchHostnameVerification(true);
            if (!Boolean.TRUE.equals(hostnameVerificationDisabled)) {
                throw new IllegalStateException("--insecure cannot be honoured: hostname verification was "
                        + "already fixed for this process before this connection was built. The JDK reads "
                        + "that setting once, so it has to be decided before any client exists.");
            }
            builder.sslContext(trustAllSslContext());
        } else if (Boolean.TRUE.equals(hostnameVerificationDisabled)) {
            System.err.println("midgie: warning: hostname verification is off for this process because "
                    + "--insecure was requested; this connection cannot turn it back on.");
            if (config.caCert != null) {
                builder.sslContext(caCertSslContext(config.caCert));
            }
        } else if (config.caCert != null) {
            builder.sslContext(caCertSslContext(config.caCert));
        }
        this.httpClient = builder.build();
        LOG.debug("REST client ready: base={}, requestTimeout={}s, maxRetries={}, tls={}",
                restBase, requestTimeout.toSeconds(), maxRetries,
                config.insecure ? "insecure (trust-all)"
                        : (config.caCert != null ? "cacert " + config.caCert : "default"));
    }

    /** {@code GET {type}/{oid}} → the object XML. Throws {@link RestException} (404-aware) on failure. */
    public String getObject(String restType, String oid) throws IOException {
        return getObject(restType, oid, new String[0]);
    }

    /**
     * {@code GET {type}/{oid}} with {@code ?include=…} for items midPoint leaves out by default. Some items
     * come back as {@code incomplete="true"} unless explicitly requested — a {@code LookupTableType}'s
     * {@code row} is the case midgie needs, since its state rows would otherwise read as an empty container.
     */
    public String getObject(String restType, String oid, String... include) throws IOException {
        StringBuilder uri = new StringBuilder(objectUri(restType, oid).toString());
        for (int i = 0; i < include.length; i++) {
            uri.append(i == 0 ? '?' : '&').append("include=")
                    .append(URLEncoder.encode(include[i], StandardCharsets.UTF_8));
        }
        HttpRequest request = baseRequest(URI.create(uri.toString()))
                .header("Accept", "application/xml")
                .GET()
                .build();
        return send(request, "GET " + restType + "/" + oid, null, true);
    }

    /** {@code GET {type}} → an {@code ObjectListType} of all objects of that type (used to read schemas). */
    public String listObjects(String restType) throws IOException {
        HttpRequest request = baseRequest(restCollectionUri(restType))
                .header("Accept", "application/xml")
                .GET()
                .build();
        return send(request, "GET " + restType, null, true);
    }

    /**
     * {@code POST {type}/search} with a {@code <query>} body → an {@code ObjectListType} of the matches.
     * A read, but midPoint models search as a POST; the caller (which holds the {@link PrismContext})
     * builds the query XML and parses the response, keeping this class REST-only.
     */
    public String searchObjects(String restType, String queryXml) throws IOException {
        HttpRequest request = baseRequest(URI.create(restBase + "/" + restType + "/search"))
                .header("Content-Type", "application/xml")
                .header("Accept", "application/xml")
                .POST(HttpRequest.BodyPublishers.ofString(queryXml, StandardCharsets.UTF_8))
                .build();
        return send(request, "POST " + restType + "/search", queryXml, true);
    }

    /** {@code PATCH {type}/{oid}} with an {@code objectModification} body. */
    public void patchObject(String restType, String oid, String modificationXml) throws IOException {
        HttpRequest request = baseRequest(objectUri(restType, oid))
                .header("Content-Type", "application/xml")
                .header("Accept", "application/xml")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(modificationXml, StandardCharsets.UTF_8))
                .build();
        send(request, "PATCH " + restType + "/" + oid, modificationXml, false);
    }

    /** {@code DELETE {type}/{oid}}. Throws {@link RestException} (404-aware) on failure. */
    public void deleteObject(String restType, String oid) throws IOException {
        HttpRequest request = baseRequest(objectUri(restType, oid))
                .header("Accept", "application/xml")
                .DELETE()
                .build();
        send(request, "DELETE " + restType + "/" + oid, null, false);
    }

    /** {@code POST {type}} with a full object body (create). Returns the new OID from Location if present. */
    public String createObject(String restType, String objectXml) throws IOException {
        HttpRequest request = baseRequest(restCollectionUri(restType))
                .header("Content-Type", "application/xml")
                .header("Accept", "application/xml")
                .POST(HttpRequest.BodyPublishers.ofString(objectXml, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = sendRaw(request, "POST " + restType, objectXml, false);
        return oidFromLocation(response);
    }

    // --- internals ---

    private HttpRequest.Builder baseRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Authorization", authHeader);
    }

    private String send(HttpRequest request, String what, String requestBody, boolean safeRead)
            throws IOException {
        return sendRaw(request, what, requestBody, safeRead).body();
    }

    /**
     * Send the request, retrying idempotent reads on transient failures (HTTP 502/503/504, connection
     * refused, timeout) up to {@code maxRetries} with exponential backoff. Writes are never retried —
     * re-running {@code apply} is the safe recovery. {@code requestBody} (null for a bodyless GET/DELETE)
     * is logged at TRACE only, alongside the response body.
     *
     * <p>{@code safeRead} is a property of the <em>call</em>, not of the HTTP verb. Every object lookup
     * goes through {@code POST {type}/search}, so keying retries on "is it a GET" left the tool's primary
     * read uncovered: against a midPoint that is still starting, the first search failed immediately
     * however high {@code --retries} was set.
     */
    private HttpResponse<String> sendRaw(HttpRequest request, String what, String requestBody,
            boolean safeRead) throws IOException {
        boolean retryable = safeRead;
        int attempt = 0;
        while (true) {
            try {
                LOG.debug("→ {}  {}", what, request.uri());
                if (requestBody != null && !requestBody.isEmpty()) {
                    LOG.trace("→ {} request body:\n{}", what, requestBody.stripTrailing());
                }
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                String responseBody = response.body();
                LOG.debug("← {}  HTTP {} ({} byte body)", what, status, bodyLength(responseBody));
                if (responseBody != null && !responseBody.isEmpty()) {
                    LOG.trace("← {} response body:\n{}", what, responseBody.stripTrailing());
                }
                if (isTransient(status) && retryable && attempt < maxRetries) {
                    LOG.debug("{}  HTTP {} is transient — retry {} of {}", what, status, attempt + 1, maxRetries);
                    backoff(attempt++);
                    continue;
                }
                if (status >= 300 && status < 400) {
                    // A redirect on the REST base almost always means --url is off (missing context path or
                    // wrong scheme). Following it would chase an HTML page, not the API, so report it clearly.
                    String location = response.headers().firstValue("Location").orElse("(no Location header)");
                    LOG.debug("{}  redirected (HTTP {}) to '{}'", what, status, location);
                    throw new RestException(status, what + " — the server redirected (HTTP " + status + ") to '"
                            + location + "'. Point --url at the exact midPoint REST base: it usually needs the "
                            + "application context path (e.g. http://host:port/midpoint) and the matching scheme "
                            + "(http vs https).");
                }
                if (status < 200 || status >= 300) {
                    throw new RestException(status,
                            what + " failed: HTTP " + status + statusHint(status) + shortBody(response.body()));
                }
                return response;
            } catch (java.net.ConnectException | java.net.http.HttpTimeoutException e) {
                LOG.debug("{}  transport error: {}", what, e.toString());
                if (retryable && attempt < maxRetries) {
                    LOG.debug("{}  retry {} of {} after transport error", what, attempt + 1, maxRetries);
                    backoff(attempt++);
                    continue;
                }
                if (e instanceof java.net.ConnectException) {
                    throw new TransportException(what + " — cannot connect to " + restBase
                            + " (is the server up and the URL correct? " + e.getMessage() + ")", e);
                }
                throw new TransportException(what + " — timed out talking to " + restBase
                        + " (" + e.getMessage() + ")", e);
            } catch (javax.net.ssl.SSLException e) {
                // The commonest TLS misconfiguration is a private CA with no --cacert. The raw JDK message
                // ("PKIX path building failed ...") names neither the server nor the flags that fix it.
                LOG.debug("{}  TLS error: {}", what, e.toString());
                throw new IOException(what + " — TLS handshake with " + restBase + " failed ("
                        + e.getMessage() + "). Pass --cacert <FILE> for a private CA, or --insecure for a "
                        + "self-signed development server.", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(what + " was interrupted", e);
            }
        }
    }

    /** Server-unavailable statuses worth retrying: 503, plus 502/504 from a proxy (e.g. midPoint starting). */
    private static boolean isTransient(int status) {
        return status == 502 || status == 503 || status == 504;
    }

    /** Sleep for {@code retryBaseDelay * 2^attempt}, capped, before the next attempt. */
    private void backoff(int attempt) throws IOException {
        long millis = Math.min(retryBaseDelay.toMillis() * (1L << Math.min(attempt, 20)), RETRY_MAX_DELAY.toMillis());
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while backing off before a retry", e);
        }
    }

    /** Actionable hint for the common HTTP failures. */
    private static String statusHint(int status) {
        switch (status) {
            case 401:
                return " — authentication failed (check the user/password)";
            case 403:
                return " — not authorized (the user lacks permission for this operation)";
            case 404:
                return " — not found";
            default:
                return status >= 500 ? " — server error" : "";
        }
    }

    private URI objectUri(String restType, String oid) {
        return URI.create(restBase + "/" + restType + "/" + oid);
    }

    private URI restCollectionUri(String restType) {
        return URI.create(restBase + "/" + restType);
    }

    /**
     * The REST base to address and to print, with any {@code user:password@} stripped.
     *
     * <p>A URL of the form {@code https://admin:s3cret@mp.example.com/midpoint} is the shape every curl user
     * reaches for, and {@code java.net.http} accepts it — but never authenticates with it, since the client
     * always sends its own Basic header. So the credential does nothing except travel: into the connection
     * and timeout errors printed on stderr regardless of {@code --quiet}, into the debug log, and into the
     * confirmation prompts. Dropping it here means no printed form of the URL can carry it.
     */
    private static URI restBaseOf(URI host) {
        String text = withoutUserInfo(host).toString();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        if (!text.endsWith("/ws/rest")) {
            text = text + "/ws/rest";
        }
        return URI.create(text);
    }

    /** {@code uri} with any userinfo component removed; unchanged when it carries none. */
    static URI withoutUserInfo(URI uri) {
        if (uri == null || uri.getRawUserInfo() == null) {
            return uri;
        }
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(),
                    uri.getQuery(), uri.getFragment());
        } catch (java.net.URISyntaxException e) {
            // Cannot rebuild it: better to fail than to print or send the credential.
            throw new IllegalArgumentException("could not parse the server URL: " + e.getMessage(), e);
        }
    }

    private static String oidFromLocation(HttpResponse<String> response) {
        return response.headers().firstValue("Location")
                .map(location -> location.substring(location.lastIndexOf('/') + 1))
                .orElse(null);
    }

    private static int bodyLength(String body) {
        return (body == null) ? 0 : body.length();
    }

    private static String shortBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String trimmed = body.strip();
        return "\n" + (trimmed.length() > 800 ? trimmed.substring(0, 800) + " …" : trimmed);
    }

    /** SSLContext that trusts exactly the CA certificate(s) in {@code caCertFile} (proper verification). */
    private static SSLContext caCertSslContext(File caCertFile) {
        try (java.io.InputStream in = new java.io.FileInputStream(caCertFile)) {
            java.security.cert.CertificateFactory cf =
                    java.security.cert.CertificateFactory.getInstance("X.509");
            java.util.Collection<? extends java.security.cert.Certificate> certs = cf.generateCertificates(in);
            if (certs.isEmpty()) {
                throw new IllegalStateException("no certificates found");
            }
            java.security.KeyStore keyStore = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
            keyStore.load(null, null);
            int index = 0;
            for (java.security.cert.Certificate cert : certs) {
                keyStore.setCertificateEntry("ca-" + index++, cert);
            }
            javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory
                    .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, tmf.getTrustManagers(), new SecureRandom());
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("Could not load --cacert " + caCertFile + ": " + e.getMessage(), e);
        }
    }

    private static SSLContext trustAllSslContext() {
        TrustManager[] trustAll = { new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) { }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) { }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        } };
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new SecureRandom());
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not build insecure SSL context: " + e.getMessage(), e);
        }
    }
}
