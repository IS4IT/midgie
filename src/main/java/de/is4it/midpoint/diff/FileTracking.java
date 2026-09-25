/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What was last applied, file by file: the per-file rows of {@code midgie-state} for one repository, and the
 * last-applied history {@link RemovalDetector} compares against.
 *
 * <p>Each deployed file has a row keyed by the repository's root commit (or {@code --app}) and its path in the
 * repository, holding the git blob id of the content last applied. Whatever path a run deploys — the whole
 * repository, a folder, one file — it reads and writes exactly the rows of the files it covers, so deploys of
 * different paths never disagree about what a file was last applied as. A blob id is the content's own hash,
 * the same in every clone and worktree, and changes only when the file does, so a run writes only the rows of
 * files that changed.
 *
 * <p>Last-applied for an object is the combination of its files, each as its row records it, following three
 * rules that keep moves and partial deploys safe:
 * <ul>
 *   <li>a file of this run with a row contributes the content its row names;</li>
 *   <li>a file whose row remains but which no longer exists anywhere contributes too — the old path of a
 *       renamed file, or a deleted fragment — marked in scope only when its path lies inside this run, so a
 *       deletion elsewhere is never reported as an orphan here;</li>
 *   <li>a file that still exists outside this run is left out on both sides: deploying part of an object
 *       applies only that part, and must not read the rest as removed.</li>
 * </ul>
 *
 * <p>Rows written by earlier versions record a whole tree at one commit. A file without a row of its own is
 * read at the commit of the nearest such row above it, once; the tree row is removed when a run covers it
 * completely.
 */
final class FileTracking {

