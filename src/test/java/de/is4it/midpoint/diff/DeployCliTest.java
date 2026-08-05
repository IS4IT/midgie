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
    void aMissingPolicyFileSaysSo(@TempDir Path dir) {
        Result r = run("deploy", TREE, "--url", DEAD_HOST, "--user", "u", "-p", "x",
                "--policy", dir.resolve("absent.policy").toString());
        assertEquals(2, r.exit);
        assertTrue(r.err.contains("--policy file does not exist"), r.err);
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

    // --- plan files are output, never deploy input ---

    @Test
    void aGeneratedPlanFileIsNeverDeployed(@TempDir Path dir) throws IOException {
        // The marker travels with the content, so it holds wherever the file ends up: renamed, out of its
        // plan directory, and in a plainly-named folder that no path rule would skip.
        write(dir, "roles/real.xml", role("11111111-1111-1111-1111-111111111111", "Real Role", ""));
        write(dir, "archive/copied-out.xml", PlanStore.markGenerated(
                role("22222222-2222-2222-2222-222222222222", "Planned Role", "")));

        Result r = run("deploy", dir.toString());
        assertEquals(0, r.exit, r.err);
        assertTrue(r.out.contains("Real Role"), r.out);
        assertFalse(r.out.contains("Planned Role"), "a midgie-generated file must not become an apply unit:\n" + r.out);
        assertTrue(r.err.contains("midgie-generated"), r.err);
    }

    @Test
    void dotDirectoriesAreNotWalked(@TempDir Path dir) throws IOException {
        write(dir, "roles/real.xml", role("11111111-1111-1111-1111-111111111111", "Real Role", ""));
        write(dir, ".midgie-plan/001-roles-x.create.xml",
                role("33333333-3333-3333-3333-333333333333", "Hidden Role", ""));

        Result r = run("deploy", dir.toString());
        assertEquals(0, r.exit, r.err);
        assertFalse(r.out.contains("Hidden Role"), "a dot-directory must not be descended into:\n" + r.out);
    }

    @Test
    void anExplicitlyNamedDotDirectoryIsStillWalked(@TempDir Path dir) throws IOException {
        write(dir, ".hidden/role.xml", role("44444444-4444-4444-4444-444444444444", "Named Role", ""));

        Result r = run("deploy", dir.resolve(".hidden").toString());
        assertEquals(0, r.exit, r.err);
        assertTrue(r.out.contains("Named Role"), "naming a dot-directory outright must still work:\n" + r.out);
    }

    @Test
    void planDirectoryNestedInTheTreeIsExcluded(@TempDir Path dir) throws IOException {
        // The unmarked case the marker cannot catch: files that were already there when --plan was pointed
        // inside the tree. Excluded by canonical path for the current run.
        write(dir, "roles/real.xml", role("11111111-1111-1111-1111-111111111111", "Real Role", ""));
        write(dir, "plan/001-roles-x.create.xml", role("55555555-5555-5555-5555-555555555555", "Stale Plan Role", ""));

        Result withoutFlag = run("deploy", dir.toString());
        assertTrue(withoutFlag.out.contains("Stale Plan Role"),
                "without --plan the directory is an ordinary folder:\n" + withoutFlag.out);

        Result r = run("deploy", dir.toString(), "--plan", dir.resolve("plan").toString(),
                "--url", DEAD_HOST, "-u", "u", "-p", "p");
        assertFalse(r.out.contains("Stale Plan Role"),
                "the --plan directory must not be discovery input:\n" + r.out + r.err);
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
            int exit = Main.commandLine().execute(args);
            return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Result(int exit, String out, String err) {
    }

    @Test
    void anUnreadableObjectFileFailsTheRunInsteadOfBeingSkipped(@TempDir Path dir) throws Exception {
        // The file says it is a midPoint object and cannot be parsed. Dropping it with an advisory meant
        // deploy exited 0 with that object never deployed — and --quiet hid the advisory too, so a pipeline
        // went green on an incomplete tree.
        Files.writeString(dir.resolve("broken.xml"),
                "<role xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\">\n"
                        + "  <name>Broken</name>\n  <noSuchItem>x</noSuchItem>\n</role>",
                StandardCharsets.UTF_8);

        Result quiet = run("deploy", dir.toString(), "--quiet");

        assertEquals(2, quiet.exit, "an unreadable object is an error: " + quiet.err);
        assertTrue(quiet.err.contains("broken.xml"), "and --quiet must not hide it: " + quiet.err);
    }

    @Test
    void unrelatedXmlInTheTreeIsStillOnlyAnAdvisory(@TempDir Path dir) throws Exception {
        // Discovery treats any well-formed non-schema XML as a candidate, so a tree may legitimately hold
        // files that were never meant to be objects.
        Files.writeString(dir.resolve("notes.xml"), "<notes><item>hello</item></notes>", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("role.xml"),
                "<role xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\">\n"
                        + "  <name>Fine</name>\n</role>", StandardCharsets.UTF_8);

        Result r = run("deploy", dir.toString());
        assertEquals(0, r.exit, r.out + "\n" + r.err);
        assertTrue(r.err.contains("notes.xml"), "the unrelated file is still mentioned: " + r.err);
    }
}
