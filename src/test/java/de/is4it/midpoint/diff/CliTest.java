/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Command-layer tests driving the real CLI offline (no server), covering the argument validation and
 * exit-code contract that the engine and rig-gated tests don't. Uses the committed {@code examples/}
 * role pair (differ only in {@code description}).
 */
class CliTest {

    private static final String A = "examples/a.xml"; // desired: "Desired description"
    private static final String B = "examples/b.xml"; // current: "Current description"
    private static final String DEAD_HOST = "http://localhost:1/midpoint";

    // --- usage / validation errors -> exit 2 (these fail fast, before any prism context or network) ---

    @Test
    void missingCurrentFileIsAUsageError() {
        Result r = run("diff", A);
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("CURRENT"), r.err);
    }

    @Test
    void liveFileAndHostAreMutuallyExclusive() {
        Result r = run("diff", A, B, "--url", DEAD_HOST);
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("not both"), r.err);
    }

    @Test
    void badScopeExitsTwo() {
        assertEquals(2, run("diff", A, B, "--scope", "nope").exit);
    }

    @Test
    void badEquivalenceExitsTwo() {
        Result r = run("diff", A, B, "--equivalence", "nope");
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("auto|real-value"), "the message must list auto as a value: " + r.err);
    }

    @Test
    void equivalenceAutoIsAcceptedAndIsTheDefault() {
        // a.xml vs b.xml differ in <description> only, so both runs are the same one-line drift; what is
        // being pinned is that 'auto' parses and that omitting --equivalence means exactly it.
        Result explicit = run("diff", A, B, "--equivalence", "auto");
        assertEquals(1, explicit.exit, explicit.err);
        assertEquals(explicit.out, run("diff", A, B).out, "the default must be --equivalence auto");
        assertFalse(explicit.err.contains("prism-experimental"),
                "auto must not carry natural-keys' experimental note: " + explicit.err);
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
        assertTrue(r.err.contains("--url"), r.err);
    }

    @Test
    void applyHostWithoutPasswordExitsTwo() {
        Result r = run("apply", A, "--url", DEAD_HOST, "--user", "u");
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("--password"), r.err);
    }

    @Test
    void aHardErrorExitsTwoNotOne() {
        // 1 means "differences found" in this tool's contract, so picocli's default of 1 for an execution
        // exception reported phantom drift to a CI gate for a typo'd flag — with a Java stack trace attached.
        Result r = run("diff", A, "--url", DEAD_HOST, "--user", "u", "--password", "p",
                "--cacert", "/no/such/ca.pem");

        assertEquals(2, r.exit, "a setup failure is an error, not drift: " + r.err);
        assertFalse(r.err.contains("\tat "), "and no stack trace is dumped at the operator: " + r.err);
    }

    @Test
    void noSubcommandIsAUsageErrorOnStderr() {
        // Help on stdout with exit 0 means 'midgie $ARGS > delta.xml' with an empty $ARGS writes the help
        // text into the payload file and calls it a success.
        Result r = run();

        assertEquals(2, r.exit, r.err);
        assertTrue(r.err.contains("Usage:"), "usage belongs on stderr: " + r.err);
        assertTrue(r.out.isBlank(), "and must not pollute the payload stream: " + r.out);
    }

    @Test
    void aMistypedPasswordFlagDoesNotEchoTheSecret() {
        // picocli's default parameter-exception handler echoes unmatched tokens verbatim, so a single typo
        // published the password into whatever log the run went to.
        Result r = run("diff", A, B, "--pasword", "hunter2");

        assertEquals(2, r.exit);
        assertFalse(r.err.contains("hunter2"), "the value must not be echoed: " + r.err);
        assertTrue(r.err.contains("--pasword"), "but the offending option is still named: " + r.err);
    }

    // --- local inputs are checked before the server is contacted ---

    @Test
    void aMissingDesiredFileIsReportedInsteadOfAConnectionError() {
        // The dead host is the point: reading the input used to wait on the schema round trip, so a typo'd
        // path came back as whatever the network said. What failed here is knowable without a socket.
        Result r = run("diff", "/no/such/desired.xml", "--url", DEAD_HOST, "-u", "u", "-p", "p");

        assertEquals(2, r.exit);
        assertTrue(r.err.contains("/no/such/desired.xml"), r.err);
        assertTrue(r.err.contains("no such file or directory"), r.err);
    }

    @Test
    void aMalformedDesiredFileIsReportedInsteadOfAConnectionError(@TempDir Path dir) throws IOException {
        Path broken = dir.resolve("broken.xml");
        Files.writeString(broken, "<role><name>Unclosed</name>", StandardCharsets.UTF_8);

        Result r = run("diff", broken.toString(), "--url", DEAD_HOST, "-u", "u", "-p", "p");

        assertEquals(2, r.exit);
        assertTrue(r.err.contains("not well-formed XML"), r.err);
    }

    @Test
    void applyChecksItsInputBeforeTheServerToo() {
        Result r = run("apply", "/no/such/desired.xml", "--url", DEAD_HOST, "-u", "u", "-p", "p");

        assertEquals(2, r.exit);
        assertTrue(r.err.contains("no such file or directory"), r.err);
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

    // --- accrued-in-place merge guard (D5) ---

    @Test
    void mergeUnderNaturalKeysRefusesAccruedInPlaceEdit(@TempDir Path dir) throws Exception {
        String[] files = accruedMergeScenario(dir);
        Result r = run("diff", files[0], files[1], "--path", "assignment=merge", "--equivalence", "natural-keys");
        assertEquals(2, r.exit, r.err);
        assertTrue(r.err.contains("refusing to merge"), r.err);
    }

    @Test
    void allowInplaceMergeProceedsWithWarning(@TempDir Path dir) throws Exception {
        String[] files = accruedMergeScenario(dir);
        Result r = run("diff", files[0], files[1], "--path", "assignment=merge",
                "--equivalence", "natural-keys", "--allow-inplace-merge", "--format", "summary");
        assertEquals(1, r.exit, r.err);
        assertTrue(r.err.contains("accrued"), r.err);
    }

    @Test
    void realValueMergeIsNotRefused(@TempDir Path dir) throws Exception {
        // Same inputs under the default real-value strategy: a clean union (add the new value, keep the
        // accrued one), so nothing is edited in place and the run is not refused.
        String[] files = accruedMergeScenario(dir);
        Result r = run("diff", files[0], files[1], "--path", "assignment=merge", "--format", "summary");
        assertEquals(1, r.exit, r.err);
    }

    /** {desired, live} paths: live has an accrued assignment[2]; desired swaps in a new managed assignment[3]. */
    private static String[] accruedMergeScenario(Path dir) throws IOException {
        Path desired = dir.resolve("desired.xml");
        Path live = dir.resolve("live.xml");
        Files.writeString(desired, user(assignment("1", "role-1"), assignment("3", "role-3")), StandardCharsets.UTF_8);
        Files.writeString(live, user(assignment("1", "role-1"), assignment("2", "role-2")), StandardCharsets.UTF_8);
        return new String[] {desired.toString(), live.toString()};
    }

    private static String user(String... assignments) {
        return "<user xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " xmlns:c=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " oid=\"22222222-2222-2222-2222-222222222222\">\n"
                + "  <name>jack</name>\n" + String.join("", assignments) + "</user>";
    }

    private static String assignment(String id, String targetOid) {
        return "  <assignment id=\"" + id + "\"><targetRef oid=\"" + targetOid + "\" type=\"c:RoleType\"/></assignment>\n";
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
            int exit = Main.commandLine().execute(args);
            return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Result(int exit, String out, String err) {
    }
}