    private static final Logger LOG = LoggerFactory.getLogger(FileTracking.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String DEPLOY_PREFIX = "deploy:";

    /** A file's row. */
    record FileRow(String path, String key, String blob, boolean dirty, String at, String oid, String label) {
    }

    /** A tree row written by an earlier version: the tree at {@code path}, applied at {@code revision}. */
    record TreeRow(String path, String key, String revision) {
    }

    private final GitRevision git;
    private final String namespace;
    private final List<String> scopes;
    private final Map<String, FileRow> files = new LinkedHashMap<>();
    private final Map<String, TreeRow> trees = new LinkedHashMap<>();
    private final Set<String> unreadable = new LinkedHashSet<>();

    /**
     * @param namespace the repository's short root commit, or the {@code --app} name
     * @param scopes    the repository-relative paths this run deploys; {@code ""} is the whole repository
     */
    FileTracking(GitRevision git, String namespace, List<String> scopes, List<SyncState.Row> rows) {
        this.git = git;
        this.namespace = namespace;
        this.scopes = List.copyOf(scopes);
        for (SyncState.Row row : rows) {
            String key = row.key();
            if (key == null || row.value() == null) {
                continue;
            }
            String path;
            if (key.equals(namespace)) {
                path = "";
            } else if (key.startsWith(namespace + "/")) {
                path = key.substring(namespace.length() + 1);
            } else {
                continue; // another repository's row, or a deploy: row
            }
            String value = row.value().strip();
            if (value.startsWith("{")) {
                FileRow file = parseFileRow(path, key, value, row.label());
                if (file != null) {
                    files.put(path, file);
                }
            } else if (value.matches("[0-9a-fA-F]{7,40}(-dirty)?")) {
                trees.put(path, new TreeRow(path, key, value));
            }
        }
        LOG.debug("tracking {}: {} file row(s), {} tree row(s) from earlier versions, scope {}", namespace,
                files.size(), trees.size(), scopes);
    }

    /** The key of a file's row. */
    String keyOf(String path) {
        return path.isEmpty() ? namespace : namespace + "/" + path;
    }

    /** The key of the row that records the last run deploying {@code scope}. */
    String deployKeyOf(String scope) {
        return DEPLOY_PREFIX + keyOf(scope);
    }

    List<String> scopes() {
        return scopes;
    }

    /** Whether {@code path} lies inside what this run deploys. */
    boolean inScope(String path) {
        for (String scope : scopes) {
            if (scope.isEmpty() || path.equals(scope) || path.startsWith(scope + "/")) {
                return true;
            }
        }
        return false;
    }

    /** The repository path of {@code file}, or {@code null} when it lies outside the repository. */
    String pathOf(File file) {
        return git.repoRelative(file);
    }

    /**
     * Files whose last-applied content this run needed and could not read — a blob or commit the repository
     * no longer holds (history rewritten, garbage-collected, fetched shallow). Non-empty means removals cannot
     * be told this run.
     */
    Set<String> unreadable() {
        return unreadable;
    }

    /** The last-applied history for {@link RemovalDetector}, per the three rules in the class comment. */
    RemovalDetector.History history() {
        return units -> {
            Map<String, RemovalDetector.Snapshot> snapshots = new LinkedHashMap<>();
            Set<String> current = new LinkedHashSet<>();
            for (DeployObject unit : units) {
                for (File file : unit.sourceFiles()) {
                    String path = pathOf(file);
                    if (path != null) {
                        current.add(path);
                    }
                }
            }
            for (String path : current) {
                FileRow row = files.get(path);
                if (row != null) {
                    add(snapshots, path, readBlob(path, row.blob()), true);
                    continue;
                }
                TreeRow tree = treeAbove(path);
                if (tree != null) {
                    add(snapshots, path, readAt(path, tree), true);
                }
            }
            for (FileRow row : files.values()) {
                if (!current.contains(row.path()) && !git.existsInWorkTree(row.path())) {
                    add(snapshots, row.path(), readBlob(row.path(), row.blob()), inScope(row.path()));
                }
            }
            // Files deleted since an earlier version's tree row: the per-file rows cannot know them.
            for (TreeRow tree : trees.values()) {
                if (!overlapsScope(tree.path())) {
                    continue;
                }
                for (String deleted : git.deletedSince(base(tree.revision()), tree.path())) {
                    if (!current.contains(deleted) && !files.containsKey(deleted)) {
                        add(snapshots, deleted, readAt(deleted, tree), inScope(deleted));
                    }
                }
            }
            return new ArrayList<>(snapshots.values());
        };
    }

    /**
     * The existing files outside this run that the rows say describe the same object as {@code unit} — the
     * sign of a partial deploy.
     */
    List<String> otherFilesOf(DeployObject unit, Set<String> deployedPaths) {
        List<String> others = new ArrayList<>();
        String label = labelOf(unit);
        for (FileRow row : files.values()) {
            if (deployedPaths.contains(row.path()) || inScope(row.path()) || !git.existsInWorkTree(row.path())) {
                continue;
            }
            boolean sameOid = unit.oid() != null && unit.oid().equals(row.oid());
            if (sameOid || label.equals(row.label())) {
                others.add(row.path());
            }
        }
        return others;
    }

    /**
     * Where an object that left this run's files lives now, if the rows know: an existing file whose row
     * describes it. {@code identityKey} is the OID, or {@code Type/Name} for an object without one.
     */
    String movedTo(String identityKey, List<String> fromPaths) {
        for (FileRow row : files.values()) {
            if (fromPaths.contains(row.path()) || !git.existsInWorkTree(row.path())) {
                continue;
            }
            if (identityKey.equals(row.oid()) || identityKey.equals(typeAndName(row.label()))) {
                return row.path();
            }
        }
        return null;
    }

    /**
     * The row changes a fully applied run makes: a row for every file of its objects, rows removed for files
     * that are gone and whose content this run has dealt with ({@code consumed}), and earlier versions' tree
     * rows it now covers completely.
     */
    SyncState.Changes changesAfter(List<DeployObject> units, Set<String> consumed, List<String> notes) {
        SyncState.Changes changes = new SyncState.Changes();
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        List<String> uncommitted = new ArrayList<>();
        for (DeployObject unit : units) {
            for (File file : unit.sourceFiles()) {
                String path = pathOf(file);
                if (path == null) {
                    continue;
                }
                String blob = git.blobAtHead(path);
                if (blob == null) {
                    uncommitted.add(path);
                    continue;
                }
                boolean dirty = git.isModified(path);
                FileRow existing = files.get(path);
                String at = (existing != null && blob.equals(existing.blob()) && dirty == existing.dirty()
                        && existing.at() != null) ? existing.at() : now;
                changes.set(keyOf(path), fileValue(blob, at, dirty, unit.oid()), labelOf(unit));
            }
        }
        if (!uncommitted.isEmpty()) {
            notes.add("not tracking " + uncommitted.size() + " file(s) never committed to git ("
                    + String.join(", ", uncommitted) + "); commit them, or what they applied cannot be recovered "
                    + "later");
        }
        for (FileRow row : files.values()) {
            if (consumed.contains(row.path()) && !git.existsInWorkTree(row.path())) {
                changes.delete(row.key());
            }
        }
        for (TreeRow tree : trees.values()) {
            if (coveredByScope(tree.path())) {
                changes.delete(tree.key());
            }
        }
        return changes;
    }

    /** The {@code deploy:} row's value: when, from which commit, by whom, and what the run did. */
    static String deployValue(String commit, String by, String version, int created, int patched, int inSync,
            int removed, int skipped, int failed) {
        ObjectNode node = JSON.createObjectNode();
        node.put("at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        if (commit != null) {
            node.put("commit", commit);
        }
        if (by != null) {
            node.put("by", by);
        }
        if (version != null) {
            node.put("midgie", version);
        }
        node.put("created", created);
        node.put("patched", patched);
        node.put("in-sync", inSync);
        node.put("removed", removed);
        node.put("skipped", skipped);
        node.put("failed", failed);
        return node.toString();
    }

    static String labelOf(DeployObject unit) {
        return unit.typeName() + " \"" + (unit.name() != null ? unit.name() : "?") + "\"";
    }

    // --- internals ---

    private void add(Map<String, RemovalDetector.Snapshot> snapshots, String path, String content, boolean inScope) {
        if (content != null && !content.isBlank() && !snapshots.containsKey(path)) {
            snapshots.put(path, new RemovalDetector.Snapshot(path, content, inScope));
        }
    }

    private String readBlob(String path, String blob) {
        String content = git.blob(blob);
        if (content == null) {
            unreadable.add(path + " (blob " + GitRevision.abbreviate(blob) + ")");
        }
        return content;
    }

    /** {@code path} as an earlier version's tree row recorded it: the file at that tree's commit, if it existed. */
    private String readAt(String path, TreeRow tree) {
        String revision = base(tree.revision());
        if (!git.hasRevision(revision)) {
            unreadable.add(path + " (revision " + GitRevision.abbreviate(revision) + ")");
            return null;
        }
        return git.fileAt(revision, path); // null: the file did not exist then — nothing was applied
    }

    /** The nearest earlier-version tree row at or above {@code path}. */
    private TreeRow treeAbove(String path) {
        TreeRow best = null;
        for (TreeRow tree : trees.values()) {
            boolean covers = tree.path().isEmpty() || path.equals(tree.path()) || path.startsWith(tree.path() + "/");
            if (covers && (best == null || tree.path().length() > best.path().length())) {
                best = tree;
            }
        }
        return best;
    }

    /** Whether a tree at {@code path} and this run's scope share any file. */
    private boolean overlapsScope(String path) {
        for (String scope : scopes) {
            if (path.isEmpty() || scope.isEmpty() || scope.equals(path) || scope.startsWith(path + "/")
                    || path.startsWith(scope + "/")) {
                return true;
            }
        }
        return false;
    }

    /** Whether this run covers everything under {@code path}, so a tree row there has nothing left to answer. */
    private boolean coveredByScope(String path) {
        return inScope(path);
    }

    private static String base(String revision) {
        return revision.endsWith("-dirty") ? revision.substring(0, revision.length() - "-dirty".length()) : revision;
    }

    private static String fileValue(String blob, String at, boolean dirty, String oid) {
        ObjectNode node = JSON.createObjectNode();
        node.put("blob", blob);
        node.put("at", at);
        if (dirty) {
            node.put("dirty", true);
        }
        if (oid != null && !oid.isBlank()) {
            node.put("oid", oid);
        }
        return node.toString();
    }

    private static FileRow parseFileRow(String path, String key, String value, String label) {
        try {
            JsonNode node = JSON.readTree(value);
            String blob = node.path("blob").asText(null);
            if (blob == null) {
                return null;
            }
            return new FileRow(path, key, blob, node.path("dirty").asBoolean(false), node.path("at").asText(null),
                    node.path("oid").asText(null), label);
        } catch (Exception e) {
            LOG.debug("ignoring {} row '{}': its value is not the JSON midgie writes", SyncState.TABLE_NAME, key);
            return null;
        }
    }

    /** {@code RoleType "Manager"} → {@code RoleType/Manager}, the form an OID-less object is identified by. */
    private static String typeAndName(String label) {
        if (label == null) {
            return null;
        }
        int space = label.indexOf(' ');
        if (space < 0 || !label.endsWith("\"") || label.charAt(space + 1) != '"') {
            return null;
        }
        return label.substring(0, space) + "/" + label.substring(space + 2, label.length() - 1);
    }
}
