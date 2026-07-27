/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @Option(names = "--write-policy", paramLabel = "FILE",
            description = "Also write a starter --policy file for PATH, listing the objects whose multi-valued "
                    + "containers have a schema natural key. Refuses to overwrite an existing file.")
    File writePolicy;

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
            if (writePolicy != null) {
                return seedPolicy(prismContext);
            }
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

    /**
     * Write a starter policy file for the tree: one line per object whose fragments declare a multi-valued
     * container that the schema gives a natural key, since those are exactly the places where
     * {@code natural-keys} + {@code merge} lets individual entries be managed instead of the whole list being
     * replaced. Comments name the containers that prompted each line, so the file explains itself.
     */
    private Integer seedPolicy(PrismContext prismContext) throws Exception {
        if (path == null) {
            System.err.println("midgie: --write-policy needs a PATH to scan.");
            return EXIT_ERROR;
        }
        if (writePolicy.exists()) {
            System.err.println("midgie: " + writePolicy + " already exists; refusing to overwrite it.");
            return EXIT_ERROR;
        }

        Discovery.ScanResult scan = Discovery.scan(prismContext, List.of(path));
        Map<String, Map<String, String>> keyedByObject = new LinkedHashMap<>();
        for (File file : scan.objectFiles()) {
            PrismObject<? extends Objectable> object;
            try {
                object = prismContext.parseObject(file);
            } catch (Exception e) {
                LOG.debug("skipping {} while seeding the policy", file, e);
                continue;
            }
            if (object.getCompileTimeClass() == null) {
                continue;
            }
            Map<String, String> keyed = NaturalKeys.keyedContainers(object);
            if (keyed.isEmpty()) {
                continue;
            }
            String selector = object.getCompileTimeClass().getSimpleName() + "/"
                    + (object.getName() != null ? object.getName().getOrig() : "?");
            keyedByObject.computeIfAbsent(selector, key -> new LinkedHashMap<>()).putAll(keyed);
        }

        StringBuilder out = new StringBuilder();
        out.append("# midgie policy, seeded by 'midgie init --write-policy' from ").append(path).append("\n#\n")
                .append("# Every object below declares a multi-valued container that the midPoint schema gives a\n")
                .append("# natural key, so its entries can be matched individually. With natural-keys + merge,\n")
                .append("# midgie updates just the entries your files list and leaves the others alone; under the\n")
                .append("# default (replace) it would delete every entry you did not list.\n")
                .append("#\n# These are suggestions — review them, and delete any line whose object your files are\n")
                .append("# meant to be authoritative for.\n");
        if (keyedByObject.isEmpty()) {
            out.append("\n# Nothing in this tree addresses a keyed multi-valued container, so no rules are needed.\n");
        }
        for (Map.Entry<String, Map<String, String>> entry : keyedByObject.entrySet()) {
            out.append('\n');
            for (Map.Entry<String, String> keyed : entry.getValue().entrySet()) {
                out.append("#   ").append(keyed.getKey()).append("  (key: ").append(keyed.getValue()).append(")\n");
            }
            out.append(entry.getKey()).append("  equivalence=natural-keys multivalue=merge\n");
        }
        Files.writeString(writePolicy.toPath(), out.toString(), StandardCharsets.UTF_8);

        status("Wrote " + writePolicy + " with " + keyedByObject.size()
                + " suggested rule(s) — review it, then pass it as 'deploy --policy " + writePolicy + "'.");
        return 0;
    }

    private void status(String message) {
        if (!quiet) {
            System.out.println(message);
        }
    }
}
