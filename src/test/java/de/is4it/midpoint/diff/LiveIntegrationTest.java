/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end tests against a running midPoint (the {@code docker/} rig). They drive the real CLI over
 * REST, so they cover the live paths — {@code diff --host}, {@code apply}, create-if-absent, idempotency —
 * that the offline unit tests cannot. When the rig is unreachable the whole class is skipped, so
 * {@code mvn test} stays green without docker.
 *
 * <p>Connection defaults match {@code docker/.env}; override with {@code -Dmpdiff.it.host=...},
 * {@code -Dmpdiff.it.user=...}, {@code -Dmpdiff.it.password=...}. Uses one throwaway user (a fixed OID,
 * name {@code zz-it-user}) that is deleted before and after the run.
 */
class LiveIntegrationTest {

    private static final String HOST = System.getProperty("mpdiff.it.host", "http://localhost:12080/midpoint");
    private static final String USER = System.getProperty("mpdiff.it.user", "administrator");
    private static final String PASSWORD = System.getProperty("mpdiff.it.password", "T3stPw890uio");

    private static final String ADMIN_OID = "00000000-0000-0000-0000-000000000002";
    private static final String TEST_OID = "a17e57e5-0000-0000-0000-000000000001";

    private static MidpointRestClient client;
    private static boolean rigUp;

    @BeforeAll
    static void connect() {
        client = new MidpointRestClient(URI.create(HOST), USER, PASSWORD, true);
        rigUp = reachable();
        if (rigUp) {
            deleteTestUser(); // start from a known-clean state
        }
    }

    @AfterAll
    static void cleanup() {
        if (rigUp) {
            deleteTestUser();
        }
    }

    @Test
    void applyCreatesPatchesAndIsIdempotentOverRest(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteTestUser(); // isolate from the other test's use of the shared OID

        // 1) create-if-absent -> POST creates the object (--yes: the test runs non-interactively)
        File v1 = writeUser(dir, "v1", "IT User One");
        CliResult create = run("apply", v1.getPath(), "--host", HOST, "-u", USER, "-p", PASSWORD, "--create-if-absent", "--yes");
        assertEquals(0, create.exit, create.err);
        assertTrue(create.out.contains("Created"), "expected 'Created', got: " + create.out);
        assertTrue(client.getObject("users", TEST_OID).contains("IT User One"), "server should hold the created user");

        // 2) diff --host sees the drift and emits a replace of fullName
        File v2 = writeUser(dir, "v2", "IT User Two");
        CliResult diff = run("diff", v2.getPath(), "--host", HOST, "-u", USER, "-p", PASSWORD, "--format", "summary");
        assertEquals(1, diff.exit, diff.err); // 1 == drift
        assertTrue(diff.out.contains("replace fullName"), "expected 'replace fullName', got: " + diff.out);

        // 3) apply -> PATCH the change
        CliResult apply = run("apply", v2.getPath(), "--host", HOST, "-u", USER, "-p", PASSWORD, "--yes");
        assertEquals(0, apply.exit, apply.err);
        assertTrue(apply.out.contains("Applied"), "expected 'Applied', got: " + apply.out);
        assertTrue(client.getObject("users", TEST_OID).contains("IT User Two"), "server should hold the patched value");

        // 4) re-apply the same desired state -> idempotent no-op (no PATCH). No --yes needed: a no-op
        //    never reaches the confirmation.
        CliResult again = run("apply", v2.getPath(), "--host", HOST, "-u", USER, "-p", PASSWORD);
        assertEquals(0, again.exit, again.err);
        assertTrue(again.out.contains("No changes"), "re-apply must be a no-op, got: " + again.out);
    }

    @Test
    void applyWithoutYesRefusesInNonInteractiveSession(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteTestUser();

        // Create the user (with --yes), then try to apply a change WITHOUT --yes. The test JVM has no
        // console, so apply must refuse (exit 2) and leave the value untouched.
        run("apply", writeUser(dir, "a", "Refuse One").getPath(),
                "--host", HOST, "-u", USER, "-p", PASSWORD, "--create-if-absent", "--yes");

        CliResult refused = run("apply", writeUser(dir, "b", "Refuse Two").getPath(),
                "--host", HOST, "-u", USER, "-p", PASSWORD);
        assertEquals(2, refused.exit, refused.out + refused.err);
        assertTrue(refused.err.contains("--yes"), "should point the user at --yes, got: " + refused.err);
        assertTrue(client.getObject("users", TEST_OID).contains("Refuse One"), "value must be unchanged");
    }

    @Test
    void diffHostAgainstUnchangedObjectReportsNoDrift(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");

        // Fetch the live administrator, use it verbatim as the desired state -> no drift, exit 0.
        String adminXml = client.getObject("users", ADMIN_OID);
        File desired = dir.resolve("admin.xml").toFile();
        Files.writeString(desired.toPath(), adminXml, StandardCharsets.UTF_8);

        CliResult diff = run("diff", desired.getPath(), "--host", HOST, "-u", USER, "-p", PASSWORD, "--format", "summary");
        assertEquals(0, diff.exit, diff.err); // 0 == no differences
        assertTrue(diff.out.contains("No differences"), "expected 'No differences', got: " + diff.out);
    }

    // --- helpers ---

    private static boolean reachable() {
        try {
            client.getObject("users", ADMIN_OID);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteTestUser() {
        try {
            client.deleteObject("users", TEST_OID);
        } catch (Exception ignored) {
            // absent (404) or rig down — nothing to clean up
        }
    }

    private static File writeUser(Path dir, String tag, String fullName) throws IOException {
        String xml = "<user xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\" oid=\""
                + TEST_OID + "\">\n"
                + "  <name>zz-it-user</name>\n"
                + "  <fullName>" + fullName + "</fullName>\n"
                + "</user>";
        Path path = dir.resolve("user-" + tag + ".xml");
        Files.writeString(path, xml, StandardCharsets.UTF_8);
        return path.toFile();
    }

    /** Run the CLI in-process, capturing stdout/stderr and the exit code. */
    private static CliResult run(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int exit = new CommandLine(new Main()).execute(args);
            return new CliResult(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record CliResult(int exit, String out, String err) {
    }
}
