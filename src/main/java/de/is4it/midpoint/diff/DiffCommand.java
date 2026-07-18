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

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code diff} subcommand: compute the minimal itemDelta between a desired object and the current
 * one and emit the {@link com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectModificationType}
 * XML that midPoint's REST {@code PATCH} consumes.
 *
 * <p>The current object comes either from a local LIVE file (offline mode) or, with {@code --host},
 * from a running midPoint over REST. Either way the diff itself runs locally via {@link DiffEngine}.
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

    @Parameters(index = "0", paramLabel = "DESIRED", description = "Desired-state object XML.")
    File desiredFile;

    @Parameters(index = "1", arity = "0..1", paramLabel = "LIVE",
            description = "Live/current object XML (offline mode). Omit when using --host.")
    File liveFile;

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

        // A LIVE file means offline; an explicit --host means live. A host inherited only from
        // MIDPOINT_URL is a fallback that a LIVE file overrides (so an env var doesn't break file diffs).
        boolean live;
        if (liveFile != null) {
            if (connectionOptions.isExplicitHost()) {
                System.err.println("midpoint-diff: provide either a LIVE file or --host, not both.");
                return EXIT_ERROR;
            }
            live = false;
        } else if (connectionOptions.isLive()) {
            live = true;
        } else {
            System.err.println("midpoint-diff: offline diff needs a LIVE file, "
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
                    : Io.parseObject(prismContext, liveFile);

            DiffEngine.DiffResult result = new DiffEngine(prismContext)
                    .diff(desired, current, strategy.equivalence, strategy.scope, strategy.multiValue);

            // Merge notes go to stderr so stdout stays a clean PATCH body.
            if (!quiet) {
                for (String path : result.mergeFallbackPaths()) {
                    System.err.println("midpoint-diff: note: '" + path + "' diffs to a whole-value replace; "
                            + "merge does not apply there, so it was left as replace.");
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
        String liveXml = client.getObject(target.restType, target.oid);
        return prismContext.parseObject(liveXml);
    }
}
