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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline tests for the {@code glob = flags} overrides file. */
class OverridesTest {

    @Test
    void firstMatchingGlobWinsAndUnmatchedFallsToGlobal(@TempDir Path dir) throws IOException {
        File file = write(dir, "roles.txt",
                "# per-path strategy\n"
                        + "**/roles/*.xml = --scope full\n"
                        + "**/*.xml       = --scope present\n");
        Overrides overrides = Overrides.load(file, global());

        assertEquals(DiffEngine.Scope.FULL,
                overrides.strategyFor(new File("cfg/roles/manager.xml")).scope, "roles rule matches first");
        assertEquals(DiffEngine.Scope.PRESENT,
                overrides.strategyFor(new File("cfg/users/jsmith.xml")).scope, "falls through to the '**/*.xml' rule");
    }

    @Test
    void noMatchUsesTheGlobalStrategy(@TempDir Path dir) throws IOException {
        File file = write(dir, "roles.txt", "**/roles/*.xml = --scope full\n");
        Overrides overrides = Overrides.load(file, global());

        StrategyOptions.Resolved forUser = overrides.strategyFor(new File("cfg/users/jsmith.xml"));
        assertEquals(DiffEngine.Scope.PRESENT, forUser.scope, "no rule matches -> the global default");
    }

    @Test
    void blankLinesAndCommentsAreIgnored(@TempDir Path dir) throws IOException {
        File file = write(dir, "roles.txt", "\n# just a comment\n\n   \n");
        Overrides overrides = Overrides.load(file, global());
        assertEquals(DiffEngine.Scope.PRESENT, overrides.strategyFor(new File("anything.xml")).scope);
    }

    @Test
    void aLineWithoutAnEqualsIsAnError(@TempDir Path dir) throws IOException {
        File file = write(dir, "bad.txt", "**/roles/*.xml --scope full\n");
        IOException e = assertThrows(IOException.class, () -> Overrides.load(file, global()));
        assertTrue(e.getMessage().contains("glob = flags"), e.getMessage());
    }

    @Test
    void invalidStrategyFlagsAreAnError(@TempDir Path dir) throws IOException {
        File file = write(dir, "bad.txt", "**/roles/*.xml = --scope bogus\n");
        IOException e = assertThrows(IOException.class, () -> Overrides.load(file, global()));
        assertTrue(e.getMessage().contains("invalid strategy flags"), e.getMessage());
    }

    private static StrategyOptions.Resolved global() {
        StrategyOptions options = new StrategyOptions();
        options.scope = "present";
        options.multivalue = "replace";
        options.equivalence = "real-value";
        return options.resolve();
    }

    private static File write(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toFile();
    }
}
