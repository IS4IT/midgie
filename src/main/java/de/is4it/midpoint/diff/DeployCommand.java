/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.util.exception.SchemaException;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code deploy} subcommand: discover a tree of desired-state object XML, sort it by dependency, and
 * either print the apply order (offline) or reconcile each unit against a live server ({@code --url}).
 *
 * <p>The order is two-tier: extension <b>schemas</b> (sorted by {@code <xsd:import>} dependency and
 * registered so objects using extension attributes parse), then <b>objects</b> (grouped by identity —
 * OID, else type+name — and sorted by outbound reference OIDs).
 *
 * <p>With {@code --url} each unit is reconciled in that order — look up current state, diff
 * ({@code --scope present}), PATCH, or POST to create with {@code --create-if-absent} — so a role is created
 * before the user that references it. The unit is the <b>object</b>, not the file: an object described by
 * several files is combined first ({@link FragmentUnion}) and applied once. {@code --retry} re-applies the failed set until it
 * stabilises; {@code --plan <dir>} writes the changes as reviewable files and {@code --apply-plan <dir>}
 * replays them verbatim; {@code --policy <file>} varies the diff strategy per object/item. Writing is gated:
 * a single up-front confirmation by default ({@code --yes}, {@code --confirm-each-apply}, {@code --dry-run}).
 *
 * <p>Exit codes: offline {@code 0}/{@code 2}. Apply {@code 0} (all created/patched/in-sync/skipped or
 * declined) / {@code 2} (any unit stuck, or a setup error). {@code --dry-run}/{@code --plan} {@code 0}
 * in-sync / {@code 1} would change something / {@code 2} error.
 */
