/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.util.DOMUtil;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Put a deployment back the way it was: replay the rollback plan the last {@code deploy} left behind.
 *
 * <p>Nothing is recomputed. The net holds the inverse of each change as it was actually applied, so this
 * command only has to decide whether replaying it is still safe and then send it. Two checks stand in the
 * way, and they are the reason this is a command of its own rather than {@code deploy --apply-plan}:
 *
 * <ul>
 *   <li><b>Provenance</b> — the net records which server and which deployment it came from. A mismatch is
 *       an error, not a prompt: a plan written against staging must not be replayable against production
 *       because somebody answered yes.</li>
 *   <li><b>Staleness</b> — the net records each object's repository version after the write. If an object
 *       has moved since, someone else has changed it, and replaying would revert their change along with
 *       ours. That refuses too, but {@code --force} exists because sometimes reverting anyway is exactly
 *       what you want.</li>
 * </ul>
 *
 * <p>A rollback plan only ever contains patches. Objects the deploy <em>created</em> are named in the
 * manifest but never undone, so there is no create path here and nothing this command can delete.
 *
 * <p>After the replay, the {@code midgie-state} rows the deploy moved are moved back for each object that was
 * restored — but only a row still holding what the deploy wrote; one a later run has moved on is that run's.
 */
@Command(name = "rollback",
        description = "Replay the rollback plan from the last deploy, putting the objects it changed back.",
        sortOptions = false, mixinStandardHelpOptions = true)
