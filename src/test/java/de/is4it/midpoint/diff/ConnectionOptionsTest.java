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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void proxyPasswordIsReadFromItsFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("proxy-pw");
        Files.writeString(file, "tunnel-password\n");
        ConnectionOptions options = new ConnectionOptions();
        options.urlOption = URI.create("https://mp.example.com/midpoint");
        options.proxyOption = "http://proxy.corp:3128";
        options.proxyUserOption = "alice";
        options.proxyPasswordFile = file.toFile();

        ProxyConfig proxy = options.proxy();

        assertEquals("alice", proxy.user());
        assertEquals("tunnel-password", proxy.password());
    }

    @Test
    void aProxyUserWithNoProxyIsRefused() {
        ConnectionOptions options = new ConnectionOptions();
        options.urlOption = URI.create("https://mp.example.com/midpoint");
        options.proxyUserOption = "alice";

        // Not a warning: the run would otherwise go direct, silently ignoring credentials that were asked for.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, options::proxy);
        assertTrue(error.getMessage().contains("--proxy"), error.getMessage());
    }

    @Test
    void aProxyPasswordWithNoUserIsRefused(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("proxy-pw");
        Files.writeString(file, "orphan\n");
        ConnectionOptions options = new ConnectionOptions();
        options.urlOption = URI.create("https://mp.example.com/midpoint");
        options.proxyOption = "http://proxy.corp:3128";
        options.proxyPasswordFile = file.toFile();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, options::proxy);
        assertTrue(error.getMessage().contains("--proxy-user"), error.getMessage());
    }

    @Test
    void aProxyUserWithNoPasswordAnywhereIsRefused() {
        ConnectionOptions options = new ConnectionOptions();
        options.urlOption = URI.create("https://mp.example.com/midpoint");
        options.proxyOption = "http://proxy.corp:3128";
        options.proxyUserOption = "alice";

        // Under surefire there is no console to prompt at, which is also the CI and container case.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, options::proxy);
        assertTrue(error.getMessage().contains("--proxy-password-file"), error.getMessage());
    }

    @Test
    void noProxySettingsMeansNoProxy() {
        ConnectionOptions options = new ConnectionOptions();
        options.urlOption = URI.create("https://mp.example.com/midpoint");

        assertNull(options.proxy(), "the JDK's own -Dhttps.proxyHost route has to stay untouched");
    }

    @Test
    void explicitHostIsLiveAndFlagged() {
        ConnectionOptions options = new ConnectionOptions();
        assertFalse(options.isExplicitUrl());

        options.urlOption = URI.create("http://localhost:12080/midpoint");
        assertTrue(options.isExplicitUrl());
        assertTrue(options.isLive());
        assertEquals(options.urlOption, options.url());
    }
}