@Command(
        name = "deploy",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Discover a folder tree, sort by dependency, and print the apply order or apply it (--url)."
)
public class DeployCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(DeployCommand.class);

    private static final int EXIT_ERROR = 2;
    private static final int MAX_RETRY_ROUNDS = 20;

    @Parameters(index = "0..*", arity = "0..*", paramLabel = "PATH",
            description = "Files or directories of desired-state object XML, walked recursively.")
    List<File> paths;

    @Mixin
    StrategyOptions strategyOptions;

    @Mixin
    SchemaOptions schemaOptions;

    @Mixin
    ConnectionOptions connectionOptions;

    @Option(names = "--dry-run",
            description = "Compute and print every change but do not write. Needs --url. Exits 1 if anything would change.")
    boolean dryRun;

    @Option(names = {"--yes", "-y"},
            description = "Apply without the interactive confirmation prompt (required in non-interactive use).")
    boolean assumeYes;

    @Option(names = "--confirm-each-apply",
            description = "Prompt before each individual change (per itemDelta) instead of once for the whole tree.")
    boolean confirmEachApply;

    // Creating and name-matching are the defaults; these opt out, following the same shape as
    // --no-rollback-plan and --no-live-schema. picocli's `negatable = true` looks like the tidier way to
    // express it and is not: a negatable flag flips *away from* its declared default
    // (CommandLine.applyValueToSingleValuedField, "#712 flip the default value"), so with defaultValue
    // "true" both spellings inverted — plain --create-if-absent turned creation OFF.
    @Option(names = "--no-create-if-absent",
            description = "Fail on an object that does not exist instead of creating it, and skip an "
                    + "OID-less one. Creating is otherwise the default.")
    boolean noCreateIfAbsent;

    @Option(names = "--no-match-by-name",
            description = "Skip an OID-less object instead of searching the server by type+name and "
                    + "patching the single match, which is the default.")
    boolean noMatchByName;

    // Accepted so that invocations written before these became the default keep parsing; inert.
    @Option(names = {"--create-if-absent", "-c"}, hidden = true,
            description = "Accepted for compatibility; creating is the default. No effect.")
    boolean createIfAbsentLegacy;

    @Option(names = {"--match-by-name", "-m"}, hidden = true,
            description = "Accepted for compatibility; matching by name is the default. No effect.")
    boolean matchByNameLegacy;

    boolean createIfAbsent() {
        return !noCreateIfAbsent;
    }

    boolean matchByName() {
        return !noMatchByName;
    }

    @Option(names = "--retry",
            description = "After the apply pass, re-apply the failed units until a round makes no progress (catches by-name refs).")
    boolean retry;

    @Option(names = "--plan", paramLabel = "DIR",
            description = "Write the ordered per-object changes (create/patch files) to DIR instead of applying. Needs --url.")
    File planDir;

    @Option(names = "--apply-plan", paramLabel = "DIR",
            description = "Replay a plan directory written by --plan (POST creates, PATCH patches, in order). Needs --url.")
    File applyPlanDir;

    @Option(names = "--rollback-plan", paramLabel = "DIR",
            description = "Where to leave the rollback plan this run writes (default: a per-deployment "
                    + "directory under $XDG_STATE_HOME/midgie). Each run gets its own timestamped "
                    + "subdirectory; the newest few are kept.")
    File rollbackPlanDir;

    @Option(names = "--no-rollback-plan",
            description = "Do not write a rollback plan for this run. The net is otherwise always written, "
                    + "which is the point of it.")
    boolean noRollbackPlan;

    @Option(names = "--policy", paramLabel = "FILE",
            description = "Per-object/-item diff-strategy policy file ('<Type/Name/item glob> key=value' lines), "
                    + "layered on midgie's built-in apply=on-create rules; most-specific selector wins, and a "
                    + "file line wins a tie with a built-in one.")
    File policyFile;

    @Option(names = "--no-prune",
            description = "Only report the values this tree deployed previously and no longer declares, instead "
                    + "of removing them with each object's change — which is the default once the server "
                    + "tracks the tree ('midgie init --create-state'). Removal only ever reaches values the "
                    + "tree applied itself, never accrued state.")
    boolean noPrune;

    // Accepted so that invocations written before removal became the default keep parsing; inert.
    @Option(names = "--prune", hidden = true,
            description = "Accepted for compatibility; removing what the tree dropped is the default. No effect.")
    boolean pruneLegacy;

    @Option(names = "--app", paramLabel = "NAME",
            description = "Track this tree under NAME instead of the identity derived from its git repository "
                    + "(root commit + path). Only needed for setups where that derivation does not fit.")
    String app;

    @Option(names = "--format", paramLabel = "xml|json|summary",
            description = "With --dry-run and exactly one object: write its change to stdout (or -o) — the "
                    + "PATCH body as ObjectModificationType (xml/json) or a changed-path list (summary); for an "
                    + "object that does not exist yet, the POST body. The plan rows move to stderr.")
    String format;

    @Option(names = {"--output", "-o"}, paramLabel = "FILE",
            description = "Write the --format output to FILE instead of stdout (implies --format xml).")
    File output;

    @Option(names = {"--quiet", "-q"},
            description = "Suppress the plan/status output and notes; keep errors, failures, the --format "
                    + "output, and the exit code.")
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

    /**
     * Set by {@code diff}, which is this command's preview of one object under another name: its stdout is the
     * delta, so the plan rows and the summary are dropped rather than moved, and the notes about how
     * tracking is set up — addressed to someone deploying, not someone looking — go to the debug log.
     * Everything else, the reconciliation included, is exactly what {@code deploy --dry-run --format} does.
     */
    boolean asDiff;

    // Resolved once per run.
    private StrategyOptions.Resolved globalStrategy;
    private Policy policy;
    /**
     * The objects this run applied completely — created, patched or found in sync, with every removal it meant
     * to send. Only their files' rows move forward: a row moved past an object that failed, was skipped or
     * declined, or kept a removal back, would make what it dropped undetectable for ever.
     */
    private final Set<DeployObject> appliedUnits = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    // The OID each written object ended up under, for the rollback plan's record of its state rows.
    private final Map<DeployObject, String> writtenOids = new IdentityHashMap<>();

    /** Set when last-applied cannot be recovered at all, so no comparison this run means anything. */
    private boolean detectionBroken;
    // Objects whose removals this run could not judge for a reason that may pass: their rows stay put.
    private final Set<DeployObject> unjudgedUnits = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    /** Plan entries the operator declined under --confirm-each-apply; they are neither applied nor failed. */
    private int declinedEntries;

    /** Names the OIDs in what this run writes for people to read: plan files, the rollback plan, a payload. */
    private OidComments oidComments = new OidComments();

    /** The safety net being accumulated for this run; null when no writing pass will happen. */
    private RollbackPlan rollback;

    /** Each object's removals, which its own PATCH carries unless --no-prune. */
    private final Map<DeployObject, List<RemovalDetector.Removal>> removalsByUnit = new IdentityHashMap<>();

    /** For each object, the paths of the files it was compared against as last applied. */
    private Map<DeployObject, List<String>> comparedPaths = Map.of();

    /** Paths of gone files whose object left the tree; reported, so their rows have nothing left to say. */
    private final Set<String> orphanPaths = new LinkedHashSet<>();

    /** Objects that left the tree: named after everything else the run prints, and again by --apply-plan. */
    private final List<PlanState.Orphan> orphans = new ArrayList<>();

    /** What the writing passes did, for the deploy: row; failed is settled once retries are over. */
    private final Tally sent = new Tally();

    /**
     * Whether the run got as far as writing — a writing pass ran, or the preview found nothing to write. A run
     * declined or refused at the prompt did not, and records nothing.
     */
    private boolean sentSomething;

    /**
     * What each object's change was in the preview the operator confirmed, or {@code null} when nothing was
     * confirmed that way ({@code --yes}, or a prompt per object, which shows the change it asks about).
     */
    private Map<DeployObject, String> confirmedChanges;

    /** Removals that cannot be turned into a delete; they fail the run so the revision stays put. */
    private final List<RemovalDetector.Removal> unprunable = new ArrayList<>();

    @Override
    public Integer call() {
        if (trace) {
            Logging.enableTrace();
        } else if (debug) {
            Logging.enableDebug();
        }
        if (assumeYes && confirmEachApply) {
            System.err.println("midgie: use either --yes or --confirm-each-apply, not both.");
            return EXIT_ERROR;
        }
        if (planDir != null && applyPlanDir != null) {
            System.err.println("midgie: use either --plan or --apply-plan, not both.");
            return EXIT_ERROR;
        }
        if (output != null && format == null) {
            format = "xml";
        }
        if (format != null) {
            if (!List.of("xml", "json", "summary").contains(format)) {
                System.err.println("midgie: --format must be 'xml', 'json' or 'summary', got: " + format);
                return EXIT_ERROR;
            }
            if (!dryRun || planDir != null || applyPlanDir != null) {
                // The delta of a run that writes is the one it sent, and a plan has its own files; only a
                // preview has a single change to hand over.
                System.err.println("midgie: --format/--output write the change a --dry-run would make; "
                        + "use them with --dry-run (and without --plan/--apply-plan).");
                return EXIT_ERROR;
            }
        }
        if (connectionOptions.oid != null && applyPlanDir != null) {
            System.err.println("midgie: --apply-plan replays a plan as written; --oid would have no effect.");
            return EXIT_ERROR;
        }
        boolean live = connectionOptions.isLive();
        LOG.debug("deploy: live={}, dryRun={}, createIfAbsent={}, matchByName={}, retry={}, plan={}, "
                + "applyPlan={}, policy={}", live, dryRun, createIfAbsent(), matchByName(), retry,
                planDir, applyPlanDir, policyFile);
        // --create-if-absent and --match-by-name are deliberately absent here: they default ON, so their
        // value no longer says the operator asked for anything, and including them would make every offline
        // 'deploy <tree>' — which only prints the plan — fail for needing --url.
        boolean writeFlags = dryRun || assumeYes || confirmEachApply
                || retry || planDir != null || applyPlanDir != null || policyFile != null;
        if (!live && writeFlags) {
            System.err.println("midgie: --dry-run/--yes/--retry/--plan/--apply-plan/--policy need "
                    + "--url; without it, deploy only prints the plan.");
            return EXIT_ERROR;
        }
        if (applyPlanDir == null && (paths == null || paths.isEmpty())) {
            System.err.println("midgie: provide at least one PATH, or --apply-plan <dir>.");
            return EXIT_ERROR;
        }
        // --apply-plan replays a plan verbatim and returns before any of these is read. Accepting them
        // silently meant 'deploy tree --apply-plan plan/ --prune' reported success having never looked for
        // a removal, let alone made one.
        if (applyPlanDir != null) {
            List<String> ignored = new ArrayList<>();
            if (noPrune || pruneLegacy) {
                ignored.add(noPrune ? "--no-prune" : "--prune");
            }
            if (policyFile != null) {
                ignored.add("--policy");
            }
            // Asked of the spellings the operator can type, not of the effective value: creating and
            // name-matching default on, so testing createIfAbsent() would report them ignored on every
            // --apply-plan run. Only a flag actually given is worth saying will be ignored — either form.
            if (createIfAbsentLegacy || noCreateIfAbsent) {
                ignored.add(noCreateIfAbsent ? "--no-create-if-absent" : "--create-if-absent");
            }
            if (matchByNameLegacy || noMatchByName) {
                ignored.add(noMatchByName ? "--no-match-by-name" : "--match-by-name");
            }
            if (paths != null && !paths.isEmpty()) {
                ignored.add("PATH");
            }
            if (!ignored.isEmpty()) {
                System.err.println("midgie: --apply-plan replays a plan as written; "
                        + String.join(", ", ignored) + " would have no effect. Re-run --plan to change what "
                        + "is applied.");
                return EXIT_ERROR;
            }
        }
        if (planDir != null && (dryRun || retry)) {
            System.err.println("midgie: --plan writes the plan files it computes; "
                    + "--dry-run and --retry do not apply to it.");
            return EXIT_ERROR;
        }

        // Resolved whether or not this run writes: grouping consults the strategy before anything is
        // applied, and a bad --scope/--path value should fail an offline run too rather than be ignored.
        globalStrategy = strategyOptions.resolve();
        if (globalStrategy == null) {
            return EXIT_ERROR;
        }

        try {
            if (applyPlanDir != null) {
                Integer exit = runApplyPlan();
                reportOrphans();
                return exit;
            }

            PrismContext prismContext = SchemaOptions.newBaseContext();
            policy = Policy.effective(policyFile); // load early: fail fast on a bad policy
            if (policyFile != null) {
                // Named outright, like a tree-local config file: the policy decides what a deploy deletes, and
                // when it arrived through a config file rather than the command line, nothing else on screen
                // says which file that was — or that a relative path was read from the config file's folder.
                note("policy: " + policyFile.getAbsoluteFile().toPath().normalize() + " ("
                        + policy.ruleCount() + " rule(s), plus " + policy.builtInCount() + " built-in)");
            }

            // Everything the local filesystem can settle is settled before the first round trip. Reading the
            // tree costs milliseconds and the server costs seconds, so a typo'd path, an unreadable mount or
            // an empty directory should not be paid for twice over the network first — and against a host
            // that is down, a local mistake would otherwise surface as a connection error.
            // A plan directory may legitimately sit inside the tree it is planning; its files are output, not
            // source, so they are never discovery input.
            Discovery.ScanResult scan = Discovery.scan(prismContext, paths, planDirectories());
            if (!scan.unreadable().isEmpty()) {
                // A file left broken by a merge, say. Deploying the rest would go green with it missing, and on
                // a tracked tree read everything it used to declare as removed.
                for (String failure : scan.unreadable()) {
                    warn(failure);
                }
                return EXIT_ERROR;
            }
            List<SchemaFile> deployableSchemas = scan.schemas().stream()
                    .filter(SchemaFile::deployable).collect(Collectors.toList());
            List<SchemaFile> localSchemas = scan.schemas().stream()
                    .filter(schema -> !schema.deployable()).collect(Collectors.toList());
            if (scan.objectFiles().isEmpty() && deployableSchemas.isEmpty()) {
                // Nothing to deploy, and no amount of schema from the server can change that: grouping has
                // no files to make objects out of. The same question is asked again after parsing, for the
                // tree that holds candidate files which all turn out not to be midPoint objects.
                System.err.println("midgie: " + nothingFound(scan));
                return EXIT_ERROR;
            }

            // Collect every extension schema — the tree, CLI flags, and the live server — and register in one
            // batch (prism replaces the dynamic set on each call). Order matters: the tree/flag schemas go in
            // first so they win the per-namespace dedupe (SchemaSupport.register) over the live read, which
            // also stops prism warning about re-registering a schema that is both in the tree and on the
            // server. The live half is fetched separately so that the local half — which is also where
            // --schema-dir and --schema are validated — happens on this side of the round trip.
            Map<String, Element> schemaMap = new LinkedHashMap<>();
            for (SchemaFile schema : scan.schemas()) {
                schemaMap.put(schema.description(), schema.schemaElement());
            }
            schemaMap.putAll(schemaOptions.collectSchemas(prismContext, null));

            // Last moment, and not a moment earlier: building the client resolves the password, which may
            // mean an interactive prompt or a trip to a password safe. Being asked for a passphrase and then
            // told the tree is empty is a poor trade, so everything local has already had its say.
            MidpointRestClient client = null;
            if (live) {
                client = connectionOptions.newClient();
                if (client == null) {
                    return EXIT_ERROR;
                }
            }

            if (live && !quiet) {
                // After the tree, before any parsing: this warns that the bundled schema may misparse the
                // objects, so it has to precede the parse it is about, not the walk that found the files.
                VersionSupport.warnIfMismatch(prismContext, client);
            }
            if (live) {
                schemaMap.putAll(schemaOptions.collectLiveSchemas(prismContext, client));
            }
            LOG.debug("scan: {} schema file(s), {} candidate object file(s), {} warning(s); "
                    + "collected {} extension schema(s)", scan.schemas().size(), scan.objectFiles().size(),
                    scan.warnings().size(), schemaMap.size());
            SchemaSupport.register(prismContext, schemaMap);

            List<String> warnings = new ArrayList<>(scan.warnings());
            List<String> unreadable = new ArrayList<>();
            List<DeployObject> objects =
                    DeployObject.group(parseObjects(prismContext, scan, warnings, unreadable));
            if (!unreadable.isEmpty()) {
                // These files say they are midPoint objects and cannot be read. Carrying on would deploy the
                // rest of the tree and exit 0, so a pipeline goes green with an object missing.
                for (String failure : unreadable) {
                    warn(failure);
                }
                return EXIT_ERROR;
            }

            LOG.debug("grouped into {} object(s); {} deployable schema(s), {} local-only schema(s)",
                    objects.size(), deployableSchemas.size(), localSchemas.size());

            if (deployableSchemas.isEmpty() && objects.isEmpty()) {
                System.err.println("midgie: " + nothingFound(scan));
                return EXIT_ERROR;
            }
            String clash = clashBetween(deployableSchemas, objects);
            if (clash != null) {
                System.err.println("midgie: " + clash);
                return EXIT_ERROR;
            }
            int unitCount = deployableSchemas.size() + objects.size();
            if ((connectionOptions.oid != null || format != null) && unitCount != 1) {
                // Both name "the" object: --oid says where it lives, --format hands over its one change.
                System.err.println("midgie: " + (connectionOptions.oid != null ? "--oid" : "--format/--output")
                        + " applies to exactly one object, and " + String.join(", ", pathLabels())
                        + " describe(s) " + unitCount + ".");
                return EXIT_ERROR;
            }
            if (connectionOptions.oid != null) {
                objects = withOid(objects, connectionOptions.oid);
                // The one unit may be an extension schema, which is deployed as its SchemaType object; --oid
                // used to be dropped there without a word, and the schema went to the OID in its file.
                for (SchemaFile schema : deployableSchemas) {
                    schema.fragment().object().setOid(connectionOptions.oid);
                }
            }

            TopoSort.Result<SchemaFile> schemaOrder = sortSchemas(deployableSchemas);
            TopoSort.Result<DeployObject> objectOrder = sortObjects(objects);
            LOG.debug("schema order: {}", schemaOrder.ordered().stream()
                    .map(SchemaFile::targetNamespace).collect(Collectors.toList()));
            LOG.debug("object order: {}", objectOrder.ordered().stream()
                    .map(DeployObject::identityLabel).collect(Collectors.toList()));
            if (!schemaOrder.brokenAt().isEmpty() || !objectOrder.brokenAt().isEmpty()) {
                LOG.debug("dependency cycle(s) broken: {} schema, {} object",
                        schemaOrder.brokenAt().size(), objectOrder.brokenAt().size());
            }

            if (!live) {
                printPlanTable(schemaOrder, objectOrder);
                emitWarnings(localSchemas, schemaOrder, objectOrder, warnings);
                return 0;
            }

            emitWarnings(localSchemas, schemaOrder, objectOrder, warnings);
            Reconciler reconciler = new Reconciler(prismContext, client, createIfAbsent(), matchByName());
            List<DeployObject> units = flatten(schemaOrder, objectOrder);
            // Every object of the tree is named from the tree itself; only references beyond it are looked up.
            oidComments = new OidComments(OidComments.serverLookup(prismContext, client));
            for (DeployObject unit : units) {
                oidComments.know(unit.oid(), unit.typeName(), unit.name());
            }
            LOG.debug("{} apply unit(s); mode={}", units.size(), planDir != null ? "plan" : "apply");

            Tracking tracking = resolveTracking(prismContext, client);
            List<RemovalDetector.Removal> removals = findRemovals(prismContext, tracking, units);
            settleMultiValue(tracking);
            reportRemovals(removals, tracking);
            // Under --no-prune too: an object's removals are not sent then, but they are still what tells an item
            // the tree deployed and dropped apart from one it never deployed, which is reported as kept.
            for (RemovalDetector.Removal removal : removals) {
                if (!removal.isPrunable()) {
                    if (prune()) {
                        warn("cannot remove " + removal + " — no item definition for it.");
                        unprunable.add(removal);
                    }
                } else if (removal.unit != null) {
                    removalsByUnit.computeIfAbsent(removal.unit, key -> new ArrayList<>()).add(removal);
                }
            }

            // Only a run that actually writes needs a net; --dry-run and --plan touch nothing.
            if (planDir == null && !dryRun && !noRollbackPlan) {
                rollback = new RollbackPlan(prismContext, client, oidComments);
            }

            Integer exit;
            try {
                exit = (planDir != null) ? runPlan(reconciler, units, tracking) : runApply(reconciler, units);
            } catch (Exception e) {
                // A lost connection or a rejected login ends the run part way, and what was written before it
                // is exactly what the net is for; writing it only after a clean pass left none in that case.
                writeRollbackPlan(client, tracking);
                throw e;
            }

            // After the pass, not before it: a rule is only marked as having matched when it is offered to
            // an object, and that happens inside the pass. Reported earlier, every rule looked unmatched —
            // including the ones that were about to be applied.
            reportUnmatchedPolicyRules();

            if (planDir == null && !dryRun) {
                appliedUnits.removeAll(unjudgedUnits);
            }
            if (!unprunable.isEmpty()) {
                // Left unsaid, the object's rows would move past these and they would never be offered again.
                for (RemovalDetector.Removal removal : unprunable) {
                    appliedUnits.remove(removal.unit);
                }
                // A preview and a plan fail as the run would: they used to exit 0 or 1 over a tree whose
                // real deploy then failed on exactly these.
                exit = EXIT_ERROR;
            }
            // Written after the apply: the tree's objects are on the server now, whatever the plan said.
            oidComments.treeDeployed();
            // State first: the net records which rows the run moved, so a rollback can move them back.
            if (planDir == null && !dryRun && sentSomething) {
                recordState(prismContext, client, tracking, units);
            }
            writeRollbackPlan(client, tracking);
            reportOrphans();
            return exit;
        } catch (Exception e) {
            String message = (e.getMessage() != null) ? e.getMessage() : e.toString();
            System.err.println("midgie: " + message);
            LOG.trace("deploy failed", e);
            return EXIT_ERROR;
        }
    }

    // --- delete detection (three-way merge against what was deployed last time) ---

    /** What this run knows about tracking: the deployment's identity, the state object, and its rows. */
    private static final class Tracking {
        final DeploymentIdentity identity;
        final PrismObject<? extends Objectable> table;
        final FileTracking files;          // null when this run is not tracked
        final String currentRevision;

        Tracking(DeploymentIdentity identity, PrismObject<? extends Objectable> table, FileTracking files,
                String currentRevision) {
            this.identity = identity;
            this.table = table;
            this.files = files;
            this.currentRevision = currentRevision;
        }

        boolean canRecord() {
            return table != null && files != null && currentRevision != null;
        }

        boolean canDetect() {
            return canRecord();
        }
    }

    /**
     * Work out whether this run can track removals, explaining once when it cannot. midgie never creates the
     * state object here — {@code midgie init --create-state} is the deliberate opt-in — so an untracked
     * server just keeps behaving exactly as before.
     */
    private Tracking resolveTracking(PrismContext prismContext, MidpointRestClient client) {
        File tree = (paths != null && !paths.isEmpty()) ? paths.get(0) : null;
        DeploymentIdentity identity = DeploymentIdentity.of(tree, app);
        if (!identity.isAvailable()) {
            trackingNote("note: removals cannot be detected — " + identity.unavailableReason() + ".");
            return new Tracking(identity, null, null, null);
        }

        PrismObject<? extends Objectable> table;
        try {
            table = SyncState.find(prismContext, client);
        } catch (Exception e) {
            // Every other "cannot detect" branch says so. Staying silent here — the likely cause is the
            // deploy account having no read on lookupTables — makes a run with tracking quietly dead look
            // exactly like a healthy one.
            trackingNote("note: removals cannot be detected — could not read " + SyncState.TABLE_NAME + ": "
                    + safe(e.getMessage()));
            LOG.trace("could not read {}", SyncState.TABLE_NAME, e);
            return new Tracking(identity, null, null, null);
        }
        if (table == null) {
            trackingNote("note: no " + SyncState.TABLE_NAME + " on the server, so removals cannot be detected — "
                    + "run 'midgie init --create-state --url …' to enable it.");
            return new Tracking(identity, null, null, null);
        }
        if (!SyncState.isEnabled(table)) {
            trackingNote("note: " + SyncState.TABLE_NAME + " is '" + SyncState.lifecycleOf(table)
                    + "', so removal tracking is switched off.");
            return new Tracking(identity, null, null, null);
        }

        GitRevision git = identity.git();
        String current = git.head();
        if (current != null && git.isDirty()) {
            trackingNote("note: the tree has uncommitted changes; a changed file is recorded as its committed "
                    + "version, marked dirty, which does not describe exactly what was deployed.");
        }
        // The paths this run deploys, as the repository knows them: every file row inside them is this
        // run's to read and to write, and no other.
        List<String> scopes = new ArrayList<>();
        for (File path : paths) {
            String scope = git.scopeOf(path);
            if (scope == null) {
                trackingNote("note: " + path + " is outside the repository of " + tree + "; its files are not "
                        + "tracked.");
            } else {
                scopes.add(scope);
            }
        }
        FileTracking files = new FileTracking(git, identity.namespace(), scopes, SyncState.rows(table));
        LOG.debug("tracking {}: current={}, scope={}", identity.key(), current, scopes);
        return new Tracking(identity, table, files, current);
    }

    /**
     * Values deployed earlier that the tree no longer declares, or an empty list. Also says where an object
     * that left the deployed files went, and when an object has files outside what this run deploys.
     */
    private List<RemovalDetector.Removal> findRemovals(PrismContext prismContext, Tracking tracking,
            List<DeployObject> units) {
        if (!tracking.canDetect()) {
            return List.of();
        }
        FileTracking files = tracking.files;
        RemovalDetector detector =
                new RemovalDetector(prismContext, files.history(), "the file rows of " + SyncState.TABLE_NAME);
        List<RemovalDetector.Removal> detected = detector.detect(units);
        if (!files.unreadable().isEmpty()) {
            // A content git can no longer produce reads exactly like a file that did not exist yet, so without
            // this the run would find nothing, look clean, and then record new rows — turning "we cannot tell
            // what was removed" into "nothing was removed", permanently.
            detectionBroken = true;
            warn("what was last applied is not in this repository for " + String.join(", ", files.unreadable())
                    + " (history rewritten, squash-merged, or fetched shallow), so removals cannot be detected "
                    + "this run. Nothing will be removed and no rows are written. To start over, delete those "
                    + "rows from " + SyncState.TABLE_NAME + ".");
            return List.of();
        }
        comparedPaths = detector.comparedPaths();
        reportUnjudged(detector.unjudged(), units, files);
        for (RemovalDetector.Orphan orphan : detector.orphaned()) {
            String movedTo = files.movedTo(orphan.objectLabel, orphan.paths);
            if (movedTo != null) {
                // Not gone, moved: its old content stays recorded until a deploy of the new place reads it.
                note("note: " + orphan.objectLabel + " moved from " + orphan.sourceFile + " to " + movedTo
                        + "; deploy that path to apply what changed with the move.");
                continue;
            }
            // The whole object left the tree. midgie does not delete objects, and taking one apart value by
            // value would strip its name, so say it plainly and leave the decision to the operator.
            orphanPaths.addAll(orphan.paths);
            orphans.add(new PlanState.Orphan(orphan.objectLabel, orphan.sourceFile));
        }
        Set<String> deployed = new HashSet<>();
        for (DeployObject unit : units) {
            for (File file : unit.sourceFiles()) {
                String path = files.pathOf(file);
                if (path != null) {
                    deployed.add(path);
                }
            }
        }
        for (DeployObject unit : units) {
            List<String> others = files.otherFilesOf(unit, deployed);
            if (!others.isEmpty()) {
                // Deploying part of an object applies only that part: what the other files contribute is
                // neither sent nor read as removed, but the operator should know the object is split.
                note("note: " + unit.objectPath() + " also has " + others.size() + " file(s) outside the "
                        + "deployed path (" + String.join(", ", others) + "); deploying part of an object "
                        + "applies only that part.");
            }
        }
        return withoutRestricted(policy, globalStrategy, detected);
    }

    /**
     * Say which objects' removals could not be judged, and keep the rows of those whose cause may pass. A value
     * dropped from such an object stays on the server this run; left unsaid, and with the rows moved on, it would
     * stay for good — "cannot tell" read as "nothing was removed".
     */
    private void reportUnjudged(List<RemovalDetector.Unjudged> unjudged, List<DeployObject> units,
            FileTracking files) {
        for (RemovalDetector.Unjudged entry : unjudged) {
            List<DeployObject> owners = new ArrayList<>();
            if (entry.unit() != null) {
                owners.add(entry.unit());
            } else {
                for (DeployObject unit : units) {
                    for (File file : unit.sourceFiles()) {
                        if (entry.paths().contains(files.pathOf(file)) && !owners.contains(unit)) {
                            owners.add(unit);
                        }
                    }
                }
            }
            String subject = owners.isEmpty() ? String.join(", ", entry.paths())
                    : owners.get(0).objectPath() + " (" + String.join(", ", entry.paths()) + ")";
            if (entry.holdBack()) {
                unjudgedUnits.addAll(owners);
                warn(subject + ": " + entry.reason() + ", so values dropped from it since cannot be detected and "
                        + "are not removed this run. Its record is kept, and the next run tries again.");
            } else {
                warn(subject + ": " + entry.reason() + ", so values dropped from it since cannot be detected and "
                        + "are not removed this run. Its record moves on to this run's content, so detection works "
                        + "again from the next deploy; check the object for values the tree no longer declares.");
            }
        }
    }

    /**
     * Drop the removals under items the policy's {@code apply} rules restrict. Once the object exists midgie
     * does not write an {@code on-create} item at all, and never removes an {@code if-absent} one — and
     * pruning a value is a removal like any other, so a value dropped from the tree there stays on the server.
     */
    static List<RemovalDetector.Removal> withoutRestricted(Policy policy, StrategyOptions.Resolved baseline,
            List<RemovalDetector.Removal> removals) {
        if (policy == null || removals.isEmpty()) {
            return removals;
        }
        List<RemovalDetector.Removal> kept = new ArrayList<>();
        for (RemovalDetector.Removal removal : removals) {
            ApplyRules apply = policy.resolveFor(typeOf(removal), nameOf(removal), oidOf(removal), baseline).apply;
            ApplyRules.Mode mode = (removal.itemPath != null) ? apply.modeOf(removal.itemPath)
                    : apply.modeOf(removal.path);
            if (mode != ApplyRules.Mode.ALWAYS) {
                LOG.debug("not a removal: {} {} is apply={}", removal.objectLabel, removal.path, mode.keyword);
            } else {
                kept.add(removal);
            }
        }
        return kept;
    }

    /*
     * The object a removal's rules are looked up for: the one in the tree, as the change itself is, and the
     * history's only when the removal has none. Resolved against the history, a rule naming an object by its new
     * name missed every removal made in the commit that renamed it.
     */

    private static String typeOf(RemovalDetector.Removal removal) {
        if (removal.unit != null && removal.unit.typeName() != null) {
            return removal.unit.typeName();
        }
        return (removal.objectClass != null) ? removal.objectClass.getSimpleName() : "?";
    }

    private static String nameOf(RemovalDetector.Removal removal) {
        return (removal.unit != null && removal.unit.name() != null) ? removal.unit.name() : removal.objectName;
    }

    private static String oidOf(RemovalDetector.Removal removal) {
        return (removal.unit != null && removal.unit.oid() != null) ? removal.unit.oid() : removal.oid;
    }

    /**
     * Say what is no longer declared, and whether it will be removed or only reported. Without
     * {@code --no-prune} only the count is given; otherwise each object names its own removals beside its change
     * ({@link #reportRemoving}), since that is where they are sent.
     */
    private void reportRemovals(List<RemovalDetector.Removal> removals, Tracking tracking) {
        if (removals.isEmpty()) {
            return;
        }
        String summary = removals.size() + " value(s) deployed earlier are no longer declared by the tree";
        if (!prune()) {
            note(summary + " (reported only: --no-prune).");
            return;
        }
        note(summary + removalDisposition() + ".");
    }

    /**
     * Name what the change keeps on the server beyond the tree, so a value someone added is visible: list values
     * merge keeps, and on a tracked run items inside values the tree declares that it never deployed. One line
     * per object; the fix, when the tree should own a list, is a policy rule.
     */
    private void reportKept(OutputLayout.Block block, DeployObject unit, Reconciler.Outcome outcome) {
        if (outcome.kept.isEmpty() && outcome.keptNested.isEmpty()) {
            return;
        }
        List<String> parts = new ArrayList<>();
        if (!outcome.kept.isEmpty()) {
            parts.add(String.join(", ", outcome.kept) + " (multivalue=merge; a '<path>  multivalue=replace' "
                    + "policy rule makes the tree own such a list)");
        }
        if (!outcome.keptNested.isEmpty()) {
            parts.add(String.join(", ", outcome.keptNested) + " (inside values the tree declares; it never "
                    + "deployed them)");
        }
        note(block, unit, "note: keeps live value(s) the tree does not list: " + String.join("; ", parts) + ".");
    }

    /**
     * Under {@code -v}, name each list {@code --equivalence auto} compared as whole values rather than pairing its
     * entries by natural key, and the key inside it that is not filled in. That choice decides whether a change
     * inside an entry is an edit in place or a replacement of the entry, and it was visible only inside a debug
     * log line.
     */
    private void reportKeyPairingDeclined(OutputLayout.Block block, DeployObject unit, Reconciler.Outcome outcome) {
        if (!verbose()) {
            return;
        }
        for (String declined : outcome.keyPairingDeclined) {
            note(block, unit, "note: " + declined + ".");
        }
    }

    /** Name the dropped values an object's PATCH deletes, so the change being confirmed is the whole change. */
    private void reportRemoving(OutputLayout.Block block, DeployObject unit, Reconciler.Outcome outcome) {
        if (outcome.removing.isEmpty()) {
            return;
        }
        note(block, unit, "note: removing " + outcome.removing.size() + " value(s) no longer declared by the tree:");
        for (RemovalDetector.Removal removal : outcome.removing) {
            noteItem(block, String.valueOf(removal));
        }
    }

    /**
     * Say which differences a preview is not going to send because a policy {@code apply} rule holds them
     * back — otherwise a task shown as {@code in-sync} while its {@code executionState} plainly differs reads
     * as a diff that missed something. Each path names the rule that held it back.
     */
    private void reportHeldBack(OutputLayout.Block block, DeployObject unit, Reconciler.Outcome outcome) {
        if (!outcome.heldBack.isEmpty()) {
            note(block, unit, "note: left alone by the policy: " + String.join(", ", outcome.heldBack));
        }
    }

    /**
     * Say which encrypted values differ but were not sent. {@code diff} and {@code apply} always said so;
     * {@code deploy} reported such an object {@code in-sync} without a word, so a password hint changed in the
     * tree — protected since midPoint 4.10.5 — looked applied when it had been ignored. Printed on every run,
     * not just a preview, because nothing else tells the operator the value was never written.
     */
    private void reportProtectedSkipped(OutputLayout.Block block, DeployObject unit, Reconciler.Outcome outcome) {
        if (!outcome.protectedSkipped.isEmpty()) {
            note(block, unit, "note: skipped protected value(s) at " + String.join(", ", outcome.protectedSkipped)
                    + " — encrypted values are never patched; they are written only when midgie creates the "
                    + "object. Change them through midPoint.");
        }
    }

    /** Name the policy lines that applied to nothing, so a typo is not mistaken for a rule that ran. */
    private void reportUnmatchedPolicyRules() {
        if (policy == null) {
            return;
        }
        for (String rule : policy.unmatchedRules()) {
            note("note: policy rule matched no object in this tree: " + rule);
        }
    }

    /** How this run will treat the removals it just counted — never claiming a deletion that cannot happen. */
    private String removalDisposition() {
        if (planDir != null) {
            return " (written into each object's plan file)";
        }
        return dryRun ? " (each object's change would remove its own)" : " (each object's change removes its own)";
    }

    private boolean prune() {
        return !noPrune;
    }

    /**
     * Settle {@code --multivalue auto} for this run. {@code merge} keeps what the server holds beyond the tree —
     * entries midPoint ships, an admin added, another tree owns — and is only safe where the removals of what
     * the tree itself dropped are tracked; without them it could express neither a removal nor an edit of an
     * entry without a natural key. So: merge when this run can track the tree, else replace, said out loud.
     * {@code --no-prune} does not change the answer: it withholds the removals, and flipping the whole run to
     * replace because of it would delete more, not less.
     *
     * <p>The same answer decides ownership below the top level ({@link StrategyOptions.Resolved#settleTracking}),
     * whatever {@code --multivalue} says: on a tracked run an item inside a declared container value that the
     * tree does not mention is left alone unless the tree deployed it and dropped it since.
     */
    private void settleMultiValue(Tracking tracking) {
        boolean tracked = tracking.canRecord() && !detectionBroken;
        globalStrategy = globalStrategy.settleTracking(tracked);
        if (!globalStrategy.multiValueAuto) {
            return;
        }
        globalStrategy = globalStrategy.settleMultiValue(tracked);
        if (tracked) {
            LOG.debug("--multivalue auto: merge — removals are tracked for {}", tracking.identity.key());
        } else {
            trackingNote("note: removals are not tracked, so --multivalue auto means replace: live values a "
                    + "multi-valued item holds beyond what the file lists are deleted.");
        }
    }

    /** This object's removals, for its PATCH to carry (withheld from it under --no-prune). */
    private List<RemovalDetector.Removal> removalsFor(DeployObject unit) {
        return removalsByUnit.getOrDefault(unit, List.of());
    }

    /**
     * Write this run to {@code midgie-state}, as one PATCH of individual rows: the rows of the files of every
     * object it applied completely, and a {@code deploy:} row per deployed path. An object that failed, was
     * skipped or declined, or kept a removal back keeps its files' rows as they were, so what it dropped stays
     * detectable on the next run; the rest of the tree moves on regardless.
     */
    private void recordState(PrismContext prismContext, MidpointRestClient client, Tracking tracking,
            List<DeployObject> units) {
        if (!tracking.canRecord()) {
            return;
        }
        try {
            StateRows rows = stateRows(tracking, units, appliedUnits);
            if (rollback != null) {
                for (DeployObject unit : rows.applied()) {
                    rollback.recordRows(writtenOids.get(unit), rows.byUnit().getOrDefault(unit, List.of()));
                }
                rollback.recordRunRows(rows.run());
            }
            SyncState.Changes changes = rows.changes();
            String value = FileTracking.deployValue(tracking.currentRevision, connectionOptions.account(),
                    VersionSupport.toolVersion(), sent.created, sent.patched, sent.inSync, sent.removed,
                    sent.skipped, sent.failed);
            for (String scope : tracking.files.scopes()) {
                changes.set(tracking.files.deployKeyOf(scope), value, null);
            }
            SyncState.apply(prismContext, client, tracking.table, changes);
            if (!detectionBroken && !rows.applied().isEmpty()) {
                int left = units.size() - rows.applied().size();
                status("Recorded " + rows.applied().size() + " applied object(s) at "
                        + GitRevision.abbreviate(tracking.currentRevision) + " in " + SyncState.TABLE_NAME + "."
                        + (left > 0 ? " " + left + " object(s) not applied keep their earlier record, so what "
                                + "they dropped stays detectable." : ""));
            }
        } catch (Exception e) {
            // Bookkeeping must never fail a deploy that already succeeded — but it is said whatever --quiet
            // asks: the next run judges its removals against the record this one failed to write.
            warn("could not record what was deployed: " + safe(e.getMessage()));
            LOG.trace("recording the deployment failed", e);
        }
    }

    /**
     * The file rows a run that applied {@code applied} writes, and how they split: per object, the rows of its
     * files and of the gone files it compared against, each as it was and as it will be; for the run, what no one
     * object claims — the rows of an object that left the tree, an earlier version's tree rows.
     */
    private record StateRows(List<DeployObject> applied, SyncState.Changes changes,
            Map<DeployObject, List<RollbackPlan.RowChange>> byUnit, List<RollbackPlan.RowChange> run) {
    }

    private StateRows stateRows(Tracking tracking, List<DeployObject> units, Set<DeployObject> appliedSet) {
        List<DeployObject> applied = new ArrayList<>();
        Set<String> consumed = new LinkedHashSet<>(orphanPaths);
        Set<String> notApplied = new LinkedHashSet<>();
        for (DeployObject unit : units) {
            if (appliedSet.contains(unit)) {
                applied.add(unit);
                consumed.addAll(comparedPaths.getOrDefault(unit, List.of()));
            } else {
                for (File file : unit.sourceFiles()) {
                    String path = tracking.files.pathOf(file);
                    if (path != null) {
                        notApplied.add(path);
                    }
                }
            }
        }
        Map<DeployObject, List<RollbackPlan.RowChange>> byUnit = new IdentityHashMap<>();
        if (detectionBroken) {
            return new StateRows(applied, new SyncState.Changes(), byUnit, List.of());
        }
        List<String> notes = new ArrayList<>();
        SyncState.Changes changes = tracking.files.changesAfter(applied, consumed, notApplied, notes);
        for (String line : notes) {
            note("note: " + line + ".");
        }

        Map<String, SyncState.Row> before = new LinkedHashMap<>();
        for (SyncState.Row row : SyncState.rows(tracking.table)) {
            before.putIfAbsent(row.key(), row); // the row SyncState.apply updates
        }
        Set<String> changed = new LinkedHashSet<>(changes.set.keySet());
        changed.addAll(changes.delete);
        Set<String> claimed = new HashSet<>();
        for (DeployObject unit : applied) {
            Set<String> keys = new LinkedHashSet<>();
            for (File file : unit.sourceFiles()) {
                String path = tracking.files.pathOf(file);
                if (path != null) {
                    keys.add(tracking.files.keyOf(path));
                }
            }
            for (String path : comparedPaths.getOrDefault(unit, List.of())) {
                keys.add(tracking.files.keyOf(path));
            }
            keys.retainAll(changed);
            claimed.addAll(keys);
            byUnit.put(unit, rowChanges(keys, before, changes));
        }
        Set<String> rest = new LinkedHashSet<>(changed);
        rest.removeAll(claimed);
        return new StateRows(applied, changes, byUnit, rowChanges(rest, before, changes));
    }

    private static List<RollbackPlan.RowChange> rowChanges(Set<String> keys, Map<String, SyncState.Row> before,
            SyncState.Changes changes) {
        List<RollbackPlan.RowChange> rows = new ArrayList<>();
        for (String key : keys) {
            SyncState.Row old = before.get(key);
            String[] next = changes.set.get(key);
            String oldValue = (old != null) ? old.value() : null;
            String newValue = (next != null) ? next[0] : null;
            if (Objects.equals(oldValue, newValue)) {
                continue; // a row the run leaves as it was has nothing to put back
            }
            rows.add(new RollbackPlan.RowChange(key, oldValue, (old != null) ? old.label() : null, newValue,
                    (next != null) ? next[1] : null));
        }
        return rows;
    }

    /** The strategy for one object: the CLI flags, with any matching policy knob, the file's or a built-in, layered on. */
    private StrategyOptions.Resolved strategyFor(DeployObject unit) {
        return (policy != null)
                ? policy.resolveFor(unit.typeName(), unit.name(), unit.oid(), globalStrategy)
                : globalStrategy;
    }

    /**
     * Parse the candidate object files (schema is now registered); skip anything that isn't an object.
     *
     * <p>Discovery treats any well-formed XML that is not a schema as a candidate, so a tree may hold files
     * that were never meant to be objects — those are skipped with an advisory. A file whose root element is
     * in the common-3 namespace is a different matter: it says plainly that it is a midPoint object, and if
     * it cannot be parsed the tree cannot be deployed as written. Those go to {@code unreadable}, which the
     * caller turns into an error, rather than into an advisory that {@code --quiet} would swallow.
     */
    private static List<Fragment> parseObjects(PrismContext prismContext, Discovery.ScanResult scan,
            List<String> warnings, List<String> unreadable) {
        List<Fragment> fragments = new ArrayList<>();
        for (File file : scan.objectFiles()) {
            PrismObject<? extends Objectable> object;
            try {
                object = prismContext.parseObject(file);
            } catch (Exception e) {
                if (Discovery.declaresMidpointObject(file)) {
                    unreadable.add("could not parse " + file + " (" + safe(e.getMessage()) + ")");
                } else {
                    warnings.add("skipped " + file + " — could not parse (" + safe(e.getMessage()) + ")");
                }
                continue;
            }
            if (object.getCompileTimeClass() == null) {
                warnings.add("skipped " + file + " — not a recognized midPoint object type");
                continue;
            }
            fragments.add(Fragment.of(object, file));
        }
        return fragments;
    }

    /**
     * Two units that would land on one thing, or {@code null}. Two schemas of one namespace: ordering kept the
     * first for every import of it and midPoint holds one schema per namespace, so which one won depended on the
     * file names. A schema and an object, or two schemas, with one OID: each wrote over the other in turn.
     */
    static String clashBetween(List<SchemaFile> schemas, List<DeployObject> objects) {
        Map<String, SchemaFile> byNamespace = new LinkedHashMap<>();
        Map<String, SchemaFile> byOid = new LinkedHashMap<>();
        for (SchemaFile schema : schemas) {
            SchemaFile earlier = byNamespace.putIfAbsent(schema.targetNamespace(), schema);
            if (earlier != null) {
                return "two extension schemas declare the namespace " + schema.targetNamespace() + ": "
                        + earlier.sourceFile() + " and " + schema.sourceFile() + ". midPoint holds one schema per "
                        + "namespace; deploy one of them.";
            }
            String oid = schema.fragment().oid();
            if (oid != null && !oid.isBlank()) {
                SchemaFile sameOid = byOid.putIfAbsent(oid, schema);
                if (sameOid != null) {
                    return sameOid.sourceFile() + " and " + schema.sourceFile() + " are two schemas with one OID ("
                            + oid + "); each would overwrite the other.";
                }
            }
        }
        for (DeployObject object : objects) {
            SchemaFile schema = (object.oid() != null) ? byOid.get(object.oid()) : null;
            if (schema != null) {
                return object.sourceFiles().get(0) + " (" + object.typeName() + ") and the schema in "
                        + schema.sourceFile() + " have one OID (" + object.oid() + "); each would overwrite the "
                        + "other.";
            }
        }
        return null;
    }

    private static TopoSort.Result<SchemaFile> sortSchemas(List<SchemaFile> schemas) {
        Map<String, SchemaFile> byNamespace = new LinkedHashMap<>();
        for (SchemaFile schema : schemas) {
            byNamespace.putIfAbsent(schema.targetNamespace(), schema);
        }
        return TopoSort.sort(schemas,
                schema -> schema.importedNamespaces().stream().map(byNamespace::get)
                        .filter(Objects::nonNull).collect(Collectors.toList()),
                Comparator.comparing(SchemaFile::targetNamespace)
                        .thenComparing(schema -> schema.sourceFile().getPath()));
    }

    private static TopoSort.Result<DeployObject> sortObjects(List<DeployObject> objects) {
        Map<String, DeployObject> byOid = new LinkedHashMap<>();
        for (DeployObject object : objects) {
            if (object.oid() != null) {
                byOid.putIfAbsent(object.oid(), object);
            }
        }
        return TopoSort.sort(objects,
                object -> object.outboundOids().stream().map(byOid::get)
                        .filter(Objects::nonNull).collect(Collectors.toList()),
                Comparator.comparing(DeployObject::typeName)
                        .thenComparing(object -> object.name() == null ? "" : object.name())
                        .thenComparing(DeployObject::identityKey));
    }

    /**
     * The ordered apply units: the deployable schema objects first, then the tree's objects — one unit per
     * object, whatever number of files describe it.
     */
    private List<DeployObject> flatten(TopoSort.Result<SchemaFile> schemaOrder,
            TopoSort.Result<DeployObject> objectOrder) throws SchemaException {
        List<DeployObject> units = new ArrayList<>();
        for (SchemaFile schema : schemaOrder.ordered()) {
            units.add(DeployObject.of(schema.fragment()));
        }
        units.addAll(objectOrder.ordered());
        return units;
    }

    // --- apply ---

    private Integer runApply(Reconciler reconciler, List<DeployObject> units) throws Exception {
        if (dryRun) {
            Tally preview = pass(reconciler, units, true, false);
            emitSummary(preview, true);
            if (format != null) {
                Reconciler.Outcome outcome = preview.lastOutcome;
                String payload = (outcome != null) ? reconciler.render(outcome, format) : null;
                if (payload != null && payload.stripLeading().startsWith("<")) {
                    payload = oidComments.annotate(payload, outcome.oid, units.get(0).typeName());
                }
                if (payload == null) {
                    // A skipped object has no change to hand over, and an empty payload with exit 0 would
                    // read as "in sync". The row saying why was a status line, which diff does not print.
                    if (outcome != null && outcome.action == Reconciler.Action.SKIP) {
                        System.err.println("midgie: " + units.get(0).objectPath() + ": " + outcome.detail + ".");
                    }
                    return EXIT_ERROR;
                }
                // The payload — written even under --quiet, which silences prose, not results.
                Io.writePayload(output, payload);
            }
            if (preview.failed > 0) {
                return EXIT_ERROR;
            }
            return preview.changed() > 0 ? 1 : 0;
        }
        if (confirmEachApply && Io.console() == null) {
            System.err.println("midgie: --confirm-each-apply needs an interactive session; "
                    + "use --yes for non-interactive apply.");
            return EXIT_ERROR;
        }
        if (!assumeYes && !confirmEachApply) {
            Tally preview = pass(reconciler, units, true, false);
            emitSummary(preview, true);
            if (preview.changed() == 0) {
                // Nothing to confirm, and nothing a writing pass would do differently: the objects are deployed
                // as they are. Without this an interactive run over an in-sync tree never moved the rows, which
                // --yes over the same tree did, so the removals of later commits were judged against old history.
                appliedUnits.addAll(preview.landedUnits);
                sent.addCounts(preview);
                sent.failed = preview.failed;
                sentSomething = true;
                return preview.failed > 0 ? EXIT_ERROR : 0;
            }
            Integer stop = confirmOnce(preview.changed());
            if (stop != null) {
                return stop;
            }
            confirmedChanges = preview.changes;
        }
        Tally applied = pass(reconciler, units, false, confirmEachApply);
        sentSomething = true;
        sent.addCounts(applied);
        List<DeployObject> failed = applied.failedUnits;
        if (retry && !failed.isEmpty()) {
            // Summarise after the retry rounds, not before: a run whose retries fixed everything used to end
            // on a line saying units had failed while exiting 0.
            emitSummary(applied, false);
            failed = retryUntilStable(failed, remaining -> {
                Tally again = pass(reconciler, remaining, false, confirmEachApply);
                sent.addCounts(again);
                return again.failedUnits;
            });
            status(failed.isEmpty()
                    ? "Done after retry: all units applied."
                    : "Done after retry: " + failed.size() + " unit(s) still failing.");
        } else {
            emitSummary(applied, false);
        }
        sent.failed = failed.size();
        return failed.isEmpty() ? 0 : EXIT_ERROR;
    }

    /**
     * Reconcile every unit in order. When {@code preview} is set, nothing is written. A transport-level
     * failure (auth, connection) aborts; a per-object rejection is recorded and the run continues.
     */
    private Tally pass(Reconciler reconciler, List<DeployObject> units, boolean preview, boolean perUnitPrompt)
            throws Exception {
        Tally tally = new Tally();
        for (DeployObject unit : units) {
            try {
                Reconciler.Outcome outcome = reconciler.plan(unit.object(), strategyFor(unit), removalsFor(unit), prune());
                Reconciler.Action action = outcome.action;
                tally.lastOutcome = outcome;
                if (preview) {
                    tally.changes.put(unit, changeOf(outcome));
                } else if (confirmedChanges != null && confirmedChanges.containsKey(unit)
                        && !confirmedChanges.get(unit).equals(changeOf(outcome))) {
                    // The writing pass reads the server again, and what it found had moved since the preview:
                    // sending it would apply a change nobody confirmed.
                    tally.failed++;
                    tally.failedUnits.add(unit);
                    reportUnit(unit, "failed", "changed on the server since the preview you confirmed, so "
                            + "nothing was sent for it; run deploy again to see the change as it is now", true);
                    continue;
                }
                if (!preview && perUnitPrompt
                        && (action == Reconciler.Action.CREATE || action == Reconciler.Action.PATCH)) {
                    if (!promptEach(action, outcome)) {
                        tally.skipped++;
                        reportUnit(unit, "skipped", "declined", false);
                        continue;
                    }
                }
                if (!preview && (action == Reconciler.Action.CREATE || action == Reconciler.Action.PATCH)) {
                    String affectedOid = reconciler.execute(outcome);
                    writtenOids.put(unit, (affectedOid != null) ? affectedOid : outcome.oid);
                    if (rollback != null) {
                        rollback.recordApplied(action, outcome, affectedOid, unit.identityLabel());
                    }
                }
                record(tally, unit, action, outcome, preview);
                boolean landed = action == Reconciler.Action.CREATE || action == Reconciler.Action.PATCH
                        || action == Reconciler.Action.IN_SYNC;
                if (landed && !outcome.removalsDeferred) {
                    tally.landedUnits.add(unit);
                    if (!preview) {
                        appliedUnits.add(unit);
                    }
                }
            } catch (Exception e) {
                if (isFatal(e)) {
                    throw e;
                }
                tally.failed++;
                tally.failedUnits.add(unit);
                reportUnit(unit, preview ? "error" : "failed", safe(e.getMessage()), true);
                LOG.trace("unit {} {} failed", unit.typeName(), unit.identityLabel(), e);
            }
        }
        return tally;
    }

    /** What an outcome would send, for telling whether two passes would do the same. */
    private static String changeOf(Reconciler.Outcome outcome) {
        return outcome.action + "\n" + outcome.oid + "\n" + Objects.toString(outcome.modificationXml, "");
    }

    private void record(Tally tally, DeployObject unit, Reconciler.Action action, Reconciler.Outcome outcome,
            boolean preview) {
        String label;
        String detail = "";
        boolean error = false;
        switch (action) {
            case CREATE:
                tally.created++;
                label = preview ? "would-create" : "created";
                break;
            case PATCH:
                tally.patched++;
                tally.removed += outcome.removing.size();
                label = preview ? "would-patch" : "patched";
                detail = (outcome.changes > 0 ? outcome.changes + " change(s): " : "") + safe(outcome.detail);
                break;
            case IN_SYNC:
                tally.inSync++;
                label = "in-sync";
                break;
            case SKIP:
                tally.skipped++;
                label = preview ? "skip" : "skipped";
                detail = safe(outcome.detail);
                break;
            case FAIL:
            default:
                tally.failed++;
                tally.failedUnits.add(unit);
                label = preview ? "error" : "failed";
                detail = safe(outcome.detail);
                error = true;
                break;
        }
        OutputLayout.Block block = unitBlock(unit, label, detail);
        addNotes(block, unit, outcome,
                preview || assumeYes || confirmEachApply, // once per run: in the preview when there is one
                preview);
        print(block, error);
    }

    /**
     * The warnings and notes about one object, under its result. Input advisories and the blast radius come
     * first, so a destructive change is visible in the plan before anything is written (and still shows in a real
     * apply); the notes that are said once per run only when {@code oncePerRun}; what a policy holds back only in
     * a preview.
     */
    private void addNotes(OutputLayout.Block block, DeployObject unit, Reconciler.Outcome outcome, boolean oncePerRun,
            boolean preview) {
        for (String warning : outcome.warnings) {
            note(block, unit, "warning: " + warning);
        }
        if (oncePerRun) {
            reportKeyPairingDeclined(block, unit, outcome);
            reportRemoving(block, unit, outcome);
            reportKept(block, unit, outcome);
        }
        if (preview) {
            reportHeldBack(block, unit, outcome);
        }
        reportProtectedSkipped(block, unit, outcome);
    }

    /**
     * Leave the run's rollback plan on disk, and prune the older ones.
     *
     * <p>Never fails the deploy. The apply has already happened by the time this runs, so turning a
     * successful deploy into a non-zero exit because a directory was not writable would be the wrong trade
     * — but it is reported loudly, because an operator who believes there is a net and has none is worse
     * off than one who knows there is not.
     */
    private void writeRollbackPlan(MidpointRestClient client, Tracking tracking) {
        File base = (rollbackPlanDir != null) ? rollbackPlanDir
                : RollbackPlan.defaultBase(tracking.identity != null ? tracking.identity.key() : null,
                        (paths != null && !paths.isEmpty()) ? paths.get(0) : null);
        String deployment = (tracking.identity != null && tracking.identity.isAvailable())
                ? tracking.identity.key() : null;
        writeRollbackPlan(client, base, deployment, tracking.currentRevision);
    }

    private void writeRollbackPlan(MidpointRestClient client, File base, String deployment, String revision) {
        if (rollback == null || rollback.isEmpty()) {
            return;
        }
        List<String> unrecorded = rollback.unrecorded();
        if (!unrecorded.isEmpty()) {
            warn("the rollback plan cannot undo " + unrecorded.size() + " object(s) this run changed, because their "
                    + "inverse could not be computed: " + String.join(", ", unrecorded) + ".");
        }
        try {
            String server = (client != null) ? String.valueOf(client.host()) : null;
            File dir = rollback.write(base, server, deployment, revision);
            RollbackPlan.rotate(base, RollbackPlan.DEFAULT_KEEP, deployment);
            status("Rollback plan: " + dir + " (" + rollback.undoableCount() + " object(s) undoable). "
                    + "Replay with: midgie rollback --url <url>"
                    + (rollbackPlanDir != null ? " --rollback-plan " + rollbackPlanDir : ""));
        } catch (Exception e) {
            warn("could not write the rollback plan: " + safe(e.getMessage())
                    + " — this run cannot be rolled back automatically.");
            LOG.trace("writing the rollback plan failed", e);
        }
    }

    /**
     * Why the scan came back empty, in terms the operator can check.
     *
     * <p>"No midPoint objects found under the given path(s)" named neither the path nor what was there,
     * which made the two very different causes look identical: a tree of the wrong sort of files, and a
     * path with nothing in it at all. The second is what an unmounted volume looks like — {@code docker -v}
     * creates an empty directory when the bind source is missing or {@code $PWD} did not expand, and mounts
     * that instead of failing — so in a container the empty case is worth naming outright.
     */
    private String nothingFound(Discovery.ScanResult scan) {
        String where = (paths == null || paths.isEmpty()) ? "the given path(s)"
                : paths.stream().map(File::toString).collect(Collectors.joining(", "));
        if (scan.walkedFiles() > 0) {
            return "no midPoint objects found under " + where + " — walked " + scan.walkedFiles()
                    + " file(s), none of them a deployable midPoint object.";
        }
        String message = "no midPoint objects found under " + where + " — it holds no files at all.";
        if (Io.inContainer() && paths != null && !paths.isEmpty()) {
            message += " An empty path inside a container usually means the mount did not land: check it"
                    + " with 'docker run --entrypoint ls <image> -la " + paths.get(0) + "'.";
        }
        return message;
    }

    /** Plan directories this run writes to or reads from — never discovery input, even when nested in a path. */
    private List<File> planDirectories() {
        List<File> dirs = new ArrayList<>();
        if (planDir != null) {
            dirs.add(planDir);
        }
        if (applyPlanDir != null) {
            dirs.add(applyPlanDir);
        }
        // A rollback net placed inside the tree: its patch files carry the generated-file marker anyway, which
        // is what keeps them out, and this is the path-based guard DESIGN promises beside it.
        if (rollbackPlanDir != null) {
            dirs.add(rollbackPlanDir);
        }
        return dirs;
    }

    // --- plan (write reviewable files, no server writes) ---

    private Integer runPlan(Reconciler reconciler, List<DeployObject> units, Tracking tracking) throws Exception {
        if (PlanStore.isIncomplete(planDir)) {
            // An unfinished plan cannot be replayed anyway, so there is nothing to protect; refusing until
            // the operator deletes it by hand only makes a failed run harder to repeat.
            int discarded = PlanStore.discardIncomplete(planDir);
            note("note: discarded " + discarded + " file(s) of an unfinished plan in " + planDir + ".");
        } else if (PlanStore.hasPlanFiles(planDir)) {
            System.err.println("midgie: --plan directory " + planDir
                    + " already contains plan files; use an empty (or new) directory.");
            return EXIT_ERROR;
        }
        // Marked from the first file to the last: a run that dies half way leaves the marker behind, and
        // --apply-plan then refuses the partial plan instead of replaying part of the tree as a success.
        PlanStore.markIncomplete(planDir);
        int width = String.valueOf(units.size()).length();
        Tally tally = new Tally();
        // What an apply of this plan would count as applied, for the tracking rows it records (see PlanState).
        Set<DeployObject> wouldApply = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Map<DeployObject, String> planFiles = new IdentityHashMap<>();
        int seq = 0;
        for (DeployObject unit : units) {
            seq++;
            try {
                // One unit per object, so each object is planned once against the server as it stands. The
                // local projection this used to keep — advanced with each applied delta so that a second
                // fragment of a to-be-created object became a patch rather than a second create — has
                // nothing left to answer for now that an object's files are reconciled together.
                Reconciler.Outcome outcome = reconciler.plan(unit.object(), strategyFor(unit), removalsFor(unit), prune());

                boolean lands = outcome.action == Reconciler.Action.CREATE || outcome.action == Reconciler.Action.PATCH
                        || outcome.action == Reconciler.Action.IN_SYNC;
                if (lands && !outcome.removalsDeferred) {
                    wouldApply.add(unit);
                    if (outcome.oid != null) {
                        writtenOids.put(unit, outcome.oid);
                    }
                }
                String label;
                String detail = "";
                boolean error = false;
                switch (outcome.action) {
                    case CREATE:
                        planFiles.put(unit, PlanStore.write(planDir, seq, width, outcome.restType, outcome.oid,
                                unit.name(), PlanStore.Kind.CREATE,
                                oidComments.annotate(outcome.createBody, outcome.oid, unit.typeName())));
                        tally.created++;
                        label = "would-create";
                        break;
                    case PATCH:
                        planFiles.put(unit, PlanStore.write(planDir, seq, width, outcome.restType, outcome.oid,
                                unit.name(), PlanStore.Kind.PATCH,
                                oidComments.annotate(outcome.modificationXml, outcome.oid, unit.typeName())));
                        tally.patched++;
                        label = "would-patch";
                        detail = outcome.changes + " change(s)";
                        break;
                    case IN_SYNC:
                        tally.inSync++;
                        label = "in-sync";
                        break;
                    case SKIP:
                        tally.skipped++;
                        label = "skip";
                        detail = safe(outcome.detail);
                        break;
                    case FAIL:
                    default:
                        tally.failed++;
                        label = "error";
                        detail = safe(outcome.detail);
                        error = true;
                        break;
                }
                OutputLayout.Block block = unitBlock(unit, label, detail);
                addNotes(block, unit, outcome, true, true);
                print(block, error);
            } catch (Exception e) {
                // One unit that cannot be planned must not abandon the loop mid-write: the plan would be
                // both partial and unexplained. Record it and carry on, as the apply pass does.
                if (isFatal(e)) {
                    throw e;
                }
                tally.failed++;
                reportUnit(unit, "error", safe(e.getMessage()), true);
                LOG.trace("planning {} {} failed", unit.typeName(), unit.identityLabel(), e);
            }
        }
        emitSummary(tally, true);
        if (tally.changed() > 0) {
            status("Wrote " + tally.changed() + " plan file(s) to " + planDir + ".");
        }
        if (tally.failed > 0) {
            note("note: the plan in " + planDir + " is incomplete and will not be replayed; fix the errors "
                    + "above and re-run --plan.");
            return EXIT_ERROR;
        }
        writePlanState(tracking, units, wouldApply, planFiles);
        PlanStore.markComplete(planDir);
        return tally.changed() > 0 ? 1 : 0;
    }

    /**
     * Record in the plan the tracking rows an apply of it would write (see {@link PlanState}), so that a
     * plan-and-apply pipeline moves its rows on like a plain deploy does. Nothing on an untracked run, or one
     * whose removals could not be told.
     */
    private void writePlanState(Tracking tracking, List<DeployObject> units, Set<DeployObject> wouldApply,
            Map<DeployObject, String> planFiles) throws IOException {
        if (!tracking.canRecord() || detectionBroken) {
            return;
        }
        wouldApply.removeAll(unjudgedUnits);
        for (RemovalDetector.Removal removal : unprunable) {
            wouldApply.remove(removal.unit);
        }
        StateRows rows = stateRows(tracking, units, wouldApply);
        List<PlanState.Unit> planned = new ArrayList<>();
        for (DeployObject unit : rows.applied()) {
            List<RollbackPlan.RowChange> unitRows = rows.byUnit().getOrDefault(unit, List.of());
            if (!unitRows.isEmpty()) {
                planned.add(new PlanState.Unit(planFiles.get(unit), writtenOids.get(unit), unitRows));
            }
        }
        List<String> deployKeys = new ArrayList<>();
        for (String scope : tracking.files.scopes()) {
            deployKeys.add(tracking.files.deployKeyOf(scope));
        }
        PlanState.write(planDir, new PlanState.Contents(tracking.identity.key(), tracking.currentRevision,
                deployKeys, planned, rows.run(), orphans));
    }

    // --- apply-plan (replay saved files verbatim) ---

    private Integer runApplyPlan() throws Exception {
        List<PlanStore.Entry> entries = PlanStore.read(applyPlanDir);
        if (entries.isEmpty()) {
            System.err.println("midgie: no plan files (*.create.xml / *.patch.xml) in " + applyPlanDir + ".");
            return EXIT_ERROR;
        }
        LOG.debug("apply-plan: {} entry/entries from {}", entries.size(), applyPlanDir);
        PlanState.Contents state = PlanState.read(applyPlanDir);
        if (state != null) {
            orphans.addAll(state.orphans());
        }
        if (dryRun) {
            for (PlanStore.Entry entry : entries) {
                reportEntry(entry, entry.kind == PlanStore.Kind.CREATE ? "would-create" : "would-patch", "", false);
            }
            status("Plan: " + entries.size() + " change(s) would be applied from " + applyPlanDir + ".");
            return 1;
        }
        if (confirmEachApply && Io.console() == null) {
            System.err.println("midgie: --confirm-each-apply needs an interactive session; use --yes.");
            return EXIT_ERROR;
        }
        if (!assumeYes && !confirmEachApply) {
            for (PlanStore.Entry entry : entries) {
                reportEntry(entry, entry.kind == PlanStore.Kind.CREATE ? "would-create" : "would-patch", "", false);
            }
            Integer stop = confirmOnce(entries.size());
            if (stop != null) {
                return stop;
            }
        }
        // Only now is a server needed, so only now is the password resolved: a dry run never asks for one,
        // and neither does a run the operator declines at the prompt above.
        MidpointRestClient client = connectionOptions.newClient();
        if (client == null) {
            return EXIT_ERROR;
        }
        // Only now is prism worth its start-up: for the inverses of the net, and for the state rows. Extension
        // schema too, since a stored body may carry extension items.
        PrismContext prismContext = (!noRollbackPlan || state != null) ? schemaOptions.buildContext(client) : null;
        if (!noRollbackPlan) {
            rollback = new RollbackPlan(prismContext, client, oidComments);
        }
        File netBase = (rollbackPlanDir != null) ? rollbackPlanDir
                : RollbackPlan.defaultBase((state != null) ? state.deployment() : null, applyPlanDir);
        String deployment = (state != null) ? state.deployment() : null;
        String revision = (state != null) ? state.revision() : null;

        Set<String> applied = new HashSet<>();
        List<PlanStore.Entry> failed;
        try {
            failed = applyEntries(client, entries, confirmEachApply, applied);
            if (retry && !failed.isEmpty()) {
                failed = retryUntilStable(failed,
                        remaining -> applyEntries(client, remaining, confirmEachApply, applied));
            }
        } catch (Exception e) {
            writeRollbackPlan(client, netBase, deployment, revision); // as for a deploy that dies part way
            throw e;
        }
        status("Done: " + (entries.size() - failed.size() - declinedEntries) + " applied, "
                + declinedEntries + " declined, " + failed.size() + " failed.");
        if (state != null) {
            recordPlannedState(prismContext, client, state, entries, applied);
        }
        writeRollbackPlan(client, netBase, deployment, revision);
        return failed.isEmpty() ? 0 : EXIT_ERROR;
    }

    /**
     * Write the tracking rows the plan recorded, for the objects whose entry was applied — and those already in
     * sync, which had none — plus the run's own rows when every entry was. A row another run has moved since
     * the plan was made is that run's and is left alone. Bookkeeping never fails the replay, but a failure is
     * said whatever {@code --quiet} asks: the next deploy's removal detection depends on these rows.
     */
    private void recordPlannedState(PrismContext prismContext, MidpointRestClient client, PlanState.Contents state,
            List<PlanStore.Entry> entries, Set<String> applied) {
        try {
            PrismObject<? extends Objectable> table = SyncState.find(prismContext, client);
            if (table == null || !SyncState.isEnabled(table)) {
                warn("the plan records tracking rows, but " + SyncState.TABLE_NAME + " is not in use on this server; "
                        + "none were written.");
                return;
            }
            Map<String, String> current = new LinkedHashMap<>();
            for (SyncState.Row row : SyncState.rows(table)) {
                current.putIfAbsent(row.key(), row.value());
            }
            SyncState.Changes changes = new SyncState.Changes();
            int[] movedOn = {0};
            for (PlanState.Unit unit : state.units()) {
                if (unit.entry() != null && !applied.contains(unit.entry())) {
                    continue; // not applied: its rows stay, so what it dropped stays detectable
                }
                List<RollbackPlan.RowChange> written = plannedRows(unit.rows(), current, changes, movedOn);
                if (rollback != null) {
                    rollback.recordRows(unit.oid(), written);
                }
            }
            if (applied.size() == entries.size()) {
                List<RollbackPlan.RowChange> written = plannedRows(state.runRows(), current, changes, movedOn);
                if (rollback != null) {
                    rollback.recordRunRows(written);
                }
            }
            int created = (int) entries.stream()
                    .filter(e -> e.kind == PlanStore.Kind.CREATE && applied.contains(e.fileName)).count();
            String value = FileTracking.deployValue(state.revision(), connectionOptions.account(),
                    VersionSupport.toolVersion(), created, applied.size() - created, 0, 0, declinedEntries,
                    entries.size() - applied.size() - declinedEntries);
            for (String key : state.deployKeys()) {
                changes.set(key, value, null);
            }
            SyncState.apply(prismContext, client, table, changes);
            if (movedOn[0] > 0) {
                warn(movedOn[0] + " tracking row(s) were written by another run after this plan was made and were "
                        + "left alone; re-plan from the current tree to record them.");
            }
        } catch (Exception e) {
            warn("could not record what was applied in " + SyncState.TABLE_NAME + ": " + safe(e.getMessage())
                    + " — until the tree is deployed again, removals are judged against the earlier record.");
            LOG.trace("recording the planned state failed", e);
        }
    }

    /** Add the planned {@code rows} that still find the value they replace to {@code changes}; returns those. */
    private static List<RollbackPlan.RowChange> plannedRows(List<RollbackPlan.RowChange> rows,
            Map<String, String> current, SyncState.Changes changes, int[] movedOn) {
        List<RollbackPlan.RowChange> written = new ArrayList<>();
        for (RollbackPlan.RowChange row : rows) {
            if (!Objects.equals(current.get(row.key()), row.before())) {
                movedOn[0]++;
                continue;
            }
            if (row.after() == null) {
                changes.delete(row.key());
            } else {
                changes.set(row.key(), row.after(), row.afterLabel());
            }
            written.add(row);
        }
        return written;
    }

    private List<PlanStore.Entry> applyEntries(MidpointRestClient client, List<PlanStore.Entry> entries,
            boolean perUnitPrompt, Set<String> applied) throws Exception {
        List<PlanStore.Entry> failed = new ArrayList<>();
        for (PlanStore.Entry entry : entries) {
            try {
                if (perUnitPrompt && !promptEntry(entry)) {
                    // Counted, not forgotten: the summary derives "applied" from the entry count, so an
                    // unrecorded decline was reported as a write that never happened.
                    declinedEntries++;
                    reportEntry(entry, "skipped", "declined", false);
                    continue;
                }
                if (entry.kind == PlanStore.Kind.CREATE) {
                    // Comments are for the reviewer; the server gets the change and nothing else.
                    String oid = client.createObject(entry.restType, OidComments.strip(entry.body));
                    if (rollback != null) {
                        rollback.recordCreated(entry.restType, oid, null);
                    }
                    reportEntry(entry, "created", "", false);
                } else {
                    // The body is replayed as reviewed, minus its comments; it is only parsed to see
                    // whether it sets a reference by filter, which decides the re-resolve option.
                    client.patchObject(entry.restType, entry.oid, OidComments.strip(entry.body),
                            PlanStore.setsReferenceByFilter(entry));
                    if (rollback != null) {
                        rollback.recordPlanPatch(entry.restType, entry.oid, entry.body);
                    }
                    reportEntry(entry, "patched", "", false);
                }
                applied.add(entry.fileName);
            } catch (Exception e) {
                if (isFatal(e)) {
                    throw e;
                }
                failed.add(entry);
                reportEntry(entry, "failed", safe(e.getMessage()), true);
                LOG.trace("plan entry {}/{} failed", entry.restType, entry.oid, e);
            }
        }
        return failed;
    }

    // --- retry ---

    /** Re-apply the failed items round after round until one makes no progress; returns what is still stuck. */
    private <T> List<T> retryUntilStable(List<T> failed, RetryPass<T> pass) throws Exception {
        int round = 1;
        while (!failed.isEmpty() && round <= MAX_RETRY_ROUNDS) {
            int before = failed.size();
            note("retry round " + round + ": re-applying " + before + " failed unit(s)...");
            failed = pass.apply(failed);
            LOG.debug("retry round {}: {} → {} still failing", round, before, failed.size());
            if (failed.size() >= before) {
                break; // no progress
            }
            round++;
        }
        if (!failed.isEmpty()) {
            note(failed.size() + " unit(s) still failing after retry — giving up.");
        }
        return failed;
    }

    /** A retry pass: apply a list of still-failing items and return the ones that failed again. */
    private interface RetryPass<T> {
        List<T> apply(List<T> items) throws Exception;
    }

    // --- confirmation ---

    private boolean promptEach(Reconciler.Action action, Reconciler.Outcome outcome) {
        String verb = (action == Reconciler.Action.CREATE)
                ? "Create" : "Apply " + outcome.changes + " change(s) to";
        return promptYes(verb + " " + outcome.restType + "/" + (outcome.oid != null ? outcome.oid : "(new)"));
    }

    private boolean promptEntry(PlanStore.Entry entry) {
        String verb = (entry.kind == PlanStore.Kind.CREATE) ? "Create" : "Patch";
        return promptYes(verb + " " + entry.restType + "/" + entry.oid);
    }

    private boolean promptYes(String prompt) {
        Console console = Io.console();
        if (console == null) {
            return false;
        }
        String answer = console.readLine("%s? [y/N] ", prompt);
        String normalized = (answer == null) ? "" : answer.trim().toLowerCase();
        return normalized.equals("y") || normalized.equals("yes");
    }

    private Integer confirmOnce(int changeCount) {
        Console console = Io.console();
        boolean interactive = console != null;
        String answer = interactive
                ? console.readLine("Apply %d change(s) to %s? [y/N] ", changeCount, connectionOptions.url())
                : null;
        switch (Confirmation.decide(false, interactive, answer)) {
            case PROCEED:
                return null;
            case ABORTED:
                System.err.println("midgie: aborted — nothing was applied.");
                return 0;
            case NON_INTERACTIVE:
            default:
                System.err.println("midgie: refusing to deploy without confirmation in a non-interactive "
                        + "session; re-run with --yes (or --dry-run to preview).");
                return EXIT_ERROR;
        }
    }

    /** Auth failures and connection/timeout errors doom the whole run; a per-object HTTP error does not. */
    private static boolean isFatal(Exception e) {
        if (e instanceof MidpointRestClient.RestException restException) {
            return restException.statusCode() == 401;
        }
        return e instanceof IOException;
    }

    // --- output ---

    private void printPlanTable(TopoSort.Result<SchemaFile> schemaOrder, TopoSort.Result<DeployObject> objectOrder) {
        // One step per object, not per file: an object described by several files is applied once, so
        // counting files here would promise more writes than the run will make.
        int steps = schemaOrder.ordered().size() + objectOrder.ordered().size();
        System.out.println("Apply order (" + steps + (steps == 1 ? " step):" : " steps):"));

        Map<String, Integer> schemaSeqByNamespace = new LinkedHashMap<>();
        Map<String, Integer> objectSeqByOid = new LinkedHashMap<>();
        int seq = 0;

        for (SchemaFile schema : schemaOrder.ordered()) {
            seq++;
            schemaSeqByNamespace.put(schema.targetNamespace(), seq);
            List<Integer> needs = schema.importedNamespaces().stream()
                    .map(schemaSeqByNamespace::get).filter(Objects::nonNull).sorted().collect(Collectors.toList());
            System.out.println(line(seq, schema.fragment()) + needsSuffix(needs));
        }

        for (DeployObject object : objectOrder.ordered()) {
            List<Integer> needs = object.outboundOids().stream()
                    .map(objectSeqByOid::get).filter(Objects::nonNull).sorted().collect(Collectors.toList());
            seq++;
            System.out.println(line(seq, object) + needsSuffix(needs));
            // The other files feeding this one step, listed under it: the step is the object, but which
            // files went into it is exactly what an operator checks the apply order for.
            List<File> files = object.sourceFiles();
            for (File extra : files.subList(1, files.size())) {
                System.out.println(String.format("      + %s", extra));
            }
            if (object.oid() != null) {
                objectSeqByOid.put(object.oid(), seq);
            }
        }
    }

    private void emitWarnings(List<SchemaFile> localSchemas, TopoSort.Result<SchemaFile> schemaOrder,
            TopoSort.Result<DeployObject> objectOrder, List<String> warnings) {
        if (quiet) {
            return;
        }
        for (SchemaFile local : localSchemas) {
            System.err.println(NoteStyle.of("midgie: note: registered local schema (not deployed): "
                    + local.sourceFile() + " [" + local.targetNamespace() + "]"));
        }
        for (SchemaFile schema : schemaOrder.brokenAt()) {
            System.err.println("midgie: warning: schema dependency cycle — broke it at "
                    + schema.sourceFile() + "; the order there is approximate.");
        }
        for (DeployObject object : objectOrder.brokenAt()) {
            System.err.println("midgie: warning: object dependency cycle — broke it at "
                    + object.identityLabel() + "; the order there is approximate.");
        }
        for (String warning : warnings) {
            System.err.println("midgie: warning: " + warning);
        }
    }

    private void emitSummary(Tally tally, boolean preview) {
        status(preview
                ? String.format("Plan: %d to create, %d to patch, %d in-sync, %d value(s) to remove, %d skipped, "
                        + "%d error(s).", tally.created, tally.patched, tally.inSync, tally.removed, tally.skipped,
                        tally.failed)
                : String.format("Done: %d created, %d patched, %d in-sync, %d value(s) removed, %d skipped, "
                        + "%d failed.", tally.created, tally.patched, tally.inSync, tally.removed, tally.skipped,
                        tally.failed));
    }

    private void reportUnit(DeployObject unit, String label, String detail, boolean error) {
        print(unitBlock(unit, label, detail), error);
    }

    /**
     * One object's block: the result, then source (file) and target (object) — the object path last, so it reads
     * into the change detail's item paths — with the OID between them under {@code --debug}, and the detail.
     */
    private OutputLayout.Block unitBlock(DeployObject unit, String label, String detail) {
        String head = unit.sourceLabel() + "  " + (verbose() ? unit.oidLabel() + "  " : "") + unit.objectPath();
        return new OutputLayout.Block(label, head).add(detail);
    }

    private void reportEntry(PlanStore.Entry entry, String label, String detail, boolean error) {
        // Plan files are keyed by OID and carry no name, so an apply-plan row is identified by its OID.
        String head = entry.restType + "  " + safe(entry.oid) + "  "
                + (entry.kind == PlanStore.Kind.CREATE ? "(create)" : "(patch)");
        print(new OutputLayout.Block(label, head).add(detail), error);
    }

    /**
     * Print one block, whole, on one stream: where the result rows go ({@link #status}), or stderr for a failure,
     * which is printed whatever {@code --quiet} asks. A block's notes were left out under {@code --quiet} already.
     */
    private void print(OutputLayout.Block block, boolean error) {
        if (!error && (quiet || asDiff)) {
            return;
        }
        PrintStream stream = (error || payloadOnStdout()) ? System.err : System.out;
        for (String line : block.render(OutputLayout.width(), NoteStyle::of)) {
            stream.println(line);
        }
    }

    /**
     * A warning or note about one object, into its block. Under {@code --quiet} it is left out, as notes always
     * were; for a live {@code diff}, which prints no result rows, it goes to stderr as a line of its own, naming
     * the object.
     */
    private void note(OutputLayout.Block block, DeployObject unit, String text) {
        if (quiet) {
            return;
        }
        if (asDiff) {
            int colon = text.indexOf(": ");
            String line = "midgie: " + text.substring(0, colon + 2) + unit.objectPath() + ": "
                    + text.substring(colon + 2);
            System.err.println(NoteStyle.isNote(text) ? NoteStyle.of(line) : line);
            return;
        }
        block.add(text);
    }

    /** An item under the note before it, such as one removed value. */
    private void noteItem(OutputLayout.Block block, String text) {
        if (quiet) {
            return;
        }
        if (asDiff) {
            System.err.println(NoteStyle.of("midgie:   " + text));
            return;
        }
        block.addItem(text);
    }

    /**
     * The objects that left the tree, last, so they are still on screen when the run ends: once their rows are
     * gone, no later run names them again. For the same reason they are not dimmed like the other notes.
     */
    private void reportOrphans() {
        if (quiet) {
            return;
        }
        for (PlanState.Orphan orphan : orphans) {
            System.err.println("midgie: note: " + orphan.object() + " is no longer declared by the tree ("
                    + orphan.file() + " was removed); its object is left untouched — delete it yourself if that "
                    + "is intended.");
        }
    }

    /** True when the OID should be shown: {@code --debug} (or {@code --trace}, which implies it). */
    private boolean verbose() {
        return debug || trace;
    }

    private void status(String message) {
        if (quiet || asDiff) {
            return;
        }
        // When the change itself is going to stdout, the rows go beside it rather than into it.
        (payloadOnStdout() ? System.err : System.out).println(message);
    }

    private boolean payloadOnStdout() {
        return format != null && (output == null || Io.isStdout(output));
    }

    /**
     * A note about how removal tracking is set up for this run. Worth saying to someone deploying; to someone
     * running {@code diff} it is the same sentence on every call about a step they are not taking.
     */
    private void trackingNote(String message) {
        if (asDiff) {
            LOG.debug(message);
        } else {
            note(message);
        }
    }

    /** The paths as given, for a message that has to name them. */
    private List<String> pathLabels() {
        List<String> labels = new ArrayList<>();
        if (paths != null) {
            for (File path : paths) {
                labels.add(path.toString());
            }
        }
        return labels;
    }

    /**
     * The one object, addressed by {@code oid} instead of whatever its files carry. Set on every fragment and
     * regrouped, so the identity, the reference edges and the removal lookup all see the same OID.
     */
    private static List<DeployObject> withOid(List<DeployObject> objects, String oid) throws SchemaException {
        List<Fragment> fragments = new ArrayList<>();
        for (DeployObject object : objects) {
            for (Fragment fragment : object.fragments()) {
                fragment.object().setOid(oid);
                fragments.add(fragment);
            }
        }
        return DeployObject.group(fragments);
    }

    private void note(String message) {
        if (!quiet) {
            String line = "midgie: " + message;
            System.err.println(NoteStyle.isNote(message) ? NoteStyle.of(line) : line);
        }
    }

    /**
     * A warning {@code --quiet} does not swallow. Reserved for the cases where what midgie wrote or deleted
     * is not what the command line asked for — silencing those turns "removed nothing" into a green run that
     * looks identical to a healthy one.
     */
    private void warn(String message) {
        System.err.println("midgie: warning: " + message);
    }

    private static String safe(String value) {
        return (value != null) ? value : "";
    }

    private String line(int seq, Fragment fragment) {
        return line(seq, fragment.sourceFile().toString(), fragment.oidLabel(), fragment.objectPath());
    }

    private String line(int seq, DeployObject unit) {
        return line(seq, unit.sourceFiles().get(0).toString(), unit.oidLabel(), unit.objectPath());
    }

    private String line(int seq, String source, String oidLabel, String objectPath) {
        StringBuilder out = new StringBuilder(String.format("  %2d  %-44s", seq, source));
        if (verbose()) {
            out.append(String.format("  %-38s", oidLabel));
        }
        return out.append("  ").append(objectPath).toString();
    }

    private static String needsSuffix(List<Integer> needs) {
        if (needs.isEmpty()) {
            return "";
        }
        return "   (needs " + needs.stream().map(n -> "#" + n).collect(Collectors.joining(",")) + ")";
    }

    /** Running counts for a preview or apply pass, plus the units that failed (for retry). */
    private static final class Tally {
        int created;
        int patched;
        int inSync;
        int skipped;
        int failed;
        int removed;   // values dropped from the tree that a PATCH deleted
        final List<DeployObject> failedUnits = new ArrayList<>();
        final List<DeployObject> landedUnits = new ArrayList<>();   // on the server as the tree has them
        final Map<DeployObject, String> changes = new IdentityHashMap<>(); // a preview's change per object
        Reconciler.Outcome lastOutcome;   // what --format renders, when the run holds one object

        int changed() {
            return created + patched;
        }

        /** Add what another pass did — the retry rounds after the first — leaving failed to the caller. */
        void addCounts(Tally other) {
            created += other.created;
            patched += other.patched;
            inSync += other.inSync;
            skipped += other.skipped;
            removed += other.removed;
        }
    }
}
