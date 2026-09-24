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
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end tests against a running midPoint (the {@code docker/} rig). They drive the real CLI over
 * REST, so they cover the live paths — {@code diff --url}, single-file {@code deploy}, create-if-absent, idempotency —
 * that the offline unit tests cannot. When the rig is unreachable the whole class is skipped, so
 * {@code mvn test} stays green without docker.
 *
 * <p>Connection defaults match {@code docker/.env}; override with {@code -Dmidgie.it.url=...},
 * {@code -Dmidgie.it.user=...}, {@code -Dmidgie.it.password=...}. Uses one throwaway user (a fixed OID,
 * name {@code zz-it-user}) that is deleted before and after the run.
 */
class LiveIntegrationTest {

    private static final String HOST = System.getProperty("midgie.it.url", "http://localhost:12080/midpoint");
    private static final String USER = System.getProperty("midgie.it.user", "administrator");
    private static final String PASSWORD = System.getProperty("midgie.it.password", "T3stPw890uio");

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String ADMIN_OID = "00000000-0000-0000-0000-000000000002";
    private static final String TEST_OID = "a17e57e5-0000-0000-0000-000000000001";

    // Throwaway objects for the deploy tests (deleted before/after; user first so the role is unreferenced).
    private static final String DEPLOY_ROLE_OID = "a17e57e5-0000-0000-0000-0000000000d1";
    private static final String DEPLOY_USER_OID = "a17e57e5-0000-0000-0000-0000000000d2";
    private static final String DEPLOY_MATCH_OID = "a17e57e5-0000-0000-0000-0000000000d3";
    private static final String LOOKUP_OID = "a17e57e5-0000-0000-0000-0000000000d4";
    // Built-in roles, only ever induced by the throwaway role, which nobody is assigned.
    private static final String SUPERUSER_ROLE_OID = "00000000-0000-0000-0000-000000000004";
    private static final String END_USER_ROLE_OID = "00000000-0000-0000-0000-000000000008";
    // An OID-less role, created by the server with a random OID; cleaned up by name.
    private static final String COMBO_NAME = "zz-it-combo-role";

    private static MidpointRestClient client;
    private static boolean rigUp;

    @BeforeAll
    static void connect() {
        // Not insecure: the rig is plain http, and --insecure sets a JVM-global property the JDK latches
        // on first use of java.net.http — so this one call disabled hostname verification for every later
        // test in the same surefire JVM, and whether it took effect depended on class order.
        client = new MidpointRestClient(URI.create(HOST), USER, PASSWORD, false);
        rigUp = reachable();
        if (rigUp) {
            deleteTestUser(); // start from a known-clean state
            deleteDeployObjects();
        }
    }

    @AfterAll
    static void cleanup() {
        if (rigUp) {
            deleteTestUser();
            deleteDeployObjects();
        }
    }

