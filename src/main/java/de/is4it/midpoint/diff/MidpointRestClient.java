/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.net.Authenticator;
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

    /**
     * Whether Basic authentication to a proxy has been enabled for this process, or {@code null} while
     * undecided.
     *
     * <p>The same shape as {@link #hostnameVerificationDisabled}, and for the same reason. Since 8u111 the JDK
     * refuses Basic on a {@code CONNECT} tunnel unless {@code jdk.http.auth.tunneling.disabledSchemes} says
     * otherwise, and {@code java.net.http} reads that property once, into a {@code static final}, on first
     * use. Left to whichever client is built first, an authenticating proxy would work or not by accident of
     * call order — and the failure is opaque: the credentials are simply never sent, and the run ends in the
     * proxy's 407 or, once the JDK has retried, "too many authentication attempts".
     */
    private static Boolean proxyTunnelAuthEnabled;

    /**
     * Allow Basic on a {@code CONNECT} tunnel for this process. Called from {@code Main} before any client
     * exists, and only for a run that configured proxy credentials — the JDK default is a defence against
     * handing a password to a proxy that asked for it unprompted, and it stays in place for everyone else.
     */
    static synchronized void latchProxyTunnelAuth(boolean enabled) {
        if (proxyTunnelAuthEnabled != null) {
            return;
        }
        proxyTunnelAuthEnabled = enabled;
        if (enabled) {
            // Empty, not "none": the property is a list of schemes to disable, and an empty list disables none.
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        }
        LOG.debug("proxy authentication over a CONNECT tunnel {} for this process",
                enabled ? "enabled" : "left at the JDK default");
    }

    /**
     * Whether Basic is still barred from {@code CONNECT} tunnels.
     *
     * <p>Read from the property rather than from {@link #proxyTunnelAuthEnabled}, so that a value set any
     * other way — {@code JAVA_OPTS}, a wrapper script, the test harness — counts as much as midgie's own
     * latch. Unset means the JDK's own default from {@code net.properties}, which disables Basic.
     */
    private static boolean basicDisabledOnTunnels() {
        String disabled = System.getProperty("jdk.http.auth.tunneling.disabledSchemes");
        return disabled == null || disabled.toLowerCase(java.util.Locale.ROOT).contains("basic");
    }

    /** Timeout / retry / TLS / proxy knobs for the client (fluent setters; sensible defaults). */
    public static final class Config {
        private boolean insecure;
        private File caCert;
        private ProxyConfig proxy;
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

        /**
         * The forward proxy, or {@code null} to leave the JDK's default {@link java.net.ProxySelector} in
         * place — which is what keeps a {@code -Dhttps.proxyHost} passed through {@code JAVA_OPTS} working.
         */
        public Config proxy(ProxyConfig value) {
            this.proxy = value;
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
    private final ProxyConfig proxy;
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
        this.proxy = config.proxy;

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
        if (config.proxy != null) {
            builder.proxy(config.proxy.selector());
            Authenticator authenticator = config.proxy.authenticator();
            if (authenticator != null) {
                builder.authenticator(authenticator);
                if (basicDisabledOnTunnels()) {
                    // Only https targets tunnel, so this is a warning rather than a refusal: a plain-http
                    // server behind the same proxy authenticates fine either way.
                    System.err.println("midgie: warning: proxy credentials cannot be sent over a CONNECT "
                            + "tunnel in this process — jdk.http.auth.tunneling.disabledSchemes still "
                            + "disables Basic, so an https server behind the proxy will be refused with 407.");
                }
            }
        }
        this.httpClient = builder.build();
        LOG.debug("REST client ready: base={}, requestTimeout={}s, maxRetries={}, tls={}, proxy={}",
                restBase, requestTimeout.toSeconds(), maxRetries,
                config.insecure ? "insecure (trust-all)"
                        : (config.caCert != null ? "cacert " + config.caCert : "default"),
                (config.proxy != null) ? config.proxy.describe() : "none");
    }

    /**
     * The REST base this client talks to, with any credentials stripped. Recorded in a rollback plan so a
     * replay can refuse to run against a different server than the one the plan was written from.
     */
    public URI host() {
        return withoutUserInfo(restBase);
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

    /** {@code GET nodes/current}: the node answering this request, which carries the server's build version. */
    public String getCurrentNode() throws IOException {
        HttpRequest request = baseRequest(URI.create(restCollectionUri("nodes") + "/current"))
                .header("Accept", "application/xml")
                .GET()
                .build();
        return send(request, "GET nodes/current", null, true);
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
        patchObject(restType, oid, modificationXml, false);
    }

    /**
     * {@code PATCH {type}/{oid}}, optionally asking midPoint to re-resolve the search filters carried by the
     * delta ({@code ?options=reevaluateSearchFilters}).
     *
     * <p>Needed whenever the delta sets a reference written as a filter rather than an OID. A modify does
     * <em>not</em> resolve such a filter on its own — that happens at import — so without the option the
     * reference is stored unresolved and the object is left pointing at nothing. With it, midPoint resolves
     * the filter and fails the whole modify with its own message if the filter matches no object or several.
     */
    public void patchObject(String restType, String oid, String modificationXml,
            boolean reevaluateSearchFilters) throws IOException {
        URI uri = objectUri(restType, oid);
        if (reevaluateSearchFilters) {
            uri = URI.create(uri + "?options=reevaluateSearchFilters");
        }
        HttpRequest request = baseRequest(uri)
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
                    LOG.trace("→ {} request body:\n{}", what, masked(requestBody).stripTrailing());
                }
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                String responseBody = response.body();
                LOG.debug("← {}  HTTP {} ({} byte body)", what, status, bodyLength(responseBody));
                if (responseBody != null && !responseBody.isEmpty()) {
                    LOG.trace("← {} response body:\n{}", what, masked(responseBody).stripTrailing());
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
                checkMidpointResultStatus(status, what, responseBody, safeRead);
                return response;
            } catch (java.net.ConnectException | java.net.http.HttpTimeoutException e) {
                LOG.debug("{}  transport error: {}", what, e.toString());
                if (retryable && attempt < maxRetries) {
                    LOG.debug("{}  retry {} of {} after transport error", what, attempt + 1, maxRetries);
                    backoff(attempt++);
                    continue;
                }
                if (e instanceof java.net.ConnectException) {
                    if (isUnresolvedHost(e)) {
                        // The JDK reports a DNS failure as a ConnectException whose own message is null,
                        // with UnresolvedAddressException two levels down. Read as "cannot connect … null",
                        // that sends the operator to check the server and the URL, when neither was ever
                        // contacted: the name did not resolve where midgie is running, which in a container
                        // is a different resolver from the shell that started it.
                        throw new TransportException(what + " — cannot resolve host '" + restBase.getHost()
                                + "'. Check the name, and the DNS of the machine or container midgie runs "
                                + "in — a container does not inherit the resolver of the shell that started "
                                + "it.", e);
                    }
                    throw new TransportException(what + " — cannot connect to " + restBase
                            + " (is the server up and the URL correct?" + detail(e) + ")", e);
                }
                throw new TransportException(what + " — timed out talking to " + restBase
                        + " (" + e.getMessage() + ")", e);
            } catch (java.net.UnknownHostException e) {
                // Not every path wraps a DNS failure in ConnectException; this one says so outright.
                LOG.debug("{}  cannot resolve host: {}", what, e.toString());
                throw new TransportException(what + " — cannot resolve host '" + restBase.getHost()
                        + "'. Check the name, and the DNS of the machine or container midgie runs in — a "
                        + "container does not inherit the resolver of the shell that started it.", e);
            } catch (javax.net.ssl.SSLException e) {
                // The commonest TLS misconfiguration is a private CA with no --cacert. The raw JDK message
                // ("PKIX path building failed ...") names neither the server nor the flags that fix it.
                LOG.debug("{}  TLS error: {}", what, e.toString());
                throw new TransportException(what + " — TLS handshake with " + restBase + " failed ("
                        + e.getMessage() + "). Pass --cacert <FILE> for a private CA, or --insecure for a "
                        + "self-signed development server.", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(what + " was interrupted", e);
            } catch (IOException e) {
                if (e instanceof RestException || e instanceof TransportException) {
                    throw e;   // raised above, and already carrying its own explanation
                }
                // Credentials a proxy rejects never come back as a status. The JDK re-sends the CONNECT until
                // it gives up, and what surfaces is "too many authentication attempts" (up to JDK 25) or an
                // internal exchange failure (JDK 26) — neither of which mentions the proxy at all.
                if (proxy != null && proxy.hasCredentials()
                        && isProxyAuthFailure(e, "https".equalsIgnoreCase(request.uri().getScheme()))) {
                    LOG.debug("{}  proxy authentication failed: {}", what, e.toString());
                    throw new TransportException(what + " — the proxy at " + proxy.address()
                            + " rejected the credentials for user '" + proxy.user() + "'.", e);
                }
                // A connection dropped part way ("Connection reset", "received no bytes") is as passing as a
                // refused one for a read, and was never retried; nor did it say which request it ended.
                LOG.debug("{}  transport error: {}", what, e.toString());
                if (retryable && attempt < maxRetries) {
                    LOG.debug("{}  retry {} of {} after transport error", what, attempt + 1, maxRetries);
                    backoff(attempt++);
                    continue;
                }
                throw new TransportException(what + " — the connection to " + restBase + " failed ("
                        + ((e.getMessage() != null) ? e.getMessage() : e.toString()) + ")", e);
            }
        }
    }

    /**
     * midPoint signals an imperfect operation with its own 2xx codes, which a plain "is it 2xx" test reads as
     * plain success: {@code 250} = partial error, {@code 240} = handled error
     * ({@code AbstractRestController.createResponse}). The body is then either the payload or the
     * {@code OperationResult}, depending on the endpoint.
     *
     * <p>A <b>write</b> that comes back this way did not fully succeed, and reporting it as applied is the
     * worst outcome available, so it fails. A <b>read</b> is different: a resource whose schema midPoint
     * cannot complete answers 250 to every GET while remaining perfectly deployable — refusing it would make
     * a half-configured resource impossible to reconcile — so it warns and carries on. Verified against a
     * live server: that resource's GET and search return 250 while its PATCH returns 204.
     *
     * <p>When the body is the {@code OperationResult} rather than the payload there is nothing to work with,
     * so that fails too even for a read.
     */
    private static void checkMidpointResultStatus(int status, String what, String body, boolean safeRead)
            throws RestException {
        if (status != 240 && status != 250) {
            return;
        }
        String kind = (status == 250) ? "partial" : "handled";
        String reason = midpointResultMessage(body);
        if (!safeRead || isOperationResultBody(body)) {
            throw new RestException(status, what + " — midPoint reported a " + kind + " error (HTTP " + status
                    + ")" + (reason.isEmpty() ? "." : ": " + reason));
        }
        System.err.println("midgie: warning: " + what + " — midPoint reported a " + kind + " error (HTTP "
                + status + ")" + (reason.isEmpty() ? "" : ": " + reason)
                + ". The object may be incomplete; treat a full-scope diff of it with care.");
    }

    /**
     * Whether the response carries an {@code OperationResult} instead of the payload. Read off the root
     * element's declared type: this class deliberately holds no prism context, and the discriminator is the
     * envelope midPoint chose, not a guess at content.
     */
    private static boolean isOperationResultBody(String body) {
        if (body == null) {
            return false;
        }
        java.util.regex.Matcher root = java.util.regex.Pattern
                .compile("xsi:type=\"[^\"]*OperationResultType\"").matcher(body);
        return root.find() && root.start() < 2000;
    }

    /** midPoint's own explanation, when the body carries one. */
    private static String midpointResultMessage(String body) {
        if (body == null) {
            return "";
        }
        java.util.regex.Matcher message = java.util.regex.Pattern
                .compile("<message>(.*?)</message>", java.util.regex.Pattern.DOTALL).matcher(body);
        return message.find() ? message.group(1).strip() : "";
    }

    /**
     * Whether a failure is really "that name does not resolve". The JDK's HTTP client reports it as a
     * {@link java.net.ConnectException} carrying no message of its own, with the real cause — an
     * {@link java.nio.channels.UnresolvedAddressException} or {@link java.net.UnknownHostException} — further
     * down the chain, so only the chain can tell it apart from a refused connection.
     */
    private static boolean isUnresolvedHost(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.nio.channels.UnresolvedAddressException
                    || cause instanceof java.net.UnknownHostException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;   // a self-referencing cause would loop forever
            }
        }
        return false;
    }

    /** An exception's message as a trailing detail, or nothing at all — {@code null} is not a diagnosis. */
    private static String detail(Throwable error) {
        String message = error.getMessage();
        return (message == null || message.isBlank()) ? "" : " " + message;
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
    private String statusHint(int status) {
        switch (status) {
            case 401:
                return " — authentication failed (check the user/password)";
            case 403:
                return " — not authorized (the user lacks permission for this operation)";
            case 404:
                return " — not found";
            case 407:
                // 407 is the proxy talking, not midPoint, and the account credentials are not what it wants.
                return (proxy != null && proxy.hasCredentials())
                        ? " — the proxy at " + proxy.address() + " rejected the credentials for user '"
                                + proxy.user() + "'"
                        : " — the proxy demands authentication; pass --proxy-user with "
                                + "--proxy-password-env/--proxy-password-file";
            default:
                return status >= 500 ? " — server error" : "";
        }
    }

    /**
     * Whether this transport failure is the JDK giving up on proxy authentication.
     *
     * <p>Matched on the message because the JDK offers nothing else: no dedicated exception type, no status.
     * The two texts differ between JDK versions, so both are recognised, and a miss only costs the nicer
     * message — the original exception is rethrown either way. The JDK 26 one is a null {@code exchImpl}
     * inside the client, which other internal failures can produce too, so it counts only for a tunnelled
     * (https) request and only as the null-pointer failure it is; any message mentioning it used to.
     */
    static boolean isProxyAuthFailure(IOException e, boolean tunnelled) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null) {
                continue;
            }
            if (message.contains("too many authentication attempts") || message.contains("Proxy-Authorization")) {
                return true;
            }
            if (tunnelled && cause instanceof NullPointerException && message.contains("exchImpl")) {
                return true;
            }
        }
        return false;
    }

    private URI objectUri(String restType, String oid) {
        return URI.create(restCollectionUri(restType) + "/" + requireOid(oid));
    }

    private URI restCollectionUri(String restType) {
        if (restType == null || !REST_TYPE.matcher(restType).matches()) {
            throw new IllegalArgumentException("not a midPoint REST collection: '" + restType + "'");
        }
        return URI.create(restBase + "/" + restType);
    }

    private static final java.util.regex.Pattern REST_TYPE = java.util.regex.Pattern.compile("[A-Za-z]+");
    private static final java.util.regex.Pattern OID =
            java.util.regex.Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** Whether {@code oid} is one midPoint's repository can hold: a UUID. */
    static boolean isOid(String oid) {
        return oid != null && OID.matcher(oid).matches();
    }

    /**
     * {@code oid}, if it is a UUID — the only form midPoint 4.10's native repository stores.
     *
     * <p>Every object address is built by concatenation, and an OID arrives from a tree file, {@code --oid}, or a
     * plan or rollback file anyone may have edited. Unchecked, {@code ../roles/x?options=raw} addressed another
     * collection and added a query option to a PATCH or DELETE, and an OID with a space failed with a bare
     * "Illegal character in path". A runtime exception, so it fails the one object rather than the run.
     */
    static String requireOid(String oid) {
        if (!isOid(oid)) {
            throw new IllegalArgumentException("'" + oid + "' is not a midPoint OID — midPoint stores objects "
                    + "under UUIDs, e.g. 00000000-0000-0000-0000-000000000002");
        }
        return oid;
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
        String trimmed = masked(body).strip();
        return "\n" + (trimmed.length() > 800 ? trimmed.substring(0, 800) + " …" : trimmed);
    }

    /** The elements of a protected value that carry the secret itself, in any namespace prefix. */
    private static final java.util.regex.Pattern SECRET_ELEMENT = java.util.regex.Pattern.compile(
            "(<(?:[\\w.-]+:)?(clearValue|cipherValue|digestValue)\\b[^>]*>)(.*?)(</(?:[\\w.-]+:)?\\2\\s*>)",
            java.util.regex.Pattern.DOTALL);

    /**
     * {@code body} with the content of every {@code clearValue}, {@code cipherValue} and {@code digestValue}
     * replaced by {@code ***}. A PATCH or a create carries a desired password as {@code clearValue}, and a
     * server's object its encrypted or hashed form; {@code --trace} printed both into whatever log the run went
     * to, and so did an error body that echoed the request. Other values a file keeps in plain text are not
     * recognisable here, which is why {@code --trace} still says it may show sensitive values.
     */
    static String masked(String body) {
        return (body == null) ? null : SECRET_ELEMENT.matcher(body).replaceAll("$1***$4");
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
