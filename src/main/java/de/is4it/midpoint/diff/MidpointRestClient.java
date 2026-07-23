/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.net.URI;
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
 * Minimal midPoint REST client for the live modes ({@code diff --host}, {@code apply}). This is the
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

    private static final Duration RETRY_MAX_DELAY = Duration.ofSeconds(30);

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
            // "insecure" must mean insecure: trust any cert AND skip hostname verification. The latter
            // is only honoured by java.net.http via this system property.
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
            builder.sslContext(trustAllSslContext());
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
        HttpRequest request = baseRequest(objectUri(restType, oid))
                .header("Accept", "application/xml")
                .GET()
                .build();
        return send(request, "GET " + restType + "/" + oid, null);
    }

    /** {@code GET {type}} → an {@code ObjectListType} of all objects of that type (used to read schemas). */
    public String listObjects(String restType) throws IOException {
        HttpRequest request = baseRequest(restCollectionUri(restType))
                .header("Accept", "application/xml")
                .GET()
                .build();
        return send(request, "GET " + restType, null);
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
        return send(request, "POST " + restType + "/search", queryXml);
    }

    /** {@code PATCH {type}/{oid}} with an {@code objectModification} body. */
    public void patchObject(String restType, String oid, String modificationXml) throws IOException {
        HttpRequest request = baseRequest(objectUri(restType, oid))
                .header("Content-Type", "application/xml")
                .header("Accept", "application/xml")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(modificationXml, StandardCharsets.UTF_8))
                .build();
        send(request, "PATCH " + restType + "/" + oid, modificationXml);
    }

    /** {@code DELETE {type}/{oid}}. Throws {@link RestException} (404-aware) on failure. */
    public void deleteObject(String restType, String oid) throws IOException {
        HttpRequest request = baseRequest(objectUri(restType, oid))
                .header("Accept", "application/xml")
                .DELETE()
                .build();
        send(request, "DELETE " + restType + "/" + oid, null);
    }

    /** {@code POST {type}} with a full object body (create). Returns the new OID from Location if present. */
    public String createObject(String restType, String objectXml) throws IOException {
        HttpRequest request = baseRequest(restCollectionUri(restType))
                .header("Content-Type", "application/xml")
                .header("Accept", "application/xml")
                .POST(HttpRequest.BodyPublishers.ofString(objectXml, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = sendRaw(request, "POST " + restType, objectXml);
        return oidFromLocation(response);
    }

    // --- internals ---

    private HttpRequest.Builder baseRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Authorization", authHeader);
    }

    private String send(HttpRequest request, String what, String requestBody) throws IOException {
        return sendRaw(request, what, requestBody).body();
    }

    /**
     * Send the request, retrying idempotent GETs on transient failures (HTTP 503, connection refused,
     * timeout) up to {@code maxRetries} with exponential backoff. Writes are never retried — re-running
     * {@code apply} is the safe recovery. {@code requestBody} (null for a bodyless GET/DELETE) is logged
     * at TRACE only, alongside the response body.
     */
    private HttpResponse<String> sendRaw(HttpRequest request, String what, String requestBody) throws IOException {
        boolean retryable = "GET".equals(request.method());
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
                    // A redirect on the REST base almost always means --host is off (missing context path or
                    // wrong scheme). Following it would chase an HTML page, not the API, so report it clearly.
                    String location = response.headers().firstValue("Location").orElse("(no Location header)");
                    LOG.debug("{}  redirected (HTTP {}) to '{}'", what, status, location);
                    throw new RestException(status, what + " — the server redirected (HTTP " + status + ") to '"
                            + location + "'. Point --host at the exact midPoint REST base: it usually needs the "
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
                    throw new IOException(what + " — cannot connect to " + restBase
                            + " (is the server up and the URL correct? " + e.getMessage() + ")", e);
                }
                throw new IOException(what + " — timed out talking to " + restBase + " (" + e.getMessage() + ")", e);
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
        long millis = Math.min(retryBaseDelay.toMillis() * (1L << attempt), RETRY_MAX_DELAY.toMillis());
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

    private static URI restBaseOf(URI host) {
        String text = host.toString();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        if (!text.endsWith("/ws/rest")) {
            text = text + "/ws/rest";
        }
        return URI.create(text);
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
