/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
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
import picocli.CommandLine.Spec;

/**
 * The {@code diff} subcommand: compute the minimal itemDelta between two states of the <b>same</b>
 * object — its desired state and its current state — and emit the
 * {@link com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectModificationType} XML that
 * midPoint's REST {@code PATCH} consumes. This is not a comparison of two different objects: both inputs
 * are the same object (same OID) at two points in time, and the delta transforms current &rarr; desired.
 *
 * <p>The current-state object comes either from a local CURRENT file (offline mode) or, with {@code --url},
 * from a running midPoint. A live diff is {@code deploy --dry-run --format} for that one object — see
 * {@link #diffLive} — so an object that does not exist yet shows as the POST that would create it. Either
 * way the diff itself runs locally via {@link DiffEngine}, and {@code diff} never writes to the server.
 * Exit codes: {@code 0} = no differences, {@code 1} = differences found (drift), {@code 2} = error.
 */
@Command(
        name = "diff",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Diff a desired object against the current one and emit the minimal itemDelta."
)
public class DiffCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(DiffCommand.class);

    /** Exit code signalling a usage/runtime error, distinct from the drift codes 0/1. */
    private static final int EXIT_ERROR = 2;

    @Parameters(index = "0", paramLabel = "DESIRED", description = "The object as you want it (desired state).")
    File desiredFile;

    @Parameters(index = "1", arity = "0..1", paramLabel = "CURRENT",
            description = "The same object as it is now (current state), as a local XML file. "
                    + "Omit when using --url, which fetches the current state from the server.")
    File currentFile;

    @Mixin
    StrategyOptions strategyOptions;

    @Mixin
    ConnectionOptions connectionOptions;

    @Mixin
    SchemaOptions schemaOptions;

    @Spec
    picocli.CommandLine.Model.CommandSpec spec;

    /**
     * Options that only mean something against a server. An offline diff accepted them on the command line
     * and did nothing with them, so a mistaken --no-prune or --cacert looked as if it had applied.
     */
    private static final List<String> LIVE_ONLY = List.of("--no-prune", "--no-match-by-name", "--user",
            "--password", "--password-env", "--password-file", "--oid", "--insecure", "--cacert", "--proxy",
            "--no-proxy", "--proxy-user", "--proxy-password", "--proxy-password-env", "--proxy-password-file",
            "--timeout", "--retries", "--no-live-schema");

    // --- Output ---

    @Option(names = "--format", paramLabel = "xml|json|summary", defaultValue = "xml",
            description = "xml/json: ObjectModificationType. summary: human-readable changed-path list.")
    String format;

    @Option(names = {"--output", "-o"}, paramLabel = "FILE",
            description = "Write the delta to FILE instead of stdout.")
    File output;

    @Option(names = {"--quiet", "-q"},
            description = "Suppress advisory notes/warnings; keep the delta output, errors, and exit code.")
    boolean quiet;

    @Option(names = {"--debug", "-v"},
            description = "Follow what midgie is doing on stderr: one line per step and per object-level "
                    + "decision. Use --trace for the detail behind them.")
    boolean debug;

    @Option(names = "--trace",
            description = "Everything --debug shows plus the detail behind it: per-file and per-value "
                    + "decisions, stack traces, REST request/response bodies (password values masked; "
                    + "other sensitive values may show), "
                    + "and the prism/framework logs.")
    boolean trace;

    // Name-matching is the default; this opts out. See the note on DeployCommand's copy for why this is a
    // separate --no-x option rather than picocli's `negatable`.
    @Option(names = "--no-match-by-name",
            description = "In live mode, skip an OID-less object instead of searching the server by type+name "
                    + "and diffing the single match, which is the default.")
    boolean noMatchByName;

    // Accepted so that invocations written before this became the default keep parsing; inert.
    @Option(names = {"--match-by-name", "-m"}, hidden = true,
            description = "Accepted for compatibility; matching by name is the default. No effect.")
    boolean matchByNameLegacy;

    @Option(names = "--no-prune",
            description = "In live mode, leave out the removals of values the tracked tree no longer declares, "
                    + "which deploy would send with the change.")
    boolean noPrune;

    @Option(names = "--policy", paramLabel = "FILE",
            description = "Per-object/-item diff-strategy policy file, as deploy takes it (live mode only). A live "
                    + "diff applies midgie's built-in apply=on-create rules with or without one.")
    File policyFile;

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

        // A CURRENT file means offline; an explicit --url means live. A URL inherited only from
        // MIDPOINT_URL is a fallback that a CURRENT file overrides (so an env var doesn't break file diffs).
        if (currentFile == null) {
            if (!connectionOptions.isLive()) {
                System.err.println("midgie: offline diff needs a CURRENT file, "
                        + "or set --url (or MIDPOINT_URL) for live mode.");
                return EXIT_ERROR;
            }
            LOG.debug("diff mode: live (--url)");
            return diffLive();
        }
        if (connectionOptions.isExplicitUrl()) {
            System.err.println("midgie: provide either a CURRENT file or --url, not both.");
            return EXIT_ERROR;
        }
        if (Io.isStdin(desiredFile) && Io.isStdin(currentFile)) {
            // stdin can only be drained once: the second parse got an empty string and failed with
            // prism's "Index 0 out of bounds for length 0", which names neither stdin nor the mistake.
            System.err.println("midgie: only one input can be read from stdin.");
            return EXIT_ERROR;
        }
        if (strategy.smartRefs == StrategyOptions.SmartRefs.VERIFY) {
            System.err.println("midgie: --smart-refs verify needs a running server to run the filter "
                    + "against; use 'accept' or 'resolve' for an offline diff.");
            return EXIT_ERROR;
        }
        if (policyFile != null) {
            System.err.println("midgie: --policy applies to a live diff, which reconciles the object as deploy "
                    + "would; an offline diff compares the two files as they are.");
            return EXIT_ERROR;
        }
        LOG.debug("diff mode: offline (CURRENT file)");
        List<String> inert = new ArrayList<>();
        picocli.CommandLine.ParseResult parsed = spec.commandLine().getParseResult();
        for (String option : LIVE_ONLY) {
            if (parsed != null && parsed.hasMatchedOption(option)) {
                inert.add(option);
            }
        }
        if (!inert.isEmpty() && !quiet) {
            System.err.println(NoteStyle.of("midgie: note: " + String.join(", ", inert)
                    + (inert.size() == 1 ? " has" : " have")
                    + " no effect on an offline diff, which compares the two files as they are."));
        }
        return diffOffline(strategy);
    }

    /**
     * A live diff is {@code deploy --dry-run --format} for one object, and is run as exactly that: the same
     * discovery, target resolution, policy, reconciliation and notes, so the two cannot drift apart again.
     * What {@code diff} adds is that it cannot write — it has no flag that leads to a PATCH — and that its
     * stdout is the delta and nothing else.
     */
    private Integer diffLive() {
        File desired = desiredFile;
        try {
            // Before anything else, as an offline diff does: deploy's discovery would report a missing or
            // malformed file as a tree with no objects in it, which is true and names the wrong problem.
            Io.checkInputFile(desiredFile);
            if (Io.isStdin(desiredFile)) {
                desired = Io.stdinToTempFile();
            }
        } catch (Exception e) {
            System.err.println("midgie: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            LOG.trace("diff failed", e);
            return EXIT_ERROR;
        }
        DeployCommand preview = new DeployCommand();
        preview.asDiff = true;
        preview.paths = List.of(desired);
        preview.strategyOptions = strategyOptions;
        preview.schemaOptions = schemaOptions;
        preview.connectionOptions = connectionOptions;
        preview.dryRun = true;
        preview.format = format;
        preview.output = output;
        preview.policyFile = policyFile;
        preview.noPrune = noPrune;
        preview.noMatchByName = noMatchByName;
        preview.quiet = quiet;
        preview.debug = debug;
        preview.trace = trace;
        try {
            return preview.call();
        } finally {
            if (desired != desiredFile) {
                Io.deleteStdinCopy(desired);
            }
        }
    }

    /** Two local files: no server, no policy, no tracking — just the delta that turns CURRENT into DESIRED. */
    private Integer diffOffline(StrategyOptions.Resolved strategy) {
        try {
            // Before the schema read: a path that does not exist or is not XML is knowable here.
            Io.checkInputFile(desiredFile);
            Io.checkInputFile(currentFile);

            PrismContext prismContext = schemaOptions.buildContext(null);
            PrismObject<? extends Objectable> desired = Io.parseObject(prismContext, desiredFile);
            PrismObject<? extends Objectable> current = Io.parseObject(prismContext, currentFile);

            DiffEngine.DiffResult result = new DiffEngine(prismContext)
                    .diff(desired, current, strategy.equivalence, strategy.scope, strategy.multiValue,
                            SmartReferences.checkFor(strategy, prismContext, null));

            if ((debug || trace) && !quiet) {
                for (String declined : result.keyPairingDeclined()) {
                    System.err.println(NoteStyle.of("midgie: note: " + declined + "."));
                }
            }

            // Refuse (by default) to emit a merge delta that would overwrite an accrued value in place.
            if (!result.accruedInPlaceEditPaths().isEmpty() && !strategy.allowInplaceMerge) {
                System.err.println(StrategyOptions.accruedInPlaceRefusal(result.accruedInPlaceEditPaths()));
                return EXIT_ERROR;
            }

            // Refuse a delta that would leave one entry of a keyed list twice (see NaturalKeys.introducedDuplicates).
            DiffEngine.Projection projection = DiffEngine.project(current, result.delta());
            if (projection.failure() != null && !quiet) {
                System.err.println("midgie: warning: " + DiffEngine.unprojectedNote(projection.failure()) + ".");
            }
            List<String> duplicates = NaturalKeys.introducedDuplicates(current, projection.after());
            if (!duplicates.isEmpty()) {
                System.err.println("midgie: " + NaturalKeys.duplicateRefusal(duplicates));
                return EXIT_ERROR;
            }

            // Merge notes go to stderr so stdout stays a clean PATCH body.
            if (!quiet) {
                for (String path : result.mergeFallbackPaths()) {
                    System.err.println(NoteStyle.of("midgie: note: '" + path + "' diffs to a whole-value replace; "
                            + "merge does not apply there, so it was left as replace."));
                }
                for (String path : result.smartReferencePaths()) {
                    System.err.println(NoteStyle.of("midgie: note: '" + path + "' is a smart reference; the OID "
                            + "midPoint resolved its filter to is left in place."));
                }
                for (String path : result.protectedSkippedPaths()) {
                    System.err.println(NoteStyle.of("midgie: note: skipped protected value at '" + path
                            + "' — ciphertext changes on every fetch and is not safely diffable."));
                }
                for (String path : result.accruedInPlaceEditPaths()) {
                    System.err.println("midgie: warning: '" + path + "' edits an accrued (live-only) "
                            + "value in place; merge cannot preserve it, so it will be overwritten "
                            + "(proceeding under --allow-inplace-merge).");
                }
                for (String warning : result.inputWarnings()) {
                    System.err.println("midgie: warning: " + warning);
                }
                if (result.removalWarning() != null) {
                    System.err.println("midgie: " + result.removalWarning());
                }
            }

            String payload = DeltaOutput.render(prismContext, result.delta(), format);
            if ("xml".equalsIgnoreCase(format)) {
                // No server offline, so only the object itself can be named; its references stay bare OIDs.
                OidComments names = new OidComments();
                names.know(desired.getOid(), desired.getCompileTimeClass() != null
                        ? desired.getCompileTimeClass().getSimpleName() : null, LiveLookup.nameOf(desired));
                payload = names.annotate(payload, desired.getOid(), null);
            }
            Io.writePayload(output, payload);
            return result.hasDrift() ? 1 : 0;
        } catch (Exception e) {
            String message = (e.getMessage() != null) ? e.getMessage() : e.toString();
            System.err.println("midgie: " + message);
            LOG.trace("diff failed", e);
            return EXIT_ERROR;
        }
    }
}
