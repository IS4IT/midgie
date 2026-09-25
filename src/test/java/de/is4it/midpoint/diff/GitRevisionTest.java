/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the git plumbing behind delete detection. Nothing here may be load-bearing: a directory that is not a
 * repository has to degrade quietly, because the whole feature is optional.
 */
class GitRevisionTest {

    @Test
    void aPlainDirectoryIsNotARepositoryAndNothingThrows(@TempDir Path dir) {
        GitRevision git = GitRevision.of(dir.toFile());

        assertFalse(git.isRepository(), "a temp directory is not a git work tree");
        assertNull(git.head(), "no HEAD outside a repository");
        assertNull(git.rootCommit(), "no root commit outside a repository");
        assertNull(git.fileAt("HEAD", "whatever.xml"), "no content outside a repository");
        assertTrue(git.deletedSince("HEAD").isEmpty(), "no deletions outside a repository");
        assertNull(git.repoRelative(dir.resolve("x.xml").toFile()), "no repo-relative path outside a repository");
    }

    @Test
    void identityIsUnavailableOutsideARepositoryWithAReason(@TempDir Path dir) {
        DeploymentIdentity identity = DeploymentIdentity.of(dir.toFile(), null);

        assertFalse(identity.isAvailable(), "a non-repository cannot be identified");
        assertNotNull(identity.unavailableReason(), "the user must be told why");
        assertTrue(identity.unavailableReason().contains("git"), identity.unavailableReason());
    }

    @Test
    void anExplicitAppNameOverridesTheDerivationButStillNeedsHistory(@TempDir Path dir) throws Exception {
        // --app replaces the derived *key*, not the requirement for history: recovering last-applied still
        // reads previous content out of git. Claiming tracking is available without a repository would make
        // the run report no removals and look identical to a healthy one.
        DeploymentIdentity outside = DeploymentIdentity.of(dir.toFile(), "my-app");
        assertFalse(outside.isAvailable(), "--app cannot manufacture history that is not there");
        assertNotNull(outside.unavailableReason(), "and the user must be told why");

        Files.writeString(dir.resolve("a.xml"), "<x/>", StandardCharsets.UTF_8);
        Assumptions.assumeTrue(initRepository(dir), "git is not usable here");
        DeploymentIdentity inside = DeploymentIdentity.of(dir.toFile(), "my-app");
        assertTrue(inside.isAvailable(), "inside a real repository the override applies");
        assertEquals("my-app", inside.key(), "and it replaces the root-commit derivation");
    }

    @Test
    void aSingleFileHasAnIdentityOfItsOwnNotItsDirectorys(@TempDir Path dir) throws Exception {
        // Deploying one file is not deploying its directory, so it must never be keyed as the directory — but it
        // is a deployment of its own, keyed by its own path, the way a subfolder is.
        Files.writeString(dir.resolve("role.xml"), "<x/>", StandardCharsets.UTF_8);
        Assumptions.assumeTrue(initRepository(dir), "git is not usable here");

        DeploymentIdentity file = DeploymentIdentity.of(dir.resolve("role.xml").toFile(), null);
        DeploymentIdentity tree = DeploymentIdentity.of(dir.toFile(), null);
        assertTrue(file.isAvailable(), file.unavailableReason());
        assertTrue(file.key().endsWith("/role.xml"), file.key());
        assertFalse(file.key().equals(tree.key()), "never the directory's key: " + file.key());
        assertEquals(tree.namespace(), file.namespace(), "but the same repository's rows");
        assertEquals("my-app", DeploymentIdentity.of(dir.resolve("role.xml").toFile(), "my-app").key(),
                "and --app can name such a deployment deliberately");
    }

    @Test
    void identityIsDerivedFromTheRootCommitAndTreePath(@TempDir Path dir) throws Exception {
        // The derivation must be root-commit based, so every clone and remote agrees on it. Build a tiny
        // repository with a sub-tree and check the key is <root>/<prefix> and stable across calls.
        Path tree = Files.createDirectories(dir.resolve("config"));
        Files.writeString(tree.resolve("a.xml"), "<x/>", StandardCharsets.UTF_8);
        Assumptions.assumeTrue(initRepository(dir), "git is not usable here");

        GitRevision git = GitRevision.of(dir.toFile());
        String root = git.rootCommit();
        assertNotNull(root, "a committed repository has a root commit");
        assertFalse(git.isShallow(), "a freshly created repository is not shallow");

        DeploymentIdentity identity = DeploymentIdentity.of(tree.toFile(), null);
        assertTrue(identity.isAvailable(), identity.unavailableReason());
        assertTrue(root.startsWith(identity.key().substring(0, identity.key().indexOf('/'))),
                "the key starts with the root commit: " + identity.key() + " vs " + root);
        assertTrue(identity.key().endsWith("/config"), "and carries the tree's path: " + identity.key());
        assertEquals(identity.key(), DeploymentIdentity.of(tree.toFile(), null).key(), "must be stable");

        // A sibling tree in the same repository is a different deployment.
        Path other = Files.createDirectories(dir.resolve("other"));
        assertFalse(identity.key().equals(DeploymentIdentity.of(other.toFile(), null).key()),
                "sub-trees must not share an identity");
    }

