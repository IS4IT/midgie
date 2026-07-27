/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.Console;
import java.io.File;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.util.exception.SchemaException;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code apply} subcommand: reconcile a running midPoint object to the desired state. It fetches the
 * current object over REST, computes the minimal itemDelta (same engine as {@code diff}), prints what
 * would change, and — after confirmation — {@code PATCH}es it. With {@code --create-if-absent}, a missing
 * object is created via {@code POST} (operational/metadata items stripped first).
 *
 * <p>Because {@code apply} mutates a live server, it confirms before writing: it prompts interactively,
 * or, in a non-interactive session, refuses unless {@code --yes} is given. Idempotent: when the object
 * already matches, nothing is sent.
 *
 * <p>Exit codes: {@code 2} = error. A real apply returns {@code 0} on success (applied, created, in sync,
 * or user-declined). {@code --dry-run} changes nothing and gates like {@code diff}: {@code 0} when already
 * in sync, {@code 1} when it would change something (create or patch). {@code --quiet} drops status and
 * advisory messages, keeping the delta output, errors, and exit code.
 */
@Command(
        name = "apply",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Reconcile a live midPoint object to the desired state (diff, then PATCH/POST)."
)
public class ApplyCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(ApplyCommand.class);

    private static final int EXIT_ERROR = 2;

    /** Outcome of the pre-write confirmation. */
    enum Confirmation { PROCEED, ABORTED, NON_INTERACTIVE }

    @Parameters(index = "0", paramLabel = "DESIRED", description = "Desired-state object XML.")
    File desiredFile;

    @Mixin
    StrategyOptions strategyOptions;

    @Mixin
    ConnectionOptions connectionOptions;

    @Mixin
    SchemaOptions schemaOptions;

    @Option(names = {"--create-if-absent", "-c"},
            description = "If the object does not exist, POST the whole desired object to create it.")
    boolean createIfAbsent;

    @Option(names = {"--match-by-name", "-m"},
            description = "If the object has no oid, find it on the server by type+name (a single match) "
                    + "instead of failing; combine with --create-if-absent to create it when there is no match.")
    boolean matchByName;

    @Option(names = "--dry-run",
            description = "Compute and print the delta but do not send it. Exits 1 if it would change anything.")
    boolean dryRun;

    @Option(names = {"--yes", "-y"},
            description = "Apply without the interactive confirmation prompt (required in non-interactive use).")
    boolean assumeYes;

    @Option(names = {"--quiet", "-q"},
            description = "Suppress status and advisory messages; keep the delta output, errors, and exit code.")
    boolean quiet;

    @Option(names = {"--debug", "-v"},
            description = "Log a full DEBUG trace on stderr (this tool's own decisions plus framework logs) for troubleshooting.")
    boolean debug;

    @Option(names = "--trace",
            description = "Even more than --debug: also log REST request/response bodies and full HTTP error "
                    + "bodies (may include sensitive values). Implies --debug.")
    boolean trace;

    @Option(names = "--format", paramLabel = "xml|json|summary", defaultValue = "xml",
            description = "Dry-run output format. xml/json: ObjectModificationType. summary: changed-path list.")
    String format;

    @Option(names = {"--output", "-o"}, paramLabel = "FILE",
            description = "Write the dry-run delta to FILE instead of stdout.")
    File output;

    @Override
    public Integer call() {
        if (trace) {
            Logging.enableTrace();
        } else if (debug) {
            Logging.enableDebug();
        }
        StrategyOptions.Resolved strategy = strategyOptions.resolve();
        if (strategy == null) {
            return EXIT_ERROR;
        }
        if (!connectionOptions.isLive()) {
            System.err.println("midgie: apply needs a running server — pass --url (or set MIDPOINT_URL).");
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
            PrismObject<? extends Objectable> desired = Io.parseObject(prismContext, desiredFile);
            LiveLookup.Target resolved =
                    LiveLookup.resolveCurrent(prismContext, client, desired, connectionOptions.oid, matchByName);
            LiveTarget target = LiveTarget.of(resolved.restType, resolved.oid);
            LOG.debug("apply: {} → {}", target.restType + "/" + (target.oid != null ? target.oid : "(by name)"),
                    resolved.current == null ? "absent (create path)" : "present (patch path)");
            return (resolved.current == null)
                    ? applyCreate(prismContext, client, target, desired)
                    : applyPatch(prismContext, client, target, desired, strategy, resolved.current);
        } catch (Exception e) {
            String message = (e.getMessage() != null) ? e.getMessage() : e.toString();
            System.err.println("midgie: " + message);
            LOG.debug("apply failed", e);
            return EXIT_ERROR;
        }
    }

    /** Object is absent (the search found nothing): create it from the desired object, if allowed. */
    private Integer applyCreate(PrismContext prismContext, MidpointRestClient client, LiveTarget target,
            PrismObject<? extends Objectable> desired) throws Exception {
        String address = (target.oid != null) ? target.restType + "/" + target.oid
                : target.restType + " '" + LiveLookup.nameOf(desired) + "'";
        if (!createIfAbsent) {
            System.err.println("midgie: " + address + " does not exist. Pass --create-if-absent to create it.");
            return EXIT_ERROR;
        }
        note("midgie: " + address + " does not exist; will create it from " + desiredFile.getName() + ".");
        if (dryRun) {
            note("midgie: dry run — would create " + address + " (not created).");
            // -o gets the body that would be POSTed. Writing nothing left the previous run's delta in the
            // file, which a consumer would read as this run's answer.
            Io.writePayload(output, createBody(prismContext, desired));
            return 1; // would change
        }
        Integer stop = confirmOrExit("Create " + address + "?");
        if (stop != null) {
            return stop;
        }
        String createdOid = client.createObject(target.restType, createBody(prismContext, desired));
        String oid = (createdOid != null) ? createdOid : target.oid;
        status(oid != null
                ? "Created " + target.restType + "/" + oid + "."
                : "Created " + target.restType + " '" + LiveLookup.nameOf(desired)
                        + "' (the server did not report its OID).");
        return 0;
    }

    /** Object exists: diff, show the change, and PATCH it (after confirmation). */
    private Integer applyPatch(PrismContext prismContext, MidpointRestClient client, LiveTarget target,
            PrismObject<? extends Objectable> desired, StrategyOptions.Resolved strategy,
            PrismObject<? extends Objectable> current)
            throws Exception {
        DiffEngine.DiffResult result = new DiffEngine(prismContext)
                .diff(desired, current, strategy.equivalence, strategy.scope, strategy.multiValue);

        // Refuse (by default) before writing anything if the merge would overwrite an accrued value in place.
        if (!result.accruedInPlaceEditPaths().isEmpty() && !strategy.allowInplaceMerge) {
            System.err.println(StrategyOptions.accruedInPlaceRefusal(result.accruedInPlaceEditPaths()));
            return EXIT_ERROR;
        }

        for (String path : result.mergeFallbackPaths()) {
            note("midgie: note: '" + path + "' diffs to a whole-value replace; "
                    + "merge does not apply there, so it was left as replace.");
        }
        for (String path : result.protectedSkippedPaths()) {
            note("midgie: note: skipped protected value at '" + path
                    + "' — ciphertext changes on every fetch and is not safely diffable.");
        }
        for (String path : result.accruedInPlaceEditPaths()) {
            note("midgie: warning: '" + path + "' edits an accrued (live-only) value in place; "
                    + "merge cannot preserve it, so it will be overwritten (proceeding under --allow-inplace-merge).");
        }
        for (String warning : result.inputWarnings()) {
            note("midgie: warning: " + warning);
        }
        // Blast radius, before the confirmation prompt: "N change(s)" alone hides how much is destroyed.
        if (result.removalWarning() != null) {
            note("midgie: " + result.removalWarning());
        }

        if (!result.hasDrift()) {
            String message = "No changes: " + target.restType + "/" + target.oid + " already matches desired.";
            if (dryRun) {
                // In dry-run stdout is the payload stream, so the prose goes to stderr and the (empty) delta
                // is still written: 'apply --dry-run -o delta.xml' used to leave the previous run's delta in
                // place, and a consumer parsing stdout got a sentence instead of an ObjectModificationType.
                Io.writePayload(output, DeltaOutput.render(prismContext, result.delta(), format));
                note("midgie: " + message);
            } else {
                status(message);
            }
            return 0;
        }

        int changes = result.delta().getModifications().size();
        if (dryRun) {
            // The delta is dry-run's payload — written even under --quiet (redirect stdout to silence it).
            Io.writePayload(output, DeltaOutput.render(prismContext, result.delta(), format));
            note("midgie: dry run — " + changes + " change(s) NOT applied to "
                    + target.restType + "/" + target.oid + ".");
            return 1; // would change
        }
        String modificationXml = DeltaOutput.toModificationXml(prismContext, result.delta());

        // Show what will change (stderr, so stdout stays the status line) then confirm.
        note("midgie: will apply " + changes + " change(s) to " + target.restType + "/" + target.oid + ":");
        for (String line : DeltaOutput.toSummary(result.delta()).split("\n")) {
            note("  " + line);
        }
        Integer stop = confirmOrExit("Apply " + changes + " change(s) to " + target.restType + "/" + target.oid + "?");
        if (stop != null) {
            return stop;
        }

        client.patchObject(target.restType, target.oid, modificationXml);
        status("Applied " + changes + " change(s) to " + target.restType + "/" + target.oid + ".");
        return 0;
    }

    /** Serialize the desired object for a create POST, stripping operational/metadata items midPoint owns. */
    static String createBody(PrismContext prismContext, PrismObject<? extends Objectable> desired)
            throws SchemaException {
        // On a copy: this is called from Reconciler.planCreate, which runs in the read-only preview pass
        // that deploy does before the real one. Stripping the caller's own object there changed what a
        // later fragment of the same object diffed to, so the emitted PATCH depended on whether --yes had
        // skipped the preview.
        PrismObject<? extends Objectable> body = desired.clone();
        body.getValue().removeOperationalItems();
        return XmlPretty.tidyRoot(prismContext.xmlSerializer().serialize(body));
    }

    /** Prompt/decide whether to write. Returns {@code null} to proceed, or the exit code to return. */
    private Integer confirmOrExit(String prompt) {
        Console console = System.console();
        boolean interactive = console != null;
        String answer = (!assumeYes && interactive) ? console.readLine("%s [y/N] ", prompt) : null;
        switch (decideConfirmation(assumeYes, interactive, answer)) {
            case PROCEED:
                return null;
            case ABORTED:
                System.err.println("midgie: aborted — nothing was applied.");
                return 0;
            case NON_INTERACTIVE:
            default:
                System.err.println("midgie: refusing to write without confirmation in a non-interactive "
                        + "session; re-run with --yes to proceed.");
                return EXIT_ERROR;
        }
    }

    /** Pure confirmation decision (no I/O), so it can be unit-tested. */
    static Confirmation decideConfirmation(boolean autoYes, boolean interactive, String answer) {
        if (autoYes) {
            return Confirmation.PROCEED;
        }
        if (!interactive) {
            return Confirmation.NON_INTERACTIVE;
        }
        String normalized = (answer == null) ? "" : answer.trim().toLowerCase();
        return (normalized.equals("y") || normalized.equals("yes")) ? Confirmation.PROCEED : Confirmation.ABORTED;
    }

    /** Status line (stdout); suppressed under {@code --quiet}. */
    private void status(String message) {
        if (!quiet) {
            System.out.println(message);
        }
    }

    /** Advisory line (stderr); suppressed under {@code --quiet}. */
    private void note(String message) {
        if (!quiet) {
            System.err.println(message);
        }
    }
}
