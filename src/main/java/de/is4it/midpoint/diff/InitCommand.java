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
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
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
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * The {@code init} subcommand: the one-off set-up steps, each behind its own flag.
 *
 * <p>{@code --create-state} enables delete detection for a server by creating midgie's state object.
 * {@code deploy} never creates it — this is the deliberate step that opts a server in, so midgie cannot
 * leave an unexplained object behind. The created {@code midgie-state} lookup table describes and protects
 * itself (see {@link SyncState}); asking for it again reports what is already there and changes nothing.
 * {@code --write-policy} seeds a starter {@code --policy} file for a tree.
 *
 * <p>Every step is opt-in: {@code init} on its own writes nothing, changes nothing and prints its help,
 * because the flags say what they do to a server or a working copy and none of them should be the thing
 * that happens when you were only exploring the command.
 *
 * <p>Given a PATH, it also prints the deployment identity derived for that tree — the repository's root
 * commit plus the tree's path inside it — which is the key its state row will use.
 *
 * <p>Exit codes: {@code 0} done (created, already present, or written), {@code 2} error or no step asked
 * for.
 */
@Command(
        name = "init",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "One-off set-up steps for a server or a tree; each is opt-in, so pick at least one."
)
public class InitCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(InitCommand.class);

    private static final int EXIT_ERROR = 2;

    @Parameters(index = "0", arity = "0..1", paramLabel = "PATH",
            description = "Desired-state tree whose deployment identity should be reported (optional).")
    File path;

    @Spec
    CommandSpec spec;   // to print this subcommand's own help when no step is asked for

    @Mixin
    ConnectionOptions connectionOptions;

    @Mixin
    SchemaOptions schemaOptions;

    @Option(names = "--create-state",
            description = "Create midgie's state object on the server, enabling delete detection for deploy. "
                    + "Reports and changes nothing if it already exists.")
    boolean createState;

    @Option(names = "--write-policy", paramLabel = "FILE",
            description = "Write a starter --policy file for PATH, listing the objects whose multi-valued "
                    + "containers have a schema natural key. Refuses to overwrite an existing file.")
    File writePolicy;

    @Option(names = "--write-cacert", paramLabel = "FILE",
            description = "Fetch the certificate chain --url presents, show it, and write the issuing CA(s) "
                    + "as the PEM --cacert takes. Asks before writing; refuses to overwrite.")
    File writeCacert;

    @Option(names = "--write-properties", paramLabel = "FILE",
            description = "Write an annotated midgie.properties template: every option that can carry a "
                    + "default, commented out, with its help text. Refuses to overwrite an existing file.")
    File writeProperties;

    @Option(names = {"--yes", "-y"},
            description = "Answer the --write-cacert trust prompt with yes (required in non-interactive use).")
    boolean assumeYes;

    @Option(names = {"--quiet", "-q"},
            description = "Suppress status output; keep errors and the exit code.")
    boolean quiet;

    @Option(names = {"--debug", "-v"},
            description = "Follow what midgie is doing on stderr: one line per step and per object-level "
                    + "decision. Use --trace for the detail behind them.")
    boolean debug;

    @Option(names = "--trace",
            description = "Everything --debug shows plus the detail behind it: per-file and per-value "
                    + "decisions, stack traces, REST request/response bodies (may include sensitive values), "
                    + "and the prism/framework logs.")
    boolean trace;

    @Override
    public Integer call() {
        if (trace) {
            Logging.enableTrace();
        } else if (debug) {
            Logging.enableDebug();
        }
        // No step asked for. Printing help beats guessing at one: every step here writes something, to a
        // server or to the working copy. Exit 2, not 0 — a pipeline whose 'init --create-state' lost its
        // flag must fail rather than pass having done nothing.
        if (!createState && writePolicy == null && writeCacert == null && writeProperties == null) {
            System.err.println("midgie: init does nothing on its own — pick a step (--create-state, "
                    + "--write-policy, --write-cacert, --write-properties).");
            spec.commandLine().usage(System.err);
            return EXIT_ERROR;
        }
        // Every refusal first, before any step has written anything: a mistyped --write-policy used to exit 2
        // having already created the state object, so the operator saw an error for a command that had in
        // fact changed the server. The same holds now that one run can write three files.
        if (writePolicy != null && path == null) {
            System.err.println("midgie: --write-policy needs a PATH to scan for keyed containers.");
            return EXIT_ERROR;
        }
        for (File target : new File[] {writePolicy, writeCacert, writeProperties}) {
            if (target != null && target.exists()) {
                System.err.println("midgie: " + target + " already exists; refusing to overwrite it.");
                return EXIT_ERROR;
            }
        }

        try {
            // Needs nothing but the command model, so it works offline — you write the file that holds the
            // URL before you have anywhere to point it.
            if (writeProperties != null) {
                Io.writePayload(writeProperties, PropertiesTemplate.render(spec.root()).stripTrailing());
                status("Wrote " + writeProperties + " — uncomment what this machine should not have to repeat, "
                        + "then move it to ~/.midgie.properties or point $MIDGIE_CONF at it.");
            }
            // Needs the host, but no account: you cannot authenticate to a server you do not trust yet.
            if (writeCacert != null) {
                Integer failure = writeCacert();
                if (failure != null) {
                    return failure;
                }
            }
        } catch (Exception e) {
            String message = (e.getMessage() != null) ? e.getMessage() : e.toString();
            System.err.println("midgie: " + message);
            LOG.debug("init failed", e);
            return EXIT_ERROR;
        }

        if (!createState && writePolicy == null) {
            return 0;   // the offline steps are done and neither of the live ones was asked for
        }
        if (!connectionOptions.isLive()) {
            System.err.println("midgie: init needs a running server for --create-state/--write-policy — "
                    + "pass --url (or set MIDPOINT_URL).");
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

            if (createState) {
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
                reportIdentity();   // the key this tree's state row will use: part of the tracking step
            }
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

    /**
     * Fetch the chain the server presents, show it, and — once the operator says so — write its issuers as
     * the PEM {@code --cacert} reads. Returns an exit code on failure, or {@code null} when it went fine.
     *
     * <p>The confirmation is the security. Fetching a certificate means trusting whatever answers the
     * connection, so the only thing separating this from writing an impostor's CA into your trust file is a
     * person comparing the fingerprint with what the CA's owner published. That is why the fingerprints are
     * printed, why the prompt defaults to no, and why a non-interactive run refuses instead of assuming.
     */
    private Integer writeCacert() throws IOException {
        URI url = connectionOptions.url();
        if (url == null) {
            System.err.println("midgie: --write-cacert needs --url (or MIDPOINT_URL) to fetch a "
                    + "certificate from.");
            return EXIT_ERROR;
        }
        List<X509Certificate> chain = ServerCertificates.fetch(url, connectionOptions.timeout());
        List<X509Certificate> anchors = ServerCertificates.anchors(chain);

        status(url.getHost() + " presented " + chain.size() + " certificate(s):");
        for (int i = 0; i < chain.size(); i++) {
            status("[" + i + "]" + (i == 0 ? " the server itself" : " issuer")
                    + "\n" + ServerCertificates.describe(chain.get(i)));
        }
        status("");
        status("About to trust " + anchors.size() + " of them" + (chain.size() > 1 ? " (the issuers)" : "")
                + ". Check the sha256 above against what the CA's owner publishes: anything that answered "
                + "this connection could have presented these.");

        Console console = System.console();
        boolean interactive = console != null;
        String answer = interactive
                ? console.readLine("Write %s and trust it for later runs? [y/N] ", writeCacert)
                : null;
        switch (ApplyCommand.decideConfirmation(assumeYes, interactive, answer)) {
            case PROCEED:
                break;
            case ABORTED:
                System.err.println("midgie: aborted — nothing was written.");
                return 0;
            case NON_INTERACTIVE:
            default:
                System.err.println("midgie: refusing to write a trust file without confirmation in a "
                        + "non-interactive session; re-run interactively, or with --yes if the fingerprints "
                        + "are already known to be right.");
                return EXIT_ERROR;
        }

        try {
            Io.writePayload(writeCacert, ServerCertificates.toPem(anchors).stripTrailing());
        } catch (CertificateEncodingException e) {
            throw new IOException("cannot encode the certificate: " + e.getMessage(), e);
        }
        status("Wrote " + writeCacert + " — pass it as '--cacert " + writeCacert + "', or put "
                + "'cacert = " + writeCacert.getAbsolutePath() + "' in midgie.properties, and TLS verifies "
                + "instead of needing --insecure.");
        return null;
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
                    + escapeSelectorChars(object.getName() != null ? object.getName().getOrig() : "?");
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

    /**
     * Escape the selector metacharacters an object name may contain: a {@code /}, since the selector itself
     * is a /-separated path, and a {@code *}, which would otherwise be refused as a segment mixing a wildcard
     * with other text.
     */
    private static String escapeSelectorChars(String name) {
        return name.replace("*", "\\*").replace("/", "\\/");
    }

    private void status(String message) {
        if (!quiet) {
            System.out.println(message);
        }
    }
}
