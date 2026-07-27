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

import org.w3c.dom.Element;

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
        // Before touching the server: a mistyped --write-policy used to exit 2 having already created the
        // state object, so the operator saw an error for a command that had in fact changed the server.
        if (writePolicy != null) {
            if (path == null) {
                System.err.println("midgie: --write-policy needs a PATH to scan for keyed containers.");
                return EXIT_ERROR;
            }
            if (writePolicy.exists()) {
                System.err.println("midgie: " + writePolicy + " already exists; refusing to overwrite it.");
                return EXIT_ERROR;
            }
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
                return seedPolicy(prismContext, client);
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
    private Integer seedPolicy(PrismContext prismContext, MidpointRestClient client) throws Exception {
        // Same two-step as deploy: discover and register the tree's own extension schemas *before* parsing,
        // or every object using a tree-local extension attribute fails to parse and silently vanishes from
        // the suggestions — exactly the objects most likely to need a rule.
        //
        // The live client goes in too. registerDynamicSchemaExtensions *replaces* the dynamic set, so this
        // second registration discards whatever buildContext(client) read from the server: leaving it out
        // meant that as soon as the tree held any schema file, objects using a server-registered extension
        // stopped parsing and lost their suggestions.
        Discovery.ScanResult scan = Discovery.scan(prismContext, List.of(path));
        Map<String, Element> schemaMap = new LinkedHashMap<>();
        for (SchemaFile schema : scan.schemas()) {
            schemaMap.put(schema.description(), schema.schemaElement());
        }
        schemaMap.putAll(schemaOptions.collectSchemas(prismContext, client));
        SchemaSupport.register(prismContext, schemaMap);

        Map<String, Map<String, String>> keyedByObject = new LinkedHashMap<>();
        int skipped = 0;
        for (File file : scan.objectFiles()) {
            PrismObject<? extends Objectable> object;
            try {
                object = prismContext.parseObject(file);
            } catch (Exception e) {
                System.err.println("midgie: warning: could not read " + file
                        + " while seeding the policy (" + e.getMessage() + "); it has no suggestions.");
                LOG.debug("skipping {} while seeding the policy", file, e);
                skipped++;
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
                    + escapeSlashes(object.getName() != null ? object.getName().getOrig() : "?");
            keyedByObject.computeIfAbsent(selector, key -> new LinkedHashMap<>()).putAll(keyed);
        }

        StringBuilder out = new StringBuilder();
        out.append("# midgie policy, seeded by 'midgie init --write-policy' from ").append(path).append("\n#\n")
                .append("# Each container listed below is multi-valued, has a natural key in the midPoint schema,\n")
                .append("# and your files fill that key in — so midgie can match its entries individually.\n")
                .append("# 'multivalue=merge' on such a path updates just the entries your files list and leaves\n")
                .append("# the rest alone; under the default (replace) every entry you did not list is deleted.\n")
                .append("#\n")
                .append("# The merge rules are deliberately scoped to those paths rather than to the whole object.\n")
                .append("# 'equivalence' cannot be: prism takes one equivalence for the whole object diff, so the\n")
                .append("# natural-keys line below applies to every container of that object — including ones whose\n")
                .append("# key nothing fills in (an assignment usually has no <identifier>). Keeping merge narrow is\n")
                .append("# what stops that from mattering, since the accrued-value guard only applies where merge is.\n")
                .append("#\n# These are suggestions — review them, and delete any rule whose container your files\n")
                .append("# are meant to be authoritative for.\n");
        if (keyedByObject.isEmpty()) {
            out.append("\n# Nothing in this tree addresses a keyed multi-valued container, so no rules are needed.\n");
        }
        for (Map.Entry<String, Map<String, String>> entry : keyedByObject.entrySet()) {
            out.append('\n').append(quoteIfNeeded(entry.getKey())).append("  equivalence=natural-keys\n");
            for (Map.Entry<String, String> keyed : entry.getValue().entrySet()) {
                out.append("#   key: ").append(keyed.getValue()).append('\n')
                        .append(quoteIfNeeded(entry.getKey() + "/" + keyed.getKey()))
                        .append("  multivalue=merge\n");
            }
        }
        Files.writeString(writePolicy.toPath(), out.toString(), StandardCharsets.UTF_8);

        status("Wrote " + writePolicy + " with suggestions for " + keyedByObject.size() + " object(s)"
                + (skipped > 0 ? " (" + skipped + " file(s) could not be read)" : "")
                + (keyedByObject.isEmpty()
                        ? " — nothing in this tree needs tuning, so it is a comment-only file."
                        : " — review it, then pass it as 'deploy --policy " + writePolicy + "'."));
        return 0;
    }

    /**
     * Write a selector in a form {@code Policy.load} reads back.
     *
     * <p>Object names are emitted verbatim, and midPoint allows characters the selector grammar gives its
     * own meaning to: a {@code /} reads as another path segment, and a {@code =} can start the settings.
     * Without this, midgie could generate a policy file midgie then refuses to load.
     */
    private static String quoteIfNeeded(String selector) {
        return (selector.indexOf('=') >= 0) ? "\"" + selector + "\"" : selector;
    }

    /** A {@code /} inside an object name is escaped, since the selector itself is a /-separated path. */
    private static String escapeSlashes(String name) {
        return name.replace("/", "\\/");
    }

    private void status(String message) {
        if (!quiet) {
            System.out.println(message);
        }
    }
}
