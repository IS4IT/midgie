/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Command-layer tests driving the real CLI offline (no server), covering the argument validation and
 * exit-code contract that the engine and rig-gated tests don't. Uses the committed {@code examples/}
 * role pair (differ only in {@code description}).
 */
class CliTest {

    private static final String A = "examples/a.xml"; // desired: "Desired description"
    private static final String B = "examples/b.xml"; // live:    "Current description"
    private static final String DEAD_HOST = "http://localhost:1/midpoint";

    // --- usage / validation errors -> exit 2 (these fail fast, before any prism context or network) ---

    @Test
    void missingLiveFileIsAUsageError() {
        Result r = run("diff", A);
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("LIVE"), r.err);
    }

    @Test
    void liveFileAndHostAreMutuallyExclusive() {
        Result r = run("diff", A, B, "--host", DEAD_HOST);
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("not both"), r.err);
    }

    @Test
    void badScopeExitsTwo() {
        assertEquals(2, run("diff", A, B, "--scope", "nope").exit);
    }

    @Test
    void badEquivalenceExitsTwo() {
        assertEquals(2, run("diff", A, B, "--equivalence", "nope").exit);
    }

    @Test
    void badMultivalueExitsTwo() {
        assertEquals(2, run("diff", A, B, "--multivalue", "nope").exit);
    }

    @Test
    void malformedPathOverrideExitsTwo() {
        assertEquals(2, run("diff", A, B, "--path", "assignment").exit); // missing "=merge|replace"
    }

    @Test
    void applyWithoutHostExitsTwo() {
        Result r = run("apply", A);
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("--host"), r.err);
    }

    @Test
    void applyHostWithoutPasswordExitsTwo() {
        Result r = run("apply", A, "--host", DEAD_HOST, "--user", "u");
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("--password"), r.err);
    }

    // --- happy paths ---

    @Test
    void diffOfTwoFilesReportsDriftWithExitOne() {
        Result r = run("diff", A, B, "--format", "summary");
        assertEquals(1, r.exit, r.err);
        assertTrue(r.out.contains("replace description"), r.out);
    }

    @Test
    void diffOfIdenticalFilesReportsNoDriftWithExitZero() {
        Result r = run("diff", A, A, "--format", "summary");
        assertEquals(0, r.exit, r.err);
        assertTrue(r.out.contains("No differences"), r.out);
    }

    @Test
    void unknownFormatExitsTwo() {
        Result r = run("diff", A, B, "--format", "bogus");
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("--format"), r.err);
    }

    // --- CLI harness ---

    private static Result run(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int exit = new CommandLine(new Main()).execute(args);
            return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Result(int exit, String out, String err) {
    }
}
