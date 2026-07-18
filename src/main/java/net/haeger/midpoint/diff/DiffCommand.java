package net.haeger.midpoint.diff;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.prism.equivalence.ParameterizedEquivalenceStrategy;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.schema.DeltaConvertor;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;
import com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectFactory;
import com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectModificationType;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code diff} subcommand: compute the minimal itemDelta between a desired object and a live one
 * and emit the {@link ObjectModificationType} XML that midPoint's REST {@code PATCH} consumes.
 *
 * <p>This is the offline core (DESIGN.md build order step 1). The diff logic lives in {@link DiffEngine};
 * this class only maps CLI flags to it, renders the output, and sets the exit code:
 * {@code 0} = no differences, {@code 1} = differences found (drift), {@code 2} = error.
 *
 * <p><b>Not yet implemented</b> (later increments, guarded so they fail loudly rather than silently
 * producing a wrong delta): live fetch (no {@code LIVE} file) and {@code --schema} dynamic SchemaType
 * registration. {@code --schema-dir}, {@code --multivalue merge}, and {@code --path} work today.
 */
@Command(
        name = "diff",
        mixinStandardHelpOptions = true,
        description = "Diff a desired object against a live one and emit the minimal itemDelta."
)
public class DiffCommand implements Callable<Integer> {

    /** Exit code signalling a usage/runtime error, distinct from the drift codes 0/1. */
    private static final int EXIT_ERROR = 2;

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

    @Option(names = "--equivalence",
            paramLabel = "real-value|consider-different-ids|natural-keys|ignore-metadata|data|literal",
            defaultValue = "real-value",
            description = "Value-equivalence strategy, loosest to strictest. See parseEquivalence() for what each considers.")
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
    public Integer call() {
        // Validate flags up front so unsupported combinations fail loudly instead of emitting a
        // subtly-wrong delta.
        DiffEngine.Scope scopeValue = parseScope();
        if (scopeValue == null) {
            return EXIT_ERROR;
        }
        ParameterizedEquivalenceStrategy strategy = parseEquivalence();
        if (strategy == null) {
            return EXIT_ERROR;
        }
        DiffEngine.MultiValueStrategy multiValue = parseMultiValue();
        if (multiValue == null) {
            return EXIT_ERROR;
        }
        if (multiValue.isMergeActive() && strategy != EquivalenceStrategy.REAL_VALUE) {
            // Not fatal: allow exploring merge against real midPoint under other strategies, but warn —
            // id-considering strategies pair values and edit them in place, so accrued values may be
            // mutated instead of preserved (see DiffEngine.applyMerge).
            System.err.println("midpoint-diff: warning: merge is designed for --equivalence real-value "
                    + "(id-agnostic content matching); with --equivalence " + equivalence
                    + " values may be paired and edited in place instead of cleanly added/kept. Proceeding.");
        }
        if (schemaFiles != null && schemaFiles.length > 0) {
            System.err.println("midpoint-diff: --schema (dynamic SchemaType registration) is not implemented yet. "
                    + "Use --schema-dir for extension XSDs.");
            return EXIT_ERROR;
        }
        if (liveFile == null) {
            System.err.println("midpoint-diff: offline diff needs a LIVE file. "
                    + "Live mode (--host) is not implemented yet — see DESIGN.md build order.");
            return EXIT_ERROR;
        }

        try {
            // Base schema from the classpath; the (File) ctor also registers extension XSDs from --schema-dir.
            MidPointPrismContextFactory factory = (schemaDir != null)
                    ? new MidPointPrismContextFactory(schemaDir)
                    : new MidPointPrismContextFactory();
            PrismContext prismContext = factory.createInitializedPrismContext();

            DiffEngine.DiffResult result =
                    new DiffEngine(prismContext).diff(desiredFile, liveFile, strategy, scopeValue, multiValue);

            // Merge notes go to stderr so stdout stays a clean PATCH body.
            for (String path : result.mergeFallbackPaths()) {
                System.err.println("midpoint-diff: note: '" + path + "' diffs to a whole-value replace; "
                        + "merge does not apply there, so it was left as replace.");
            }

            ObjectDelta<? extends Objectable> delta = result.delta();
            if ("summary".equalsIgnoreCase(format)) {
                printSummary(delta);
            } else {
                printModificationXml(prismContext, delta);
            }

            return delta.isEmpty() ? 0 : 1;
        } catch (Exception e) {
            String message = (e.getMessage() != null) ? e.getMessage() : e.toString();
            System.err.println("midpoint-diff: " + message);
            return EXIT_ERROR;
        }
    }

