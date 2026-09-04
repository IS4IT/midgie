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
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * How midgie reports a path it cannot read or write. The file system throws these with the bare path as
 * their whole message — {@code AccessDeniedException.getMessage()} is {@code "/work"} — and midgie prints
 * an exception's message verbatim, so the container case produced exactly {@code "midgie: /work"}.
 */
class UnreadablePathTest {

    private static PrismContext prismContext;

    @BeforeAll
    static void bootstrap() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @Test
    void anUnreadableDirectoryNamesItselfAndBothSidesOfTheMismatch(@TempDir Path dir) throws Exception {
        Path locked = Files.createDirectory(dir.resolve("locked"));
        Files.writeString(dir.resolve("role.xml"), role(), StandardCharsets.UTF_8);
        withoutPermissions(locked, () -> {
            // Named from inside the walk, so the message points at the subdirectory that failed rather than
            // at the root that was given — the root is readable and reporting it would send you to the wrong
            // place. The walk fails before anything is parsed, so no prism context is needed here.
            IOException e = assertThrows(IOException.class,
                    () -> Discovery.scan(null, List.of(dir.toFile())));

            assertTrue(e.getMessage().contains(locked.toString()), "names the failing path: " + e.getMessage());
            assertTrue(e.getMessage().startsWith("cannot read "), e.getMessage());
            assertTrue(e.getMessage().contains("permission denied"), e.getMessage());
            assertTrue(e.getMessage().contains("midgie runs as "), "says who midgie is: " + e.getMessage());
            assertTrue(e.getMessage().contains("is owned by "), "and who owns it: " + e.getMessage());
            assertTrue(e.getMessage().contains("mode 0000"), "with the mode that denied it: " + e.getMessage());
        });
    }

    @Test
    void anUnreadableDirectoryFailsTheRunRatherThanBeingSkipped(@TempDir Path dir) throws Exception {
        Path locked = Files.createDirectory(dir.resolve("locked"));
        Files.writeString(dir.resolve("role.xml"), role(), StandardCharsets.UTF_8);
        withoutPermissions(locked, () -> {
            // A readable sibling is present: deploying it and exiting 0 would report success over a tree
            // midgie only partly saw, and --prune would read the unseen objects as removals.
            assertThrows(IOException.class, () -> Discovery.scan(null, List.of(dir.toFile())));
        });
    }

    @Test
    void anUnreadableObjectFileReportsTheSameWay(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("role.xml"), role(), StandardCharsets.UTF_8);
        withoutPermissions(file, () -> {
            IOException e = assertThrows(IOException.class, () -> Io.parseObject(prismContext, file.toFile()));

            assertTrue(e.getMessage().startsWith("cannot read " + file), e.getMessage());
            assertTrue(e.getMessage().contains("permission denied"), e.getMessage());
        });
    }

    @Test
    void aWriteToAMissingDirectoryStillSaysWhy(@TempDir Path dir) {
        File target = dir.resolve("nope").resolve("out.xml").toFile();

        IOException e = assertThrows(IOException.class, () -> Io.writePayload(target, "<x/>"));

        assertTrue(e.getMessage().startsWith("cannot write " + target), e.getMessage());
        assertTrue(e.getMessage().contains("no such file or directory"), e.getMessage());
    }

    /**
     * Run {@code body} with every permission bit stripped from {@code path}, restoring them afterwards so
     * the temporary directory can still be cleaned up.
     *
     * <p>Skips the test where the denial cannot be staged at all: a non-POSIX file system, or a process
     * running as root, for which mode 0000 is no obstacle.
     */
    private static void withoutPermissions(Path path, ThrowingRunnable body) throws Exception {
        assumeTrue(path.getFileSystem().supportedFileAttributeViews().contains("posix"), "POSIX file system");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("---------"));
        try {
            assumeTrue(!Files.isReadable(path), "not running as root");
            body.run();
        } finally {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static String role() {
        return "<role xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\">"
                + "<name>Example Role</name></role>";
    }
}