    @Test
    void previousContentAndDeletionsAreRecoverable(@TempDir Path dir) throws Exception {
        // The heart of delete detection: what a file said at an earlier revision, and which files went away.
        Files.writeString(dir.resolve("keep.xml"), "<keep/>", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("gone.xml"), "<gone/>", StandardCharsets.UTF_8);
        Assumptions.assumeTrue(initRepository(dir), "git is not usable here");
        GitRevision git = GitRevision.of(dir.toFile());
        String first = git.head();

        assertEquals("<gone/>", git.fileAt(first, "gone.xml"), "content at a revision is recoverable");
        Files.delete(dir.resolve("gone.xml"));
        run(dir, "git", "add", "-A");
        run(dir, "git", "commit", "-qm", "drop it");

        assertTrue(git.deletedSince(first).contains("gone.xml"), "the deleted file is reported");
        assertFalse(git.deletedSince(first).contains("keep.xml"), "the surviving file is not");
        assertEquals("<gone/>", git.fileAt(first, "gone.xml"), "and its old content is still readable");
    }

    @Test
    void aRecordedRevisionIsVerifiedAndCanNeverActAsAGitOption(@TempDir Path dir) throws Exception {
        // The revision is read back from the midgie-state object on the server, so it is not trusted input.
        // git treats a leading '-' as an option, and options like --output=<file> write files as the
        // deploying user; requiring a bare sha means a planted row can only ever be rejected.
        Files.writeString(dir.resolve("a.xml"), "<x/>", StandardCharsets.UTF_8);
        Assumptions.assumeTrue(initRepository(dir), "git is not usable here");
        GitRevision git = GitRevision.of(dir.toFile());
        Path planted = dir.resolve("pwned.txt");

        assertTrue(git.deletedSince("--output=" + planted).isEmpty(), "an option-shaped revision is refused");
        assertNull(git.fileAt("--output=" + planted, "a.xml"), "and cannot read a file either");
        assertFalse(Files.exists(planted), "git must never have been handed it");

        // And an unreachable revision is distinguishable from a reachable one, so "cannot recover
        // last-applied" is never mistaken for "nothing was removed".
        assertFalse(git.hasRevision("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"), "an unknown commit");
        assertFalse(git.hasRevision("--all"), "and a non-sha is not a revision at all");
        assertTrue(git.hasRevision(git.head()), "while the real HEAD is recognised");
    }

    /** Create a repository with one commit. Returns false when git is not usable here. */
    private static boolean initRepository(Path dir) throws Exception {
        if (run(dir, "git", "init", "-q", ".") != 0) {
            return false;
        }
        run(dir, "git", "config", "user.email", "test@example.invalid");
        run(dir, "git", "config", "user.name", "midgie test");
        run(dir, "git", "add", "-A");
        return run(dir, "git", "commit", "-qm", "initial") == 0;
    }

    /**
     * Run a command in {@code dir}, bounded and without orphaning the child — the production code guards
     * both (see {@code GitRevision.run}). An unbounded waitFor hangs the whole build until CI's wall clock
     * gives up, and the exception path used to leave the process running.
     */
    private static int run(Path dir, String... command) throws Exception {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            return process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) ? process.exitValue() : -1;
        } catch (java.io.IOException e) {
            return -1; // no git on this machine
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void repoRelativePathsAreResolvedForFilesInTheTree(@TempDir Path dir) throws Exception {
        Path nested = Files.createDirectories(dir.resolve("a/b"));
        File file = nested.resolve("c.xml").toFile();
        Files.writeString(file.toPath(), "<x/>", StandardCharsets.UTF_8);
        Assumptions.assumeTrue(initRepository(dir), "git is not usable here");
        assertEquals("a/b/c.xml", GitRevision.of(dir.toFile()).repoRelative(file));
    }
}