    @Test
    void deployingALookupTableIsIdempotent(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        tryDelete(SyncState.REST_TYPE, LOOKUP_OID);

        // midPoint does not return a LookupTableType's rows unless asked: they come back as
        // <row incomplete="true"/>. Taken at face value that reads as "the table has no rows", so every run
        // re-proposed the whole table and the deploy never converged. Only a live server produces that
        // marker, so this is the test that would have caught it.
        File table = writeLookupTable(dir, "v1", "Abgelaufen");
        assertEquals(0, run("deploy", table.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--yes").exit);

        CliResult second = run("diff", table.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--format", "summary");
        assertEquals(0, second.exit, "an unchanged lookup table must be in sync, got: " + second.out);

        // A real row change must still be seen — the fix must not suppress the rows it just learned to read.
        File edited = writeLookupTable(dir, "v2", "Abgelaufen (LOGA)");
        CliResult drift = run("diff", edited.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--format", "summary");
        assertEquals(1, drift.exit, drift.err);
        assertTrue(drift.out.contains("label"), "the changed row label must surface: " + drift.out);

        assertEquals(0, run("deploy", edited.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes").exit);
        assertEquals(0, run("diff", edited.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--format", "summary").exit, "and it settles after applying");

        tryDelete(SyncState.REST_TYPE, LOOKUP_OID);
    }

    @Test
    void autoUpdatesAKeyedEntryInPlaceAndMidpointKeepsItsContainerIds(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteTestUser();

        // The claim --equivalence auto is built on, against a real server: editing one entry of a keyed
        // multi-valued container must produce a deep in-place PATCH that midPoint accepts, leaving the
        // repository-assigned container ids — and everything hanging off them, metadata included — alone.
        File created = writeKeyedPanels(dir, "v1", 20);
        assertEquals(0, run("deploy", created.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--yes").exit);
        List<String> idsBefore = containerIds(client.getObject("users", TEST_OID));
        assertEquals(3, idsBefore.size(), "expected the page and its two panels to carry ids: " + idsBefore);

        File edited = writeKeyedPanels(dir, "v2", 25);
        CliResult diff = run("diff", edited.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--format", "summary");
        assertEquals(1, diff.exit, diff.err);
        assertTrue(diff.out.contains("panel/") && diff.out.contains("displayOrder"),
                "auto must reach the panel in place rather than replace the page, got: " + diff.out);

        assertEquals(0, run("deploy", edited.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes").exit);
        String after = client.getObject("users", TEST_OID);
        assertEquals(idsBefore, containerIds(after), "midPoint must keep the container ids across the edit");
        assertTrue(after.contains("<displayOrder>25</displayOrder>"), "and hold the new value:\n" + after);

        // Re-running must settle: a deep PATCH that did not fully apply would show as drift here.
        assertEquals(0, run("diff", edited.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--format", "summary").exit, "the applied delta must leave the object in sync");

        // The contrast that makes the above worth asserting: under the previous default the same edit
        // replaces the whole page, so midPoint assigns fresh ids to it and to both panels.
        File again = writeKeyedPanels(dir, "v3", 30);
        assertEquals(0, run("deploy", again.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--equivalence", "real-value", "--yes").exit);
        assertNotEquals(idsBefore, containerIds(client.getObject("users", TEST_OID)),
                "real-value is expected to churn the container ids — that is what auto avoids");
    }

    @Test
    void deployCreatesPatchesAndIsIdempotentForOneFile(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteTestUser(); // isolate from the other test's use of the shared OID

        // 1) a missing object -> POST creates it (--yes: the test runs non-interactively)
        File v1 = writeUser(dir, "v1", "IT User One");
        CliResult create = run("deploy", v1.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes");
        assertEquals(0, create.exit, create.err);
        assertTrue(create.out.contains("created"), "expected 'created', got: " + create.out);
        assertTrue(client.getObject("users", TEST_OID).contains("IT User One"), "server should hold the created user");

        // 2) diff --url sees the drift and emits a replace of fullName
        File v2 = writeUser(dir, "v2", "IT User Two");
        CliResult diff = run("diff", v2.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--format", "summary");
        assertEquals(1, diff.exit, diff.err); // 1 == drift
        assertTrue(diff.out.contains("replace fullName"), "expected 'replace fullName', got: " + diff.out);

        // 3) deploy -> PATCH the change
        CliResult patch = run("deploy", v2.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes");
        assertEquals(0, patch.exit, patch.err);
        assertTrue(patch.out.contains("patched"), "expected 'patched', got: " + patch.out);
        assertTrue(client.getObject("users", TEST_OID).contains("IT User Two"), "server should hold the patched value");

        // 4) deploy the same desired state again -> idempotent no-op (no PATCH). No --yes needed: a no-op
        //    never reaches the confirmation.
        CliResult again = run("deploy", v2.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD);
        assertEquals(0, again.exit, again.err);
        assertTrue(again.out.contains("in-sync"), "a re-deploy must be a no-op, got: " + again.out);
    }

    @Test
    void deployRefusesAPatchWithoutYesInNonInteractiveSession(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteTestUser();

        // Create the user (with --yes), then try to deploy a change WITHOUT --yes. The test JVM has no
        // console, so deploy must refuse (exit 2) and leave the value untouched.
        run("deploy", writeUser(dir, "a", "Refuse One").getPath(),
                "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes");

        CliResult refused = run("deploy", writeUser(dir, "b", "Refuse Two").getPath(),
                "--url", HOST, "-u", USER, "-p", PASSWORD);
        assertEquals(2, refused.exit, refused.out + refused.err);
        assertTrue(refused.err.contains("--yes"), "should point the user at --yes, got: " + refused.err);
        assertTrue(client.getObject("users", TEST_OID).contains("Refuse One"), "value must be unchanged");
    }

    @Test
    void dryRunGatesViaExitCodeAndQuietSuppressesChatter(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteTestUser();

        // --dry-run on a missing object -> would change -> exit 1, the POST body on stdout, and it stays
        // absent. 'diff' says the same thing about it rather than failing with "does not exist".
        File v1 = writeUser(dir, "v1", "Dry One");
        CliResult dryCreate = run("deploy", v1.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--dry-run", "--format", "xml");
        assertEquals(1, dryCreate.exit, dryCreate.err);
        assertTrue(dryCreate.out.contains("Dry One"), "the create body is the payload, got: " + dryCreate.out);
        CliResult diffCreate = run("diff", v1.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD);
        assertEquals(1, diffCreate.exit, diffCreate.err);
        assertEquals(dryCreate.out, diffCreate.out, "diff is deploy --dry-run --format for one object");
        assertFalse(testUserExists(), "dry-run must not create the object");

        // create it for real, then dry-run a change -> would change -> exit 1, delta printed, not applied.
        run("deploy", v1.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes");
        File v2 = writeUser(dir, "v2", "Dry Two");
        CliResult dryPatch = run("deploy", v2.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--dry-run", "--format", "summary");
        assertEquals(1, dryPatch.exit, dryPatch.err);
        assertTrue(dryPatch.out.contains("replace fullName"), "dry-run prints the delta, got: " + dryPatch.out);
        assertFalse(dryPatch.out.contains("Plan:"), "the plan rows must not land in the payload: " + dryPatch.out);
        assertTrue(dryPatch.err.contains("Plan:"), "the plan summary moves to stderr, got: " + dryPatch.err);
        assertTrue(client.getObject("users", TEST_OID).contains("Dry One"), "dry-run must not change the object");

        CliResult xmlDeploy = run("deploy", v2.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--dry-run", "--format", "xml");
        CliResult xmlDiff = run("diff", v2.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD);
        assertEquals(xmlDeploy.exit, xmlDiff.exit);
        assertEquals(xmlDeploy.out, xmlDiff.out, "the same PATCH body from both commands");

        // same dry-run with --quiet -> exit 1, delta still on stdout, but no plan rows on stderr.
        CliResult dryQuiet = run("deploy", v2.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--dry-run", "--format", "summary", "--quiet");
        assertEquals(1, dryQuiet.exit, dryQuiet.err);
        assertTrue(dryQuiet.out.contains("replace fullName"), "quiet keeps the delta payload");
        assertFalse(dryQuiet.err.contains("Plan:"), "quiet suppresses the plan rows, got: " + dryQuiet.err);

        // deploy for real, then dry-run again -> in sync -> exit 0.
        run("deploy", v2.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes");
        CliResult drySync = run("deploy", v2.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--dry-run");
        assertEquals(0, drySync.exit, drySync.err);
    }

    @Test
    void diffHostAgainstUnchangedObjectReportsNoDrift(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");

        // Fetch the live administrator, use it verbatim as the desired state -> no drift, exit 0.
        String adminXml = client.getObject("users", ADMIN_OID);
        File desired = dir.resolve("admin.xml").toFile();
        Files.writeString(desired.toPath(), adminXml, StandardCharsets.UTF_8);

        CliResult diff = run("diff", desired.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--format", "summary");
        assertEquals(0, diff.exit, diff.err); // 0 == no differences
        assertTrue(diff.out.contains("No differences"), "expected 'No differences', got: " + diff.out);
    }

    @Test
    void liveVersionIsReadableAndMatchesTheBundledSchemaLine() throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");

        PrismContext prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
        String live = VersionSupport.liveVersion(prismContext, client);

        assertNotNull(live, "should read the server version from GET /nodes");
        assertTrue(VersionSupport.sameMajorLine(live, VersionSupport.compileTimeVersion()),
                "rig version " + live + " should share a major line with the bundled schema "
                        + VersionSupport.compileTimeVersion() + " (the rig is pinned to the same MP_VER)");
    }

    @Test
    void deployCreatesInDependencyOrderThenIsIdempotent(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteDeployObjects();
        writeDeployTree(dir);

        // create-if-absent --yes -> POSTs the role, then the user that assigns it, in that order.
        CliResult create = run("deploy", dir.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--create-if-absent", "--yes");
        assertEquals(0, create.exit, create.err);
        assertTrue(create.out.contains("created"), "expected 'created' lines, got: " + create.out);
        int roleAt = create.out.indexOf("IT Deploy Role");
        int userAt = create.out.indexOf("zz-it-deploy-user");
        assertTrue(roleAt >= 0 && userAt >= 0 && roleAt < userAt,
                "the referenced role must be created before the user:\n" + create.out);
        assertTrue(client.getObject("roles", DEPLOY_ROLE_OID).contains("IT Deploy Role"));
        assertTrue(client.getObject("users", DEPLOY_USER_OID).contains("zz-it-deploy-user"));

        // Re-run as a dry-run -> everything already matches -> in-sync, exit 0 (idempotent).
        CliResult dry = run("deploy", dir.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--create-if-absent", "--dry-run");
        assertEquals(0, dry.exit, dry.out + dry.err);
        assertTrue(dry.out.contains("in-sync"), "re-deploy must be a no-op, got: " + dry.out);
    }

    @Test
    void deployWithoutYesRefusesInNonInteractiveSession(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteDeployObjects();
        writeDeployTree(dir);

        // No --yes and no console -> deploy must refuse after the preview and write nothing.
        CliResult refused = run("deploy", dir.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--create-if-absent");
        assertEquals(2, refused.exit, refused.out + refused.err);
        assertTrue(refused.err.contains("--yes"), "should point the user at --yes, got: " + refused.err);
        assertFalse(exists("roles", DEPLOY_ROLE_OID), "nothing may be created without confirmation");
    }

    @Test
    void deployMatchByNamePatchesAnOidlessObjectByName(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteDeployObjects();

        // Seed a role with a known OID and name.
        Path seed = java.nio.file.Files.createDirectories(dir.resolve("seed"));
        Files.writeString(seed.resolve("role.xml"), role(DEPLOY_MATCH_OID, "AOM Role", "orig"), StandardCharsets.UTF_8);
        run("deploy", seed.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--create-if-absent", "--yes");

        // Deploy an OID-less fragment with the SAME name and a changed description; --match-by-name finds
        // it by name and PATCHes it.
        Path aom = java.nio.file.Files.createDirectories(dir.resolve("aom"));
        Files.writeString(aom.resolve("role.xml"), role(null, "AOM Role", "changed"), StandardCharsets.UTF_8);
        CliResult patched = run("deploy", aom.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--match-by-name", "--yes");
        assertEquals(0, patched.exit, patched.err);
        assertTrue(patched.out.contains("patched"), "match-by-name should patch the match, got: " + patched.out);
        assertTrue(client.getObject("roles", DEPLOY_MATCH_OID).contains("changed"),
                "the matched role should carry the new description");
    }

    @Test
    void deployMatchByNameWithCreateIfAbsentCreatesThenMatches(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteByName("roles", COMBO_NAME);

        Path tree = java.nio.file.Files.createDirectories(dir.resolve("combo"));
        Files.writeString(tree.resolve("role.xml"), role(null, COMBO_NAME, "v1"), StandardCharsets.UTF_8);

        // No match yet: --match-by-name finds nothing, --create-if-absent then POSTs it.
        CliResult created = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--match-by-name", "--create-if-absent", "--yes");
        assertEquals(0, created.exit, created.err);
        assertTrue(created.out.contains("created"), "no match -> create, got: " + created.out);

        // Re-run identical: --match-by-name now finds it by name -> in-sync (no duplicate created).
        CliResult resynced = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--match-by-name", "--create-if-absent", "--yes");
        assertEquals(0, resynced.exit, resynced.err);
        assertTrue(resynced.out.contains("in-sync"), "re-run must match, not create again, got: " + resynced.out);

        // Change the fragment: match -> patch.
        Files.writeString(tree.resolve("role.xml"), role(null, COMBO_NAME, "v2"), StandardCharsets.UTF_8);
        CliResult patched = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--match-by-name", "--create-if-absent", "--yes");
        assertEquals(0, patched.exit, patched.err);
        assertTrue(patched.out.contains("patched"), "changed fragment -> patch, got: " + patched.out);

        deleteByName("roles", COMBO_NAME);
    }

    @Test
    void deployPlanThenApplyPlanRoundTrips(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteDeployObjects();
        Path tree = dir.resolve("tree");
        writeDeployTree(tree);
        Path plan = dir.resolve("plan");

        // --plan writes create files for the fresh tree and touches nothing on the server (exit 1 = would change).
        CliResult planned = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--create-if-absent", "--plan", plan.toString());
        assertEquals(1, planned.exit, planned.err);
        assertFalse(exists("roles", DEPLOY_ROLE_OID), "--plan must not write to the server");
        try (java.util.stream.Stream<Path> files = Files.list(plan)) {
            assertTrue(files.anyMatch(p -> p.toString().endsWith(".create.xml")), "expected create files");
        }

        // --apply-plan replays them: the role (lower sequence) is POSTed before the user that assigns it.
        CliResult applied = run("deploy", "--apply-plan", plan.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes");
        assertEquals(0, applied.exit, applied.err);
        assertTrue(exists("roles", DEPLOY_ROLE_OID) && exists("users", DEPLOY_USER_OID), "both objects created");

        // Re-plan now that they exist -> nothing to change -> exit 0.
        CliResult again = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--create-if-absent", "--plan", dir.resolve("plan2").toString());
        assertEquals(0, again.exit, again.out + again.err);
    }

    @Test
    void deployPolicyChangesThePerObjectStrategy(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteDeployObjects();

        // Seed a role that carries a description.
        Path seed = java.nio.file.Files.createDirectories(dir.resolve("seed"));
        Files.writeString(seed.resolve("r.xml"), role(DEPLOY_ROLE_OID, "IT Deploy Role", "seeded"), StandardCharsets.UTF_8);
        run("deploy", seed.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--create-if-absent", "--yes");

        // Desired state omits the description. Under the default --scope present it is left alone (in-sync).
        Path tree = java.nio.file.Files.createDirectories(dir.resolve("tree").resolve("roles"));
        Files.writeString(tree.resolve("r.xml"), role(DEPLOY_ROLE_OID, "IT Deploy Role", null), StandardCharsets.UTF_8);
        CliResult present = run("deploy", dir.resolve("tree").toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--dry-run");
        assertEquals(0, present.exit, present.out + present.err);

        // A policy forcing scope=full on the role makes it a full replace -> the description drifts -> exit 1.
        Path policy = dir.resolve("policy.txt");
        Files.writeString(policy, "RoleType/* scope=full\n", StandardCharsets.UTF_8);
        CliResult full = run("deploy", dir.resolve("tree").toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--policy", policy.toString(), "--dry-run");
        assertEquals(1, full.exit, full.out + full.err);
    }

    @Test
    void diffMatchByNameResolvesAnOidlessObject(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteDeployObjects();

        // Seed a role with an OID, name, and description.
        File seed = dir.resolve("seed.xml").toFile();
        Files.writeString(seed.toPath(), role(DEPLOY_ROLE_OID, "IT Deploy Role", "orig"), StandardCharsets.UTF_8);
        run("deploy", seed.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--create-if-absent", "--yes");

        // An OID-less desired with the same name and a changed description.
        File oidless = dir.resolve("oidless.xml").toFile();
        Files.writeString(oidless.toPath(), role(null, "IT Deploy Role", "changed"), StandardCharsets.UTF_8);

        // Name-matching is the default, so a bare diff resolves it and sees the drift.
        CliResult matched = run("diff", oidless.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--format", "summary");
        assertEquals(1, matched.exit, matched.err);
        assertTrue(matched.out.contains("replace description"), matched.out);

        // --no-match-by-name opts out: the object is found (a create must not duplicate its name) but not
        // patched, so there is no change to show — an error, not an empty "in sync".
        CliResult optedOut = run("diff", oidless.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--no-match-by-name");
        assertEquals(2, optedOut.exit);
        assertTrue(optedOut.err.contains("--no-match-by-name rules out"), optedOut.err);
        assertTrue(optedOut.out.isBlank(), "no payload for a skipped object: " + optedOut.out);

        // The old spelling still parses and still means "match by name" — scripts predate the default.
        CliResult legacy = run("diff", oidless.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--match-by-name", "--format", "summary");
        assertEquals(1, legacy.exit, legacy.err);
        assertTrue(legacy.out.contains("replace description"), legacy.out);
    }

    @Test
    void pruneRemovesDroppedValuesInTheSamePatchAsTheChangeAndRollsBack(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteDeployObjects();
        assertEquals(0, run("init", "--create-state", "--url", HOST, "-u", USER, "-p", PASSWORD).exit);

        // A tracked tree: a git repository of its own, so its identity (root commit + path) is fresh.
        Path tree = Files.createDirectories(dir.resolve("tree"));
        Path file = tree.resolve("role.xml");
        Files.writeString(file, trackedRole(List.of("alpha", "beta"), SUPERUSER_ROLE_OID), StandardCharsets.UTF_8);
        assumeTrue(git(tree, "init", "-q", ".") == 0, "git not available — skipping");
        git(tree, "config", "user.email", "test@example.invalid");
        git(tree, "config", "user.name", "midgie test");
        commit(tree, "v1");
        Path plans = dir.resolve("rollback");
        CliResult first = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--rollback-plan", plans.toString());
        assertEquals(0, first.exit, first.err);
        assertTrue(first.out.contains("Recorded revision"), first.out);

        // v2 drops 'beta' and retargets the inducement. Under merge the retarget is an add of the new entry
        // (an inducement has no key to match on), so only the removal of the old one — in the same PATCH —
        // keeps the role from holding both.
        Files.writeString(file, trackedRole(List.of("alpha", "gamma"), END_USER_ROLE_OID), StandardCharsets.UTF_8);
        commit(tree, "v2");
        CliResult preview = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--multivalue", "merge", "--prune", "--dry-run", "--format", "xml");
        assertEquals(1, preview.exit, preview.err);
        assertTrue(preview.out.contains("beta") && preview.out.contains(SUPERUSER_ROLE_OID),
                "the one PATCH body carries the removals too: " + preview.out);
        assertTrue(preview.err.contains("removing 2 value(s)"), preview.err);

        CliResult second = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--multivalue", "merge", "--prune", "--yes", "--rollback-plan", plans.toString());
        assertEquals(0, second.exit, second.out + second.err);
        assertTrue(second.out.contains("1 patched"), second.out);
        String live = client.getObject("roles", DEPLOY_ROLE_OID);
        assertFalse(live.contains(">beta<"), "the dropped value is gone:\n" + live);
        assertTrue(live.contains(">gamma<"), "the added one is there:\n" + live);
        assertEquals(1, live.split("<inducement").length - 1, "one inducement, not old and new:\n" + live);
        assertTrue(live.contains(END_USER_ROLE_OID) && !live.contains(SUPERUSER_ROLE_OID), live);

        CliResult again = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--multivalue", "merge", "--prune", "--dry-run");
        assertEquals(0, again.exit, "settled after one run: " + again.out + again.err);

        // The rollback plan holds one inverse for the object, removals included.
        CliResult back = run("rollback", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--rollback-plan", plans.toString());
        assertEquals(0, back.exit, back.out + back.err);
        String restored = client.getObject("roles", DEPLOY_ROLE_OID);
        assertTrue(restored.contains(">beta<") && restored.contains(SUPERUSER_ROLE_OID),
                "rollback puts the removed values back:\n" + restored);
        assertFalse(restored.contains(">gamma<") || restored.contains(END_USER_ROLE_OID), restored);
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

    private static boolean testUserExists() {
        try {
            client.getObject("users", TEST_OID);
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

    private static void deleteDeployObjects() {
        tryDelete("users", DEPLOY_USER_OID);
        tryDelete("roles", DEPLOY_ROLE_OID);
        tryDelete("roles", DEPLOY_MATCH_OID);
        deleteByName("roles", COMBO_NAME);
    }

    private static void tryDelete(String type, String oid) {
        try {
            client.deleteObject(type, oid);
        } catch (Exception ignored) {
            // absent (404) or rig down
        }
    }

    /** Delete every object of {@code type} whose name matches — for cleaning up server-assigned OIDs. */
    private static void deleteByName(String type, String name) {
        try {
            String query = "<query xmlns=\"http://prism.evolveum.com/xml/ns/public/query-3\">"
                    + "<filter><equal><path>name</path><value>" + name + "</value></equal></filter></query>";
            String list = client.searchObjects(type, query);
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile("oid=\"([0-9a-fA-F-]{36})\"").matcher(list);
            while (matcher.find()) {
                tryDelete(type, matcher.group(1));
            }
        } catch (Exception ignored) {
            // search unsupported / rig down — nothing to clean up
        }
    }

    private static boolean exists(String type, String oid) {
        try {
            client.getObject(type, oid);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeDeployTree(Path dir) throws IOException {
        Path roles = java.nio.file.Files.createDirectories(dir.resolve("roles"));
        Path users = java.nio.file.Files.createDirectories(dir.resolve("users"));
        Files.writeString(roles.resolve("base.xml"), role(DEPLOY_ROLE_OID, "IT Deploy Role", null),
                StandardCharsets.UTF_8);
        Files.writeString(users.resolve("member.xml"),
                "<user xmlns=\"" + NS + "\" xmlns:c=\"" + NS + "\" oid=\"" + DEPLOY_USER_OID + "\">\n"
                        + "  <name>zz-it-deploy-user</name>\n"
                        + "  <assignment><targetRef oid=\"" + DEPLOY_ROLE_OID + "\" type=\"c:RoleType\"/></assignment>\n"
                        + "</user>",
                StandardCharsets.UTF_8);
    }

    /** A lookup table with three rows keyed by {@code key} — the shape whose rows the server withholds. */
    private static File writeLookupTable(Path dir, String tag, String expiredLabel) throws IOException {
        String xml = "<lookupTable xmlns=\"" + NS + "\" oid=\"" + LOOKUP_OID + "\">\n"
                + "  <name>zz-it-lookup</name>\n"
                + "  <row><key>active</key><label>Aktiv</label></row>\n"
                + "  <row><key>planned</key><label>Geplant</label></row>\n"
                + "  <row><key>expired</key><label>" + expiredLabel + "</label></row>\n"
                + "</lookupTable>";
        Path path = dir.resolve("lookup-" + tag + ".xml");
        Files.writeString(path, xml, StandardCharsets.UTF_8);
        return path.toFile();
    }

    /** A user whose {@code adminGuiConfiguration} holds two panels keyed by {@code identifier}. */
    private static File writeKeyedPanels(Path dir, String tag, int assignmentsOrder) throws IOException {
        String xml = "<user xmlns=\"" + NS + "\" xmlns:c=\"" + NS + "\" oid=\"" + TEST_OID + "\">\n"
                + "  <name>zz-it-user</name>\n"
                + "  <adminGuiConfiguration><objectDetails>\n"
                + "    <objectDetailsPage>\n"
                + "      <type>c:UserType</type>\n"
                + "      <panel><identifier>basic</identifier><displayOrder>10</displayOrder></panel>\n"
                + "      <panel><identifier>assignments</identifier><displayOrder>"
                + assignmentsOrder + "</displayOrder></panel>\n"
                + "    </objectDetailsPage>\n"
                + "  </objectDetails></adminGuiConfiguration>\n"
                + "</user>";
        Path path = dir.resolve("panels-" + tag + ".xml");
        Files.writeString(path, xml, StandardCharsets.UTF_8);
        return path.toFile();
    }

    /** The container ids midPoint assigned, in document order — what an in-place update has to preserve. */
    private static List<String> containerIds(String xml) {
        List<String> ids = new ArrayList<>();
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("<(?:objectDetailsPage|panel) id=\"(\\d+)\"").matcher(xml);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    private static String trackedRole(List<String> subtypes, String inducedRoleOid) {
        StringBuilder xml = new StringBuilder("<role xmlns=\"" + NS + "\" oid=\"" + DEPLOY_ROLE_OID + "\">\n"
                + "  <name>IT Deploy Role</name>\n");
        for (String subtype : subtypes) {
            xml.append("  <subtype>").append(subtype).append("</subtype>\n");
        }
        return xml.append("  <inducement>\n    <targetRef oid=\"").append(inducedRoleOid)
                .append("\" type=\"RoleType\"/>\n  </inducement>\n</role>").toString();
    }

    private static void commit(Path repo, String message) throws Exception {
        git(repo, "add", "-A");
        git(repo, "commit", "-qm", message);
    }

    private static int git(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor();
        } catch (IOException e) {
            return -1; // no git on the PATH
        }
    }

    private static String role(String oid, String name, String description) {
        return "<role xmlns=\"" + NS + "\"" + (oid != null ? " oid=\"" + oid + "\"" : "") + ">\n"
                + "  <name>" + name + "</name>\n"
                + (description != null ? "  <description>" + description + "</description>\n" : "")
                + "</role>";
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
