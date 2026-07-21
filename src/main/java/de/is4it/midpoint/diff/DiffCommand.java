/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.util.concurrent.Callable;

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
 * <p>The current-state object comes either from a local CURRENT file (offline mode) or, with {@code --host},
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

    /** Exit code signalling a usage/runtime error, distinct from the drift codes 0/1. */
    private static final int EXIT_ERROR = 2;

    @Parameters(index = "0", paramLabel = "DESIRED", description = "The object as you want it (desired state).")
    File desiredFile;

    @Parameters(index = "1", arity = "0..1", paramLabel = "CURRENT",
            description = "The same object as it is now (current state), as a local XML file. "
                    + "Omit when using --host, which fetches the current state from the server.")
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
            description = "Raise the log level to DEBUG (framework logs on stderr) for troubleshooting.")
    boolean debug;

    @Override
    public Integer call() {
        if (debug) {
            Logging.enableDebug();
        }
        StrategyOptions.Resolved strategy = strategyOptions.resolve();
        if (strategy == null) {
            return EXIT_ERROR;
        }

        // A CURRENT file means offline; an explicit --host means live. A host inherited only from
        // MIDPOINT_URL is a fallback that a CURRENT file overrides (so an env var doesn't break file diffs).
        boolean live;
        if (currentFile != null) {
            if (connectionOptions.isExplicitHost()) {
                System.err.println("midpoint-diff: provide either a CURRENT file or --host, not both.");
                return EXIT_ERROR;
            }
            live = false;
        } else if (connectionOptions.isLive()) {
            live = true;
        } else {
            System.err.println("midpoint-diff: offline diff needs a CURRENT file, "
                    + "or set --host (or MIDPOINT_URL) for live mode.");
            return EXIT_ERROR;
        }

        MidpointRestClient client = null;
        if (live) {
            client = connectionOptions.newClient();
            if (client == null) {
                return EXIT_ERROR;
            }
        }

        try {
            PrismContext prismContext = schemaOptions.buildContext(live ? client : null);
            if (live && !quiet) {
                VersionSupport.warnIfMismatch(prismContext, client);
            }

            PrismObject<? extends Objectable> desired = Io.parseObject(prismContext, desiredFile);
            PrismObject<? extends Objectable> current = live
                    ? fetchLive(prismContext, client, desired)
                    : Io.parseObject(prismContext, currentFile);

            DiffEngine.DiffResult result = new DiffEngine(prismContext)
                    .diff(desired, current, strategy.equivalence, strategy.scope, strategy.multiValue);

            // Refuse (by default) to emit a merge delta that would overwrite an accrued value in place.
            if (!result.accruedInPlaceEditPaths().isEmpty() && !strategy.allowInplaceMerge) {
                System.err.println(StrategyOptions.accruedInPlaceRefusal(result.accruedInPlaceEditPaths()));
                return EXIT_ERROR;
            }

            // Merge notes go to stderr so stdout stays a clean PATCH body.
            if (!quiet) {
                for (String path : result.mergeFallbackPaths()) {
                    System.err.println("midpoint-diff: note: '" + path + "' diffs to a whole-value replace; "
                            + "merge does not apply there, so it was left as replace.");
                }
                for (String path : result.protectedSkippedPaths()) {
                    System.err.println("midpoint-diff: note: skipped protected value at '" + path
                            + "' — ciphertext changes on every fetch and is not safely diffable.");
                }
                for (String path : result.accruedInPlaceEditPaths()) {
                    System.err.println("midpoint-diff: warning: '" + path + "' edits an accrued (live-only) "
                            + "value in place; merge cannot preserve it, so it will be overwritten "
                            + "(proceeding under --allow-inplace-merge).");
                }
            }

            Io.writePayload(output, DeltaOutput.render(prismContext, result.delta(), format));
            return result.hasDrift() ? 1 : 0;
        } catch (Exception e) {
            String message = (e.getMessage() != null) ? e.getMessage() : e.toString();
            System.err.println("midpoint-diff: " + message);
            return EXIT_ERROR;
        }
    }

    private PrismObject<? extends Objectable> fetchLive(
            PrismContext prismContext, MidpointRestClient client, PrismObject<? extends Objectable> desired)
            throws Exception {
        LiveTarget target = LiveTarget.resolve(desired, connectionOptions.oid);
        PrismObject<? extends Objectable> current =
                LiveLookup.byOid(prismContext, client, desired.getCompileTimeClass(), target.restType, target.oid);
        if (current == null) {
            throw new SchemaException("Object " + target.restType + "/" + target.oid
                    + " does not exist on the server.");
        }
        return current;
    }
}
