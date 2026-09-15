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
import com.evolveum.midpoint.util.exception.SchemaException;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code diff} subcommand: compute the minimal itemDelta between two states of the <b>same</b>
 * object — its desired state and its current state — and emit the
 * {@link com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectModificationType} XML that
 * midPoint's REST {@code PATCH} consumes. This is not a comparison of two different objects: both inputs
 * are the same object (same OID) at two points in time, and the delta transforms current &rarr; desired.
 *
 * <p>The current-state object comes either from a local CURRENT file (offline mode) or, with {@code --url},
 * fetched from a running midPoint over REST. Either way the diff itself runs locally via {@link DiffEngine}.
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
                    + "decisions, stack traces, REST request/response bodies (may include sensitive values), "
                    + "and the prism/framework logs.")
    boolean trace;

    // Name-matching is the default; this opts out. See the note on ApplyCommand's copy for why this is a
    // separate --no-x option rather than picocli's `negatable`.
    @Option(names = "--no-match-by-name",
            description = "Require an oid. In live mode an OID-less object is otherwise resolved on the "
                    + "server by type+name (a single match), which is the default.")
    boolean noMatchByName;

    // Accepted so that invocations written before this became the default keep parsing; inert.
    @Option(names = {"--match-by-name", "-m"}, hidden = true,
            description = "Accepted for compatibility; matching by name is the default. No effect.")
    boolean matchByNameLegacy;

    boolean matchByName() {
        return !noMatchByName;
    }

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
        boolean live;
        if (currentFile != null) {
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
            live = false;
        } else if (connectionOptions.isLive()) {
            live = true;
        } else {
            System.err.println("midgie: offline diff needs a CURRENT file, "
                    + "or set --url (or MIDPOINT_URL) for live mode.");
            return EXIT_ERROR;
        }

        LOG.debug("diff mode: {}", live ? "live (--url)" : "offline (CURRENT file)");

        try {
            // Before the schema read, which in live mode is a round trip: a path that does not exist or is
            // not XML is knowable here, and waiting made a local mistake arrive as a connection error.
            // currentFile is null in live mode, where the check is a no-op.
            Io.checkInputFile(desiredFile);
            Io.checkInputFile(currentFile);

            // And before the client, because building it resolves the password — possibly by prompting, or
            // by pulling it out of a password safe. Neither is worth doing for a run that cannot proceed.
            MidpointRestClient client = null;
            if (live) {
                client = connectionOptions.newClient();
                if (client == null) {
                    return EXIT_ERROR;
                }
            }

            PrismContext prismContext = schemaOptions.buildContext(live ? client : null);
            if (live && !quiet) {
                VersionSupport.warnIfMismatch(prismContext, client);
            }

            PrismObject<? extends Objectable> desired = Io.parseObject(prismContext, desiredFile);
            PrismObject<? extends Objectable> current = live
                    ? fetchLive(prismContext, client, desired)
                    : Io.parseObject(prismContext, currentFile);

            DiffEngine.DiffResult result = new DiffEngine(prismContext)
                    .diff(desired, current, strategy.equivalence, strategy.scope, strategy.multiValue,
                            SmartReferences.checkFor(strategy, prismContext, client));

            // Refuse (by default) to emit a merge delta that would overwrite an accrued value in place.
            if (!result.accruedInPlaceEditPaths().isEmpty() && !strategy.allowInplaceMerge) {
                System.err.println(StrategyOptions.accruedInPlaceRefusal(result.accruedInPlaceEditPaths()));
                return EXIT_ERROR;
            }

            // Merge notes go to stderr so stdout stays a clean PATCH body.
            if (!quiet) {
                for (String path : result.mergeFallbackPaths()) {
                    System.err.println("midgie: note: '" + path + "' diffs to a whole-value replace; "
                            + "merge does not apply there, so it was left as replace.");
                }
                for (String path : result.smartReferencePaths()) {
                    System.err.println("midgie: note: '" + path + "' is a smart reference; the OID midPoint "
                            + "resolved its filter to is left in place.");
                }
                for (String path : result.incompleteSkippedPaths()) {
                    System.err.println("midgie: warning: the server did not return '" + path
                            + "' (it came back incomplete), so its values are unknown, not empty — midgie "
                            + "will not patch an item it cannot see. Check the account's read authorization "
                            + "for that item.");
                }
                for (String path : result.protectedSkippedPaths()) {
                    System.err.println("midgie: note: skipped protected value at '" + path
                            + "' — ciphertext changes on every fetch and is not safely diffable.");
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

            Io.writePayload(output, DeltaOutput.render(prismContext, result.delta(), format));
            return result.hasDrift() ? 1 : 0;
        } catch (Exception e) {
            String message = (e.getMessage() != null) ? e.getMessage() : e.toString();
            System.err.println("midgie: " + message);
            LOG.trace("diff failed", e);
            return EXIT_ERROR;
        }
    }

    private PrismObject<? extends Objectable> fetchLive(
            PrismContext prismContext, MidpointRestClient client, PrismObject<? extends Objectable> desired)
            throws Exception {
        LiveLookup.Target target =
                LiveLookup.resolveCurrent(prismContext, client, desired, connectionOptions.oid, matchByName());
        if (target.current == null) {
            String where = (target.oid != null) ? "Object " + target.restType + "/" + target.oid
                    : "A " + target.restType + " named '" + LiveLookup.nameOf(desired) + "'";
            throw new SchemaException(where + " does not exist on the server.");
        }
        return target.current;
    }
}
