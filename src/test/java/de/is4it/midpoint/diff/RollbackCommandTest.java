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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Command-layer tests for {@code rollback}, driving the real CLI. The refusals are the point: a plan that
 * belongs to another server must not replay, and neither must one whose objects have moved on. Both are
 * decided before any write, so they are testable without a server — the dead host is never reached.
 */
class RollbackCommandTest {

    private static final String DEAD_HOST = "http://localhost:1/midpoint";
    private static final String DEAD_REST = "http://localhost:1/midpoint/ws/rest";

    @Test
    void refusesAPlanWrittenAgainstAnotherServer(@TempDir Path dir) throws IOException {
        File run = planWith(dir, "<server>http://elsewhere:8080/midpoint/ws/rest</server>",
                "<object type=\"roles\" oid=\"" + OID + "\" name=\"r\" action=\"patched\" version=\"1\"/>");

        Result r = run("rollback", "--url", DEAD_HOST, "-u", "u", "-p", "p",
                "--rollback-plan", run.getPath(), "--yes");

        assertEquals(2, r.exit, r.out + r.err);
        assertTrue(r.err.contains("was written against"), r.err);
        assertTrue(r.err.contains("elsewhere"), r.err);
    }

    @Test
    void refusesAPlanBelongingToAnotherDeployment(@TempDir Path dir) throws IOException {
        File run = planWith(dir, "<server>" + DEAD_REST + "</server>"
                        + "<deployment>abc123/other-tree</deployment>",
                "<object type=\"roles\" oid=\"" + OID + "\" name=\"r\" action=\"patched\" version=\"1\"/>");

        // This repository is a real git tree, so the identity resolves and will not match the recorded one.
        Result r = run("rollback", ".", "--url", DEAD_HOST, "-u", "u", "-p", "p",
                "--rollback-plan", run.getPath(), "--yes");

        assertEquals(2, r.exit, r.out + r.err);
        assertTrue(r.err.contains("belongs to deployment"), r.err);
    }

    @Test
    void saysSoWhenThereIsNoPlan(@TempDir Path dir) {
        Result r = run("rollback", "--url", DEAD_HOST, "-u", "u", "-p", "p",
                "--rollback-plan", dir.resolve("never-written").toString(), "--yes");

        assertEquals(2, r.exit, r.out + r.err);
        assertTrue(r.err.contains("no rollback plan"), r.err);
    }

    @Test
    void aPlanOfOnlyCreatesHasNothingToUndoAndSaysWhat(@TempDir Path dir) throws IOException {
        // The inverse of an add is an object delete, so creates are recorded and left alone. Exit 0: there
        // is genuinely nothing to do, and this must not read as a failure in a pipeline.
        File run = new File(dir.toFile(), "20260101-000000");
        writePlan(run, "<server>" + DEAD_REST + "</server>",
                "<object type=\"roles\" oid=\"" + OID + "\" name=\"made\" action=\"created\"/>");

        Result r = run("rollback", "--url", DEAD_HOST, "-u", "u", "-p", "p",
                "--rollback-plan", run.getPath(), "--yes");

        assertEquals(0, r.exit, r.out + r.err);
        assertTrue(r.out.contains("Nothing to roll back"), r.out);
        assertTrue(r.err.contains("not removed by a rollback"), r.err);
    }

    @Test
    void anUnfinishedPlanIsNeverPicked(@TempDir Path base) throws IOException {
        // A run that died half way is newer by name but must not be replayed.
        File good = new File(base.toFile(), "20260101-000000");
        writePlan(good, "<server>" + DEAD_REST + "</server>",
                "<object type=\"roles\" oid=\"" + OID + "\" name=\"made\" action=\"created\"/>");
        File broken = new File(base.toFile(), "20990101-000000");
        PlanStore.markIncomplete(broken);
        Files.writeString(new File(broken, RollbackPlan.MANIFEST).toPath(), "<rollbackManifest/>",
                StandardCharsets.UTF_8);

        Result r = run("rollback", "--url", DEAD_HOST, "-u", "u", "-p", "p",
                "--rollback-plan", base.toString(), "--yes");

        assertEquals(0, r.exit, r.out + r.err);
        assertTrue(r.out.contains("20260101-000000"), "the complete plan must be chosen:\n" + r.out);
        assertFalse(r.out.contains("20990101-000000"), r.out);
    }

    // --- fixtures & harness ---

    private static final String OID = "11111111-1111-1111-1111-111111111111";

    /** A plan with one replayable inverse, so the run gets past "nothing to do" and reaches the checks. */
    private static File planWith(Path dir, String provenance, String objects) throws IOException {
        File run = new File(dir.toFile(), "20260101-000000");
        writePlan(run, provenance, objects);
        PlanStore.write(run, 1, 3, "roles", OID, "r", PlanStore.Kind.PATCH, inversePatch());
        return run;
    }

    private static void writePlan(File run, String provenance, String objects) throws IOException {
        Files.createDirectories(run.toPath());
        Files.writeString(new File(run, RollbackPlan.MANIFEST).toPath(),
                PlanStore.markGenerated("<rollbackManifest>" + provenance + objects + "</rollbackManifest>"),
                StandardCharsets.UTF_8);
    }

    private static String inversePatch() {
        return "<apti:objectModification"
                + " xmlns:apti=\"http://midpoint.evolveum.com/xml/ns/public/common/api-types-3\""
                + " xmlns:c=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " xmlns:t=\"http://prism.evolveum.com/xml/ns/public/types-3\">\n"
                + "  <apti:oid>" + OID + "</apti:oid>\n"
                + "  <apti:itemDelta>\n"
                + "    <t:modificationType>replace</t:modificationType>\n"
                + "    <t:path>c:description</t:path>\n"
                + "    <t:value>before</t:value>\n"
                + "  </apti:itemDelta>\n"
                + "</apti:objectModification>";
    }

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
