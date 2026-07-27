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
 * Command-layer tests for {@code deploy} increment 1, driving the real CLI offline against the committed
 * {@code src/test/resources/deploy} tree plus small inline trees for the error cases. Asserts the two-tier
 * apply order (schemas by XSD import, then objects by reference OID), the fragment/identity model, and the
 * exit-code contract.
 */
class DeployCliTest {

    private static final String TREE = "src/test/resources/deploy";
    private static final String DEAD_HOST = "http://localhost:1/midpoint";

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";

    // --- happy path against the committed tree ---

    @Test
    void printsTwoTierApplyOrder() {
        Result r = run("deploy", TREE);
        assertEquals(0, r.exit, r.err);
        assertTrue(r.out.contains("Apply order (8 steps)"), r.out);

        // Schema tier: base sorts before the lcm schema that imports it, and both before any object.
        assertOrder(r.out, "base-schema", "lcm-schema");
        assertOrder(r.out, "lcm-schema", "root.xml");
        // Object tier: referenced roles before their referencing role/users; org before its member.
        assertOrder(r.out, "enduser.xml", "manager.xml");
        assertOrder(r.out, "manager", "jsmith.xml");
        assertOrder(r.out, "manager", "ajones.xml");
        assertOrder(r.out, "root.xml", "jsmith.xml");

        // Two files share the Manager OID: grouped as one object (consecutive, marked), applied individually.
        assertTrue(r.out.contains("same object as"), r.out);
        // A raw .xsd is registered but not deployed; a non-object file is skipped with a warning.
        assertTrue(r.err.contains("custom.xsd"), r.err);
        assertTrue(r.err.contains("notes.xml"), r.err);
    }

    @Test
    void quietDropsWarningsButKeepsThePlan() {
        Result r = run("deploy", TREE, "--quiet");
        assertEquals(0, r.exit, r.err);
        assertTrue(r.out.contains("Apply order"), r.out);
        assertFalse(r.err.contains("custom.xsd"), r.err);
        assertFalse(r.err.contains("notes.xml"), r.err);
    }

    // --- fragment / identity model ---

    @Test
    void sameTypeAndNameWithoutOidGroupIntoOneObject(@TempDir Path dir) throws IOException {
        write(dir, "a.xml", roleNoOid("Shared", "<subtype>one</subtype>"));
        write(dir, "b.xml", roleNoOid("Shared", "<subtype>two</subtype>"));
        Result r = run("deploy", dir.toString());
        assertEquals(0, r.exit, r.err);
        assertTrue(r.out.contains("same object as"), "same type+name are one object:\n" + r.out);
    }

    @Test
    void differentNameWithoutOidAreTwoObjects(@TempDir Path dir) throws IOException {
        write(dir, "a.xml", roleNoOid("Shared", ""));
        write(dir, "b.xml", roleNoOid("Other", ""));
        Result r = run("deploy", dir.toString());
        assertEquals(0, r.exit, r.err);
        assertFalse(r.out.contains("same object as"), "different names are distinct objects:\n" + r.out);
    }

