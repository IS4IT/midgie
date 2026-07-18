package net.haeger.midpoint.diff;

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

    @Option(names = "--format", paramLabel = "xml|summary", defaultValue = "xml",
            description = "xml: ObjectModificationType. summary: human-readable changed-path list.")
    String format;

    @Override
    public Integer call() {
        StrategyOptions.Resolved strategy = strategyOptions.resolve();
        if (strategy == null) {
            return EXIT_ERROR;
        }

        boolean live = connectionOptions.isLive();
        if (live && liveFile != null) {
            System.err.println("midpoint-diff: provide either a LIVE file or --host, not both.");
            return EXIT_ERROR;
        }
        if (!live && liveFile == null) {
            System.err.println("midpoint-diff: offline diff needs a LIVE file, or use --host for live mode.");
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

            PrismObject<? extends Objectable> desired = prismContext.parseObject(desiredFile);
            PrismObject<? extends Objectable> current = live
                    ? fetchLive(prismContext, client, desired)
                    : prismContext.parseObject(liveFile);

            DiffEngine.DiffResult result = new DiffEngine(prismContext)
                    .diff(desired, current, strategy.equivalence, strategy.scope, strategy.multiValue);

            // Merge notes go to stderr so stdout stays a clean PATCH body.
            for (String path : result.mergeFallbackPaths()) {
                System.err.println("midpoint-diff: note: '" + path + "' diffs to a whole-value replace; "
                        + "merge does not apply there, so it was left as replace.");
            }

            if ("summary".equalsIgnoreCase(format)) {
                System.out.println(DeltaOutput.toSummary(result.delta()));
            } else {
                System.out.println(DeltaOutput.toModificationXml(prismContext, result.delta()));
            }

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
