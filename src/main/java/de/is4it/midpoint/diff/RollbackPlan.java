/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.util.DOMUtil;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

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

    /** The names {@link #STAMP} produces. Nothing else under a base path is a run directory. */
    private static final Pattern RUN_NAME = Pattern.compile("\\d{8}-\\d{6}");

    /**
     * What a written net says about itself: where it came from and what state it expects to find.
     *
     * <p>Read back by {@code midgie rollback} before anything is replayed. Provenance is what stops a net
     * from one server landing on another; the per-object versions are what tell "nobody has touched this
     * since my write" from "someone else has".
     */
    static final class Manifest {
        final String server;
        final String deployment;
        final String revision;
        final List<Entry> objects;

        /** One object the run touched, as recorded. */
        static final class Entry {
            final String restType;
            final String oid;
            final String name;
            final String version; // null when the version could not be read at write time
            final boolean created;

            Entry(String restType, String oid, String name, String version, boolean created) {
                this.restType = restType;
                this.oid = oid;
                this.name = name;
                this.version = version;
                this.created = created;
            }
        }

        private Manifest(String server, String deployment, String revision, List<Entry> objects) {
            this.server = server;
            this.deployment = deployment;
            this.revision = revision;
            this.objects = List.copyOf(objects);
        }

        static Manifest read(File dir) throws IOException {
            File file = new File(dir, MANIFEST);
            if (!file.isFile()) {
                throw new IOException("not a rollback plan (no " + MANIFEST + "): " + dir);
            }
            Element root;
            try {
                root = DOMUtil.parseFile(file).getDocumentElement();
            } catch (Exception e) {
                throw new IOException("cannot read " + file + ": " + e.getMessage(), e);
            }
            List<Entry> objects = new ArrayList<>();
            NodeList nodes = root.getElementsByTagName("object");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element element = (Element) nodes.item(i);
                String version = element.getAttribute("version");
                objects.add(new Entry(element.getAttribute("type"), element.getAttribute("oid"),
                        element.getAttribute("name"), version.isBlank() ? null : version,
                        "created".equals(element.getAttribute("action"))));
            }
            return new Manifest(text(root, "server"), text(root, "deployment"), text(root, "revision"), objects);
        }

        private static String text(Element root, String name) {
            NodeList nodes = root.getElementsByTagName(name);
            if (nodes.getLength() == 0) {
                return null;
            }
            String value = nodes.item(0).getTextContent();
            return (value == null || value.isBlank()) ? null : value.trim();
        }
    }

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
    private final OidComments oidComments;

    RollbackPlan(PrismContext prismContext, MidpointRestClient client) {
        this(prismContext, client, new OidComments());
    }

    /** As above, naming the OIDs in the files it writes for whoever reviews the rollback. */
    RollbackPlan(PrismContext prismContext, MidpointRestClient client, OidComments oidComments) {
        this.prismContext = prismContext;
        this.client = client;
        this.oidComments = oidComments;
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
                recordPatch(outcome.restType, oid, name, outcome.delta);
                return;
            } else {
                return;
            }
            recordVersion(oid, readVersion(outcome.restType, oid));
        } catch (Exception e) {
            LOG.debug("could not record a rollback entry for {}/{}", outcome.restType, oid, e);
        }
    }

    /**
     * Record the inverse of a PATCH this run sent. The delta is the whole change to the object — the removals
     * of values dropped from the tree included, since they travel in the same request — so the inverse puts
     * back exactly what was changed and removed, and nothing else.
     */
    void recordPatch(String restType, String oid, String name, ObjectDelta<? extends Objectable> delta) {
        try {
            items.add(new Item(false, restType, oid, name,
                    DeltaOutput.toModificationXml(prismContext, delta.createReverseDelta())));
            recordVersion(oid, readVersion(restType, oid));
            LOG.trace("rollback: recorded inverse patch for {}/{}", restType, oid);
        } catch (Exception e) {
            LOG.debug("could not record a rollback entry for {}/{}", restType, oid, e);
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
                    PlanStore.Kind.PATCH, oidComments.annotate(item.inverseXml, item.oid, null));
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
     *
     * <p>Only {@link #isRunDirectory run directories} are candidates. The base path comes from
     * {@code --rollback-plan} or a tree-local config file, so it can name a directory that holds anything —
     * the tree itself, a home directory — and everything that is not a net this class wrote is left alone.
     * A directory that cannot be removed is logged and left alone too: failing a successful deploy over
     * housekeeping would be the wrong trade.
     */
    static void rotate(File base, int keep) {
        File[] dirs = base.listFiles(RollbackPlan::isRunDirectory);
        if (dirs == null || dirs.length <= keep) {
            return;
        }
        Arrays.sort(dirs, Comparator.comparing(File::getName).reversed());
        for (int i = keep; i < dirs.length; i++) {
            deleteTree(dirs[i].toPath());
        }
    }

    /**
     * True if {@code dir} is a net {@link #write} produced: a timestamp name, a real directory rather than a
     * link to one, and a manifest or the unfinished-plan marker inside. All three, because rotation deletes
     * what this accepts, and the name alone is not proof of anything.
     */
    static boolean isRunDirectory(File dir) {
        return RUN_NAME.matcher(dir.getName()).matches()
                && Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS)
                && (new File(dir, MANIFEST).isFile() || PlanStore.isIncomplete(dir));
    }

    /**
     * Delete {@code dir} and everything in it without following links: a link inside is removed, never the
     * directory it points to.
     */
    private static void deleteTree(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path visited, IOException failure) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.delete(visited);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.debug("could not remove the stale rollback plan {}", dir, e);
        }
    }

    /**
     * The newest complete run directory under {@code base}, or {@code null} when there is none.
     * An unfinished plan is skipped rather than reported: it cannot be replayed anyway.
     */
    static File newestUnder(File base) {
        File[] dirs = base.listFiles(RollbackPlan::isRunDirectory);
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
    static File defaultBase(String deploymentKey, File tree) {
        String xdg = System.getenv("XDG_STATE_HOME");
        File root = (xdg != null && !xdg.isBlank())
                ? new File(xdg, "midgie")
                : new File(System.getProperty("user.home"), ".local/state/midgie");
        return new File(root, sanitizeKey(deploymentKey, tree));
    }

    /**
     * One directory name per deployment. A deployment key is a commit prefix plus a tree path, so it needs
     * flattening; a tree with no derivable identity (not a repository, a shallow clone) falls back to its
     * own path instead of a shared bucket, or unrelated trees would rotate each other's nets away.
     *
     * <p>A leading dot is flattened too. An {@code --app} name comes from a tree-local config file as easily
     * as from the command line, and {@code .} or {@code ..} would otherwise resolve to the state root or
     * the directory above it rather than to a directory of this deployment's own.
     */
    private static String sanitizeKey(String key, File tree) {
        if (key != null && !key.isBlank()) {
            String cleaned = key.replaceAll("[^A-Za-z0-9._-]", "_").replaceFirst("^\\.", "_");
            if (!cleaned.isBlank()) {
                return cleaned;
            }
        }
        if (tree == null) {
            return "unidentified";
        }
        String path;
        try {
            path = tree.getCanonicalPath();
        } catch (IOException e) {
            path = tree.getAbsolutePath();
        }
        // Readable prefix plus a digest, so two same-named trees in different places stay apart.
        String name = tree.getName().replaceAll("[^A-Za-z0-9._-]", "_");
        return (name.isBlank() ? "tree" : name) + "-" + Integer.toHexString(path.hashCode());
    }
}
