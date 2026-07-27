/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort git facts about a desired-state tree, read by shelling out to {@code git}. Used to identify a
 * deployment and to recover what was deployed last time.
 *
 * <p>Nothing here is load-bearing: no git on the PATH, a directory that is not a repository, or a shallow
 * clone all resolve to "unavailable" rather than an error, and the caller simply runs without delete
 * detection. Every method returns {@code null} / an empty list instead of throwing.
 *
 * <p>The deployment identity is the <b>root commit</b> plus the tree's path inside the repository. The root
 * commit is content-addressed and part of the history, so every clone computes the same value regardless of
 * which remotes it has — unlike a remote URL, which differs when the same repository is pushed to, say, both
 * an internal customer git and ours. A <b>shallow</b> clone is the one case that must be rejected: there
 * {@code rev-list --max-parents=0} reports the grafted boundary rather than the true root, so a
 * {@code --depth 1} CI checkout would derive a different identity than a full one.
 */
final class GitRevision {

    private static final Logger LOG = LoggerFactory.getLogger(GitRevision.class);

    /** How long any single git invocation may take before it is abandoned. */
    private static final int TIMEOUT_SECONDS = 15;

    /** Daemon threads that drain a subprocess's pipes, so a chatty git can never deadlock the caller. */
    private static final ExecutorService DRAIN = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "midgie-git-drain");
        thread.setDaemon(true);
        return thread;
    });

    private final File tree;

    private GitRevision(File tree) {
        this.tree = tree;
    }

    static GitRevision of(File tree) {
        return new GitRevision(tree);
    }

    /** True when {@code tree} is inside a git working tree and git is usable. */
    boolean isRepository() {
        return "true".equals(run("rev-parse", "--is-inside-work-tree"));
    }

    /**
     * True for a shallow clone, where the history needed to recover a previous revision is missing and the
     * apparent root commit is only the grafted boundary.
     */
    boolean isShallow() {
        return "true".equals(run("rev-parse", "--is-shallow-repository"));
    }

    /** The commit currently checked out, or {@code null}. */
    String head() {
        return run("rev-parse", "HEAD");
    }

    /**
     * True when <em>this tree</em> has uncommitted changes, so HEAD does not describe what is deployed. The
     * pathspec matters in a monorepo: an unrelated edit elsewhere in the repository says nothing about the
     * configuration being deployed, and warning about it on every run only teaches people to ignore warnings.
     */
    boolean isDirty() {
        String status = run("status", "--porcelain", "--", ".");
        return status != null && !status.isBlank();
    }

    /**
     * The repository's root commit — its stable identity across clones and remotes. When a repository has
     * several roots (orphan branches, unrelated histories merged in) the reachable ones are sorted and the
     * first is taken, so the result stays deterministic.
     */
    String rootCommit() {
        String out = run("rev-list", "--max-parents=0", "HEAD");
        if (out == null || out.isBlank()) {
            return null;
        }
        List<String> roots = new ArrayList<>(Arrays.asList(out.split("\\R")));
        roots.removeIf(String::isBlank);
        if (roots.isEmpty()) {
            return null;
        }
        roots.sort(String::compareTo);
        if (roots.size() > 1) {
            LOG.debug("repository has {} root commits; using the first of {}", roots.size(), roots);
        }
        return roots.get(0);
    }

    /** The tree's path relative to the repository root ({@code "midgie/"}), or {@code ""} at the root. */
    String prefix() {
        String prefix = run("rev-parse", "--show-prefix");
        return (prefix != null) ? prefix : "";
    }

    /** A human hint for the state row's label — the repository directory name plus the tree prefix. */
    String label() {
        String top = run("rev-parse", "--show-toplevel");
        String repo = (top != null && !top.isBlank()) ? new File(top).getName() : "?";
        String prefix = prefix();
        return prefix.isBlank() ? repo : repo + "/" + trimTrailingSlash(prefix);
    }

    /**
     * The content of {@code repoRelativePath} as of {@code revision}, or {@code null} when the file did not
     * exist then (a file added since) or the revision is unreachable.
     */
    String fileAt(String revision, String repoRelativePath) {
        return run("show", revision + ":" + repoRelativePath);
    }

    /**
     * Repository-relative paths deleted between {@code revision} and the working tree, restricted to this
     * tree. Renames are followed ({@code -M}) so a moved file is not mistaken for a deletion.
     *
     * <p>The pathspec is {@code .} because git resolves pathspecs against the process's working directory,
     * which here is the tree itself; the output is still repository-relative, which is what
     * {@code git show <rev>:<path>} needs.
     */
    List<String> deletedSince(String revision) {
        String out = run("diff", "--diff-filter=D", "--name-only", "-M", revision, "--", ".");
        List<String> paths = new ArrayList<>();
        if (out != null) {
            for (String line : out.split("\\R")) {
                if (!line.isBlank()) {
                    paths.add(line.trim());
                }
            }
        }
        return paths;
    }

    /**
     * The repository-relative path of {@code file}, or {@code null} when it is outside the tree.
     *
     * <p>Both sides are canonicalised first: git reports the work-tree root with symlinks resolved, so on a
     * machine where the repository sits under a symlinked path (on macOS {@code /tmp} and {@code /var} are
     * exactly that) a plain absolute path would never match the root and every file would look external.
     */
    String repoRelative(File file) {
        String top = run("rev-parse", "--show-toplevel");
        if (top == null || top.isBlank()) {
            return null;
        }
        String root = canonical(new File(top));
        String path = canonical(file);
        if (root == null || path == null || !path.startsWith(root + File.separator)) {
            return null;
        }
        return path.substring(root.length() + 1).replace(File.separatorChar, '/');
    }

    private static String canonical(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    /**
     * Run a git command in the tree and return its trimmed stdout, or {@code null} on any failure — a
     * non-zero exit (which is normal, e.g. asking for a file that did not exist at a revision), git not being
     * installed, or a timeout.
     */
    private String run(String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));
        Process started = null;
        try {
            final Process process = new ProcessBuilder(command)
                    .directory(tree)
                    .redirectErrorStream(false)
                    .start();
            started = process;
            process.getOutputStream().close();
            // Drain both pipes on separate threads. Reading stdout inline would block before waitFor ever
            // ran — making the timeout unreachable — and would deadlock whenever git filled the stderr pipe
            // (rename or filter warnings) while waiting for us to read it.
            Future<String> stdout = DRAIN.submit(() ->
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            Future<String> stderr = DRAIN.submit(() ->
                    new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                stdout.cancel(true);
                stderr.cancel(true);
                LOG.debug("git {} timed out after {}s", String.join(" ", args), TIMEOUT_SECONDS);
                return null;
            }
            String out = stdout.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            stderr.cancel(true);
            if (process.exitValue() != 0) {
                LOG.debug("git {} exited {}", String.join(" ", args), process.exitValue());
                return null;
            }
            return out.strip();
        } catch (IOException | java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException e) {
            LOG.debug("git {} could not run ({})", String.join(" ", args), e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (started != null && started.isAlive()) {
                started.destroyForcibly();
            }
        }
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
