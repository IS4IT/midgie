/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How one deployed tree is identified in midgie's state, so a later run can find what it applied last time.
 *
 * <p>The key is the repository's <b>root commit</b> plus the tree's path within the repository, e.g.
 * {@code df7f507/midgie}. The root commit is content-addressed and part of the history, so every clone
 * derives the same key no matter which remotes it has — the case a remote URL cannot handle, since the same
 * repository is often pushed to both a customer's internal git and one's own. The path suffix keeps
 * sub-trees of one repository distinct.
 *
 * <p>Deriving it needs a real repository with history. It is unavailable — and delete detection therefore
 * off — when the tree is not in a repository, when git is not usable, or when the clone is <b>shallow</b>:
 * there {@code rev-list --max-parents=0} reports the grafted boundary rather than the true root, so a
 * {@code --depth 1} CI checkout would silently derive a different key than a full clone. {@code --app}
 * overrides all of this for setups that need a fixed name.
 */
final class DeploymentIdentity {

    private static final Logger LOG = LoggerFactory.getLogger(DeploymentIdentity.class);

    /** Length of the root-commit prefix used in the key — long enough to be unambiguous, short enough to read. */
    private static final int SHA_LENGTH = 12;

    private final String key;
    private final String label;
    private final String unavailableReason;
    private final GitRevision git;

    private DeploymentIdentity(String key, String label, String unavailableReason, GitRevision git) {
        this.key = key;
        this.label = label;
        this.unavailableReason = unavailableReason;
        this.git = git;
    }

    /**
     * Resolve the identity of {@code tree}. {@code appOverride} (from {@code --app}) wins when set; otherwise
     * the key is derived from git. Never throws — an underivable identity is a normal outcome.
     */
    static DeploymentIdentity of(File tree, String appOverride) {
        File dir = (tree != null && tree.isFile()) ? tree.getParentFile() : tree;
        if (dir == null) {
            dir = new File(".");
        }
        GitRevision git = GitRevision.of(dir);

        // History is checked before the override, not after. --app replaces the *key*, but recovering
        // last-applied still reads the previous revision out of git, so without usable history a named
        // deployment would report tracking as available and then quietly detect nothing at all.
        if (!git.isRepository()) {
            return unavailable("the tree is not in a git repository (or git is not available)", git);
        }
        if (git.isShallow()) {
            return unavailable("this is a shallow clone, whose history cannot identify the repository "
                    + "or recover previous content — fetch the full history (e.g. fetch-depth: 0)", git);
        }
        if (appOverride != null && !appOverride.isBlank()) {
            return new DeploymentIdentity(appOverride.trim(), appOverride.trim(), null, git);
        }
        String root = git.rootCommit();
        if (root == null) {
            return unavailable("the repository's root commit could not be determined", git);
        }
        String prefix = trimTrailingSlash(git.prefix());
        String key = shorten(root) + (prefix.isBlank() ? "" : "/" + prefix);
        LOG.debug("deployment identity for {}: {}", dir, key);
        return new DeploymentIdentity(key, git.label(), null, git);
    }

    private static DeploymentIdentity unavailable(String reason, GitRevision git) {
        LOG.debug("deployment identity unavailable: {}", reason);
        return new DeploymentIdentity(null, null, reason, git);
    }

    boolean isAvailable() {
        return key != null;
    }

    String key() {
        return key;
    }

    String label() {
        return label;
    }

    /** Why the identity could not be derived — safe to show the user. */
    String unavailableReason() {
        return unavailableReason;
    }

    /** Git access for the same tree, for recovering last-applied content. */
    GitRevision git() {
        return git;
    }

    private static String shorten(String sha) {
        return (sha.length() > SHA_LENGTH) ? sha.substring(0, SHA_LENGTH) : sha;
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
