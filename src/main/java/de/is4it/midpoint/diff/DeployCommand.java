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
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
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
 * <p>With {@code --url} each unit is reconciled file-by-file (look up current state, diff the fragment
 * {@code --scope present}, PATCH, or POST to create with {@code --create-if-absent}), in that order, so a
 * role is created before the user that references it. {@code --retry} re-applies the failed set until it
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

    @Option(names = {"--create-if-absent", "-c"},
            description = "POST objects that do not exist yet (otherwise a missing object fails; an OID-less one is skipped).")
    boolean createIfAbsent;

    @Option(names = {"--match-by-name", "-m"},
            description = "For an OID-less object, search the server by type+name and PATCH the single match (else skip/create).")
    boolean matchByName;

    @Option(names = "--retry",
            description = "After the apply pass, re-apply the failed units until a round makes no progress (catches by-name refs).")
    boolean retry;

    @Option(names = "--plan", paramLabel = "DIR",
            description = "Write the ordered per-object changes (create/patch files) to DIR instead of applying. Needs --url.")
    File planDir;

    @Option(names = "--apply-plan", paramLabel = "DIR",
            description = "Replay a plan directory written by --plan (POST creates, PATCH patches, in order). Needs --url.")
    File applyPlanDir;

    @Option(names = "--policy", paramLabel = "FILE",
            description = "Per-object/-item diff-strategy policy file ('<Type/Name/item glob> key=value' lines); "
                    + "most-specific selector wins.")
    File policyFile;

    @Option(names = {"--quiet", "-q"},
            description = "Suppress the plan/status output and notes; keep errors, failures, and the exit code.")
    boolean quiet;

    @Option(names = {"--debug", "-v"},
            description = "Log a full DEBUG trace on stderr (this tool's own decisions plus framework logs) for troubleshooting.")
    boolean debug;

    @Option(names = "--trace",
            description = "Even more than --debug: also log REST request/response bodies and full HTTP error "
                    + "bodies (may include sensitive values). Implies --debug.")
    boolean trace;

    // Resolved once per run.
    private StrategyOptions.Resolved globalStrategy;
    private Policy policy;

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
        boolean live = connectionOptions.isLive();
        LOG.debug("deploy: live={}, dryRun={}, createIfAbsent={}, matchByName={}, retry={}, plan={}, "
                + "applyPlan={}, policy={}", live, dryRun, createIfAbsent, matchByName, retry,
                planDir, applyPlanDir, policyFile);
        boolean writeFlags = dryRun || assumeYes || confirmEachApply || createIfAbsent || matchByName
                || retry || planDir != null || applyPlanDir != null || policyFile != null;
        if (!live && writeFlags) {
            System.err.println("midgie: --dry-run/--yes/--create-if-absent/--match-by-name/--retry/"
                    + "--plan/--apply-plan/--policy need --url; without it, deploy only prints the plan.");
            return EXIT_ERROR;
        }
        if (applyPlanDir == null && (paths == null || paths.isEmpty())) {
            System.err.println("midgie: provide at least one PATH, or --apply-plan <dir>.");
            return EXIT_ERROR;
        }

        MidpointRestClient client = null;
        if (live) {
            globalStrategy = strategyOptions.resolve();
            if (globalStrategy == null) {
                return EXIT_ERROR;
            }
            client = connectionOptions.newClient();
            if (client == null) {
                return EXIT_ERROR;
            }
        }

        try {
            if (applyPlanDir != null) {
                return runApplyPlan(client);
            }

            PrismContext prismContext = SchemaOptions.newBaseContext();
            policy = (policyFile != null) ? Policy.load(policyFile) : null; // load early: fail fast on a bad policy
            if (live && !quiet) {
                VersionSupport.warnIfMismatch(prismContext, client);
            }

            // Collect every extension schema — the tree, CLI flags, and the live server — and register in one
            // batch (prism replaces the dynamic set on each call). Order matters: the tree/flag schemas go in
            // first so they win the per-namespace dedupe (SchemaSupport.register) over the live read, which
            // also stops prism warning about re-registering a schema that is both in the tree and on the server.
            Discovery.ScanResult scan = Discovery.scan(prismContext, paths);
            Map<String, Element> schemaMap = new LinkedHashMap<>();
            for (SchemaFile schema : scan.schemas()) {
                schemaMap.put(schema.description(), schema.schemaElement());
            }
            schemaMap.putAll(schemaOptions.collectSchemas(prismContext, client));
            LOG.debug("scan: {} schema file(s), {} candidate object file(s), {} warning(s); "
                    + "collected {} extension schema(s)", scan.schemas().size(), scan.objectFiles().size(),
                    scan.warnings().size(), schemaMap.size());
            SchemaSupport.register(prismContext, schemaMap);

            List<String> warnings = new ArrayList<>(scan.warnings());
            List<DeployObject> objects = DeployObject.group(parseObjects(prismContext, scan, warnings), warnings);

            List<SchemaFile> deployableSchemas = scan.schemas().stream()
                    .filter(SchemaFile::deployable).collect(Collectors.toList());
            List<SchemaFile> localSchemas = scan.schemas().stream()
                    .filter(schema -> !schema.deployable()).collect(Collectors.toList());
            LOG.debug("grouped into {} object(s); {} deployable schema(s), {} local-only schema(s)",
                    objects.size(), deployableSchemas.size(), localSchemas.size());

            if (deployableSchemas.isEmpty() && objects.isEmpty()) {
                System.err.println("midgie: no midPoint objects found under the given path(s).");
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
            Reconciler reconciler = new Reconciler(prismContext, client, createIfAbsent, matchByName);
            List<Fragment> units = flatten(schemaOrder, objectOrder);
            LOG.debug("{} apply unit(s); mode={}", units.size(), planDir != null ? "plan" : "apply");
            return (planDir != null) ? runPlan(reconciler, units) : runApply(reconciler, units);
        } catch (Exception e) {
            String message = (e.getMessage() != null) ? e.getMessage() : e.toString();
            System.err.println("midgie: " + message);
            LOG.debug("deploy failed", e);
            return EXIT_ERROR;
        }
    }

    private StrategyOptions.Resolved strategyFor(Fragment unit) {
        return (policy != null) ? policy.resolveFor(unit.typeName(), unit.name(), unit.oid(), globalStrategy)
                : globalStrategy;
    }

    /** Parse the candidate object files (schema is now registered); skip anything that isn't an object. */
    private static List<Fragment> parseObjects(PrismContext prismContext, Discovery.ScanResult scan,
            List<String> warnings) {
        List<Fragment> fragments = new ArrayList<>();
        for (File file : scan.objectFiles()) {
            PrismObject<? extends Objectable> object;
            try {
                object = prismContext.parseObject(file);
            } catch (Exception e) {
                warnings.add("skipped " + file + " — could not parse (" + e.getMessage() + ")");
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

    /** The ordered apply units: schema apply-units (SchemaType objects) first, then object fragments. */
    private static List<Fragment> flatten(TopoSort.Result<SchemaFile> schemaOrder,
            TopoSort.Result<DeployObject> objectOrder) {
        List<Fragment> units = new ArrayList<>();
        for (SchemaFile schema : schemaOrder.ordered()) {
            units.add(schema.fragment());
        }
        for (DeployObject object : objectOrder.ordered()) {
            units.addAll(object.fragments());
        }
        return units;
    }

    // --- apply ---

    private Integer runApply(Reconciler reconciler, List<Fragment> units) throws Exception {
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
        emitSummary(applied, false);
        List<Fragment> failed = applied.failedUnits;
        if (retry && !failed.isEmpty()) {
            failed = retryUntilStable(failed,
                    remaining -> pass(reconciler, remaining, false, confirmEachApply).failedUnits);
        }
        return failed.isEmpty() ? 0 : EXIT_ERROR;
    }

    /**
     * Reconcile every unit in order. When {@code preview} is set, nothing is written. A transport-level
     * failure (auth, connection) aborts; a per-object rejection is recorded and the run continues.
     */
    private Tally pass(Reconciler reconciler, List<Fragment> units, boolean preview, boolean perUnitPrompt)
            throws Exception {
        Tally tally = new Tally();
        Map<String, Boolean> wouldCreate = new HashMap<>();
        for (Fragment unit : units) {
            try {
                Reconciler.Outcome outcome = reconciler.plan(unit.object(), strategyFor(unit));

                // In a preview, a later fragment of an object an earlier fragment would create reads as a patch.
                boolean afterCreate = preview && outcome.action == Reconciler.Action.CREATE
                        && outcome.oid != null && wouldCreate.put(outcome.oid, true) != null;
                Reconciler.Action action = afterCreate ? Reconciler.Action.PATCH : outcome.action;

                if (!preview && perUnitPrompt
                        && (action == Reconciler.Action.CREATE || action == Reconciler.Action.PATCH)) {
                    if (!promptEach(action, outcome)) {
                        tally.skipped++;
                        reportUnit(unit, "skipped", "declined", false);
                        continue;
                    }
                }
                if (!preview && (action == Reconciler.Action.CREATE || action == Reconciler.Action.PATCH)) {
                    reconciler.execute(outcome);
                }
                record(tally, unit, action, outcome, preview, afterCreate);
            } catch (Exception e) {
                if (isFatal(e)) {
                    throw e;
                }
                tally.failed++;
                tally.failedUnits.add(unit);
                reportUnit(unit, preview ? "error" : "failed", safe(e.getMessage()), true);
                LOG.debug("unit {} {} failed", unit.typeName(), unit.identityLabel(), e);
            }
        }
        return tally;
    }

    private void record(Tally tally, Fragment unit, Reconciler.Action action, Reconciler.Outcome outcome,
            boolean preview, boolean afterCreate) {
        // Input advisories and the blast radius go to stderr per unit, so a destructive change is visible
        // in the plan before anything is written (and still shows in a real apply).
        for (String warning : outcome.warnings) {
            note("warning: " + unit.objectPath() + ": " + warning);
        }
        switch (action) {
            case CREATE:
                tally.created++;
                reportUnit(unit, preview ? "would-create" : "created", "", false);
                break;
            case PATCH:
                tally.patched++;
                String detail = afterCreate ? "after create"
                        : (outcome.changes > 0 ? outcome.changes + " change(s): " : "") + safe(outcome.detail);
                reportUnit(unit, preview ? "would-patch" : "patched", detail, false);
                break;
            case IN_SYNC:
                tally.inSync++;
                reportUnit(unit, "in-sync", "", false);
                break;
            case SKIP:
                tally.skipped++;
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

    // --- plan (write reviewable files, no server writes) ---

    private Integer runPlan(Reconciler reconciler, List<Fragment> units) throws Exception {
        if (PlanStore.hasPlanFiles(planDir)) {
            System.err.println("midgie: --plan directory " + planDir
                    + " already contains plan files; use an empty (or new) directory.");
            return EXIT_ERROR;
        }
        Map<String, PrismObject<? extends Objectable>> projected = new HashMap<>();
        Tally tally = new Tally();
        int seq = 0;
        for (Fragment unit : units) {
            seq++;
            PrismObject<? extends Objectable> desired = unit.object();
            String key = unit.identityKey();
            Reconciler.Outcome outcome = projected.containsKey(key)
                    ? reconciler.planAgainst(desired, projected.get(key), strategyFor(unit))
                    : reconciler.plan(desired, strategyFor(unit));

            switch (outcome.action) {
                case CREATE:
                    PlanStore.write(planDir, seq, outcome.restType, outcome.oid, unit.name(),
                            PlanStore.Kind.CREATE, outcome.createBody);
                    projected.put(key, desired.clone());
                    tally.created++;
                    reportUnit(unit, "would-create", "", false);
                    break;
                case PATCH:
                    PlanStore.write(planDir, seq, outcome.restType, outcome.oid, unit.name(),
                            PlanStore.Kind.PATCH, outcome.modificationXml);
                    if (projected.containsKey(key)) {
                        projected.put(key, applyDelta(projected.get(key), outcome.delta));
                    }
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
        }
        emitSummary(tally, true);
        if (tally.changed() > 0) {
            status("Wrote " + tally.changed() + " plan file(s) to " + planDir + ".");
        }
        if (tally.failed > 0) {
            return EXIT_ERROR;
        }
        return tally.changed() > 0 ? 1 : 0;
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // applyTo needs the delta's own object type; matched by construction
    private static PrismObject<? extends Objectable> applyDelta(PrismObject<? extends Objectable> base,
            ObjectDelta delta) throws SchemaException {
        PrismObject clone = base.clone();
        delta.applyTo(clone);
        return clone;
    }

    // --- apply-plan (replay saved files verbatim) ---

    private Integer runApplyPlan(MidpointRestClient client) throws Exception {
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
        List<PlanStore.Entry> failed = applyEntries(client, entries, confirmEachApply);
        if (retry && !failed.isEmpty()) {
            failed = retryUntilStable(failed, remaining -> applyEntries(client, remaining, confirmEachApply));
        }
        status("Done: " + (entries.size() - failed.size()) + " applied, " + failed.size() + " failed.");
        return failed.isEmpty() ? 0 : EXIT_ERROR;
    }

    private List<PlanStore.Entry> applyEntries(MidpointRestClient client, List<PlanStore.Entry> entries,
            boolean perUnitPrompt) throws Exception {
        List<PlanStore.Entry> failed = new ArrayList<>();
        for (PlanStore.Entry entry : entries) {
            try {
                if (perUnitPrompt && !promptEntry(entry)) {
                    reportEntry(entry, "skipped", "declined", false);
                    continue;
                }
                if (entry.kind == PlanStore.Kind.CREATE) {
                    client.createObject(entry.restType, entry.body);
                    reportEntry(entry, "created", "", false);
                } else {
                    client.patchObject(entry.restType, entry.oid, entry.body);
                    reportEntry(entry, "patched", "", false);
                }
            } catch (Exception e) {
                if (isFatal(e)) {
                    throw e;
                }
                failed.add(entry);
                reportEntry(entry, "failed", safe(e.getMessage()), true);
                LOG.debug("plan entry {}/{} failed", entry.restType, entry.oid, e);
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
        int steps = schemaOrder.ordered().size()
                + objectOrder.ordered().stream().mapToInt(object -> object.fragments().size()).sum();
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
            int firstSeq = seq + 1;
            boolean first = true;
            for (Fragment fragment : object.fragments()) {
                seq++;
                String suffix = first ? needsSuffix(needs) : "   # same object as #" + firstSeq;
                System.out.println(line(seq, fragment) + suffix);
                first = false;
            }
            if (object.oid() != null) {
                objectSeqByOid.put(object.oid(), firstSeq);
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

    private void reportUnit(Fragment fragment, String label, String detail, boolean error) {
        // source (file) -> target (object): the object path sits last so it reads into the change detail's
        // item path (e.g. SystemConfigurationType/SystemConfiguration  …deploymentInformation/headerColor).
        report(label, fragment.sourceFile().toString(), verbose() ? fragment.oidLabel() : null,
                fragment.objectPath(), detail, error);
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

    private static String safe(String value) {
        return (value != null) ? value : "";
    }

    private String line(int seq, Fragment fragment) {
        StringBuilder out = new StringBuilder(String.format("  %2d  %-44s", seq, fragment.sourceFile().toString()));
        if (verbose()) {
            out.append(String.format("  %-38s", fragment.oidLabel()));
        }
        return out.append("  ").append(fragment.objectPath()).toString();
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
        final List<Fragment> failedUnits = new ArrayList<>();

        int changed() {
            return created + patched;
        }
    }
}
