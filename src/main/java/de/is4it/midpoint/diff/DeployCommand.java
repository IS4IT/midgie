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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.w3c.dom.Element;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code deploy} subcommand: discover a tree of desired-state object XML, sort it by dependency, and
 * either print the apply order (offline) or reconcile each unit against a live server ({@code --host}).
 *
 * <p>The order is two-tier. First the extension <b>schemas</b> (discovered {@code .xsd} inputs and
 * deployable {@code SchemaType} objects), sorted among themselves by {@code <xsd:import>} dependency and
 * registered so objects that use extension attributes parse. Then the <b>objects</b>, grouped by identity
 * (OID, else type+name — several files may be fragments of one object) and sorted by their outbound
 * reference OIDs.
 *
 * <p>With {@code --host} each unit is reconciled file-by-file (GET → diff the fragment {@code --scope
 * present} → PATCH, or POST to create with {@code --create-if-absent}), in that order, so a role is
 * created before the user that references it. Writing is gated: a single up-front confirmation by default
 * ({@code --yes} to bypass, {@code --confirm-each-apply} to prompt per change, {@code --dry-run} to preview
 * without writing). Retry-until-stable and the plan/apply-plan modes are a later increment.
 *
 * <p>Exit codes: offline (print the plan) {@code 0}/{@code 2}. Apply {@code 0} (all created/patched/in-sync
 * /skipped or declined) / {@code 2} (any unit failed, or a setup error). {@code --dry-run} {@code 0}
 * in-sync / {@code 1} would change something / {@code 2} error.
 */
@Command(
        name = "deploy",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Discover a folder tree, sort by dependency, and print the apply order or apply it (--host)."
)
public class DeployCommand implements Callable<Integer> {

    private static final int EXIT_ERROR = 2;

    @Parameters(index = "0..*", arity = "1..*", paramLabel = "PATH",
            description = "Files or directories of desired-state object XML, walked recursively.")
    List<File> paths;

    @Mixin
    StrategyOptions strategyOptions;

    @Mixin
    SchemaOptions schemaOptions;

    @Mixin
    ConnectionOptions connectionOptions;

    @Option(names = "--dry-run",
            description = "Compute and print every change but do not write. Needs --host. Exits 1 if anything would change.")
    boolean dryRun;

    @Option(names = {"--yes", "-y"},
            description = "Apply without the interactive confirmation prompt (required in non-interactive use).")
    boolean assumeYes;

    @Option(names = "--confirm-each-apply",
            description = "Prompt before each individual change (per itemDelta) instead of once for the whole tree.")
    boolean confirmEachApply;

    @Option(names = "--create-if-absent",
            description = "POST objects that do not exist yet (otherwise a missing object fails; an OID-less one is skipped).")
    boolean createIfAbsent;

    @Option(names = "--apply-on-match",
            description = "For an OID-less object, search the server by type+name and PATCH the single match (else skip/create).")
    boolean applyOnMatch;

    @Option(names = {"--quiet", "-q"},
            description = "Suppress the plan/status output and notes; keep errors, failures, and the exit code.")
    boolean quiet;

    @Option(names = {"--debug", "-v"},
            description = "Raise the log level to DEBUG (framework logs on stderr) for troubleshooting.")
    boolean debug;

