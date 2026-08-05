/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.util.DOMUtil;

/**
 * The safety net a {@code deploy} leaves behind: for every object it wrote, the delta that puts it back.
 *
 * <p>Most of the inverse is already in the forward delta. prism's {@code fixupDelta} is overridden for
 * single-valued <em>properties</em> alone, so multi-valued containers and references diff to add/delete
 * pairs — and when a path left on {@code multivalue=replace} wipes accrued values, those deletes carry the
 * wiped values themselves. {@code ObjectDelta.createReverseDelta()} swaps them back. Single-valued
 * properties need the {@code estimatedOldValues} that {@link DiffEngine} records, without which their
 * reverse would be an empty replace that clears the item instead of restoring it.
 *
 * <p>Entries are collected in apply order and written in <b>reverse</b>: the undo of a sequence is the
 * reverse sequence of inverses, which is also what makes it right for an object assembled from several
 * fragments. The files are ordinary {@link PlanStore} plan files, so {@code --apply-plan} replays them with
 * no new machinery, and {@code manifest.xml} carries what makes that replay safe — which server, which
 * deployment, which revision, and each object's repository version after the write.
 *
 * <p>Objects the run <em>created</em> are recorded but not undone. The inverse of an add is an object
 * delete, and an object created an hour ago may already carry links and shadows; that is a decision for a
 * person, so the manifest names them and stops there.
 */
final class RollbackPlan {

    private static final Logger LOG = LoggerFactory.getLogger(RollbackPlan.class);

    static final String MANIFEST = "manifest.xml";

    /** How many run directories to keep under a base path before the oldest are removed. */
    static final int DEFAULT_KEEP = 5;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    /** One object the run wrote to, in apply order. */
    private static final class Item {
        private final boolean created;
        private final String restType;
        private final String oid;
        private final String name;
        private final String inverseXml; // null for a create record
        private String version;

