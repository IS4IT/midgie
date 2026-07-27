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
    void anExplicitAppNameOverridesTheDerivation(@TempDir Path dir) {
        DeploymentIdentity identity = DeploymentIdentity.of(dir.toFile(), "my-app");

        assertTrue(identity.isAvailable(), "--app works even where git cannot identify the tree");
        assertEquals("my-app", identity.key());
    }

    @Test
    void identityIsDerivedFromTheRootCommitAndTreePath(@TempDir Path dir) throws Exception {
        // The derivation must be root-commit based, so every clone and remote agrees on it. Build a tiny
        // repository with a sub-tree and check the key is <root>/<prefix> and stable across calls.
        Path tree = Files.createDirectories(dir.resolve("config"));
        Files.writeString(tree.resolve("a.xml"), "<x/>", StandardCharsets.UTF_8);
        if (!initRepository(dir)) {
            return; // git unavailable in this environment: the degradation cases above already cover us
        }

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
        if (!initRepository(dir)) {
            return;
        }
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

    private static int run(Path dir, String... command) throws Exception {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            return process.waitFor();
        } catch (java.io.IOException e) {
            return -1; // no git on this machine
        }
    }

    @Test
    void repoRelativePathsAreResolvedForFilesInTheTree(@TempDir Path dir) throws Exception {
        Path nested = Files.createDirectories(dir.resolve("a/b"));
        File file = nested.resolve("c.xml").toFile();
        Files.writeString(file.toPath(), "<x/>", StandardCharsets.UTF_8);
        if (!initRepository(dir)) {
            return;
        }
        assertEquals("a/b/c.xml", GitRevision.of(dir.toFile()).repoRelative(file));
    }
}