    @Test
    void contradictingSingleValuedFragmentsAreAnError(@TempDir Path dir) throws IOException {
        write(dir, "a.xml", role("11111111-0000-0000-0e00-000000000001", "Foo", "<description>one</description>"));
        write(dir, "b.xml", role("11111111-0000-0000-0e00-000000000001", "Foo", "<description>two</description>"));
        Result r = run("deploy", dir.toString());
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("Contradiction") && r.err.contains("description"), r.err);
    }

    @Test
    void multiValuedFragmentsSharingAContainerAreWarned(@TempDir Path dir) throws IOException {
        // Two fragments of one role each add to the multi-valued <inducement> — not a contradiction, but
        // file-by-file apply would clobber it under the default (replace), so it is warned (not fatal).
        String oid = "11111111-0000-0000-0e00-000000000004";
        write(dir, "a.xml", role(oid, "Shared",
                "<inducement><targetRef oid=\"11111111-0000-0000-0e00-0000000000fa\" type=\"c:RoleType\"/></inducement>"));
        write(dir, "b.xml", role(oid, "Shared",
                "<inducement><targetRef oid=\"11111111-0000-0000-0e00-0000000000fb\" type=\"c:RoleType\"/></inducement>"));
        Result r = run("deploy", dir.toString());
        assertEquals(0, r.exit, r.err);
        assertTrue(r.err.contains("multi-valued 'inducement'"), r.err);
        assertTrue(r.err.contains("last-one-wins") && r.err.contains("multivalue=merge"), r.err);
    }

    @Test
    void differentTypesUnderOneOidAreAnError(@TempDir Path dir) throws IOException {
        String oid = "11111111-0000-0000-0e00-000000000002";
        write(dir, "a.xml", role(oid, "Foo", ""));
        write(dir, "b.xml", "<user xmlns=\"" + NS + "\" oid=\"" + oid + "\"><name>Foo</name></user>");
        Result r = run("deploy", dir.toString());
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("Contradiction"), r.err);
    }

    @Test
    void objectWithNeitherOidNorNameIsAnError(@TempDir Path dir) throws IOException {
        write(dir, "a.xml", "<role xmlns=\"" + NS + "\"><description>nameless</description></role>");
        Result r = run("deploy", dir.toString());
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("neither an oid nor a name"), r.err);
    }

    @Test
    void referenceToAnObjectOutsideTheSetIsIgnored(@TempDir Path dir) throws IOException {
        // Manager induces a role that is NOT in the tree: no edge, no error, it just deploys as a leaf.
        write(dir, "manager.xml", role("11111111-0000-0000-0e00-000000000003", "Manager",
                "<inducement><targetRef oid=\"99999999-9999-9999-9999-999999999999\"/></inducement>"));
        Result r = run("deploy", dir.toString());
        assertEquals(0, r.exit, r.err);
        assertTrue(r.out.contains("Apply order (1 step)"), r.out);
    }

    // --- dependency cycle: not fatal ---

    @Test
    void objectCycleIsWarnedButNotFatal(@TempDir Path dir) throws IOException {
        String a = "11111111-0000-0000-0e00-00000000000a";
        String b = "11111111-0000-0000-0e00-00000000000b";
        write(dir, "a.xml", role(a, "RoleA", "<inducement><targetRef oid=\"" + b + "\"/></inducement>"));
        write(dir, "b.xml", role(b, "RoleB", "<inducement><targetRef oid=\"" + a + "\"/></inducement>"));
        Result r = run("deploy", dir.toString());
        assertEquals(0, r.exit, r.err);
        assertTrue(r.out.contains("Apply order (2 steps)"), r.out);
        assertTrue(r.err.contains("cycle"), r.err);
    }

    // --- usage / connection errors -> exit 2 ---

    @Test
    void missingPathIsAnError() {
        Result r = run("deploy", "/no/such/deploy/path");
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("does not exist"), r.err);
    }

    @Test
    void hostWithoutPasswordIsAnError() {
        Result r = run("deploy", TREE, "--url", DEAD_HOST, "--user", "u");
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("password"), r.err);
    }

    @Test
    void emptyTreeIsAnError(@TempDir Path dir) {
        Result r = run("deploy", dir.toString());
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("no midPoint objects"), r.err);
    }

    // --- apply-mode flag guards (offline, no writes) ---

    @Test
    void applyFlagsWithoutHostAreAnError() {
        Result r = run("deploy", TREE, "--create-if-absent");
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("--url"), r.err);
    }

    @Test
    void shortCreateAndMatchFlagsAreRecognized() {
        // -c/-m are shorthands for --create-if-absent/--match-by-name: without --url they hit the
        // write-flags guard (not an "unknown option" error), which proves they parsed as those flags.
        Result r = run("deploy", TREE, "-c", "-m");
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("--url"), r.err);
    }

    @Test
    void dryRunWithoutHostIsAnError() {
        Result r = run("deploy", TREE, "--dry-run");
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("--url"), r.err);
    }

    @Test
    void pruneWithoutUrlIsAnError() {
        // --prune deletes, so it must never be reachable in the offline plan-printing mode.
        Result r = run("deploy", TREE, "--prune");
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("--url"), r.err);
    }

    @Test
    void policyWithoutHostIsAnError() {
        Result r = run("deploy", TREE, "--policy", "some.policy");
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("--url"), r.err);
    }

    @Test
    void badPolicyFileFailsFast(@TempDir Path dir) throws IOException {
        // An object-only knob on an item-path selector is rejected at load — which happens before any server
        // round-trip, so a dead host still surfaces the policy error (not a connection error).
        Path policy = dir.resolve("bad.policy");
        Files.writeString(policy, "**/assignment scope=full\n", StandardCharsets.UTF_8);
        Result r = run("deploy", TREE, "--url", DEAD_HOST, "--user", "u", "-p", "x", "--policy", policy.toString());
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("object-only knob"), r.err);
    }

    @Test
    void yesAndConfirmEachApplyAreMutuallyExclusive() {
        Result r = run("deploy", TREE, "--yes", "--confirm-each-apply");
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("not both"), r.err);
    }

    @Test
    void noPathAndNoApplyPlanIsAnError() {
        Result r = run("deploy");
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("PATH"), r.err);
    }

    @Test
    void planAndApplyPlanAreMutuallyExclusive() {
        Result r = run("deploy", TREE, "--plan", "out", "--apply-plan", "in");
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("not both"), r.err);
    }

    @Test
    void planWithoutHostIsAnError() {
        Result r = run("deploy", TREE, "--plan", "out");
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("--url"), r.err);
    }

    // --- fixtures & harness ---

    private static String role(String oid, String name, String extra) {
        return "<role xmlns=\"" + NS + "\" xmlns:c=\"" + NS + "\" oid=\"" + oid + "\">\n"
                + "  <name>" + name + "</name>\n" + extra + "\n</role>";
    }

    private static String roleNoOid(String name, String extra) {
        return "<role xmlns=\"" + NS + "\" xmlns:c=\"" + NS + "\">\n"
                + "  <name>" + name + "</name>\n" + extra + "\n</role>";
    }

    private static void write(Path dir, String relative, String content) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void assertOrder(String output, String before, String after) {
        int i = output.indexOf(before);
        int j = output.indexOf(after);
        assertTrue(i >= 0 && j >= 0 && i < j,
                "expected '" + before + "' before '" + after + "' in:\n" + output);
    }

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