    private DiffEngine.Scope parseScope() {
        switch (scope.toLowerCase()) {
            case "present":
                return DiffEngine.Scope.PRESENT;
            case "full":
                return DiffEngine.Scope.FULL;
            default:
                System.err.println("midpoint-diff: --scope must be 'present' or 'full', got: " + scope);
                return null;
        }
    }

    /** Parse {@code --multivalue} plus any {@code --path <itemPath>=merge|replace} overrides. Null on error. */
    private DiffEngine.MultiValueStrategy parseMultiValue() {
        boolean globalMerge;
        switch (multivalue.toLowerCase()) {
            case "replace":
                globalMerge = false;
                break;
            case "merge":
                globalMerge = true;
                break;
            default:
                System.err.println("midpoint-diff: --multivalue must be 'replace' or 'merge', got: " + multivalue);
                return null;
        }

        List<DiffEngine.MultiValueStrategy.PathRule> overrides = new ArrayList<>();
        if (pathStrategies != null) {
            for (String spec : pathStrategies) {
                int eq = spec.lastIndexOf('=');
                if (eq <= 0 || eq == spec.length() - 1) {
                    System.err.println("midpoint-diff: --path must be <itemPath>=merge|replace, got: " + spec);
                    return null;
                }
                String pathText = spec.substring(0, eq).trim();
                String strategyText = spec.substring(eq + 1).trim().toLowerCase();

                boolean merge;
                if ("merge".equals(strategyText)) {
                    merge = true;
                } else if ("replace".equals(strategyText)) {
                    merge = false;
                } else {
                    System.err.println("midpoint-diff: --path strategy must be merge|replace, got: " + strategyText);
                    return null;
                }

                ItemPath path = parseItemPath(pathText);
                if (path.isEmpty()) {
                    System.err.println("midpoint-diff: --path has an empty itemPath in: " + spec);
                    return null;
                }
                overrides.add(new DiffEngine.MultiValueStrategy.PathRule(path, merge));
            }
        }
        return DiffEngine.MultiValueStrategy.of(globalMerge, overrides);
    }

    /**
     * Build an item path from a {@code /}-separated name list without a PrismContext (flags are parsed
     * before the context exists). Any {@code prefix:} is dropped — {@link DiffEngine.MultiValueStrategy}
     * matches names namespace-leniently, so unqualified local names line up with the diff's paths.
     */
    private static ItemPath parseItemPath(String text) {
        List<QName> names = new ArrayList<>();
        for (String segment : text.split("/")) {
            String name = segment.trim();
            if (name.isEmpty()) {
                continue;
            }
            int colon = name.indexOf(':');
            names.add(new QName(colon >= 0 ? name.substring(colon + 1) : name));
        }
        return ItemPath.create(names.toArray());
    }

