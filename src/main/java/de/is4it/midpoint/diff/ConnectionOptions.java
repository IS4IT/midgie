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
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.Spec;

/**
 * Shared live-mode connection flags for the {@code diff} and {@code apply} subcommands. When a URL is
 * set the command talks to a running midPoint over REST instead of reading a local CURRENT file.
 *
 * <p>URL/user/password default from the {@code MIDPOINT_URL} / {@code MIDPOINT_USER} /
 * {@code MIDPOINT_PASSWORD} environment variables. The password is resolved without putting it on the
 * command line (which leaks into {@code ps} and shell history), in this order: {@code --password} (kept,
 * but discouraged) → {@code --password-env VAR} → {@code --password-file FILE} → {@code MIDPOINT_PASSWORD}
 * → interactive prompt. Any of the three given on the command line outranks all three from a config file,
 * whichever spelling each uses.
 *
 * <p>The proxy flags walk the same ladder for {@code --proxy-password}, and {@link #proxy()} resolves the
 * proxy itself — see {@link ProxyConfig} for where it may come from and what happens when none is set.
 */
public final class ConnectionOptions {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectionOptions.class);

    private static final List<String> PASSWORD_OPTIONS = List.of("--password", "--password-env", "--password-file");
    private static final List<String> PROXY_PASSWORD_OPTIONS =
            List.of("--proxy-password", "--proxy-password-env", "--proxy-password-file");

    /** The command these options belong to, to tell a value given on its command line from a config file's. */
    @Spec(Spec.Target.MIXEE)
    CommandSpec mixee;

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

    @Option(names = "--proxy", paramLabel = "URL",
            description = "Forward proxy for the REST connection, e.g. http://proxy.corp:3128 or "
                    + "proxy.corp:3128 (or the https_proxy/http_proxy env var, picked by the scheme of the "
                    + "server URL). Without this, the JVM's own -Dhttps.proxyHost settings still apply.")
    String proxyOption;

    @Option(names = "--no-proxy", paramLabel = "HOSTS",
            description = "Comma-separated hosts that bypass the proxy (or the no_proxy env var). A leading "
                    + "dot matches subdomains only; '*' bypasses everything. Replaces the built-in loopback "
                    + "exemption rather than adding to it, so '' sends even localhost through the proxy.")
    String noProxyOption;

    @Option(names = "--proxy-user", paramLabel = "USER",
            description = "User for a proxy that demands authentication (Basic).")
    String proxyUserOption;

    @Option(names = "--proxy-password", paramLabel = "PASSWORD",
            description = "Proxy password. INSECURE: visible in ps/shell history — prefer "
                    + "--proxy-password-env, --proxy-password-file, or the interactive prompt.")
    String proxyPasswordOption;

    @Option(names = "--proxy-password-env", paramLabel = "VAR",
            description = "Read the proxy password from environment variable VAR.")
    String proxyPasswordEnv;

    @Option(names = "--proxy-password-file", paramLabel = "FILE",
            description = "Read the proxy password from FILE (trailing newline trimmed).")
    File proxyPasswordFile;

    @Option(names = "--timeout", paramLabel = "SECONDS", defaultValue = "30",
            description = "Per-request timeout in seconds (default 30).")
    int timeoutSeconds;

    @Option(names = "--retries", paramLabel = "N", defaultValue = "0",
            description = "Retry idempotent reads on 502/503/504/connection-refused/timeout, N times with backoff "
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

    /** The midPoint account this run uses, for the record of who deployed; {@code null} when none is set. */
    String account() {
        return user();
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
        boolean trustAll = insecure;
        File trustedCa = caCert;
        if (insecure && caCert != null) {
            // One from the command line and one from a config file: the command line decides, as it does for
            // every other option. Both from the same place is a contradiction to be settled by whoever wrote it.
            boolean insecureHere = onCommandLine("--insecure");
            boolean caCertHere = onCommandLine("--cacert");
            if (insecureHere == caCertHere) {
                System.err.println("midgie: use either --insecure or --cacert, not both"
                        + (insecureHere ? "." : " — the config file sets both."));
                return null;
            }
            if (caCertHere) {
                trustAll = false;
                System.err.println("midgie: note: ignoring insecure from the config file: --cacert on the command "
                        + "line verifies the server.");
            } else {
                trustedCa = null;
                System.err.println("midgie: note: ignoring cacert from the config file: --insecure was given on "
                        + "the command line.");
            }
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
        ProxyConfig proxy;
        try {
            proxy = proxy();
        } catch (IllegalArgumentException e) {
            System.err.println("midgie: " + e.getMessage());
            return null;
        }
        warnIfCleartextOverTheNetwork();
        LOG.debug("live mode: url={}, user={}, insecure={}, cacert={}, proxy={}, timeout={}s, retries={}",
                url(), user, trustAll, trustedCa, (proxy != null) ? proxy.describe() : "none",
                timeoutSeconds, retries);
        MidpointRestClient.Config config = new MidpointRestClient.Config()
                .insecure(trustAll)
                .caCert(trustedCa)
                .proxy(proxy)
                .requestTimeout(timeout())
                .maxRetries(retries);
        return new MidpointRestClient(url(), user, password, config);
    }

    /**
     * The proxy for the resolved URL: the flags, else the environment, else none.
     *
     * <p>Also used by {@code init --write-cacert}, which has to tunnel its own TLS handshake and so needs the
     * same answer this gives the REST client.
     *
     * @return the proxy, or {@code null} for a direct connection
     * @throws IllegalArgumentException if the proxy or its credentials are unusable; the message is meant to
     *         be printed as it stands, so there is one failure channel here rather than the print-and-return-
     *         null the account password uses
     */
    ProxyConfig proxy() {
        ProxyConfig proxy = ProxyConfig.resolve(proxyOption, noProxyOption, url(), System::getenv);
        boolean passwordGiven = proxyPasswordOption != null || proxyPasswordEnv != null
                || proxyPasswordFile != null;
        if (proxy == null) {
            if (proxyUserOption != null || passwordGiven) {
                throw new IllegalArgumentException("--proxy-user/--proxy-password need a proxy to "
                        + "authenticate to: pass --proxy, or set https_proxy/http_proxy. A proxy given as "
                        + "-Dhttps.proxyHost cannot carry credentials — java.net.http never reads them.");
            }
            return null;
        }
        if (passwordGiven && proxyUserOption == null && proxy.user() == null) {
            throw new IllegalArgumentException("a proxy password was given without a user; add --proxy-user.");
        }
        // An option always outranks the credentials an https_proxy URL happened to carry; a user with no
        // password anywhere still has to produce one, which is what the ladder (and the prompt) is for.
        String proxyUser = (proxyUserOption != null) ? proxyUserOption : proxy.user();
        if (proxyUser == null || (proxyUserOption == null && proxy.hasCredentials())) {
            return proxy;
        }
        return proxy.withCredentials(proxyUser, resolveProxyPassword(proxyUser, proxy));
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

    /**
     * A secret from a named environment variable.
     *
     * @throws IllegalArgumentException if the variable is not set; the caller decides how to report it
     */
    private static String fromEnvironment(String flag, String variable) {
        String value = System.getenv(variable);
        if (value == null) {
            throw new IllegalArgumentException(flag + " " + variable + " is not set in the environment.");
        }
        return value;
    }

    /**
     * A secret from a file, with the trailing newline an editor leaves behind trimmed off.
     *
     * <p>The failure goes through {@link Io}, so both passwords get the wording the account password gained
     * on its own: the exception's own message is the bare path, which the flag has already named, so a plain
     * append read "cannot read --password-file /etc/mp.pw: /etc/mp.pw". The case worth having is a mounted
     * Secret readable only to another UID, which is reported as the denial it is.
     *
     * @throws IllegalArgumentException if the file is empty or unreadable
     */
    private static String fromFile(String flag, File file) {
        try {
            String secret = Files.readString(file.toPath(), StandardCharsets.UTF_8).stripTrailing();
            if (secret.isEmpty()) {
                throw new IllegalArgumentException(flag + " " + file + " is empty.");
            }
            return secret;
        } catch (IOException e) {
            throw new IllegalArgumentException(Io.cannotReadMessage(flag + " " + file, file, e));
        }
    }

    /**
     * The proxy password, walking the same ladder as the account password minus the {@code MIDPOINT_*} rung —
     * the environment's way of carrying this one is the userinfo in {@code https_proxy}, which
     * {@link ProxyConfig} has already read by the time this runs.
     *
     * @throws IllegalArgumentException when no source produced one; the message is printable as it stands
     */
    private String resolveProxyPassword(String proxyUser, ProxyConfig proxy) {
        String option = firstGiven(PROXY_PASSWORD_OPTIONS);
        if (option != null) {
            LOG.debug("proxy password source: {}", describe(option));
            return secretFrom(option);
        }
        if (proxyUserOption == null && proxy.password() != null) {
            LOG.debug("proxy password source: the proxy URL from the environment");
            return proxy.password();
        }
        Console console = System.console();
        if (console != null) {
            char[] entered = console.readPassword("Proxy password for %s at %s: ", proxyUser, proxy.address());
            if (entered != null && entered.length > 0) {
                LOG.debug("proxy password source: interactive prompt");
                return new String(entered);
            }
        }
        throw new IllegalArgumentException("no proxy password provided. Use --proxy-password-env, "
                + "--proxy-password-file, credentials in the https_proxy URL, or run interactively.");
    }

    /**
     * The first of {@code options} that has a value, looking at the ones given on the command line before the
     * ones a config file supplied. A file's value arrives as the option's default, so without the split a
     * file's {@code password} outranked {@code --password-file} typed on the command line, simply because
     * {@code --password} is tried first.
     */
    private String firstGiven(List<String> options) {
        for (boolean onCommandLine : new boolean[] {true, false}) {
            for (String option : options) {
                if (valueOf(option) != null && onCommandLine(option) == onCommandLine) {
                    return option;
                }
            }
        }
        return null;
    }

    /** Whether {@code option} was given on the command line rather than supplied by a config file. */
    private boolean onCommandLine(String option) {
        ParseResult parsed = (mixee != null) ? mixee.commandLine().getParseResult() : null;
        return parsed != null && parsed.hasMatchedOption(option);
    }

    private Object valueOf(String option) {
        return switch (option) {
            case "--password" -> passwordOption;
            case "--password-env" -> passwordEnv;
            case "--password-file" -> passwordFile;
            case "--proxy-password" -> proxyPasswordOption;
            case "--proxy-password-env" -> proxyPasswordEnv;
            case "--proxy-password-file" -> proxyPasswordFile;
            default -> throw new IllegalArgumentException(option);
        };
    }

    /**
     * The secret {@code option} names: the value itself, or read from the variable or file it names.
     *
     * @throws IllegalArgumentException when the variable is unset or the file unusable; printable as it stands
     */
    private String secretFrom(String option) {
        Object value = valueOf(option);
        if (option.endsWith("-env")) {
            return fromEnvironment(option, (String) value);
        }
        if (option.endsWith("-file")) {
            return fromFile(option, (File) value);
        }
        return (String) value;
    }

    /** The option and where it came from, for the debug log. */
    private String describe(String option) {
        Object value = valueOf(option);
        String shown = option.endsWith("-env") || option.endsWith("-file") ? option + " " + value : option;
        return onCommandLine(option) ? shown : shown + " (config file)";
    }

    /** Resolve the password from the most-explicit source available, or {@code null} (with an error). */
    String resolvePassword() {
        String option = firstGiven(PASSWORD_OPTIONS);
        if (option != null) {
            try {
                String password = secretFrom(option);
                LOG.debug("password source: {}", describe(option));
                return password;
            } catch (IllegalArgumentException e) {
                System.err.println("midgie: " + e.getMessage());
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
