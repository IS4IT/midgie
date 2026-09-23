/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.schema.constants.ObjectTypes;
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

    // Creating and name-matching are the defaults; these opt out. See the note on ApplyCommand's copy for
    // why these are separate --no-x options rather than picocli's `negatable`.
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
            description = "Per-object/-item diff-strategy policy file ('<Type/Name/item glob> key=value' lines); "
                    + "most-specific selector wins.")
    File policyFile;

    @Option(names = "--prune",
            description = "Delete the values this tree deployed previously and no longer declares (needs "
                    + "'midgie init --create-state'). Only ever removes values it applied itself, never "
                    + "accrued state.")
    boolean prune;

    @Option(names = "--app", paramLabel = "NAME",
            description = "Track this tree under NAME instead of the identity derived from its git repository "
                    + "(root commit + path). Only needed for setups where that derivation does not fit.")
    String app;

    @Option(names = {"--quiet", "-q"},
            description = "Suppress the plan/status output and notes; keep errors, failures, and the exit code.")
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

    // Resolved once per run.
    private StrategyOptions.Resolved globalStrategy;
    private Policy policy;
    /**
     * Why this run did not land the whole tree, or {@code null} when it did. A declined prompt, a declined
     * unit and a skipped unit all mean part of the desired state never reached the server — and both of the
     * things that follow the apply assume it did. Pruning would delete "leftovers" computed against a tree
     * that was never applied, and recording the revision would move the baseline past removals nobody acted
     * on, making them undetectable for ever.
     */
    private String notFullyApplied;

    /** Set when last-applied cannot be recovered at all, so no comparison this run means anything. */
    private boolean detectionBroken;

    /** Plan entries the operator declined under --confirm-each-apply; they are neither applied nor failed. */
    private int declinedEntries;

    /** The safety net being accumulated for this run; null when no writing pass will happen. */
    private RollbackPlan rollback;

    private void markNotFullyApplied(String reason) {
        if (notFullyApplied == null) {
            notFullyApplied = reason;
        }
    }

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
        if (connectionOptions.oid != null) {
            // Inherited from the shared connection mixin, but deploy addresses each object by the OID in its
            // own file. Accepting a write-affecting flag and ignoring it is worse than rejecting it.
            System.err.println("midgie: --oid applies to 'diff' and 'apply', which address one object; "
                    + "deploy takes the OID from each file.");
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
                || retry || prune || planDir != null || applyPlanDir != null || policyFile != null;
        if (!live && writeFlags) {
            System.err.println("midgie: --dry-run/--yes/--retry/--prune/--plan/--apply-plan/--policy need "
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
            if (prune) {
                ignored.add("--prune");
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
                return runApplyPlan();
            }

            PrismContext prismContext = SchemaOptions.newBaseContext();
            policy = (policyFile != null) ? Policy.load(policyFile) : null; // load early: fail fast on a bad policy

            // Everything the local filesystem can settle is settled before the first round trip. Reading the
            // tree costs milliseconds and the server costs seconds, so a typo'd path, an unreadable mount or
            // an empty directory should not be paid for twice over the network first — and against a host
            // that is down, a local mistake would otherwise surface as a connection error.
            // A plan directory may legitimately sit inside the tree it is planning; its files are output, not
            // source, so they are never discovery input.
            Discovery.ScanResult scan = Discovery.scan(prismContext, paths, planDirectories());
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
            LOG.debug("{} apply unit(s); mode={}", units.size(), planDir != null ? "plan" : "apply");

            Tracking tracking = resolveTracking(prismContext, client);
            List<RemovalDetector.Removal> removals = findRemovals(prismContext, tracking, units);
            reportRemovals(removals, tracking);
            if (prune && !tracking.canDetect()) {
                note("warning: --prune has nothing to compare against, so nothing will be removed.");
            }

            // Only a run that actually writes needs a net; --dry-run and --plan touch nothing.
            if (planDir == null && !dryRun && !noRollbackPlan) {
                rollback = new RollbackPlan(prismContext, client);
            }

            Integer exit = (planDir != null) ? runPlan(reconciler, units) : runApply(reconciler, units);

            // After the pass, not before it: a rule is only marked as having matched when it is offered to
            // an object, and that happens inside the pass. Reported earlier, every rule looked unmatched —
            // including the ones that were about to be applied.
            reportUnmatchedPolicyRules();

            // Prune after the tree is applied, so the desired state lands first and only leftovers go.
            if (prune && !removals.isEmpty() && planDir == null) {
                if (dryRun) {
                    exit = (exit == 0) ? 1 : exit; // would change something
                } else if (exit != 0) {
                    warn("not removing anything — the apply did not succeed; the values stay detectable "
                            + "on the next run.");
                } else if (notFullyApplied != null) {
                    // The removal set says "the tree declares this no longer". That is only a safe thing to
                    // delete once the tree has actually been applied: until then the server is missing
                    // whatever the declined or skipped units would have written.
                    warn("not removing anything — " + notFullyApplied + ", so the server does not hold the "
                            + "tree the removal set was computed against; the values stay detectable on the "
                            + "next run.");
                } else {
                    // Gate separately: when the apply pass had nothing to do it returns without prompting,
                    // so without this a --prune run could delete with no confirmation at all.
                    Integer stop = confirmPrune(removals.size());
                    if (stop != null) {
                        exit = stop;   // fall through: the apply already succeeded and must still be recorded
                    } else if (applyPrune(prismContext, client, removals) > 0) {
                        exit = EXIT_ERROR;
                    }
                }
            }
            // After the prune, so the values it removed are in the net too.
            writeRollbackPlan(client, tracking);

            if (exit == 0 && planDir == null && !dryRun) {
                if (notFullyApplied == null && !detectionBroken) {
                    recordRevision(prismContext, client, tracking);
                } else if (tracking.canRecord() && notFullyApplied != null) {
                    note("note: not recording the deployed revision — " + notFullyApplied
                            + "; anything dropped from the tree stays detectable on the next run.");
                }
            }
            return exit;
        } catch (Exception e) {
            String message = (e.getMessage() != null) ? e.getMessage() : e.toString();
            System.err.println("midgie: " + message);
            LOG.trace("deploy failed", e);
            return EXIT_ERROR;
        }
    }

    // --- delete detection (three-way merge against what was deployed last time) ---

    /** What this run knows about tracking: the tree's identity, the state object, and the last revision. */
    private static final class Tracking {
        final DeploymentIdentity identity;
        final PrismObject<? extends Objectable> table;
        final String lastRevision;
        final String currentRevision;

        Tracking(DeploymentIdentity identity, PrismObject<? extends Objectable> table,
                String lastRevision, String currentRevision) {
            this.identity = identity;
            this.table = table;
            this.lastRevision = lastRevision;
            this.currentRevision = currentRevision;
        }

        boolean canRecord() {
            return table != null && identity != null && identity.isAvailable() && currentRevision != null;
        }

        boolean canDetect() {
            return canRecord() && lastRevision != null;
        }
    }

    /**
     * Work out whether this run can track removals, explaining once when it cannot. midgie never creates the
     * state object here — {@code midgie init --create-state} is the deliberate opt-in — so an untracked
     * server just keeps behaving exactly as before.
     */
    private Tracking resolveTracking(PrismContext prismContext, MidpointRestClient client) {
        File tree = (paths != null && !paths.isEmpty()) ? paths.get(0) : null;
        if (paths != null && paths.size() > 1) {
            // Identity, the revision and the "deleted since" query are all rooted at one tree. Deploying
            // several at once would record the first one's revision as if it covered them all, quietly
            // hiding removals in the rest — so say so rather than half-tracking.
            note("note: tracking follows " + tree + " only; removals in the other path(s) are not detected. "
                    + "Deploy each tree separately, or pass --app to track them as one.");
        }
        DeploymentIdentity identity = DeploymentIdentity.of(tree, app);
        if (!identity.isAvailable()) {
            note("note: removals cannot be detected — " + identity.unavailableReason() + ".");
            return new Tracking(identity, null, null, null);
        }

        PrismObject<? extends Objectable> table;
        try {
            table = SyncState.find(prismContext, client);
        } catch (Exception e) {
            // Every other "cannot detect" branch says so. Staying silent here — the likely cause is the
            // deploy account having no read on lookupTables — makes a run with tracking quietly dead look
            // exactly like a healthy one.
            note("note: removals cannot be detected — could not read " + SyncState.TABLE_NAME + ": "
                    + safe(e.getMessage()));
            LOG.trace("could not read {}", SyncState.TABLE_NAME, e);
            return new Tracking(identity, null, null, null);
        }
        if (table == null) {
            note("note: no " + SyncState.TABLE_NAME + " on the server, so removals cannot be detected — "
                    + "run 'midgie init --create-state --url …' to enable it.");
            return new Tracking(identity, null, null, null);
        }
        if (!SyncState.isEnabled(table)) {
            note("note: " + SyncState.TABLE_NAME + " is '" + SyncState.lifecycleOf(table)
                    + "', so removal tracking is switched off.");
            return new Tracking(identity, null, null, null);
        }

        GitRevision git = identity.git();
        String current = git.head();
        if (current != null && git.isDirty()) {
            current = current + "-dirty";
            note("note: the tree has uncommitted changes; recording revision " + GitRevision.abbreviate(current)
                    + ", which does not describe exactly what was deployed.");
        }
        String last = SyncState.revisionOf(table, identity.key());
        LOG.debug("tracking {}: last={}, current={}", identity.key(), last, current);
        return new Tracking(identity, table, last, current);
    }

    /** Values deployed at the recorded revision that the tree no longer declares, or an empty list. */
    private List<RemovalDetector.Removal> findRemovals(PrismContext prismContext, Tracking tracking,
            List<DeployObject> units) {
        if (!tracking.canDetect()) {
            return List.of();
        }
        GitRevision git = tracking.identity.git();
        String base = baseRevision(tracking.lastRevision);
        if (!git.hasRevision(base)) {
            // Every git show against an unreachable revision fails exactly as it does for a file that did not
            // exist yet, so without this check the run would print nothing, look clean, and then record a new
            // baseline — turning "we cannot tell what was removed" into "nothing was removed", permanently.
            detectionBroken = true;
            warn("the last deployed revision " + GitRevision.abbreviate(base) + " is not in this repository (history rewritten, "
                    + "squash-merged, or fetched shallow), so removals cannot be detected this run. "
                    + "Nothing will be removed and the recorded revision is left alone. To start over, "
                    + "delete the '" + tracking.identity.key() + "' row from " + SyncState.TABLE_NAME + ".");
            return List.of();
        }
        RemovalDetector detector = new RemovalDetector(prismContext, git, base);
        List<RemovalDetector.Removal> removals = withoutRestricted(policy, globalStrategy, detector.detect(units));
        for (RemovalDetector.Orphan orphan : detector.orphaned()) {
            // The whole object left the tree. midgie does not delete objects, and taking one apart value by
            // value would strip its name, so say it plainly and leave the decision to the operator.
            note("note: " + orphan.objectLabel + " is no longer declared by the tree (" + orphan.sourceFile
                    + " was removed); its object is left untouched — delete it yourself if that is intended.");
        }
        return removals;
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
            String type = (removal.objectClass != null) ? removal.objectClass.getSimpleName() : "?";
            ApplyRules apply = policy.resolveFor(type, removal.objectName, removal.oid, baseline).apply;
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

    /**
     * Say what is no longer declared, and whether it will be removed or only reported. Without
     * {@code --prune} only the count is given; the per-value list belongs to a run that is about pruning.
     */
    private void reportRemovals(List<RemovalDetector.Removal> removals, Tracking tracking) {
        if (removals.isEmpty()) {
            return;
        }
        String summary = removals.size() + " value(s) deployed at " + GitRevision.abbreviate(tracking.lastRevision)
                + " are no longer declared by the tree";
        if (!prune) {
            note(summary + " (reported only — pass --prune with --dry-run or --plan for details).");
            return;
        }
        note(summary + removalDisposition() + ":");
        for (RemovalDetector.Removal removal : removals) {
            note("  " + removal.objectLabel + "  " + removal);
        }
    }

    /**
     * Say which differences a preview is not going to send because a policy {@code apply} rule holds them
     * back — otherwise a task shown as {@code in-sync} while its {@code executionState} plainly differs reads
     * as a diff that missed something. Each path names the rule that held it back.
     */
    private void reportHeldBack(DeployObject unit, Reconciler.Outcome outcome) {
        if (!outcome.heldBack.isEmpty()) {
            note("note: " + unit.objectPath() + ": left alone by the policy: "
                    + String.join(", ", outcome.heldBack));
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

    /** How this run will treat the removals it just listed — never claiming a deletion that cannot happen. */
    private String removalDisposition() {
        if (planDir != null) {
            return " (reported only — --prune does not apply in plan mode)";
        }
        return dryRun ? " (--prune: would be removed)" : " (--prune: removing)";
    }

    /**
     * Delete the detected removals. Each object gets one PATCH built solely from values that were in
     * last-applied, so pruning cannot reach accrued or foreign state however wrong the rest of the tree is.
     * An object that cannot be addressed (no OID, and no match by name) is reported and skipped.
     *
     * @return the number of objects that failed to prune
     */
    private int applyPrune(PrismContext prismContext, MidpointRestClient client,
            List<RemovalDetector.Removal> removals) {
        int unprunable = 0;
        Map<String, List<RemovalDetector.Removal>> byObject = new LinkedHashMap<>();
        for (RemovalDetector.Removal removal : removals) {
            if (removal.isPrunable()) {
                byObject.computeIfAbsent(removal.objectLabel, key -> new ArrayList<>()).add(removal);
            } else {
                // Counts as a prune failure: left unsaid, the revision would advance past it and the value
                // would never be offered again.
                warn("cannot remove " + removal + " — no item definition for it.");
                unprunable++;
            }
        }

        int failed = 0;
        for (Map.Entry<String, List<RemovalDetector.Removal>> entry : byObject.entrySet()) {
            List<RemovalDetector.Removal> group = entry.getValue();
            RemovalDetector.Removal first = group.get(0);
            try {
                String restType = ObjectTypes.getRestTypeFromClass(first.objectClass);
                String oid = resolvePruneTarget(prismContext, client, first, restType);
                if (oid == null) {
                    warn("cannot address " + entry.getKey() + " to remove "
                            + group.size() + " value(s); skipped.");
                    failed++;
                    continue;
                }
                List<RemovalDetector.Removal> stillOurs = stillLive(prismContext, client, restType, oid, group);
                if (stillOurs.isEmpty()) {
                    continue;
                }
                ObjectDelta<? extends Objectable> delta =
                        RemovalDetector.pruneDelta(prismContext, oid, stillOurs);
                if (delta == null || delta.isEmpty()) {
                    continue;
                }
                client.patchObject(restType, oid, DeltaOutput.toModificationXml(prismContext, delta),
                        DeltaOutput.carriesSmartReference(delta));
                if (rollback != null) {
                    // A prune delta is built solely from values that were in last-applied, so its inverse
                    // puts back exactly what was removed and nothing else.
                    rollback.recordPruned(restType, oid, entry.getKey(), delta);
                }
                status("  pruned       " + entry.getKey() + "  " + group.size() + " value(s)");
            } catch (Exception e) {
                warn("could not remove " + group.size() + " value(s) from " + entry.getKey()
                        + ": " + safe(e.getMessage()));
                LOG.trace("prune failed for {}", entry.getKey(), e);
                failed++;
            }
        }
        return failed + unprunable;
    }

    /**
     * Narrow a prune group to the values the live object still actually holds, comparing by real value.
     *
     * <p>This is the ownership guard, and it is what stops a prune from undoing the apply that just ran. A
     * value the tree *changed* rather than dropped also shows up as "no longer declared" (the old content is
     * gone from the file), and the recovered value still carries its container id — deleting that by id would
     * remove the entry the apply had just rewritten. Anything the server no longer holds in that form is
     * therefore skipped: someone else already removed it, or it was replaced rather than removed.
     */
    private List<RemovalDetector.Removal> stillLive(PrismContext prismContext, MidpointRestClient client,
            String restType, String oid, List<RemovalDetector.Removal> group) throws Exception {
        PrismObject<? extends Objectable> live =
                prismContext.parseObject(client.getObject(restType, oid));
        List<RemovalDetector.Removal> stillOurs = new ArrayList<>();
        for (RemovalDetector.Removal removal : group) {
            Item<?, ?> item = live.findItem(removal.itemPath.removeIds());
            boolean present = item != null && item.getValues().stream()
                    .anyMatch(value -> EquivalenceStrategy.REAL_VALUE.equals(value, removal.prismValue));
            if (present) {
                stillOurs.add(removal);
            } else {
                LOG.trace("not pruning {} at {}: the live object no longer holds that value",
                        removal.value, removal.path);
                note("note: leaving " + removal.path + " alone — the server no longer holds the value that "
                        + "was removed from " + removal.sourceFile + " (already changed or removed).");
            }
        }
        return stillOurs;
    }

    /** The OID to prune: the fragment's own, else a single match by type+name when --match-by-name is set. */
    private String resolvePruneTarget(PrismContext prismContext, MidpointRestClient client,
            RemovalDetector.Removal removal, String restType) throws Exception {
        if (removal.oid != null && !removal.oid.isBlank()) {
            return removal.oid;
        }
        if (!matchByName() || removal.objectName == null) {
            return null;
        }
        List<PrismObject<? extends Objectable>> matches = LiveLookup.byName(
                prismContext, client, removal.objectClass, restType, removal.objectName);
        return (matches.size() == 1) ? matches.get(0).getOid() : null;
    }

    /** Record the revision just deployed, so the next run can tell what has been removed since. */
    private void recordRevision(PrismContext prismContext, MidpointRestClient client, Tracking tracking) {
        if (!tracking.canRecord()) {
            return;
        }
        try {
            SyncState.record(prismContext, client, tracking.table, tracking.identity.key(),
                    tracking.currentRevision, tracking.identity.label());
            status("Recorded revision " + GitRevision.abbreviate(tracking.currentRevision) + " for " + tracking.identity.key() + ".");
        } catch (Exception e) {
            // Bookkeeping must never fail a deploy that already succeeded.
            note("warning: could not record the deployed revision: " + safe(e.getMessage()));
            LOG.trace("recording the revision failed", e);
        }
    }

    /** A dirty marker is not a git revision; compare against the commit it was based on. */
    private static String baseRevision(String revision) {
        return revision.endsWith("-dirty") ? revision.substring(0, revision.length() - "-dirty".length())
                : revision;
    }

    /** The strategy for one object: the CLI flags, with any matching {@code --policy} knob layered on. */
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
            if (preview.failed > 0) {
                return EXIT_ERROR;
            }
            return preview.changed() > 0 ? 1 : 0;
        }
        if (confirmEachApply && System.console() == null) {
            System.err.println("midgie: --confirm-each-apply needs an interactive session; "
                    + "use --yes for non-interactive apply.");
            return EXIT_ERROR;
        }
        if (!assumeYes && !confirmEachApply) {
            Tally preview = pass(reconciler, units, true, false);
            emitSummary(preview, true);
            if (preview.changed() == 0) {
                return preview.failed > 0 ? EXIT_ERROR : 0;
            }
            Integer stop = confirmOnce(preview.changed());
            if (stop != null) {
                return stop;
            }
        }
        Tally applied = pass(reconciler, units, false, confirmEachApply);
        List<DeployObject> failed = applied.failedUnits;
        if (retry && !failed.isEmpty()) {
            // Summarise after the retry rounds, not before: a run whose retries fixed everything used to end
            // on a line saying units had failed while exiting 0.
            emitSummary(applied, false);
            failed = retryUntilStable(failed,
                    remaining -> pass(reconciler, remaining, false, confirmEachApply).failedUnits);
            status(failed.isEmpty()
                    ? "Done after retry: all units applied."
                    : "Done after retry: " + failed.size() + " unit(s) still failing.");
        } else {
            emitSummary(applied, false);
        }
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
                Reconciler.Outcome outcome = reconciler.plan(unit.object(), strategyFor(unit));
                Reconciler.Action action = outcome.action;

                if (!preview && perUnitPrompt
                        && (action == Reconciler.Action.CREATE || action == Reconciler.Action.PATCH)) {
                    if (!promptEach(action, outcome)) {
                        tally.skipped++;
                        markNotFullyApplied("one or more units were declined");
                        reportUnit(unit, "skipped", "declined", false);
                        continue;
                    }
                }
                if (!preview && (action == Reconciler.Action.CREATE || action == Reconciler.Action.PATCH)) {
                    String affectedOid = reconciler.execute(outcome);
                    if (rollback != null) {
                        rollback.recordApplied(action, outcome, affectedOid, unit.identityLabel());
                    }
                }
                record(tally, unit, action, outcome, preview);
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

    private void record(Tally tally, DeployObject unit, Reconciler.Action action, Reconciler.Outcome outcome,
            boolean preview) {
        // Input advisories and the blast radius go to stderr per unit, so a destructive change is visible
        // in the plan before anything is written (and still shows in a real apply).
        for (String warning : outcome.warnings) {
            note("warning: " + unit.objectPath() + ": " + warning);
        }
        if (preview) {
            reportHeldBack(unit, outcome);
        }
        switch (action) {
            case CREATE:
                tally.created++;
                reportUnit(unit, preview ? "would-create" : "created", "", false);
                break;
            case PATCH:
                tally.patched++;
                String detail = (outcome.changes > 0 ? outcome.changes + " change(s): " : "")
                        + safe(outcome.detail);
                reportUnit(unit, preview ? "would-patch" : "patched", detail, false);
                break;
            case IN_SYNC:
                tally.inSync++;
                reportUnit(unit, "in-sync", "", false);
                break;
            case SKIP:
                tally.skipped++;
                if (!preview) {
                    markNotFullyApplied("one or more units were skipped");
                }
                reportUnit(unit, preview ? "skip" : "skipped", safe(outcome.detail), false);
                break;
            case FAIL:
            default:
                tally.failed++;
                tally.failedUnits.add(unit);
                reportUnit(unit, preview ? "error" : "failed", safe(outcome.detail), true);
                break;
        }
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
        if (rollback == null || rollback.isEmpty()) {
            return;
        }
        try {
            File base = (rollbackPlanDir != null) ? rollbackPlanDir
                    : RollbackPlan.defaultBase(tracking.identity != null ? tracking.identity.key() : null,
                            (paths != null && !paths.isEmpty()) ? paths.get(0) : null);
            String server = (client != null) ? String.valueOf(client.host()) : null;
            String deployment = (tracking.identity != null && tracking.identity.isAvailable())
                    ? tracking.identity.key() : null;
            File dir = rollback.write(base, server, deployment, tracking.currentRevision);
            RollbackPlan.rotate(base, RollbackPlan.DEFAULT_KEEP);
            status("Rollback plan: " + dir + " (" + rollback.undoableCount() + " object(s) undoable). "
                    + "Replay with: midgie rollback --url <url>");
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
        return dirs;
    }

    // --- plan (write reviewable files, no server writes) ---

    private Integer runPlan(Reconciler reconciler, List<DeployObject> units) throws Exception {
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
        int seq = 0;
        for (DeployObject unit : units) {
            seq++;
            try {
                // One unit per object, so each object is planned once against the server as it stands. The
                // local projection this used to keep — advanced with each applied delta so that a second
                // fragment of a to-be-created object became a patch rather than a second create — has
                // nothing left to answer for now that an object's files are reconciled together.
                Reconciler.Outcome outcome = reconciler.plan(unit.object(), strategyFor(unit));
                reportHeldBack(unit, outcome);

                switch (outcome.action) {
                    case CREATE:
                        PlanStore.write(planDir, seq, width, outcome.restType, outcome.oid, unit.name(),
                                PlanStore.Kind.CREATE, outcome.createBody);
                        tally.created++;
                        reportUnit(unit, "would-create", "", false);
                        break;
                    case PATCH:
                        PlanStore.write(planDir, seq, width, outcome.restType, outcome.oid, unit.name(),
                                PlanStore.Kind.PATCH, outcome.modificationXml);
                        tally.patched++;
                        reportUnit(unit, "would-patch", outcome.changes + " change(s)", false);
                        break;
                    case IN_SYNC:
                        tally.inSync++;
                        reportUnit(unit, "in-sync", "", false);
                        break;
                    case SKIP:
                        tally.skipped++;
                        reportUnit(unit, "skip", safe(outcome.detail), false);
                        break;
                    case FAIL:
                    default:
                        tally.failed++;
                        reportUnit(unit, "error", safe(outcome.detail), true);
                        break;
                }
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
        PlanStore.markComplete(planDir);
        return tally.changed() > 0 ? 1 : 0;
    }

    // --- apply-plan (replay saved files verbatim) ---

    private Integer runApplyPlan() throws Exception {
        List<PlanStore.Entry> entries = PlanStore.read(applyPlanDir);
        if (entries.isEmpty()) {
            System.err.println("midgie: no plan files (*.create.xml / *.patch.xml) in " + applyPlanDir + ".");
            return EXIT_ERROR;
        }
        LOG.debug("apply-plan: {} entry/entries from {}", entries.size(), applyPlanDir);
        if (dryRun) {
            for (PlanStore.Entry entry : entries) {
                reportEntry(entry, entry.kind == PlanStore.Kind.CREATE ? "would-create" : "would-patch", "", false);
            }
            status("Plan: " + entries.size() + " change(s) would be applied from " + applyPlanDir + ".");
            return 1;
        }
        if (confirmEachApply && System.console() == null) {
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
        List<PlanStore.Entry> failed = applyEntries(client, entries, confirmEachApply);
        if (retry && !failed.isEmpty()) {
            failed = retryUntilStable(failed, remaining -> applyEntries(client, remaining, confirmEachApply));
        }
        status("Done: " + (entries.size() - failed.size() - declinedEntries) + " applied, "
                + declinedEntries + " declined, " + failed.size() + " failed.");
        return failed.isEmpty() ? 0 : EXIT_ERROR;
    }

    private List<PlanStore.Entry> applyEntries(MidpointRestClient client, List<PlanStore.Entry> entries,
            boolean perUnitPrompt) throws Exception {
        List<PlanStore.Entry> failed = new ArrayList<>();
        for (PlanStore.Entry entry : entries) {
            try {
                if (perUnitPrompt && !promptEntry(entry)) {
                    // Counted, not forgotten: the summary derives "applied" from the entry count, so an
                    // unrecorded decline was reported as a write that never happened.
                    declinedEntries++;
                    markNotFullyApplied("one or more plan entries were declined");
                    reportEntry(entry, "skipped", "declined", false);
                    continue;
                }
                if (entry.kind == PlanStore.Kind.CREATE) {
                    client.createObject(entry.restType, entry.body);
                    reportEntry(entry, "created", "", false);
                } else {
                    // The body is replayed byte for byte; it is only parsed to see whether it sets a
                    // reference by filter, which decides the re-resolve option.
                    client.patchObject(entry.restType, entry.oid, entry.body,
                            PlanStore.setsReferenceByFilter(entry));
                    reportEntry(entry, "patched", "", false);
                }
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
        Console console = System.console();
        if (console == null) {
            return false;
        }
        String answer = console.readLine("%s? [y/N] ", prompt);
        String normalized = (answer == null) ? "" : answer.trim().toLowerCase();
        return normalized.equals("y") || normalized.equals("yes");
    }

    /**
     * Confirm before deleting previously-deployed values. Its own gate rather than the apply prompt, because
     * a prune can be the only thing a run does. Returns {@code null} to proceed, or the exit code to return.
     */
    private Integer confirmPrune(int valueCount) {
        if (assumeYes) {
            return null;
        }
        Console console = System.console();
        boolean interactive = console != null;
        String answer = interactive
                ? console.readLine("Remove %d previously deployed value(s) from %s? [y/N] ",
                        valueCount, connectionOptions.url())
                : null;
        switch (ApplyCommand.decideConfirmation(false, interactive, answer)) {
            case PROCEED:
                return null;
            case ABORTED:
                // Recording now would move the baseline past the very values the operator asked to look at
                // first, so the next run would find nothing to review.
                markNotFullyApplied("the prune was declined");
                System.err.println("midgie: aborted — nothing was removed.");
                return 0;
            case NON_INTERACTIVE:
            default:
                System.err.println("midgie: refusing to remove values without confirmation in a "
                        + "non-interactive session; re-run with --yes (or --dry-run to preview).");
                return EXIT_ERROR;
        }
    }

    private Integer confirmOnce(int changeCount) {
        Console console = System.console();
        boolean interactive = console != null;
        String answer = interactive
                ? console.readLine("Apply %d change(s) to %s? [y/N] ", changeCount, connectionOptions.url())
                : null;
        switch (ApplyCommand.decideConfirmation(false, interactive, answer)) {
            case PROCEED:
                return null;
            case ABORTED:
                markNotFullyApplied("the apply was declined");
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
            System.err.println("midgie: note: registered local schema (not deployed): "
                    + local.sourceFile() + " [" + local.targetNamespace() + "]");
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
                ? String.format("Plan: %d to create, %d to patch, %d in-sync, %d skipped, %d error(s).",
                        tally.created, tally.patched, tally.inSync, tally.skipped, tally.failed)
                : String.format("Done: %d created, %d patched, %d in-sync, %d skipped, %d failed.",
                        tally.created, tally.patched, tally.inSync, tally.skipped, tally.failed));
    }

    private void reportUnit(DeployObject unit, String label, String detail, boolean error) {
        // source (file) -> target (object): the object path sits last so it reads into the change detail's
        // item path (e.g. SystemConfigurationType/SystemConfiguration  …deploymentInformation/headerColor).
        report(label, unit.sourceLabel(), verbose() ? unit.oidLabel() : null,
                unit.objectPath(), detail, error);
    }

    private void reportEntry(PlanStore.Entry entry, String label, String detail, boolean error) {
        // Plan files are keyed by OID and carry no name, so an apply-plan row is identified by its OID.
        report(label, entry.restType, safe(entry.oid),
                entry.kind == PlanStore.Kind.CREATE ? "(create)" : "(patch)", detail, error);
    }

    /** One result row: {@code label  source  [oid — only under --debug]  target  detail}. */
    private void report(String label, String source, String oid, String target, String detail, boolean error) {
        StringBuilder out = new StringBuilder(String.format("  %-12s %-44s", label, source));
        if (oid != null) {
            out.append(String.format(" %-38s", oid));
        }
        if (target != null && !target.isEmpty()) {
            out.append(" ").append(target);
        }
        if (detail != null && !detail.isEmpty()) {
            out.append("  ").append(detail);
        }
        if (error) {
            System.err.println(out);
        } else {
            status(out.toString());
        }
    }

    /** True when the OID should be shown: {@code --debug} (or {@code --trace}, which implies it). */
    private boolean verbose() {
        return debug || trace;
    }

    private void status(String message) {
        if (!quiet) {
            System.out.println(message);
        }
    }

    private void note(String message) {
        if (!quiet) {
            System.err.println("midgie: " + message);
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
        final List<DeployObject> failedUnits = new ArrayList<>();

        int changed() {
            return created + patched;
        }
    }
}
