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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    /** How many characters of a sha to show in messages — git's own short form. */
    private static final int SHORT_SHA_LENGTH = 7;

    /** Daemon threads that drain a subprocess's pipes, so a chatty git can never deadlock the caller. */
    private static final ExecutorService DRAIN = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "midgie-git-drain");
        thread.setDaemon(true);
        return thread;
    });

    private final File tree;

    // Immutable for the life of this object, and asked for once per fragment otherwise: repoRelative() alone
    // would fork `rev-parse --show-toplevel` for every file in the tree. Cached, since a deploy is expected
    // to cost nothing when nothing changed.
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private static final String ABSENT = "\u0000absent";

    private GitRevision(File tree) {
        this.tree = tree;
    }

    /** Run {@code args} at most once per {@link GitRevision}, remembering even a null result. */
    private String cached(String key, String... args) {
        return cached(key, false, args);
    }

    private String cached(String key, boolean expectedToFail, String... args) {
        String value = cache.computeIfAbsent(key, ignored -> {
            String result = run(expectedToFail, args);
            return (result != null) ? result : ABSENT;
        });
        return ABSENT.equals(value) ? null : value;
    }

    static GitRevision of(File tree) {
        return new GitRevision(tree);
    }

    /** True when {@code tree} is inside a git working tree and git is usable. */
    boolean isRepository() {
        return "true".equals(cached("inside-work-tree", "rev-parse", "--is-inside-work-tree"));
    }

    /**
     * True for a shallow clone, where the history needed to recover a previous revision is missing and the
     * apparent root commit is only the grafted boundary.
     */
    boolean isShallow() {
        return "true".equals(cached("shallow", "rev-parse", "--is-shallow-repository"));
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
     * The repository's root commit — its stable identity across clones and remotes.
     *
     * <p>Followed along <b>first parents</b> only. Taking the lexicographically first of all reachable roots
     * would change identity the moment an unrelated history is merged in (a {@code git subtree add}, a
     * vendored template): the state row would silently start over from zero and the old row would linger.
     * The first-parent root is the line the branch actually descends from, and a merge cannot move it.
     */
    String rootCommit() {
        String out = cached("roots", "rev-list", "--first-parent", "--max-parents=0", "HEAD");
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
        String prefix = cached("prefix", "rev-parse", "--show-prefix");
        return (prefix != null) ? prefix : "";
    }

    /** A human hint for the state row's label — the repository directory name plus the tree prefix. */
    String label() {
        String top = cached("toplevel", "rev-parse", "--show-toplevel");
        String repo = (top != null && !top.isBlank()) ? new File(top).getName() : "?";
        String prefix = prefix();
        return prefix.isBlank() ? repo : repo + "/" + trimTrailingSlash(prefix);
    }

    /**
     * {@code revision} in git's short form for display, keeping a {@code -dirty} marker. Only for messages:
     * what is recorded and compared stays the full sha.
     */
    static String abbreviate(String revision) {
        if (revision == null) {
            return null;
        }
        String dirty = revision.endsWith("-dirty") ? "-dirty" : "";
        String sha = revision.substring(0, revision.length() - dirty.length());
        return (sha.length() > SHORT_SHA_LENGTH ? sha.substring(0, SHORT_SHA_LENGTH) : sha) + dirty;
    }

    /**
     * Whether {@code revision} is a commit this repository actually has.
     *
     * <p>Worth asking before comparing against it: a squash-merge, rebase, force-push or {@code gc} can make
     * the recorded revision unreachable, and every {@code git show} against it then fails the same way a file
     * that simply did not exist yet does. Without this check the two are indistinguishable, and "last-applied
     * cannot be recovered" silently reads as "nothing was removed".
     */
    boolean hasRevision(String revision) {
        return isCommitish(revision)
                && cached("verify-" + revision, true, "rev-parse", "--verify", "--quiet",
                        revision + "^{commit}") != null;
    }

    /**
     * Whether {@code revision} is safe to hand to git as a revision argument.
     *
     * <p>The revision comes off the network — it is read from the {@code midgie-state} object on the server —
     * so it is not trusted input. git takes anything starting with {@code -} as an option, and options such
     * as {@code --output=<file>} write files; requiring a bare commit sha means the value can never be
     * anything but a revision.
     */
    private static boolean isCommitish(String revision) {
        return revision != null && revision.matches("[0-9a-fA-F]{7,40}");
    }

    /**
     * The content of {@code repoRelativePath} as of {@code revision}, or {@code null} when the file did not
     * exist then (a file added since) or the revision is unreachable.
     */
    String fileAt(String revision, String repoRelativePath) {
        if (!isCommitish(revision)) {
            LOG.trace("refusing to read a file at '{}': not a commit sha", revision);
            return null;
        }
        return run(true, "show", revision + ":" + repoRelativePath);
    }

    /**
     * Repository-relative paths deleted between {@code revision} and the working tree, restricted to this
     * tree. Renames are followed ({@code -M}) so a moved file is not mistaken for a deletion.
     *
     * <p>The pathspec is {@code .} because git resolves pathspecs against the process's working directory,
     * which here is the tree itself; the output is still repository-relative, which is what
     * {@code git show <rev>:<path>} needs.
     *
     * <p>The output format is pinned rather than taken as it comes: {@code core.quotePath=false} keeps a
     * non-ASCII filename from arriving C-quoted, {@code diff.relative=false} overrides a user's global
     * setting that would make the paths cwd-relative, and {@code -z} separates them with NUL so no filename
     * can be split or trimmed. Each of those would otherwise make the follow-up {@code git show} fail and
     * that fragment's removals go silently missing.
     */
    List<String> deletedSince(String revision) {
        List<String> paths = new ArrayList<>();
        if (!isCommitish(revision)) {
            LOG.trace("refusing to diff against '{}': not a commit sha", revision);
            return paths;
        }
        String out = run("-c", "core.quotePath=false", "-c", "diff.relative=false",
                "diff", "--diff-filter=D", "--name-only", "-M", "-z", revision, "--", ".");
        if (out != null) {
            for (String path : out.split("\0")) {
                if (!path.isBlank()) {
                    paths.add(path);
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
        String top = cached("toplevel", "rev-parse", "--show-toplevel");
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
        return run(false, args);
    }

    /**
     * @param expectedToFail whether a non-zero exit is an answer rather than a fault — {@code git show
     *     <rev>:<path>} is how this class asks "did this file exist then?", and git says no by exiting 128
     *     with a message beginning "fatal:". Logging that at {@code --debug} put a line reading like a crash
     *     next to every file in the tree; the reader following a deploy does not need it, and the reader
     *     debugging one still gets it at {@code --trace}.
     */
    private String run(boolean expectedToFail, String... args) {
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
            if (process.exitValue() != 0) {
                // Several callers degrade to "detection off" on a null result, so without git's own reason
                // an unreachable revision or a misconfigured identity cannot be diagnosed at any log level.
                String reason = drained(stderr);
                if (expectedToFail) {
                    LOG.trace("git {} exited {}: {}", String.join(" ", args), process.exitValue(), reason);
                } else {
                    LOG.debug("git {} exited {}: {}", String.join(" ", args), process.exitValue(), reason);
                }
                return null;
            }
            stderr.cancel(true);
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

    /** git's own error text, for the debug log — never worth failing over if it cannot be read. */
    private static String drained(Future<String> stderr) {
        try {
            return stderr.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).strip();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
