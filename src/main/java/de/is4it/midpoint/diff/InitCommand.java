/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code init} subcommand: enable delete detection for a server by creating midgie's state object.
 *
 * <p>{@code deploy} never creates it — this is the one deliberate step that opts a server in, so midgie
 * cannot leave an unexplained object behind. The created {@code midgie-state} lookup table describes and
 * protects itself (see {@link SyncState}); running {@code init} again reports what is already there and
 * changes nothing.
 *
 * <p>Given a PATH, it also prints the deployment identity derived for that tree — the repository's root
 * commit plus the tree's path inside it — which is the key its state row will use.
 *
 * <p>Exit codes: {@code 0} created or already present, {@code 2} error.
 */
@Command(
        name = "init",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Create midgie's state object on the server, enabling delete detection for deploy."
)
public class InitCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(InitCommand.class);

    private static final int EXIT_ERROR = 2;

    @Parameters(index = "0", arity = "0..1", paramLabel = "PATH",
            description = "Desired-state tree whose deployment identity should be reported (optional).")
    File path;

    @Mixin
    ConnectionOptions connectionOptions;

    @Mixin
    SchemaOptions schemaOptions;

    @Option(names = {"--quiet", "-q"},
            description = "Suppress status output; keep errors and the exit code.")
    boolean quiet;

    @Option(names = {"--debug", "-v"},
            description = "Log a full DEBUG trace on stderr (this tool's own decisions plus framework logs) for troubleshooting.")
    boolean debug;

    @Option(names = "--trace",
            description = "Even more than --debug: also log REST request/response bodies and full HTTP error "
                    + "bodies (may include sensitive values). Implies --debug.")
    boolean trace;

    @Override
    public Integer call() {
        if (trace) {
            Logging.enableTrace();
        } else if (debug) {
            Logging.enableDebug();
        }
        if (!connectionOptions.isLive()) {
            System.err.println("midgie: init needs a running server — pass --url (or set MIDPOINT_URL).");
            return EXIT_ERROR;
        }
        MidpointRestClient client = connectionOptions.newClient();
        if (client == null) {
            return EXIT_ERROR;
        }

        try {
            PrismContext prismContext = schemaOptions.buildContext(client);
            if (!quiet) {
                VersionSupport.warnIfMismatch(prismContext, client);
            }

            PrismObject<? extends Objectable> table = SyncState.find(prismContext, client);
            if (table != null) {
                status(SyncState.TABLE_NAME + " already exists (" + table.getOid() + "); nothing to do.");
                String lifecycle = SyncState.lifecycleOf(table);
                if (!SyncState.isEnabled(table)) {
                    status("Note: its lifecycleState is '" + lifecycle
                            + "', so midgie treats tracking as disabled. Set it to 'active' to re-enable.");
                }
            } else {
                String oid = SyncState.create(prismContext, client);
                status("Created " + SyncState.TABLE_NAME + (oid != null ? " (" + oid + ")" : "")
                        + " — deploy will now record what it applies and report removals.");
            }

            reportIdentity();
            return 0;
        } catch (Exception e) {
            String message = (e.getMessage() != null) ? e.getMessage() : e.toString();
            System.err.println("midgie: " + message);
            LOG.debug("init failed", e);
            return EXIT_ERROR;
        }
    }

    /** Print the identity the given tree will be tracked under, or why it cannot be tracked. */
    private void reportIdentity() {
        if (path == null) {
            return;
        }
        if (!path.exists()) {
            System.err.println("midgie: warning: " + path + " does not exist; skipping the identity report.");
            return;
        }
        DeploymentIdentity identity = DeploymentIdentity.of(path, null);
        if (identity.isAvailable()) {
            status("Tree " + path + " will be tracked as '" + identity.key() + "' (" + identity.label() + ").");
        } else {
            status("Tree " + path + " cannot be tracked: " + identity.unavailableReason());
        }
    }

    private void status(String message) {
        if (!quiet) {
            System.out.println(message);
        }
    }
}