        private Item(boolean created, String restType, String oid, String name, String inverseXml) {
            this.created = created;
            this.restType = restType;
            this.oid = oid;
            this.name = name;
            this.inverseXml = inverseXml;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final PrismContext prismContext;
    private final MidpointRestClient client;

    RollbackPlan(PrismContext prismContext, MidpointRestClient client) {
        this.prismContext = prismContext;
        this.client = client;
    }

    /**
     * Record what a just-executed unit did, so the run can be undone.
     *
     * <p>Best-effort by design: a unit whose inverse cannot be computed is logged and skipped rather than
     * failing a deploy that has already succeeded. The manifest is what tells the operator the net is
     * incomplete.
     */
    void recordApplied(Reconciler.Action action, Reconciler.Outcome outcome, String affectedOid, String name) {
        String oid = (affectedOid != null) ? affectedOid : outcome.oid;
        try {
            if (action == Reconciler.Action.CREATE) {
                items.add(new Item(true, outcome.restType, oid, name, null));
                LOG.trace("rollback: recorded creation of {}/{}", outcome.restType, oid);
            } else if (action == Reconciler.Action.PATCH && outcome.delta != null) {
                String inverse = DeltaOutput.toModificationXml(prismContext, outcome.delta.createReverseDelta());
                items.add(new Item(false, outcome.restType, oid, name, inverse));
                LOG.trace("rollback: recorded inverse patch for {}/{}", outcome.restType, oid);
            } else {
                return;
            }
            recordVersion(oid, readVersion(outcome.restType, oid));
        } catch (Exception e) {
            LOG.debug("could not record a rollback entry for {}/{}", outcome.restType, oid, e);
        }
    }

    /** Record the inverse of a {@code --prune} delta, which puts back exactly the values it removed. */
    void recordPruned(String restType, String oid, String name, ObjectDelta<? extends Objectable> delta) {
        try {
            items.add(new Item(false, restType, oid, name,
                    DeltaOutput.toModificationXml(prismContext, delta.createReverseDelta())));
            recordVersion(oid, readVersion(restType, oid));
            LOG.trace("rollback: recorded inverse prune for {}/{}", restType, oid);
        } catch (Exception e) {
            LOG.debug("could not record a rollback entry for the prune of {}/{}", restType, oid, e);
        }
    }

    /**
     * The repository version of an object just written, which is what a rollback compares against to tell
     * "nobody has touched this since" from "someone else has". A plain GET, not the search the existence
     * probes use: the object was written a moment ago, so there is no 404 to avoid.
     */
    private String readVersion(String restType, String oid) {
        if (restType == null || oid == null) {
            return null;
        }
        try {
            String version = DOMUtil.parseDocument(client.getObject(restType, oid))
                    .getDocumentElement().getAttribute("version");
            return version.isBlank() ? null : version;
        } catch (Exception e) {
            // A net without a version is still worth having; the rollback reports what it cannot verify.
            LOG.debug("could not read the version of {}/{} for the rollback plan", restType, oid, e);
            return null;
        }
    }

    /**
     * Note the repository version of {@code oid} after a write. Several fragments may write one object, and
     * a retry may write it again; the last one is the version a rollback has to find unchanged.
     */
    private void recordVersion(String oid, String version) {
        if (oid == null || version == null) {
            return;
        }
        for (Item item : items) {
            if (oid.equals(item.oid)) {
                item.version = version;
            }
        }
    }

    boolean isEmpty() {
        return items.isEmpty();
    }

    /** How many entries would actually be replayable (creates are recorded but not undone). */
    int undoableCount() {
        return (int) items.stream().filter(item -> !item.created).count();
    }

    /**
     * Write the net into a new timestamped directory under {@code base} and return it.
     *
     * <p>The whole directory is marked incomplete while it is being written, so a run that dies half way
     * leaves something recognisably partial rather than a plan that replays only part of the tree.
     */
    File write(File base, String server, String deployment, String revision) throws IOException {
        File dir = new File(base, STAMP.format(Instant.now()));
        Io.restrictDirToOwner(dir.toPath());
        PlanStore.markIncomplete(dir);

        // Reverse apply order: the last thing written is the first thing undone.
        List<Item> undoable = items.stream().filter(item -> !item.created).toList();
        int width = String.valueOf(undoable.size()).length();
        int sequence = undoable.size();
        for (Item item : undoable) {
            PlanStore.write(dir, sequence--, width, item.restType, item.oid, item.name,
                    PlanStore.Kind.PATCH, item.inverseXml);
        }
        writeManifest(dir, server, deployment, revision);
        PlanStore.markComplete(dir);
        LOG.debug("rollback plan written to {} ({} undoable, {} created)", dir, undoable.size(),
                items.size() - undoable.size());
        return dir;
    }

    private void writeManifest(File dir, String server, String deployment, String revision) throws IOException {
        StringBuilder xml = new StringBuilder();
        xml.append(PlanStore.markGenerated("<rollbackManifest>")).append(System.lineSeparator());
        appendElement(xml, "server", server);
        appendElement(xml, "deployment", deployment);
        appendElement(xml, "revision", revision);
        appendElement(xml, "written", Instant.now().toString());
        for (Item item : items) {
            xml.append("  <object type=\"").append(escape(item.restType))
                    .append("\" oid=\"").append(escape(item.oid))
                    .append("\" name=\"").append(escape(item.name))
                    .append("\" action=\"").append(item.created ? "created" : "patched").append('"');
            if (item.version != null) {
                xml.append(" version=\"").append(escape(item.version)).append('"');
            }
            xml.append("/>").append(System.lineSeparator());
        }
        xml.append("</rollbackManifest>").append(System.lineSeparator());

        java.nio.file.Path target = new File(dir, MANIFEST).toPath();
        Io.restrictToOwner(target);
        Files.writeString(target, xml.toString(), StandardCharsets.UTF_8);
    }

    private static void appendElement(StringBuilder xml, String name, String value) {
        if (value != null && !value.isBlank()) {
            xml.append("  <").append(name).append('>').append(escape(value))
                    .append("</").append(name).append('>').append(System.lineSeparator());
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * Keep only the {@code keep} newest run directories under {@code base}.
     *
     * <p>Without this a deploy-then-deploy silently loses the net for the run that actually went wrong.
     * Directory names are the sortable timestamps {@link #write} generates, so "newest" is lexical order.
     * A directory that cannot be removed is logged and left alone: failing a successful deploy over
     * housekeeping would be the wrong trade.
     */
    static void rotate(File base, int keep) {
        File[] dirs = base.listFiles(File::isDirectory);
        if (dirs == null || dirs.length <= keep) {
            return;
        }
        Arrays.sort(dirs, Comparator.comparing(File::getName).reversed());
        for (int i = keep; i < dirs.length; i++) {
            if (!deleteTree(dirs[i])) {
                LOG.debug("could not remove the stale rollback plan {}", dirs[i]);
            }
        }
    }

    private static boolean deleteTree(File dir) {
        File[] children = dir.listFiles();
        boolean ok = true;
        for (File child : (children != null) ? children : new File[0]) {
            ok &= child.isDirectory() ? deleteTree(child) : child.delete();
        }
        return ok & dir.delete();
    }

    /**
     * The newest complete run directory under {@code base}, or {@code null} when there is none.
     * An unfinished plan is skipped rather than reported: it cannot be replayed anyway.
     */
    static File newestUnder(File base) {
        File[] dirs = base.listFiles(File::isDirectory);
        if (dirs == null) {
            return null;
        }
        Arrays.sort(dirs, Comparator.comparing(File::getName).reversed());
        for (File dir : dirs) {
            if (!PlanStore.isIncomplete(dir) && new File(dir, MANIFEST).isFile()) {
                return dir;
            }
        }
        return null;
    }

    /**
     * Where nets go when {@code --rollback-plan} does not say: {@code $XDG_STATE_HOME/midgie}, else
     * {@code ~/.local/state/midgie}, with the deployment's own key as a subdirectory so several trees on
     * one machine do not overwrite each other's history.
     *
     * <p>This is a default, not a policy. It is wrong for a Kubernetes Job, whose filesystem dies with the
     * pod that needed the net — such a deployment names a directory on a mounted volume instead.
     */
    static File defaultBase(String deploymentKey) {
        String xdg = System.getenv("XDG_STATE_HOME");
        File root = (xdg != null && !xdg.isBlank())
                ? new File(xdg, "midgie")
                : new File(System.getProperty("user.home"), ".local/state/midgie");
        return new File(root, sanitizeKey(deploymentKey));
    }

    /** A deployment key is a commit prefix plus a tree path; flatten it into one directory name. */
    private static String sanitizeKey(String key) {
        if (key == null || key.isBlank()) {
            return "unidentified";
        }
        String cleaned = key.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "unidentified" : cleaned;
    }
}
