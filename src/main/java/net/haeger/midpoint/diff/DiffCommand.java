package net.haeger.midpoint.diff;

import java.io.File;
import java.util.concurrent.Callable;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code diff} subcommand: compute the minimal itemDelta between a desired object and a live one.
 *
 * <p><b>Status: scaffold.</b> This bootstraps the midPoint prism context (base schema from the
 * classpath — no Spring, no DB, no running midPoint) to prove the dependency wiring, then stops. The
 * diff engine itself is a TODO — implement it per {@code DESIGN.md} ("Engine" + "Build order").
 *
 * <p>Confirmed engine sketch (see DESIGN.md for the exact API references):
 * <pre>
 *   PrismObject&lt;?&gt; desired = ctx.parseObject(desiredFile);
 *   PrismObject&lt;?&gt; live    = ctx.parseObject(liveFile);
 *   ObjectDelta&lt;?&gt; delta   = desired.diff(live, EquivalenceStrategy.REAL_VALUE);
 *   ObjectModificationType mod = DeltaConvertor.toObjectModificationType(delta);
 *   // scope=present  -&gt; keep only itemDeltas whose path is present in the desired object
 *   // multivalue=merge -&gt; REAL_VALUE_CONSIDER_DIFFERENT_IDS_NATURAL_KEYS for keyed containers
 *   // serialize `mod` as XML to stdout; exit 0 (no change) / 1 (drift) / 2 (error)
 * </pre>
 */
@Command(
        name = "diff",
        mixinStandardHelpOptions = true,
        description = "Diff a desired object against a live one and emit the minimal itemDelta."
)
public class DiffCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "DESIRED", description = "Desired-state object XML.")
    File desiredFile;

    @Parameters(index = "1", arity = "0..1", paramLabel = "LIVE",
            description = "Live/current object XML (offline mode). Omit when using --host.")
    File liveFile;

    // --- Strategy (see DESIGN.md "Strategy flags") ---

    @Option(names = "--scope", paramLabel = "present|full", defaultValue = "present",
            description = "present: only diff items present in DESIRED (preserve the rest). full: full replace.")
    String scope;

    @Option(names = "--multivalue", paramLabel = "replace|merge", defaultValue = "replace",
            description = "How to treat multi-valued containers. Per-path overrides via --path.")
    String multivalue;

    @Option(names = "--path", paramLabel = "<itemPath>=merge|replace",
            description = "Per-path multi-value strategy override. Repeatable.")
    String[] pathStrategies;

    @Option(names = "--equivalence", paramLabel = "real-value|literal|data", defaultValue = "real-value",
            description = "Diff equivalence strategy (metadata/operational sensitivity).")
    String equivalence;

    // --- Schema (see DESIGN.md "Schema — two layers, two knobs") ---

    @Option(names = "--schema-dir", paramLabel = "DIR",
            description = "Directory of extension XSDs to register (MidPointPrismContextFactory extra-schema dir).")
    File schemaDir;

    @Option(names = "--schema", paramLabel = "FILE",
            description = "Exported SchemaType XML to register as a dynamic schema extension. Repeatable.")
    File[] schemaFiles;

    // --- Output ---

    @Option(names = "--format", paramLabel = "xml|summary", defaultValue = "xml",
            description = "xml: ObjectModificationType. summary: human-readable changed-path list.")
    String format;

    @Override
    public Integer call() throws Exception {
        // Bootstrap the base schema from the classpath. The (File) ctor overload registers extension
        // XSDs from --schema-dir; see DESIGN.md for the --schema (SchemaType) live-registration path.
        MidPointPrismContextFactory factory = (schemaDir != null)
                ? new MidPointPrismContextFactory(schemaDir)
                : new MidPointPrismContextFactory();
        PrismContext prismContext = factory.createInitializedPrismContext();

        // Touch the context so the bootstrap isn't optimised away and the dependency is exercised.
        if (prismContext.getSchemaRegistry() == null) {
            System.err.println("midpoint-diff: schema registry failed to initialize.");
            return 2;
        }

        // TODO(DESIGN.md — Engine): parse desiredFile + liveFile, run PrismObject.diff, scope/merge,
        // convert via DeltaConvertor.toObjectModificationType, serialize, and return 0/1 by drift.
        System.err.println("midpoint-diff: prism context initialized. "
                + "Diff engine not yet implemented — see DESIGN.md.");
        return 2;
    }
}