    /**
     * Map the {@code --equivalence} value to a prism {@link EquivalenceStrategy}. Each strategy answers
     * "are these two values the same?", which drives both what counts as drift and how multi-valued items
     * are matched. Presets, loosest to strictest (see
     * {@code com.evolveum.midpoint.prism.equivalence.ParameterizedEquivalenceStrategy}):
     *
     * <pre>
     *   CLI value               prism preset                                    ignores / considers
     *   ----------------------  ----------------------------------------------  --------------------------------
     *   real-value (default)    REAL_VALUE                                      ignores container ids, metadata,
     *                                                                           operational, element names, ns
     *                                                                           prefixes, ref filters/options
     *   consider-different-ids  REAL_VALUE_CONSIDER_DIFFERENT_IDS               as real-value, but PCV ids must
     *                                                                           not contradict
     *   natural-keys            REAL_VALUE_CONSIDER_DIFFERENT_IDS_NATURAL_KEYS  as consider-different-ids, plus
     *                                                                           container natural keys (@Experimental)
     *   ignore-metadata         IGNORE_METADATA                                ignores metadata/operational/ids;
     *                                                                           keeps element names + ref filters
     *   data                    DATA                                           parsed data incl. metadata + exact
     *                                                                           ids; ignores prefixes only
     *   literal                 LITERAL                                        as data, but ns prefixes matter too
     * </pre>
     *
     * The container-id knob is the one that changes multi-valued behaviour: id-agnostic {@code real-value}
     * yields clean whole-value add/delete, while the id-considering variants ({@code consider-different-ids},
     * {@code natural-keys}) pair values and edit them in place. That is why merge prefers {@code real-value}
     * and only warns for the rest. {@code natural-keys} is prism-{@code @Experimental} (the pair-and-edit
     * matching is the merge footgun described in {@link DiffEngine}); it is exposed for exploration against
     * a real server and prints an experimental note when selected.
     */
    private ParameterizedEquivalenceStrategy parseEquivalence() {
        switch (equivalence.toLowerCase()) {
            case "real-value":
                return EquivalenceStrategy.REAL_VALUE;
            case "consider-different-ids":
                return EquivalenceStrategy.REAL_VALUE_CONSIDER_DIFFERENT_IDS;
            case "natural-keys":
                System.err.println("midpoint-diff: note: --equivalence natural-keys "
                        + "(REAL_VALUE_CONSIDER_DIFFERENT_IDS_NATURAL_KEYS) is prism-experimental; "
                        + "container-value matching by natural key may behave unexpectedly.");
                return EquivalenceStrategy.REAL_VALUE_CONSIDER_DIFFERENT_IDS_NATURAL_KEYS;
            case "ignore-metadata":
                return EquivalenceStrategy.IGNORE_METADATA;
            case "data":
                return EquivalenceStrategy.DATA;
            case "literal":
                return EquivalenceStrategy.LITERAL;
            default:
                System.err.println("midpoint-diff: --equivalence must be "
                        + "real-value|consider-different-ids|natural-keys|ignore-metadata|data|literal, got: "
                        + equivalence);
                return null;
        }
    }

    private static void printModificationXml(PrismContext prismContext, ObjectDelta<? extends Objectable> delta)
            throws Exception {
        ObjectModificationType modification = DeltaConvertor.toObjectModificationType(delta);
        String xml = prismContext.xmlSerializer().serializeRealValue(modification, ObjectFactory.F_OBJECT_MODIFICATION);
        System.out.println(xml);
    }

    private static void printSummary(ObjectDelta<? extends Objectable> delta) {
        if (delta.isEmpty()) {
            System.out.println("No differences.");
            return;
        }
        for (ItemDelta<?, ?> mod : delta.getModifications()) {
            System.out.println(operationLabel(mod) + " " + mod.getPath());
        }
    }

    private static String operationLabel(ItemDelta<?, ?> mod) {
        if (mod.getValuesToReplace() != null) {
            return "replace";
        }
        StringBuilder label = new StringBuilder();
        if (mod.getValuesToAdd() != null) {
            label.append("add");
        }
        if (mod.getValuesToDelete() != null) {
            label.append(label.length() > 0 ? "+delete" : "delete");
        }
        return label.length() > 0 ? label.toString() : "modify";
    }
}
