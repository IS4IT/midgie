package net.haeger.midpoint.diff;

import java.net.URI;

import picocli.CommandLine.Option;

/**
 * Shared live-mode connection flags for the {@code diff} and {@code apply} subcommands. When
 * {@code --host} is set the command talks to a running midPoint over REST instead of reading a local
 * LIVE file. Mixed into each command with {@code @Mixin}.
 */
public final class ConnectionOptions {

    @Option(names = "--host", paramLabel = "URL",
            description = "midPoint base URL for live mode, e.g. http://localhost:12080/midpoint "
                    + "(a trailing /ws/rest is accepted). When set, the current object is fetched over REST.")
    URI host;

    @Option(names = {"--user", "-u"}, paramLabel = "USER", description = "REST user (live mode).")
    String user;

    @Option(names = {"--password", "-p"}, paramLabel = "PASSWORD", description = "REST password (live mode).")
    String password;

    @Option(names = "--oid", paramLabel = "OID",
            description = "OID of the object to fetch/patch. Defaults to the oid in the desired object.")
    String oid;

    @Option(names = "--insecure",
            description = "Trust any TLS certificate (for self-signed https midPoint servers).")
    boolean insecure;

    /** True when live mode is requested (a {@code --host} was given). */
    public boolean isLive() {
        return host != null;
    }

    /**
     * Build a REST client, or print an error and return {@code null} if credentials are missing.
     * Only call when {@link #isLive()}.
     */
    public MidpointRestClient newClient() {
        if (user == null || password == null) {
            System.err.println("midpoint-diff: --host requires --user and --password.");
            return null;
        }
        return new MidpointRestClient(host, user, password, insecure);
    }
}
