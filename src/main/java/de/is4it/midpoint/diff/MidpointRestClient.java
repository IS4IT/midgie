/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

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

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final URI restBase;
    private final String authHeader;
    private final HttpClient httpClient;

    /**
     * @param host     the midPoint application root, e.g. {@code http://localhost:12080/midpoint}
     *                 (a trailing {@code /ws/rest} is accepted and not duplicated)
     * @param user     REST user (e.g. {@code administrator})
     * @param password REST password
     * @param insecure trust any TLS certificate (for self-signed https servers)
     */
    public MidpointRestClient(URI host, String user, String password, boolean insecure) {
        this.restBase = restBaseOf(host);
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));

        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT);
        if (insecure) {
            builder.sslContext(trustAllSslContext());
        }
        this.httpClient = builder.build();
    }

    /** {@code GET {type}/{oid}} → the object XML. Throws {@link RestException} (404-aware) on failure. */
    public String getObject(String restType, String oid) throws IOException {
        HttpRequest request = baseRequest(objectUri(restType, oid))
                .header("Accept", "application/xml")
                .GET()
                .build();
        return send(request, "GET " + restType + "/" + oid);
    }

    /** {@code GET {type}} → an {@code ObjectListType} of all objects of that type (used to read schemas). */
    public String listObjects(String restType) throws IOException {
        HttpRequest request = baseRequest(restCollectionUri(restType))
                .header("Accept", "application/xml")
                .GET()
                .build();
        return send(request, "GET " + restType);
    }

    /** {@code PATCH {type}/{oid}} with an {@code objectModification} body. */
    public void patchObject(String restType, String oid, String modificationXml) throws IOException {
        HttpRequest request = baseRequest(objectUri(restType, oid))
                .header("Content-Type", "application/xml")
                .header("Accept", "application/xml")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(modificationXml, StandardCharsets.UTF_8))
                .build();
        send(request, "PATCH " + restType + "/" + oid);
    }

    /** {@code DELETE {type}/{oid}}. Throws {@link RestException} (404-aware) on failure. */
    public void deleteObject(String restType, String oid) throws IOException {
        HttpRequest request = baseRequest(objectUri(restType, oid))
                .header("Accept", "application/xml")
                .DELETE()
                .build();
        send(request, "DELETE " + restType + "/" + oid);
    }

    /** {@code POST {type}} with a full object body (create). Returns the new OID from Location if present. */
    public String createObject(String restType, String objectXml) throws IOException {
        HttpRequest request = baseRequest(restCollectionUri(restType))
                .header("Content-Type", "application/xml")
                .header("Accept", "application/xml")
                .POST(HttpRequest.BodyPublishers.ofString(objectXml, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = sendRaw(request, "POST " + restType);
        return oidFromLocation(response);
    }

    // --- internals ---

    private HttpRequest.Builder baseRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", authHeader);
    }

    private String send(HttpRequest request, String what) throws IOException {
        return sendRaw(request, what).body();
    }

    private HttpResponse<String> sendRaw(HttpRequest request, String what) throws IOException {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(what + " was interrupted", e);
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new RestException(status, what + " failed: HTTP " + status + shortBody(response.body()));
        }
        return response;
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

    private static String shortBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String trimmed = body.strip();
        return "\n" + (trimmed.length() > 800 ? trimmed.substring(0, 800) + " …" : trimmed);
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
