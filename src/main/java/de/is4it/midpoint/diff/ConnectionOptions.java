/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine.Option;

/**
 * Shared live-mode connection flags for the {@code diff} and {@code apply} subcommands. When a URL is
 * set the command talks to a running midPoint over REST instead of reading a local CURRENT file.
 *
 * <p>URL/user/password default from the {@code MIDPOINT_URL} / {@code MIDPOINT_USER} /
 * {@code MIDPOINT_PASSWORD} environment variables. The password is resolved without putting it on the
 * command line (which leaks into {@code ps} and shell history), in this order: {@code --password} (kept,
 * but discouraged) → {@code --password-env VAR} → {@code --password-file FILE} → {@code MIDPOINT_PASSWORD}
 * → interactive prompt.
 */
public final class ConnectionOptions {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectionOptions.class);

    @Option(names = "--url", paramLabel = "URL",
            description = "midPoint base URL for live mode (or the MIDPOINT_URL env var), e.g. "
                    + "http://localhost:12080/midpoint. A trailing /ws/rest is accepted.")
    URI urlOption;

    @Option(names = {"--user", "-u"}, paramLabel = "USER", description = "REST user (or the MIDPOINT_USER env var).")
    String userOption;

    @Option(names = {"--password", "-p"}, paramLabel = "PASSWORD",
            description = "REST password. INSECURE: visible in ps/shell history — prefer --password-env, "
                    + "--password-file, MIDPOINT_PASSWORD, or the interactive prompt.")
    String passwordOption;

    @Option(names = "--password-env", paramLabel = "VAR",
            description = "Read the password from environment variable VAR.")
    String passwordEnv;

    @Option(names = "--password-file", paramLabel = "FILE",
            description = "Read the password from FILE (trailing newline trimmed).")
    File passwordFile;

    @Option(names = "--oid", paramLabel = "OID",
            description = "OID of the object to fetch/patch. Defaults to the oid in the desired object.")
    String oid;

    @Option(names = "--insecure",
            description = "Trust any TLS certificate and skip hostname verification (self-signed https servers).")
    boolean insecure;

    @Option(names = "--cacert", paramLabel = "FILE",
            description = "Verify the server's TLS certificate against this CA certificate (PEM).")
    File caCert;

    @Option(names = "--timeout", paramLabel = "SECONDS", defaultValue = "30",
            description = "Per-request timeout in seconds (default 30).")
    int timeoutSeconds;

    @Option(names = "--retries", paramLabel = "N", defaultValue = "0",
            description = "Retry idempotent reads on 503/connection-refused/timeout, N times with backoff "
                    + "(useful while the server is starting).")
    int retries;

    /** The resolved base URL: {@code --url} if given, else {@code MIDPOINT_URL}. Null when neither is set. */
    /** The per-request timeout, also used for the TLS handshake in {@code init --write-cacert}. */
    java.time.Duration timeout() {
        return java.time.Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    public URI url() {
        URI raw = rawUrl();
        if (raw == null || raw.getRawUserInfo() == null) {
            return raw;
        }
        // A password in the URL never authenticates anything — the Basic header is always built from
        // --user and the resolved password — but it would be printed by the connection/timeout errors,
        // the debug log and the confirmation prompts. Drop it before it can reach any of them.
        if (!warnedAboutUserInfo) {
            warnedAboutUserInfo = true;
            System.err.println("midgie: warning: ignoring the credentials embedded in the server URL; "
                    + "pass them with --user and --password-file/--password-env instead.");
        }
        return MidpointRestClient.withoutUserInfo(raw);
    }

    private URI rawUrl() {
        if (urlOption != null) {
            return urlOption;
        }
        String env = System.getenv("MIDPOINT_URL");
        return (env != null && !env.isBlank()) ? URI.create(env.trim()) : null;
    }

    private boolean warnedAboutUserInfo;

    /** True only when {@code --url} was passed on the command line (not merely inherited from the env). */
    public boolean isExplicitUrl() {
        return urlOption != null;
    }

    /** True when live mode is available (a URL was given on the CLI or via MIDPOINT_URL). */
    public boolean isLive() {
        return url() != null;
    }

    private String user() {
        if (userOption != null) {
            return userOption;
        }
        String env = System.getenv("MIDPOINT_USER");
        return (env != null && !env.isBlank()) ? env : null;
    }

    /** Build a REST client, or print an error and return {@code null} if the connection args are invalid. */
    public MidpointRestClient newClient() {
        if (insecure && caCert != null) {
            System.err.println("midgie: use either --insecure or --cacert, not both.");
            return null;
        }
        String user = user();
        if (user == null) {
            System.err.println("midgie: live mode needs a user (--user or MIDPOINT_USER).");
            return null;
        }
        String password = resolvePassword();
        if (password == null) {
            return null; // resolvePassword() already explained why
        }
        warnIfCleartextOverTheNetwork();
        LOG.debug("live mode: url={}, user={}, insecure={}, cacert={}, timeout={}s, retries={}",
                url(), user, insecure, caCert, timeoutSeconds, retries);
        MidpointRestClient.Config config = new MidpointRestClient.Config()
                .insecure(insecure)
                .caCert(caCert)
                .requestTimeout(timeout())
                .maxRetries(retries);
        return new MidpointRestClient(url(), user, password, config);
    }

    /**
     * Say so when Basic credentials are about to travel unencrypted to another host. The rig and every
     * example in the docs use {@code http://…:12080/midpoint}, so copying that shape at a customer is an
     * easy way to put the midPoint administrator password on the wire in the clear.
     */
    private void warnIfCleartextOverTheNetwork() {
        URI url = url();
        if (url == null || !"http".equalsIgnoreCase(url.getScheme())) {
            return;
        }
        String host = url.getHost();
        boolean loopback = host == null || "localhost".equals(host) || host.startsWith("127.")
                || "::1".equals(host) || "[::1]".equals(host);
        if (!loopback) {
            System.err.println("midgie: warning: " + url.getScheme() + ":// to " + host
                    + " sends the password unencrypted; use https for anything but a local rig.");
        }
    }

    /** Resolve the password from the most-explicit source available, or {@code null} (with an error). */
    String resolvePassword() {
        if (passwordOption != null) {
            LOG.debug("password source: --password");
            return passwordOption;
        }
        if (passwordEnv != null) {
            String value = System.getenv(passwordEnv);
            if (value == null) {
                System.err.println("midgie: --password-env " + passwordEnv + " is not set in the environment.");
                return null;
            }
            LOG.debug("password source: --password-env {}", passwordEnv);
            return value;
        }
        if (passwordFile != null) {
            try {
                String password = Files.readString(passwordFile.toPath(), StandardCharsets.UTF_8).stripTrailing();
                if (password.isEmpty()) {
                    System.err.println("midgie: --password-file " + passwordFile + " is empty.");
                    return null;
                }
                LOG.debug("password source: --password-file {}", passwordFile);
                return password;
            } catch (IOException e) {
                // The exception's own message is the bare path, which this line already names — so without
                // Io's wording it read "cannot read --password-file /etc/mp.pw: /etc/mp.pw".
                System.err.println("midgie: "
                        + Io.cannotReadMessage("--password-file " + passwordFile, passwordFile, e));
                return null;
            }
        }
        // Blank means unset, as it does for MIDPOINT_URL. An empty variable is not a password, and taking
        // it as one turns "no password given" into an authentication failure against the server.
        String envPassword = System.getenv("MIDPOINT_PASSWORD");
        if (envPassword != null && !envPassword.isBlank()) {
            LOG.debug("password source: MIDPOINT_PASSWORD env var");
            return envPassword;
        }
        Console console = System.console();
        if (console != null) {
            char[] entered = console.readPassword("Password for %s at %s: ", user(), url());
            if (entered != null && entered.length > 0) {
                LOG.debug("password source: interactive prompt");
                return new String(entered);
            }
        }
        System.err.println("midgie: no password provided. Use --password-env, --password-file, "
                + "the MIDPOINT_PASSWORD env var, or run interactively.");
        return null;
    }
}
