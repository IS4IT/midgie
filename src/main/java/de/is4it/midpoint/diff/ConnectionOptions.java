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

import picocli.CommandLine.Option;

/**
 * Shared live-mode connection flags for the {@code diff} and {@code apply} subcommands. When a host is
 * set the command talks to a running midPoint over REST instead of reading a local LIVE file.
 *
 * <p>Host/user/password default from the {@code MIDPOINT_URL} / {@code MIDPOINT_USER} /
 * {@code MIDPOINT_PASSWORD} environment variables. The password is resolved without putting it on the
 * command line (which leaks into {@code ps} and shell history), in this order: {@code --password} (kept,
 * but discouraged) → {@code --password-env VAR} → {@code --password-file FILE} → {@code MIDPOINT_PASSWORD}
 * → interactive prompt.
 */
public final class ConnectionOptions {

    @Option(names = "--host", paramLabel = "URL",
            description = "midPoint base URL for live mode (or the MIDPOINT_URL env var), e.g. "
                    + "http://localhost:12080/midpoint. A trailing /ws/rest is accepted.")
    URI hostOption;

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
            description = "Trust any TLS certificate (for self-signed https midPoint servers).")
    boolean insecure;

    /** The resolved host: {@code --host} if given, else {@code MIDPOINT_URL}. Null when neither is set. */
    public URI host() {
        if (hostOption != null) {
            return hostOption;
        }
        String env = System.getenv("MIDPOINT_URL");
        return (env != null && !env.isBlank()) ? URI.create(env.trim()) : null;
    }

    /** True only when {@code --host} was passed on the command line (not merely inherited from the env). */
    public boolean isExplicitHost() {
        return hostOption != null;
    }

    /** True when live mode is available (a host was given on the CLI or via MIDPOINT_URL). */
    public boolean isLive() {
        return host() != null;
    }

    private String user() {
        return (userOption != null) ? userOption : System.getenv("MIDPOINT_USER");
    }

    /** Build a REST client, or print an error and return {@code null} if user/password can't be resolved. */
    public MidpointRestClient newClient() {
        String user = user();
        if (user == null) {
            System.err.println("midpoint-diff: live mode needs a user (--user or MIDPOINT_USER).");
            return null;
        }
        String password = resolvePassword();
        if (password == null) {
            return null; // resolvePassword() already explained why
        }
        return new MidpointRestClient(host(), user, password, insecure);
    }

    /** Resolve the password from the most-explicit source available, or {@code null} (with an error). */
    String resolvePassword() {
        if (passwordOption != null) {
            return passwordOption;
        }
        if (passwordEnv != null) {
            String value = System.getenv(passwordEnv);
            if (value == null) {
                System.err.println("midpoint-diff: --password-env " + passwordEnv + " is not set in the environment.");
                return null;
            }
            return value;
        }
        if (passwordFile != null) {
            try {
                String password = Files.readString(passwordFile.toPath(), StandardCharsets.UTF_8).stripTrailing();
                if (password.isEmpty()) {
                    System.err.println("midpoint-diff: --password-file " + passwordFile + " is empty.");
                    return null;
                }
                return password;
            } catch (IOException e) {
                System.err.println("midpoint-diff: cannot read --password-file " + passwordFile + ": " + e.getMessage());
                return null;
            }
        }
        String envPassword = System.getenv("MIDPOINT_PASSWORD");
        if (envPassword != null) {
            return envPassword;
        }
        Console console = System.console();
        if (console != null) {
            char[] entered = console.readPassword("Password for %s at %s: ", user(), host());
            if (entered != null && entered.length > 0) {
                return new String(entered);
            }
        }
        System.err.println("midpoint-diff: no password provided. Use --password-env, --password-file, "
                + "the MIDPOINT_PASSWORD env var, or run interactively.");
        return null;
    }
}