    @Override
    public Integer call() {
        if (debug) {
            Logging.enableDebug();
        }
        if (assumeYes && confirmEachApply) {
            System.err.println("midpoint-diff: use either --yes or --confirm-each-apply, not both.");
            return EXIT_ERROR;
        }
        boolean live = connectionOptions.isLive();
        if (!live && (dryRun || assumeYes || confirmEachApply || createIfAbsent || applyOnMatch)) {
            System.err.println("midpoint-diff: --dry-run/--yes/--confirm-each-apply/--create-if-absent/--apply-on-match "
                    + "need --host; without a host, deploy only prints the plan.");
            return EXIT_ERROR;
        }

        StrategyOptions.Resolved strategy = null;
        MidpointRestClient client = null;
        if (live) {
            strategy = strategyOptions.resolve();
            if (strategy == null) {
                return EXIT_ERROR;
            }
            client = connectionOptions.newClient();
            if (client == null) {
                return EXIT_ERROR;
            }
        }

        try {
            PrismContext prismContext = SchemaOptions.newBaseContext();
            if (live && !quiet) {
                VersionSupport.warnIfMismatch(prismContext, client);
            }

            // Collect every extension schema — CLI flags, live server, and the tree — and register in one
            // batch (prism replaces the dynamic set on each call, so it must be a single registration).
            Map<String, Element> schemaMap = schemaOptions.collectSchemas(prismContext, client);
            Discovery.ScanResult scan = Discovery.scan(prismContext, paths);
            for (SchemaFile schema : scan.schemas()) {
                schemaMap.put(schema.description(), schema.schemaElement());
            }
            SchemaSupport.register(prismContext, schemaMap);

            List<String> warnings = new ArrayList<>(scan.warnings());
            List<DeployObject> objects = DeployObject.group(parseObjects(prismContext, scan, warnings));

            List<SchemaFile> deployableSchemas = scan.schemas().stream()
                    .filter(SchemaFile::deployable).collect(Collectors.toList());
            List<SchemaFile> localSchemas = scan.schemas().stream()
                    .filter(schema -> !schema.deployable()).collect(Collectors.toList());

            if (deployableSchemas.isEmpty() && objects.isEmpty()) {
                System.err.println("midpoint-diff: no midPoint objects found under the given path(s).");
                return EXIT_ERROR;
            }

            TopoSort.Result<SchemaFile> schemaOrder = sortSchemas(deployableSchemas);
            TopoSort.Result<DeployObject> objectOrder = sortObjects(objects);

            if (!live) {
                printPlanTable(schemaOrder, objectOrder);
                emitWarnings(localSchemas, schemaOrder, objectOrder, warnings);
                return 0;
            }

            emitWarnings(localSchemas, schemaOrder, objectOrder, warnings);
            Reconciler reconciler = new Reconciler(prismContext, client, strategy, createIfAbsent, applyOnMatch);
            return runApply(reconciler, flatten(schemaOrder, objectOrder));
        } catch (Exception e) {
            String message = (e.getMessage() != null) ? e.getMessage() : e.toString();
            System.err.println("midpoint-diff: " + message);
            return EXIT_ERROR;
        }
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
            System.err.println("midpoint-diff: --confirm-each-apply needs an interactive session; "
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
        return applied.failed > 0 ? EXIT_ERROR : 0;
    }

    /**
     * Reconcile every unit in order. When {@code preview} is set, nothing is written (used by
     * {@code --dry-run} and the up-front confirmation). {@code perUnitPrompt} asks before each write.
     * A transport-level failure (auth, connection) aborts; a per-object rejection is recorded and the run
     * continues (best-effort).
     */
    private Tally pass(Reconciler reconciler, List<Fragment> units, boolean preview, boolean perUnitPrompt)
            throws Exception {
        Tally tally = new Tally();
        Set<String> wouldCreate = new HashSet<>();
        for (Fragment unit : units) {
            try {
                Reconciler.Outcome outcome = reconciler.plan(unit.object());

                // In a preview, a later fragment of an object an earlier fragment would create reads as a patch.
                boolean afterCreate = preview && outcome.action == Reconciler.Action.CREATE
                        && outcome.oid != null && !wouldCreate.add(outcome.oid);
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
                reportUnit(unit, preview ? "error" : "failed", safe(e.getMessage()), true);
            }
        }
        return tally;
    }

    private void record(Tally tally, Fragment unit, Reconciler.Action action, Reconciler.Outcome outcome,
            boolean preview, boolean afterCreate) {
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
                reportUnit(unit, preview ? "error" : "failed", safe(outcome.detail), true);
                break;
        }
    }

    private boolean promptEach(Reconciler.Action action, Reconciler.Outcome outcome) {
        Console console = System.console();
        if (console == null) {
            return false;
        }
        String verb = (action == Reconciler.Action.CREATE)
                ? "Create" : "Apply " + outcome.changes + " change(s) to";
        String answer = console.readLine("%s %s/%s? [y/N] ", verb, outcome.restType,
                outcome.oid != null ? outcome.oid : "(new)");
        String normalized = (answer == null) ? "" : answer.trim().toLowerCase();
        return normalized.equals("y") || normalized.equals("yes");
    }

    private Integer confirmOnce(int changeCount) {
        Console console = System.console();
        boolean interactive = console != null;
        String answer = interactive
                ? console.readLine("Apply %d change(s) to %s? [y/N] ", changeCount, connectionOptions.host())
                : null;
        switch (ApplyCommand.decideConfirmation(false, interactive, answer)) {
            case PROCEED:
                return null;
            case ABORTED:
                System.err.println("midpoint-diff: aborted — nothing was applied.");
                return 0;
            case NON_INTERACTIVE:
            default:
                System.err.println("midpoint-diff: refusing to deploy without confirmation in a non-interactive "
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
            System.err.println("midpoint-diff: note: registered local schema (not deployed): "
                    + local.sourceFile() + " [" + local.targetNamespace() + "]");
        }
        for (SchemaFile schema : schemaOrder.brokenAt()) {
            System.err.println("midpoint-diff: warning: schema dependency cycle — broke it at "
                    + schema.sourceFile() + "; the order there is approximate.");
        }
        for (DeployObject object : objectOrder.brokenAt()) {
            System.err.println("midpoint-diff: warning: object dependency cycle — broke it at "
                    + object.identityLabel() + "; the order there is approximate.");
        }
        for (String warning : warnings) {
            System.err.println("midpoint-diff: warning: " + warning);
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
        StringBuilder out = new StringBuilder(String.format("  %-12s %-11s %-38s %-16s %s", label,
                fragment.typeName(), fragment.identityLabel(), safe(fragment.name()), fragment.sourceFile()));
        if (detail != null && !detail.isEmpty()) {
            out.append("  ").append(detail);
        }
        if (error) {
            System.err.println(out);
        } else {
            status(out.toString());
        }
    }

    private void status(String message) {
        if (!quiet) {
            System.out.println(message);
        }
    }

    private static String safe(String value) {
        return (value != null) ? value : "";
    }

    private static String line(int seq, Fragment fragment) {
        return String.format("  %2d  %-12s  %-38s  %-18s  %s", seq, fragment.typeName(),
                fragment.identityLabel(), safe(fragment.name()), fragment.sourceFile());
    }

    private static String needsSuffix(List<Integer> needs) {
        if (needs.isEmpty()) {
            return "";
        }
        return "   (needs " + needs.stream().map(n -> "#" + n).collect(Collectors.joining(",")) + ")";
    }

    /** Running counts for a preview or apply pass. */
    private static final class Tally {
        int created;
        int patched;
        int inSync;
        int skipped;
        int failed;

        int changed() {
            return created + patched;
        }
    }
}