public class RollbackCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(RollbackCommand.class);

    private static final int EXIT_ERROR = 2;

    @Parameters(index = "0", arity = "0..1", paramLabel = "TREE",
            description = "The deployed tree whose rollback plan to replay (default: the current directory). "
                    + "Only used to find the plan; nothing in it is read.")
    File tree;

    @Mixin
    ConnectionOptions connectionOptions;

    @Option(names = "--rollback-plan", paramLabel = "DIR",
            description = "The rollback plan to replay, or the base directory holding several (default: the "
                    + "per-deployment directory under $XDG_STATE_HOME/midgie). Without a run directory, the "
                    + "newest complete plan is used.")
    File rollbackPlanDir;

    @Option(names = "--app", paramLabel = "NAME",
            description = "Find the plan under NAME instead of the identity derived from the tree's git "
                    + "repository. Must match the --app the deploy used.")
    String app;

    @Option(names = "--dry-run",
            description = "Report what would be restored and change nothing (exit 1 if there is anything).")
    boolean dryRun;

    @Option(names = {"--yes", "-y"}, description = "Do not prompt before writing (for non-interactive use).")
    boolean assumeYes;

    @Option(names = "--force",
            description = "Replay even though objects have changed since the deploy. Their later changes "
                    + "are reverted along with the deploy's.")
    boolean force;

    @Option(names = {"--quiet", "-q"}, description = "Keep only errors and the exit code.")
    boolean quiet;

    @Option(names = {"--debug", "-v"}, description = "Log at DEBUG on stderr.")
    boolean debug;

    @Option(names = "--trace", description = "Log at TRACE on stderr (implies --debug), REST request and "
            + "response bodies included (password values masked, other sensitive values may show).")
    boolean trace;

    @Override
    public Integer call() {
        if (trace) {
            Logging.enableTrace();
        } else if (debug) {
            Logging.enableDebug();
        }
        try {
            File run = resolveRunDirectory();
            if (run == null) {
                return EXIT_ERROR;
            }
            RollbackPlan.Manifest manifest = RollbackPlan.Manifest.read(run);
            List<PlanStore.Entry> entries = PlanStore.read(run);
            if (entries.isEmpty()) {
                status("Nothing to roll back: " + run + " records no changes"
                        + (manifest.objects.isEmpty() ? "." : " that can be undone."));
                reportCreated(manifest);
                return 0;
            }

            if (connectionOptions.url() == null) {
                System.err.println("midgie: rollback writes to a server; pass --url (or set MIDPOINT_URL).");
                return EXIT_ERROR;
            }
            MidpointRestClient client = connectionOptions.newClient();
            if (client == null) {
                return EXIT_ERROR; // newClient() already explained why
            }
            Integer refusal = checkProvenance(manifest, client, run);
            if (refusal != null) {
                return refusal;
            }

            status("Rollback plan " + run + " — " + entries.size() + " object(s) to restore.");
            for (PlanStore.Entry entry : entries) {
                row(dryRun ? "would-restore" : "restore", entry.restType + "/" + entry.oid);
            }
            reportCreated(manifest);

            List<String> stale = findStaleObjects(manifest, client);
            if (!stale.isEmpty()) {
                for (String object : stale) {
                    warn("changed since the deploy: " + object);
                }
                if (!force) {
                    System.err.println("midgie: refusing to roll back — " + stale.size()
                            + " object(s) have changed since the deploy, so replaying would revert those "
                            + "changes too. Re-run with --force to do it anyway.");
                    return EXIT_ERROR;
                }
                warn("--force given; the later changes above will be reverted too.");
            }

            if (dryRun) {
                long rows = manifest.objects.stream().mapToLong(object -> object.rows.size()).sum()
                        + manifest.runRows.size();
                if (rows > 0) {
                    status("  and the " + rows + " tracking row(s) the deploy moved, where no later run has since.");
                }
                return entries.isEmpty() ? 0 : 1;
            }
            Integer stop = confirm(entries.size());
            if (stop != null) {
                return stop;
            }
            Set<String> restored = new LinkedHashSet<>();
            Integer exit = replay(client, entries, restored);
            restoreRows(client, manifest, run, restored, restored.size() == entries.size());
            return exit;
        } catch (Exception e) {
            String message = (e.getMessage() != null) ? e.getMessage() : e.toString();
            System.err.println("midgie: " + message);
            LOG.trace("rollback failed", e);
            return EXIT_ERROR;
        }
    }

    /**
     * The run directory to replay: the one named outright, else the newest complete one under the base.
     * Returns {@code null} after reporting why there is nothing to replay.
     */
    private File resolveRunDirectory() {
        File base = rollbackPlanDir;
        if (base == null) {
            File treeDir = (tree != null) ? tree : new File(".");
            DeploymentIdentity identity = DeploymentIdentity.of(treeDir, app);
            if (!identity.isAvailable()) {
                note("note: " + identity.unavailableReason() + ".");
            }
            base = RollbackPlan.defaultBase(identity.isAvailable() ? identity.key() : null, treeDir);
        }
        if (!base.isDirectory()) {
            System.err.println("midgie: no rollback plan at " + base
                    + " — a deploy leaves one behind unless --no-rollback-plan was given.");
            return null;
        }
        // A directory holding a manifest IS the run; otherwise it is the base holding several.
        if (new File(base, RollbackPlan.MANIFEST).isFile()) {
            return base;
        }
        File newest = RollbackPlan.newestUnder(base);
        if (newest == null) {
            System.err.println("midgie: no complete rollback plan under " + base + ".");
            return null;
        }
        return newest;
    }

    /**
     * Refuse a plan that belongs somewhere else. No {@code --force} here on purpose: a wrong-server replay
     * is not a risk an operator should be able to accept in passing, and the honest fix is to name the
     * right plan.
     */
    private Integer checkProvenance(RollbackPlan.Manifest manifest, MidpointRestClient client, File run) {
        String actual = String.valueOf(client.host());
        if (manifest.server != null && !manifest.server.equals(actual)) {
            System.err.println("midgie: refusing to roll back — the plan in " + run + " was written against "
                    + manifest.server + ", not " + actual + ".");
            return EXIT_ERROR;
        }
        if (manifest.deployment != null) {
            File treeDir = (tree != null) ? tree : new File(".");
            DeploymentIdentity identity = DeploymentIdentity.of(treeDir, app);
            if (identity.isAvailable() && !manifest.deployment.equals(identity.key())) {
                System.err.println("midgie: refusing to roll back — the plan in " + run
                        + " belongs to deployment " + manifest.deployment + ", not " + identity.key() + ".");
                return EXIT_ERROR;
            }
        }
        return null;
    }

    /**
     * Objects whose repository version no longer matches what the deploy left behind.
     *
     * <p>Read-then-compare, so it races with a concurrent writer — but a config pipeline is not a
     * high-contention workload, and the alternative is a rollback that silently reverts a colleague's edit.
     * An object with no recorded version cannot be judged and is reported rather than assumed unchanged.
     */
    private List<String> findStaleObjects(RollbackPlan.Manifest manifest, MidpointRestClient client) {
        List<String> stale = new ArrayList<>();
        for (RollbackPlan.Manifest.Entry object : manifest.objects) {
            if (object.created || object.unrecorded) {
                continue; // not being restored anyway
            }
            String label = object.restType + "/" + object.oid;
            if (object.version == null) {
                warn("no recorded version for " + label + " — cannot tell whether it has changed since.");
                continue;
            }
            String current = readVersion(client, object.restType, object.oid);
            if (current == null) {
                stale.add(label + " (could not be read)");
            } else if (!current.equals(object.version)) {
                stale.add(label + " (version " + object.version + " → " + current + ")");
            }
        }
        return stale;
    }

    private String readVersion(MidpointRestClient client, String restType, String oid) {
        try {
            String version = DOMUtil.parseDocument(client.getObject(restType, oid))
                    .getDocumentElement().getAttribute("version");
            return version.isBlank() ? null : version;
        } catch (Exception e) {
            LOG.debug("could not read the version of {}/{}", restType, oid, e);
            return null;
        }
    }

    /**
     * Send each recorded inverse, in the order the files sort — reverse of how the deploy applied them. The OID
     * of each object restored goes to {@code restored}.
     */
    private Integer replay(MidpointRestClient client, List<PlanStore.Entry> entries, Set<String> restored) {
        int applied = 0;
        int failed = 0;
        for (PlanStore.Entry entry : entries) {
            try {
                // Comments are for the reviewer; the server gets the change and nothing else.
                client.patchObject(entry.restType, entry.oid, OidComments.strip(entry.body),
                        PlanStore.setsReferenceByFilter(entry));
                applied++;
                restored.add(entry.oid);
                row("restored", entry.restType + "/" + entry.oid);
            } catch (Exception e) {
                failed++;
                System.err.println("midgie: could not restore " + entry.restType + "/" + entry.oid + ": "
                        + ((e.getMessage() != null) ? e.getMessage() : e.toString()));
                LOG.trace("restoring {}/{} failed", entry.restType, entry.oid, e);
            }
        }
        status("Done: " + applied + " restored, " + failed + " failed.");
        return failed == 0 ? 0 : EXIT_ERROR;
    }

    /**
     * Move the {@code midgie-state} rows back for the objects restored, and the run's own rows when all of them
     * were. A row is restored only while it still holds what the deploy wrote.
     *
     * <p>Without this the rows kept saying the rolled-back revision was applied, and a deploy of that revision
     * again found nothing it had dropped: the values it was meant to remove read as values someone else had
     * added, and merge kept them beside the new ones. A failure here does not undo the restore, but it is said
     * whatever {@code --quiet} asks, because the next deploy depends on it.
     */
    private void restoreRows(MidpointRestClient client, RollbackPlan.Manifest manifest, File run,
            Set<String> restored, boolean all) {
        Map<String, RollbackPlan.RowChange> wanted = new LinkedHashMap<>();
        for (RollbackPlan.Manifest.Entry object : manifest.objects) {
            if (!object.created && restored.contains(object.oid)) {
                object.rows.forEach(row -> wanted.putIfAbsent(row.key(), row));
            }
        }
        if (all) {
            manifest.runRows.forEach(row -> wanted.putIfAbsent(row.key(), row));
        }
        if (wanted.isEmpty()) {
            return;
        }
        try {
            PrismContext prismContext = SchemaOptions.newBaseContext();
            PrismObject<? extends Objectable> table = SyncState.find(prismContext, client);
            if (table == null || !SyncState.isEnabled(table)) {
                note("note: " + SyncState.TABLE_NAME + " is not in use on this server; no tracking rows to restore.");
                return;
            }
            Map<String, String> current = new LinkedHashMap<>();
            for (SyncState.Row row : SyncState.rows(table)) {
                current.putIfAbsent(row.key(), row.value());
            }
            SyncState.Changes changes = new SyncState.Changes();
            int movedOn = 0;
            for (RollbackPlan.RowChange row : wanted.values()) {
                if (!Objects.equals(current.get(row.key()), row.after())) {
                    movedOn++; // a later run wrote it since; the row is that run's now
                    continue;
                }
                if (row.before() == null) {
                    changes.delete(row.key());
                } else {
                    changes.set(row.key(), row.before(), row.beforeLabel());
                }
            }
            SyncState.apply(prismContext, client, table, changes);
            status("Restored " + (wanted.size() - movedOn) + " tracking row(s) in " + SyncState.TABLE_NAME + "."
                    + (movedOn > 0 ? " " + movedOn + " row(s) a later deploy has written since were left alone."
                            : ""));
        } catch (Exception e) {
            System.err.println("midgie: warning: could not restore the tracking rows in " + SyncState.TABLE_NAME
                    + ": " + ((e.getMessage() != null) ? e.getMessage() : e.toString()) + " — until they are, a "
                    + "deploy of the rolled-back revision may keep values it is meant to remove. The rows and "
                    + "their earlier values are listed in " + new File(run, RollbackPlan.MANIFEST) + ".");
            LOG.trace("restoring the tracking rows failed", e);
        }
    }

    /** Name the objects the deploy created, which a rollback deliberately leaves alone, and those it cannot undo. */
    private void reportCreated(RollbackPlan.Manifest manifest) {
        List<String> unrecorded = manifest.objects.stream().filter(object -> object.unrecorded)
                .map(object -> object.restType + "/" + object.oid).toList();
        if (!unrecorded.isEmpty()) {
            System.err.println("midgie: warning: the deploy changed " + unrecorded.size() + " object(s) whose "
                    + "inverse could not be computed, so this plan does not restore them: "
                    + String.join(", ", unrecorded) + ".");
        }
        List<String> created = manifest.objects.stream().filter(object -> object.created)
                .map(object -> object.restType + "/" + object.oid).toList();
        if (!created.isEmpty()) {
            note("note: the deploy created " + created.size() + " object(s), which are not removed by a "
                    + "rollback: " + String.join(", ", created) + ". Delete them by hand if that is what "
                    + "you want.");
        }
    }

    private Integer confirm(int count) {
        if (assumeYes) {
            return null;
        }
        if (System.console() == null) {
            System.err.println("midgie: refusing to write in a non-interactive session; pass --yes.");
            return EXIT_ERROR;
        }
        System.err.print("Restore " + count + " object(s)? [y/N] ");
        String answer = System.console().readLine();
        if (answer == null || !answer.trim().equalsIgnoreCase("y")) {
            status("Nothing was changed.");
            return 0;
        }
        return null;
    }

    /** One result row, in the same two columns as {@code deploy}'s. */
    private void row(String label, String object) {
        for (String line : new OutputLayout.Block(label, object).render(OutputLayout.width())) {
            status(line);
        }
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

    private void warn(String message) {
        if (!quiet) {
            System.err.println("midgie: warning: " + message);
        }
    }
}
