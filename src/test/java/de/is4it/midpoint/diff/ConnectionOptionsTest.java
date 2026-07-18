/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline tests for password resolution and host handling (the parts that don't need env vars or a TTY). */
class ConnectionOptionsTest {

    @Test
    void explicitPasswordIsUsed() {
        ConnectionOptions options = new ConnectionOptions();
        options.passwordOption = "sekret";
        assertEquals("sekret", options.resolvePassword());
    }

    @Test
    void passwordFileIsReadWithTrailingNewlineTrimmed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("pw");
        Files.writeString(file, "file-password\n");
        ConnectionOptions options = new ConnectionOptions();
        options.passwordFile = file.toFile();
        assertEquals("file-password", options.resolvePassword());
    }

    @Test
    void emptyPasswordFileIsAnError(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("pw");
        Files.writeString(file, "\n");
        ConnectionOptions options = new ConnectionOptions();
        options.passwordFile = file.toFile();
        assertNull(options.resolvePassword());
    }

    @Test
    void missingPasswordFileIsAnError() {
        ConnectionOptions options = new ConnectionOptions();
        options.passwordFile = new java.io.File("/no/such/password/file");
        assertNull(options.resolvePassword());
    }

    @Test
    void explicitHostIsLiveAndFlagged() {
        ConnectionOptions options = new ConnectionOptions();
        assertFalse(options.isExplicitHost());

        options.hostOption = URI.create("http://localhost:12080/midpoint");
        assertTrue(options.isExplicitHost());
        assertTrue(options.isLive());
        assertEquals(options.hostOption, options.host());
    }
}
