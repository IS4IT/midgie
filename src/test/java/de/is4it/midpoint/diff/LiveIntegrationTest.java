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
    private static final String TEMPLATE_OID = "a17e57e5-0000-0000-0000-0000000000d5";
    private static final String GUI_ROLE_OID = "a17e57e5-0000-0000-0000-0000000000d6";
    private static final String SMART_ROLE_OID = "a17e57e5-0000-0000-0000-0000000000d7";
    // The staging tree (docs/staging.md): objects that refer to the rig's stage constants.
    private static final String STAGE_TEMPLATE_OID = "a17e57e5-0000-0000-0000-0000000000d8";
    private static final String STAGE_ARCHETYPE_OID = "a17e57e5-0000-0000-0000-0000000000d9";
    private static final String STAGE_USER_OID = "a17e57e5-0000-0000-0000-0000000000da";
    private static final String STAGE_RESOURCE_OID = "a17e57e5-0000-0000-0000-0000000000db";
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
        assertTrue(diff.out.contains("panel[") && diff.out.contains("displayOrder"),
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
    void aPageWithAnUnnamedPanelIsStillEditedInPlace(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteTestUser();

        // One panel without an <identifier> used to push the whole page to real-value: any edit replaced it, and
        // midPoint handed out fresh ids for the page and every panel on it. The page is paired by its type now,
        // the keyed panel by its identifier, and only the unnamed one is compared by content.
        File created = writeKeyedPanels(dir, "v1", 20, true);
        assertEquals(0, run("deploy", created.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--yes").exit);
        List<String> idsBefore = containerIds(client.getObject("users", TEST_OID));
        assertEquals(4, idsBefore.size(), "the page and its three panels carry ids: " + idsBefore);

        File edited = writeKeyedPanels(dir, "v2", 25, true);
        assertEquals(0, run("deploy", edited.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes").exit);
        String after = client.getObject("users", TEST_OID);
        assertEquals(idsBefore, containerIds(after), "the page and its panels keep their ids:\n" + after);
        assertTrue(after.contains("<displayOrder>25</displayOrder>"), after);
        assertEquals(0, run("diff", edited.getPath(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--format", "summary").exit, "and the object is in sync afterwards");
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
    void anInducementNamingItsTargetByFilterStaysInSyncOnceResolved(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        tryDelete("roles", SMART_ROLE_OID);
        // An id-less inducement is compared as a whole value, so the resolved targetRef made the whole entry
        // differ: every deploy deleted it and added one whose reference named nothing.
        Path file = dir.resolve("smart-role.xml");
        Files.writeString(file, "<role xmlns=\"" + NS + "\" xmlns:c=\"" + NS + "\""
                + " xmlns:q=\"http://prism.evolveum.com/xml/ns/public/query-3\" oid=\"" + SMART_ROLE_OID + "\">"
                + "<name>zz-it-smart-role</name><inducement><targetRef type=\"c:RoleType\"><filter><q:equal>"
                + "<q:path>c:name</q:path><q:value>End user</q:value></q:equal></filter></targetRef></inducement>"
                + "</role>", StandardCharsets.UTF_8);

        CliResult create = run("deploy", file.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes");
        assertEquals(0, create.exit, create.out + create.err);
        assertTrue(client.getObject("roles", SMART_ROLE_OID).contains(END_USER_ROLE_OID),
                "midPoint should have resolved the filter at import");

        CliResult again = run("deploy", file.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes");
        assertEquals(0, again.exit, again.out + again.err);
        assertTrue(again.out.contains("0 patched, 1 in-sync"),
                "the resolved inducement must read as in sync:\n" + again.out);
        assertTrue(client.getObject("roles", SMART_ROLE_OID).contains(END_USER_ROLE_OID), "and keep its OID");
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
    void aStageConstantResolvesOnTheServerWhileTheTreeStaysInSync(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteStageObjects();
        // docs/staging.md: the tree names a constant and the server holds its value. The rig sets midgieStage
        // directly and midgieStageFromFile through _FILE (docker/docker-compose.yml); a template mapping
        // copies both onto a user, which is where the evaluated values can be seen.
        writeStageTree(dir);

        CliResult create = run("deploy", dir.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes");
        assertEquals(0, create.exit, create.out + create.err);
        String user = client.getObject("users", STAGE_USER_OID);
        assertTrue(user.contains("<costCenter>rig</costCenter>"),
                "MP_SET_midpoint_constants_midgieStage should reach the mapping:\n" + user);
        assertTrue(user.contains("<telephoneNumber>rig-from-file</telephoneNumber>"),
                "the _FILE constant should be read from its file:\n" + user);
        assertTrue(client.getObject("objectTemplates", STAGE_TEMPLATE_OID).contains("<const>midgieStage</const>"),
                "the server stores the constant's name, not its value");

        CliResult again = run("deploy", dir.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes");
        assertEquals(0, again.exit, again.out + again.err);
        assertTrue(again.out.contains("0 patched, 3 in-sync"),
                "values the server computed from constants must not read as drift:\n" + again.out);
    }

    @Test
    void aResourceKeepsItsConstantAndAHardCodedHostIsDrift(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        tryDelete("resources", STAGE_RESOURCE_OID);
        // The docs/staging.md example: a connector property given as <const>. Nothing listens on the host it
        // evaluates to; the resource is only stored and read back, never connected.
        Path file = dir.resolve("resource.xml");
        Files.writeString(file, stageResource("<expression><const>midgieStage</const></expression>"),
                StandardCharsets.UTF_8);

        CliResult create = run("deploy", file.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes");
        assertEquals(0, create.exit, create.out + create.err);
        // Read through a search, as midgie does: midPoint tries to connect on every read of a resource, and a
        // GET whose connection fails answers 250 with an OperationResult instead of the object.
        String stored = client.searchObjects("resources",
                "<query xmlns=\"http://prism.evolveum.com/xml/ns/public/query-3\"><filter><equal><path>name</path>"
                        + "<value>zz-it-stage-resource</value></equal></filter></query>");
        assertTrue(stored.contains("<const>midgieStage</const>"),
                "the server stores the expression in the connector configuration:\n" + stored);

        CliResult inSync = run("diff", file.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--format", "summary");
        assertEquals(0, inSync.exit, "the same tree must be in sync:\n" + inSync.out + inSync.err);

        // A server that holds the value hard-coded in place of the constant: that is the drift a stage diff is
        // for. Created rather than patched in, so the check does not depend on a connector-property PATCH.
        tryDelete("resources", STAGE_RESOURCE_OID);
        Path hardCoded = dir.resolve("hard-coded.xml");
        Files.writeString(hardCoded, stageResource("ldap.rig.example"), StandardCharsets.UTF_8);
        CliResult recreate = run("deploy", hardCoded.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--yes");
        assertEquals(0, recreate.exit, recreate.out + recreate.err);
        CliResult drift = run("diff", file.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--format", "summary");
        assertEquals(1, drift.exit, drift.out + drift.err);
        assertTrue(drift.out.contains("host"), "the hard-coded host must surface:\n" + drift.out);
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
    void liveVersionIsReadableAndServedByTheBundledSchema() throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");

        PrismContext prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
        String live = VersionSupport.liveVersion(prismContext, client);
        String bundled = VersionSupport.compileTimeVersion();
        String oldest = VersionSupport.oldestSupportedVersion();

        assertNotNull(live, "should read the server version from GET /nodes");
        // The rig runs MP_VER, the bundled version, unless overridden to test the line before it.
        assertTrue(VersionSupport.servesVersion(bundled, oldest, live),
                "rig version " + live + " should be one the bundled schema " + bundled + " serves ("
                        + VersionSupport.servedRange(bundled, oldest) + ")");
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
        // The user's assignment names the role it points at — from the tree, and as one this deploy creates,
        // since it is not on the server yet — and the comment does not stop the file from being replayed.
        String userPlan;
        try (java.util.stream.Stream<Path> files = Files.list(plan)) {
            userPlan = Files.readString(files.filter(p -> p.toString().contains("-users-")).findFirst().orElseThrow());
        }
        assertTrue(userPlan.contains("<!-- RoleType \"IT Deploy Role\" (not on the server yet, deployed with this tree) -->"),
                userPlan);

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
    void aTrackedTreeMergesAndRemovesWhatItDroppedInTheSamePatchAndRollsBack(@TempDir Path dir)
            throws Exception {
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
        assertTrue(first.out.contains("Recorded 1 applied object(s)"), first.out);

        // v2 drops 'beta' and retargets the inducement. A tracked tree is on merge by default, where the
        // retarget is an add of the new entry (an inducement has no key to match on), so only the removal of
        // the old one — in the same PATCH, also the default — keeps the role from holding both.
        Files.writeString(file, trackedRole(List.of("alpha", "gamma"), END_USER_ROLE_OID), StandardCharsets.UTF_8);
        commit(tree, "v2");
        CliResult preview = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--dry-run", "--format", "xml");
        assertEquals(1, preview.exit, preview.err);
        assertTrue(preview.out.contains("beta") && preview.out.contains(SUPERUSER_ROLE_OID),
                "the one PATCH body carries the removals too: " + preview.out);
        assertTrue(preview.err.contains("removing 2 value(s)"), preview.err);

        CliResult diff = run("diff", file.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD);
        assertEquals(preview.out, diff.out, "diff reads the same history and sends the same body");
        assertTrue(diff.out.contains("<!-- RoleType \"Superuser\" -->")
                        && diff.out.contains("<!-- RoleType \"End user\" -->"),
                "the references are named from the server: " + diff.out);

        CliResult second = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--yes", "--rollback-plan", plans.toString());
        assertEquals(0, second.exit, second.out + second.err);
        assertTrue(second.out.contains("1 patched"), second.out);
        String live = client.getObject("roles", DEPLOY_ROLE_OID);
        assertFalse(live.contains(">beta<"), "the dropped value is gone:\n" + live);
        assertTrue(live.contains(">gamma<"), "the added one is there:\n" + live);
        assertEquals(1, live.split("<inducement").length - 1, "one inducement, not old and new:\n" + live);
        assertTrue(live.contains(END_USER_ROLE_OID) && !live.contains(SUPERUSER_ROLE_OID), live);

        CliResult again = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--dry-run");
        assertEquals(0, again.exit, "settled after one run: " + again.out + again.err);

        // The rollback plan holds one inverse for the object, removals included, named for the reviewer.
        try (java.util.stream.Stream<Path> files = Files.walk(plans)) {
            String inverse = Files.readString(files.filter(p -> p.toString().endsWith(".patch.xml")).findFirst()
                    .orElseThrow());
            assertTrue(inverse.contains("<!-- RoleType \"IT Deploy Role\" -->")
                    && inverse.contains("<!-- RoleType \"Superuser\" -->"), inverse);
        }
        CliResult back = run("rollback", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--rollback-plan", plans.toString());
        assertEquals(0, back.exit, back.out + back.err);
        String restored = client.getObject("roles", DEPLOY_ROLE_OID);
        assertTrue(restored.contains(">beta<") && restored.contains(SUPERUSER_ROLE_OID),
                "rollback puts the removed values back:\n" + restored);
        assertFalse(restored.contains(">gamma<") || restored.contains(END_USER_ROLE_OID), restored);

        // The tracking rows went back with the object, so deploying v2 again removes v1's values again rather
        // than reading them as someone else's and keeping them beside v2's under merge.
        CliResult redeploy = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--yes", "--no-rollback-plan");
        assertEquals(0, redeploy.exit, redeploy.out + redeploy.err);
        String redeployed = client.getObject("roles", DEPLOY_ROLE_OID);
        assertFalse(redeployed.contains(">beta<"), "v2 dropped beta:\n" + redeployed);
        assertEquals(1, redeployed.split("<inducement").length - 1, "one inducement, not old and new:\n" + redeployed);
        assertTrue(redeployed.contains(END_USER_ROLE_OID) && !redeployed.contains(SUPERUSER_ROLE_OID), redeployed);
    }

    @Test
    void historyThatNoLongerCombinesIsReportedEvenUnderQuiet(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteDeployObjects();
        assertEquals(0, run("init", "--create-state", "--url", HOST, "-u", USER, "-p", PASSWORD).exit);

        Path tree = Files.createDirectories(dir.resolve("tree"));
        Path a = tree.resolve("a.xml");
        Path b = tree.resolve("b.xml");
        Files.writeString(a, splitRole("<description>x</description><subtype>alpha</subtype><subtype>beta</subtype>"),
                StandardCharsets.UTF_8);
        Files.writeString(b, splitRole("<description>x</description>"), StandardCharsets.UTF_8);
        assumeTrue(git(tree, "init", "-q", ".") == 0, "git not available — skipping");
        git(tree, "config", "user.email", "test@example.invalid");
        git(tree, "config", "user.name", "midgie test");
        commit(tree, "v1");
        assertEquals(0, run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--no-rollback-plan").exit);

        // b.xml alone moves on, so the two files' records now disagree about the description.
        Files.writeString(b, splitRole("<description>y</description>"), StandardCharsets.UTF_8);
        commit(tree, "v2");
        assertEquals(0, run("deploy", b.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--no-rollback-plan").exit);

        // a.xml catches up and drops beta. Detection cannot combine the records; that used to pass in silence.
        Files.writeString(a, splitRole("<description>y</description><subtype>alpha</subtype>"), StandardCharsets.UTF_8);
        commit(tree, "v3");
        CliResult third = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--no-rollback-plan", "--quiet");
        assertEquals(0, third.exit, third.out + third.err);
        assertTrue(third.err.contains("contradict each other") && third.err.contains("a.xml, b.xml"), third.err);
        assertTrue(third.err.contains("check the object for values the tree no longer declares"), third.err);

        // The records moved on to consistent content, so the next run has nothing to complain about.
        CliResult fourth = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--dry-run");
        assertFalse(fourth.err.contains("contradict"), fourth.err);
    }

    private static String splitRole(String body) {
        return "<role xmlns=\"" + NS + "\" oid=\"" + DEPLOY_ROLE_OID + "\"><name>IT Deploy Role</name>" + body
                + "</role>";
    }

    @Test
    void aPlanAndApplyPipelineMovesItsRowsOnAndLeavesANet(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteDeployObjects();
        assertEquals(0, run("init", "--create-state", "--url", HOST, "-u", USER, "-p", PASSWORD).exit);

        Path tree = Files.createDirectories(dir.resolve("tree"));
        Path file = tree.resolve("role.xml");
        Files.writeString(file, trackedRole(List.of("alpha"), SUPERUSER_ROLE_OID), StandardCharsets.UTF_8);
        assumeTrue(git(tree, "init", "-q", ".") == 0, "git not available — skipping");
        git(tree, "config", "user.email", "test@example.invalid");
        git(tree, "config", "user.name", "midgie test");
        commit(tree, "v1");
        assertEquals(0, run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--no-rollback-plan").exit);
        Path nets = dir.resolve("nets");

        // v2 adds gamma, through a reviewed plan.
        Files.writeString(file, trackedRole(List.of("alpha", "gamma"), SUPERUSER_ROLE_OID), StandardCharsets.UTF_8);
        commit(tree, "v2");
        planAndApply(tree, dir.resolve("plan2"), nets);
        assertTrue(client.getObject("roles", DEPLOY_ROLE_OID).contains(">gamma<"));

        // v3 drops it again. Detection compares against what v2's apply recorded; with the rows still at v1,
        // gamma was never deployed as far as they knew, and merge kept it.
        Files.writeString(file, trackedRole(List.of("alpha"), SUPERUSER_ROLE_OID), StandardCharsets.UTF_8);
        commit(tree, "v3");
        planAndApply(tree, dir.resolve("plan3"), nets);
        assertFalse(client.getObject("roles", DEPLOY_ROLE_OID).contains(">gamma<"), "v3 dropped gamma");

        // The apply left a net, found where a deploy's would be, and it moves the rows back too.
        CliResult back = run("rollback", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--rollback-plan", nets.toString());
        assertEquals(0, back.exit, back.out + back.err);
        assertTrue(client.getObject("roles", DEPLOY_ROLE_OID).contains(">gamma<"), "rollback puts gamma back");
        CliResult again = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--no-rollback-plan");
        assertEquals(0, again.exit, again.out + again.err);
        assertFalse(client.getObject("roles", DEPLOY_ROLE_OID).contains(">gamma<"),
                "the rows went back to v2, so deploying v3 removes gamma again");
    }

    private static void planAndApply(Path tree, Path plan, Path nets) {
        CliResult planned = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--plan", plan.toString());
        assertEquals(1, planned.exit, planned.out + planned.err);
        assertTrue(Files.isRegularFile(plan.resolve(PlanState.FILE)), "the plan records its tracking rows");
        CliResult applied = run("deploy", "--apply-plan", plan.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--yes", "--rollback-plan", nets.toString());
        assertEquals(0, applied.exit, applied.out + applied.err);
        assertTrue(applied.out.contains("Rollback plan:"), applied.out);
    }

    @Test
    void mergeKeepsWhatSomeoneElseAddedAndSaysSo(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteDeployObjects();
        assertEquals(0, run("init", "--create-state", "--url", HOST, "-u", USER, "-p", PASSWORD).exit);

        Path tree = Files.createDirectories(dir.resolve("tree"));
        Path file = tree.resolve("role.xml");
        Files.writeString(file, trackedRole(List.of("alpha"), SUPERUSER_ROLE_OID), StandardCharsets.UTF_8);
        assumeTrue(git(tree, "init", "-q", ".") == 0, "git not available — skipping");
        git(tree, "config", "user.email", "test@example.invalid");
        git(tree, "config", "user.name", "midgie test");
        commit(tree, "v1");
        assertEquals(0, run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--no-rollback-plan").exit);

        // Someone adds an inducement on the server — here through an untracked file on explicit merge.
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve("extra.xml"), "<role xmlns=\"" + NS + "\" oid=\"" + DEPLOY_ROLE_OID
                + "\">\n  <name>IT Deploy Role</name>\n  <inducement>\n    <targetRef oid=\"" + END_USER_ROLE_OID
                + "\" type=\"RoleType\"/>\n  </inducement>\n</role>", StandardCharsets.UTF_8);
        assertEquals(0, run("deploy", elsewhere.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--multivalue", "merge", "--yes", "--no-rollback-plan").exit);

        // The tracked tree keeps it (merge), and names it rather than passing over it in silence.
        CliResult kept = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--dry-run");
        assertEquals(0, kept.exit, kept.out + kept.err);
        // In the object's own block, under its result row, on the row's stream.
        String[] lines = kept.out.split("\\R");
        int row = java.util.stream.IntStream.range(0, lines.length)
                .filter(i -> lines[i].contains("RoleType/IT Deploy Role")).findFirst().orElse(-1);
        assertTrue(row >= 0 && row + 1 < lines.length && lines[row + 1].contains("note: keeps live value(s) the tree "
                + "does not list"), "the note follows its object's row:\n" + kept.out);
        assertTrue(kept.out.contains("inducement ×1"), kept.out);
        assertFalse(kept.err.contains("keeps live value(s)"), "and is not left on stderr:\n" + kept.err);

        // Owning the list is one flag (or policy rule) away, and then it is a change to preview.
        CliResult owned = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--dry-run",
                "--multivalue", "replace");
        assertEquals(1, owned.exit, owned.out + owned.err);
    }

    @Test
    void aTrackedTreeOwnsOnlyTheNestedItemsItDeployed(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteDeployObjects();
        assertEquals(0, run("init", "--create-state", "--url", HOST, "-u", USER, "-p", PASSWORD).exit);

        // Three mappings, paired by name. v1 deploys lifecycleState on 'given' and a description on 'full';
        // 'family' carries neither.
        Path tree = Files.createDirectories(dir.resolve("tree"));
        Path file = tree.resolve("template.xml");
        Files.writeString(file, template(templateMapping("given", "<lifecycleState>draft</lifecycleState>")
                + templateMapping("family", "") + templateMapping("full", "<description>v1 text</description>")),
                StandardCharsets.UTF_8);
        assumeTrue(git(tree, "init", "-q", ".") == 0, "git not available — skipping");
        git(tree, "config", "user.email", "test@example.invalid");
        git(tree, "config", "user.name", "midgie test");
        commit(tree, "v1");
        Path plans = dir.resolve("rollback");
        CliResult first = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--rollback-plan", plans.toString());
        assertEquals(0, first.exit, first.out + first.err);
        String deployed = client.getObject("objectTemplates", TEMPLATE_OID);
        String givenId = mappingId(deployed, "given");
        assertNotNull(givenId, deployed);

        // Someone on the server gives 'family' a lifecycleState the tree never had, and rewrites the
        // description the tree deployed on 'full' — here through an untracked file on explicit merge.
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve("admin.xml"), template(
                templateMapping("family", "<lifecycleState>proposed</lifecycleState>")
                        + templateMapping("full", "<description>admin text</description>")), StandardCharsets.UTF_8);
        CliResult admin = run("deploy", elsewhere.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--multivalue", "merge", "--yes", "--no-rollback-plan");
        assertEquals(0, admin.exit, admin.out + admin.err);

        // v2 drops what v1 deployed on 'given' and 'full'.
        Files.writeString(file, template(templateMapping("given", "") + templateMapping("family", "")
                + templateMapping("full", "")), StandardCharsets.UTF_8);
        commit(tree, "v2");
        CliResult preview = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--dry-run", "--format", "xml");
        assertEquals(1, preview.exit, preview.out + preview.err);
        assertTrue(preview.err.contains("keeps live value(s) the tree does not list")
                && preview.err.contains("mapping/lifecycleState ×1"), "(a) kept and reported: " + preview.err);
        assertTrue(preview.err.contains("mapping[name=full]/description")
                && preview.err.contains("changed on the server after it was deployed"),
                "(c) kept with a note: " + preview.err);
        assertFalse(preview.out.contains("proposed") || preview.out.contains("admin text"),
                "nothing someone else owns is in the PATCH: " + preview.out);

        CliResult second = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--rollback-plan", plans.toString());
        assertEquals(0, second.exit, second.out + second.err);
        String live = client.getObject("objectTemplates", TEMPLATE_OID);
        assertEquals(givenId, mappingId(live, "given"), "(b) the mapping survives with its id:\n" + live);
        assertFalse(mapping(live, "given").contains("lifecycleState"), "(b) the dropped item is gone:\n" + live);
        assertTrue(mapping(live, "family").contains("proposed"), "(a) the server's item stays:\n" + live);
        assertTrue(mapping(live, "full").contains("admin text"), "(c) the changed item stays:\n" + live);
        assertEquals(3, live.split("<mapping").length - 1, "no mapping removed or duplicated:\n" + live);

        CliResult again = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--dry-run");
        assertEquals(0, again.exit, "settled after one run: " + again.out + again.err);

        // (d) The rollback puts back what the tree removed.
        CliResult back = run("rollback", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--rollback-plan", plans.toString());
        assertEquals(0, back.exit, back.out + back.err);
        String restored = client.getObject("objectTemplates", TEMPLATE_OID);
        assertTrue(mapping(restored, "given").contains("draft"), "rollback restores the item:\n" + restored);
        assertEquals(givenId, mappingId(restored, "given"), restored);
    }

    @Test
    void twoLevelsDownATrackedTreeOwnsOnlyWhatItDeployed(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteDeployObjects();
        assertEquals(0, run("init", "--create-state", "--url", HOST, "-u", USER, "-p", PASSWORD).exit);

        // A role's user details page (keyed by type) holding two panels (keyed by identifier): every delete
        // below has to be resolved through two of midPoint's container ids.
        Path tree = Files.createDirectories(dir.resolve("tree"));
        Path file = tree.resolve("role.xml");
        Files.writeString(file, guiRole(panel("first", "<description>v1 text</description>") + panel("second", "")),
                StandardCharsets.UTF_8);
        assumeTrue(git(tree, "init", "-q", ".") == 0, "git not available — skipping");
        git(tree, "config", "user.email", "test@example.invalid");
        git(tree, "config", "user.name", "midgie test");
        commit(tree, "v1");
        CliResult first = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--no-rollback-plan");
        assertEquals(0, first.exit, first.out + first.err);
        String deployed = client.getObject("roles", GUI_ROLE_OID);
        String pageId = idOf(deployed, "objectDetailsPage", "<type>");
        String firstId = idOf(deployed, "panel", "<identifier>first</identifier>");
        String secondId = idOf(deployed, "panel", "<identifier>second</identifier>");
        assertNotNull(pageId, deployed);
        assertNotNull(firstId, deployed);

        // Someone gives the second panel a description the tree never had — through an untracked file on
        // explicit merge, which leaves the first panel alone.
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve("admin.xml"), guiRole(panel("second", "<description>admin text</description>")),
                StandardCharsets.UTF_8);
        CliResult admin = run("deploy", elsewhere.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--multivalue", "merge", "--yes", "--no-rollback-plan");
        assertEquals(0, admin.exit, admin.out + admin.err);

        // v2 drops the description v1 deployed on the first panel.
        Files.writeString(file, guiRole(panel("first", "") + panel("second", "")), StandardCharsets.UTF_8);
        commit(tree, "v2");
        CliResult preview = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--dry-run", "--format", "xml");
        assertEquals(1, preview.exit, preview.out + preview.err);
        assertTrue(preview.out.contains("v1 text") && !preview.out.contains("admin text"),
                "only what the tree deployed is in the PATCH: " + preview.out);
        assertTrue(preview.err.contains("panel/description ×1"), "the server's item is kept and named: " + preview.err);

        CliResult second = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--no-rollback-plan");
        assertEquals(0, second.exit, second.out + second.err);
        String live = client.getObject("roles", GUI_ROLE_OID);
        assertFalse(live.contains("v1 text"), "the dropped item is gone:\n" + live);
        assertTrue(live.contains("admin text"), "the server's item stays:\n" + live);
        assertEquals(pageId, idOf(live, "objectDetailsPage", "<type>"), "the page keeps its id:\n" + live);
        assertEquals(firstId, idOf(live, "panel", "<identifier>first</identifier>"), "the panel keeps its id:\n" + live);
        assertEquals(secondId, idOf(live, "panel", "<identifier>second</identifier>"), live);
        assertEquals(2, live.split("<panel").length - 1, "no panel removed or duplicated:\n" + live);

        CliResult again = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--dry-run");
        assertEquals(0, again.exit, "settled after one run: " + again.out + again.err);
    }

    // --- per-file tracking ---

    @Test
    void aSingleFileDeployIsTrackedLikeItsFolder(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteDeployObjects();
        Path tree = trackedRepo(dir);
        Path file = Files.createDirectories(tree.resolve("roles")).resolve("role.xml");
        Files.writeString(file, trackedRole(List.of("alpha", "beta"), SUPERUSER_ROLE_OID), StandardCharsets.UTF_8);
        commit(tree, "v1");
        CliResult first = run("deploy", file.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--no-rollback-plan");
        assertEquals(0, first.exit, first.out + first.err);
        assertTrue(first.out.contains("Recorded 1 applied object(s)"), first.out);

        // Dropping a value and deploying the one file again removes it: the file is tracked on its own.
        Files.writeString(file, trackedRole(List.of("alpha"), SUPERUSER_ROLE_OID), StandardCharsets.UTF_8);
        commit(tree, "v2");
        CliResult second = run("deploy", file.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--no-rollback-plan");
        assertEquals(0, second.exit, second.out + second.err);
        assertFalse(client.getObject("roles", DEPLOY_ROLE_OID).contains(">beta<"), "the dropped value is removed");
        assertTrue(second.out.contains("1 value(s) removed"), "and counted in the summary: " + second.out);

        List<SyncState.Row> rows = stateRows();
        String ns = namespaceOf(tree);
        SyncState.Row fileRow = rows.stream().filter(r -> r.key().equals(ns + "/roles/role.xml")).findFirst()
                .orElseThrow();
        assertTrue(fileRow.value().contains("\"blob\":\"" + gitOut(tree, "rev-parse", "HEAD:roles/role.xml") + "\""),
                fileRow.value());
        assertEquals("RoleType \"IT Deploy Role\"", fileRow.label());
        SyncState.Row deployRow = rows.stream().filter(r -> r.key().equals("deploy:" + ns + "/roles/role.xml"))
                .findFirst().orElseThrow();
        assertTrue(deployRow.value().contains("\"patched\":1") && deployRow.value().contains("\"removed\":1")
                && deployRow.value().contains("\"by\":\"" + USER + "\""), deployRow.value());
    }

    @Test
    void aRunWithNothingToConfirmRecordsItsRevisionLikeOneWithYes(@TempDir Path dir) throws Exception {
        // Without --yes, a tree already in sync returned before the rows were written, and the revision the
        // next run judges removals against stayed where it was.
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteDeployObjects();
        Path tree = trackedRepo(dir);
        Path file = Files.createDirectories(tree.resolve("roles")).resolve("role.xml");
        Files.writeString(file, trackedRole(List.of("alpha"), SUPERUSER_ROLE_OID), StandardCharsets.UTF_8);
        commit(tree, "v1");
        CliResult first = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--no-rollback-plan");
        assertEquals(0, first.exit, first.out + first.err);

        Files.writeString(tree.resolve("NOTES.txt"), "not an object\n", StandardCharsets.UTF_8);
        commit(tree, "v2");
        CliResult second = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--no-rollback-plan");

        assertEquals(0, second.exit, second.out + second.err);
        assertTrue(second.out.contains("Recorded 1 applied object(s)"), second.out);
        String ns = namespaceOf(tree);
        SyncState.Row deployRow = stateRows().stream().filter(r -> r.key().equals("deploy:" + ns)).findFirst()
                .orElseThrow();
        assertTrue(deployRow.value().contains("\"commit\":\"" + gitOut(tree, "rev-parse", "HEAD") + "\"")
                && deployRow.value().contains("\"in-sync\":1"), deployRow.value());
    }

    @Test
    void anObjectThatWasNotAppliedKeepsItsRecordWhileTheRestMovesOn(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteDeployObjects();
        Path tree = trackedRepo(dir);
        Path roles = Files.createDirectories(tree.resolve("roles"));
        Files.writeString(roles.resolve("a.xml"), trackedRole(List.of("alpha", "beta"), SUPERUSER_ROLE_OID),
                StandardCharsets.UTF_8);
        Files.writeString(roles.resolve("b.xml"), oidlessRole(COMBO_NAME, List.of("x", "y")), StandardCharsets.UTF_8);
        commit(tree, "v1");
        assertEquals(0, run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--no-rollback-plan").exit);
        String bAtV1 = gitOut(tree, "rev-parse", "HEAD:roles/b.xml");

        // Both drop a value. Under --no-match-by-name the OID-less role, which exists by now, is skipped.
        Files.writeString(roles.resolve("a.xml"), trackedRole(List.of("alpha"), SUPERUSER_ROLE_OID),
                StandardCharsets.UTF_8);
        Files.writeString(roles.resolve("b.xml"), oidlessRole(COMBO_NAME, List.of("x")), StandardCharsets.UTF_8);
        commit(tree, "v2");
        CliResult partly = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--no-rollback-plan", "--no-match-by-name");
        assertEquals(0, partly.exit, partly.out + partly.err);
        assertTrue(partly.out.contains("1 object(s) not applied keep their earlier record"), partly.out);
        assertFalse(client.getObject("roles", DEPLOY_ROLE_OID).contains(">beta<"), "the applied one moved on");

        List<SyncState.Row> rows = stateRows();
        String ns = namespaceOf(tree);
        assertTrue(rowFor(rows, ns + "/roles/a.xml").contains(gitOut(tree, "rev-parse", "HEAD:roles/a.xml")),
                "a's row records v2");
        assertTrue(rowFor(rows, ns + "/roles/b.xml").contains(bAtV1), "b's row still records v1");

        // So a later full run still finds what b dropped.
        CliResult later = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--no-rollback-plan");
        assertEquals(0, later.exit, later.out + later.err);
        assertTrue(later.out.contains("1 value(s) removed"), "y is removed now: " + later.out);
    }

    @Test
    void aRenameWithADroppedValueIsAppliedByDeployingTheNewPathAlone(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteDeployObjects();
        Path tree = trackedRepo(dir);
        Path roles = Files.createDirectories(tree.resolve("roles"));
        Files.writeString(roles.resolve("a.xml"), trackedRole(List.of("alpha", "beta"), SUPERUSER_ROLE_OID),
                StandardCharsets.UTF_8);
        commit(tree, "v1");
        assertEquals(0, run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--no-rollback-plan").exit);

        // Renamed and edited in one commit; only the new path is deployed. The old path's row is what says beta
        // was deployed, so it has to be found although its path lies outside what this run deploys.
        git(tree, "mv", "roles/a.xml", "roles/b.xml");
        Files.writeString(roles.resolve("b.xml"), trackedRole(List.of("alpha"), SUPERUSER_ROLE_OID),
                StandardCharsets.UTF_8);
        commit(tree, "v2");
        CliResult moved = run("deploy", roles.resolve("b.xml").toString(), "--url", HOST, "-u", USER, "-p",
                PASSWORD, "--yes", "--no-rollback-plan");
        assertEquals(0, moved.exit, moved.out + moved.err);
        assertFalse(client.getObject("roles", DEPLOY_ROLE_OID).contains(">beta<"), "beta is removed");

        List<String> keys = stateRows().stream().map(SyncState.Row::key).toList();
        String ns = namespaceOf(tree);
        assertTrue(keys.contains(ns + "/roles/b.xml"), keys.toString());
        assertFalse(keys.contains(ns + "/roles/a.xml"), "the old path's row has been dealt with and is gone: " + keys);
    }

    @Test
    void deployingPartOfAnObjectSaysSoAndReadsNothingElseAsRemoved(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteDeployObjects();
        Path tree = trackedRepo(dir);
        Files.writeString(Files.createDirectories(tree.resolve("roles")).resolve("role.xml"),
                "<role xmlns=\"" + NS + "\" oid=\"" + DEPLOY_ROLE_OID + "\">\n  <name>IT Deploy Role</name>\n"
                        + "  <subtype>alpha</subtype>\n</role>", StandardCharsets.UTF_8);
        Files.writeString(Files.createDirectories(tree.resolve("extra")).resolve("role-extra.xml"),
                "<role xmlns=\"" + NS + "\" oid=\"" + DEPLOY_ROLE_OID + "\">\n  <name>IT Deploy Role</name>\n"
                        + "  <subtype>beta</subtype>\n</role>", StandardCharsets.UTF_8);
        commit(tree, "v1");
        assertEquals(0, run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--no-rollback-plan").exit);

        CliResult part = run("deploy", tree.resolve("roles").toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--yes", "--no-rollback-plan");
        assertEquals(0, part.exit, part.out + part.err);
        assertTrue(part.err.contains("also has 1 file(s) outside the deployed path (extra/role-extra.xml)"),
                part.err);
        assertTrue(client.getObject("roles", DEPLOY_ROLE_OID).contains(">beta<"),
                "what the other file contributes is not read as removed");
    }

    @Test
    void anObjectThatLeftTheTreeIsNamedLastAndLeftOnTheServer(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteDeployObjects();
        Path tree = trackedRepo(dir);
        Path roles = Files.createDirectories(tree.resolve("roles"));
        Files.writeString(roles.resolve("kept.xml"), role(DEPLOY_ROLE_OID, "IT Deploy Role", null),
                StandardCharsets.UTF_8);
        Files.writeString(roles.resolve("gone.xml"), role(DEPLOY_MATCH_OID, "IT Orphan Role", null),
                StandardCharsets.UTF_8);
        commit(tree, "v1");
        Path plans = dir.resolve("rollback");
        assertEquals(0, run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--rollback-plan", plans.toString()).exit);

        // The kept role changes too, so the run has a summary and a rollback plan for the note to come after.
        git(tree, "rm", "-q", "roles/gone.xml");
        Files.writeString(roles.resolve("kept.xml"), role(DEPLOY_ROLE_OID, "IT Deploy Role", "changed"),
                StandardCharsets.UTF_8);
        commit(tree, "v2");
        CliResult second = runInterleaved("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--yes", "--rollback-plan", plans.toString());
        assertEquals(0, second.exit, second.out);
        assertTrue(second.out.contains("Done:") && second.out.contains("Rollback plan:"), second.out);
        assertTrue(lastLine(second).contains("note: RoleType/IT Orphan Role (" + DEPLOY_MATCH_OID
                        + ") is no longer declared by the tree (")
                && lastLine(second).contains("gone.xml was removed)"),
                "the orphan is the last thing the run says, by type, name and OID:\n" + second.out);
        assertTrue(exists("roles", DEPLOY_MATCH_OID), "and the object itself is left on the server");
    }

    @Test
    void anObjectThatLeftTheTreeAndTheServerIsNotAskedToBeDeleted(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteDeployObjects();
        Path tree = trackedRepo(dir);
        Path roles = Files.createDirectories(tree.resolve("roles"));
        Files.writeString(roles.resolve("kept.xml"), role(DEPLOY_ROLE_OID, "IT Deploy Role", null),
                StandardCharsets.UTF_8);
        Files.writeString(roles.resolve("gone.xml"), role(DEPLOY_MATCH_OID, "IT Orphan Role", null),
                StandardCharsets.UTF_8);
        commit(tree, "v1");
        assertEquals(0, run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--no-rollback-plan").exit);
        git(tree, "rm", "-q", "roles/gone.xml");
        Files.writeString(roles.resolve("kept.xml"), role(DEPLOY_ROLE_OID, "IT Deploy Role", "changed"),
                StandardCharsets.UTF_8);
        commit(tree, "v2");

        // Planned while the role is still there, and deleted by hand between the plan and its apply.
        Path plan = dir.resolve("plan");
        CliResult planned = runInterleaved("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--plan", plan.toString());
        assertEquals(1, planned.exit, planned.out);
        assertTrue(lastLine(planned).contains("delete it yourself"), planned.out);
        client.deleteObject("roles", DEPLOY_MATCH_OID);

        CliResult preview = runInterleaved("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--dry-run");
        assertEquals(1, preview.exit, preview.out);
        assertTrue(lastLine(preview).endsWith("(roles/gone.xml was removed), and also does not exist on the server "
                + "anymore."), "a deploy looks the object up before asking for its delete:\n" + preview.out);

        CliResult applied = runInterleaved("deploy", "--apply-plan", plan.toString(), "--url", HOST, "-u", USER,
                "-p", PASSWORD, "--yes", "--no-rollback-plan");
        assertEquals(0, applied.exit, applied.out);
        assertTrue(lastLine(applied).contains("RoleType/IT Orphan Role (" + DEPLOY_MATCH_OID + ")")
                && lastLine(applied).endsWith("and also does not exist on the server anymore."),
                "so does the apply of a plan made while it was there:\n" + applied.out);
    }

    @Test
    void anObjectThatLeftTheTreeIsNamedAgainWhenThePlanIsApplied(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteDeployObjects();
        Path tree = trackedRepo(dir);
        Path roles = Files.createDirectories(tree.resolve("roles"));
        Files.writeString(roles.resolve("kept.xml"), role(DEPLOY_ROLE_OID, "IT Deploy Role", null),
                StandardCharsets.UTF_8);
        Files.writeString(roles.resolve("gone.xml"), role(DEPLOY_MATCH_OID, "IT Orphan Role", null),
                StandardCharsets.UTF_8);
        commit(tree, "v1");
        assertEquals(0, run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--no-rollback-plan").exit);

        git(tree, "rm", "-q", "roles/gone.xml");
        Files.writeString(roles.resolve("kept.xml"), role(DEPLOY_ROLE_OID, "IT Deploy Role", "changed"),
                StandardCharsets.UTF_8);
        commit(tree, "v2");
        Path plan = dir.resolve("plan");
        CliResult planned = runInterleaved("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--plan", plan.toString());
        assertEquals(1, planned.exit, planned.out);
        assertTrue(lastLine(planned).contains("is no longer declared by the tree ("), planned.out);

        // The apply job's log is often the only one anybody reads, and after it no run names the object again.
        CliResult applied = runInterleaved("deploy", "--apply-plan", plan.toString(), "--url", HOST, "-u", USER,
                "-p", PASSWORD, "--yes", "--rollback-plan", dir.resolve("nets").toString());
        assertEquals(0, applied.exit, applied.out);
        assertTrue(applied.out.contains("Rollback plan:"), applied.out);
        assertTrue(lastLine(applied).contains("is no longer declared by the tree (")
                && lastLine(applied).contains("gone.xml was removed)"),
                "the apply names the orphan last as well:\n" + applied.out);
        assertTrue(exists("roles", DEPLOY_MATCH_OID), "and leaves it on the server");

        CliResult after = runInterleaved("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD,
                "--yes", "--no-rollback-plan");
        assertEquals(0, after.exit, after.out);
        assertFalse(after.out.contains("is no longer declared by the tree"),
                "the apply let go of the orphan's rows:\n" + after.out);
    }

    @Test
    void aTreeRowFromAnEarlierVersionIsReadOnceAndReplacedByFileRows(@TempDir Path dir) throws Exception {
        assumeTrue(rigUp, "midPoint rig not reachable at " + HOST + " — skipping live integration tests");
        deleteDeployObjects();
        Path tree = trackedRepo(dir);
        Files.writeString(Files.createDirectories(tree.resolve("roles")).resolve("role.xml"),
                trackedRole(List.of("alpha", "beta"), SUPERUSER_ROLE_OID), StandardCharsets.UTF_8);
        commit(tree, "v1");
        String v1 = gitOut(tree, "rev-parse", "HEAD");
        assertEquals(0, run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--no-rollback-plan").exit);

        // Rewind the table to what an earlier version wrote: one row for the tree, holding its commit.
        String namespace = namespaceOf(tree);
        PrismContext prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
        com.evolveum.midpoint.prism.PrismObject<? extends com.evolveum.midpoint.prism.Objectable> table =
                SyncState.find(prismContext, client);
        SyncState.Changes rewind = new SyncState.Changes();
        for (SyncState.Row row : SyncState.rows(table)) {
            if (row.key().startsWith(namespace) || row.key().startsWith("deploy:" + namespace)) {
                rewind.delete(row.key());
            }
        }
        SyncState.apply(prismContext, client, table, rewind);
        SyncState.Changes legacy = new SyncState.Changes();
        legacy.set(namespace, v1, "an earlier version's tree row");
        SyncState.apply(prismContext, client, SyncState.find(prismContext, client), legacy);

        Files.writeString(tree.resolve("roles/role.xml"), trackedRole(List.of("alpha"), SUPERUSER_ROLE_OID),
                StandardCharsets.UTF_8);
        commit(tree, "v2");
        CliResult upgraded = run("deploy", tree.toString(), "--url", HOST, "-u", USER, "-p", PASSWORD, "--yes",
                "--no-rollback-plan");
        assertEquals(0, upgraded.exit, upgraded.out + upgraded.err);
        assertFalse(client.getObject("roles", DEPLOY_ROLE_OID).contains(">beta<"),
                "the tree row's commit told what was applied, so beta is removed");
        List<String> keys = stateRows().stream().map(SyncState.Row::key).toList();
        assertFalse(keys.contains(namespace), "the tree row is replaced: " + keys);
        assertTrue(keys.contains(namespace + "/roles/role.xml"), "by a row for the file: " + keys);
    }

    // --- helpers ---

    private static String guiRole(String panels) {
        return "<role xmlns=\"" + NS + "\" xmlns:c=\"" + NS + "\" oid=\"" + GUI_ROLE_OID + "\">\n"
                + "  <name>zz-it-gui-role</name>\n  <adminGuiConfiguration><objectDetails><objectDetailsPage>\n"
                + "    <type>c:UserType</type>\n" + panels
                + "  </objectDetailsPage></objectDetails></adminGuiConfiguration>\n</role>";
    }

    private static String panel(String identifier, String extra) {
        return "    <panel><identifier>" + identifier + "</identifier>" + extra + "</panel>\n";
    }

    /**
     * The container id midPoint gave the {@code element} whose content (up to its first child element)
     * contains {@code marker}, or {@code null}.
     */
    private static String idOf(String xml, String element, String marker) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("<" + element + " id=\"(\\d+)\">\\s*" + java.util.regex.Pattern.quote(marker))
                .matcher(xml);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String template(String mappings) {
        return "<objectTemplate xmlns=\"" + NS + "\" oid=\"" + TEMPLATE_OID + "\">\n"
                + "  <name>zz-it-template</name>\n" + mappings + "</objectTemplate>";
    }

    private static String templateMapping(String name, String extra) {
        return "  <mapping>\n    <name>" + name + "</name>\n    <strength>weak</strength>\n    " + extra
                + "\n    <expression><asIs/></expression>\n    <target><path>description</path></target>\n"
                + "  </mapping>\n";
    }

    /** The XML of the mapping named {@code name} in a template, or an empty string. */
    private static String mapping(String xml, String name) {
        for (String chunk : xml.split("<mapping")) {
            if (chunk.contains("<name>" + name + "</name>")) {
                return chunk.substring(0, Math.max(chunk.indexOf("</mapping>"), 0));
            }
        }
        return "";
    }

    /** The container id midPoint gave the mapping named {@code name}, or {@code null}. */
    private static String mappingId(String xml, String name) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("^ id=\"(\\d+)\"").matcher(mapping(xml, name));
        return matcher.find() ? matcher.group(1) : null;
    }

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
        tryDelete("objectTemplates", TEMPLATE_OID);
        tryDelete("roles", GUI_ROLE_OID);
        tryDelete("roles", SMART_ROLE_OID);
        deleteByName("roles", COMBO_NAME);
        deleteStageObjects();
    }

    private static void deleteStageObjects() {
        tryDelete("users", STAGE_USER_OID);
        tryDelete("archetypes", STAGE_ARCHETYPE_OID);
        tryDelete("objectTemplates", STAGE_TEMPLATE_OID);
        tryDelete("resources", STAGE_RESOURCE_OID);
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

    /**
     * A template whose mappings copy the rig's two stage constants onto a user, an archetype that applies it,
     * and a user with that archetype. The user file names neither item the mappings set.
     */
    private static void writeStageTree(Path dir) throws IOException {
        Files.writeString(dir.resolve("template.xml"),
                "<objectTemplate xmlns=\"" + NS + "\" oid=\"" + STAGE_TEMPLATE_OID + "\">\n"
                        + "  <name>zz-it-stage-template</name>\n"
                        + stageMapping("stage", "midgieStage", "costCenter")
                        + stageMapping("stage-from-file", "midgieStageFromFile", "telephoneNumber")
                        + "</objectTemplate>",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("archetype.xml"),
                "<archetype xmlns=\"" + NS + "\" xmlns:c=\"" + NS + "\" oid=\"" + STAGE_ARCHETYPE_OID + "\">\n"
                        + "  <name>zz-it-stage-archetype</name>\n"
                        + "  <archetypePolicy><objectTemplateRef oid=\"" + STAGE_TEMPLATE_OID
                        + "\" type=\"c:ObjectTemplateType\"/></archetypePolicy>\n"
                        + "</archetype>",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("user.xml"),
                "<user xmlns=\"" + NS + "\" xmlns:c=\"" + NS + "\" oid=\"" + STAGE_USER_OID + "\">\n"
                        + "  <name>zz-it-stage-user</name>\n"
                        + "  <assignment><targetRef oid=\"" + STAGE_ARCHETYPE_OID
                        + "\" type=\"c:ArchetypeType\"/></assignment>\n"
                        + "</user>",
                StandardCharsets.UTF_8);
    }

    private static String stageMapping(String name, String constant, String target) {
        return "  <mapping>\n    <name>" + name + "</name>\n    <strength>strong</strength>\n"
                + "    <expression><const>" + constant + "</const></expression>\n"
                + "    <target><path>" + target + "</path></target>\n  </mapping>\n";
    }

    /**
     * An LDAP resource whose {@code host} property is {@code host}, a literal or an expression. A rig that has
     * run several midPoint releases keeps one LDAP connector object per release, only the current one
     * available, so the reference filters on that too.
     */
    private static String stageResource(String host) {
        return "<resource xmlns=\"" + NS + "\" xmlns:c=\"" + NS + "\""
                + " xmlns:q=\"http://prism.evolveum.com/xml/ns/public/query-3\""
                + " xmlns:icfc=\"http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/connector-schema-3\""
                + " xmlns:ldap=\"http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/bundle/"
                + "com.evolveum.polygon.connector-ldap/com.evolveum.polygon.connector.ldap.LdapConnector\""
                + " oid=\"" + STAGE_RESOURCE_OID + "\">\n"
                + "  <name>zz-it-stage-resource</name>\n"
                + "  <connectorRef type=\"c:ConnectorType\"><filter><q:and><q:equal><q:path>c:connectorType</q:path>"
                + "<q:value>com.evolveum.polygon.connector.ldap.LdapConnector</q:value></q:equal>"
                + "<q:equal><q:path>c:available</q:path><q:value>true</q:value></q:equal></q:and></filter>"
                + "</connectorRef>\n"
                + "  <connectorConfiguration><icfc:configurationProperties>\n"
                + "    <ldap:host>" + host + "</ldap:host>\n"
                + "    <ldap:port>389</ldap:port>\n"
                + "  </icfc:configurationProperties></connectorConfiguration>\n"
                + "</resource>";
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
        return writeKeyedPanels(dir, tag, assignmentsOrder, false);
    }

    /** As above, with {@code unnamedPanel} adding a third panel that carries no {@code <identifier>}. */
    private static File writeKeyedPanels(Path dir, String tag, int assignmentsOrder, boolean unnamedPanel)
            throws IOException {
        String xml = "<user xmlns=\"" + NS + "\" xmlns:c=\"" + NS + "\" oid=\"" + TEST_OID + "\">\n"
                + "  <name>zz-it-user</name>\n"
                + "  <adminGuiConfiguration><objectDetails>\n"
                + "    <objectDetailsPage>\n"
                + "      <type>c:UserType</type>\n"
                + "      <panel><identifier>basic</identifier><displayOrder>10</displayOrder></panel>\n"
                + "      <panel><identifier>assignments</identifier><displayOrder>"
                + assignmentsOrder + "</displayOrder></panel>\n"
                + (unnamedPanel ? "      <panel><displayOrder>99</displayOrder></panel>\n" : "")
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

    private static String oidlessRole(String name, List<String> subtypes) {
        StringBuilder xml = new StringBuilder("<role xmlns=\"" + NS + "\">\n  <name>" + name + "</name>\n");
        for (String subtype : subtypes) {
            xml.append("  <subtype>").append(subtype).append("</subtype>\n");
        }
        return xml.append("</role>").toString();
    }

    /** The value of the row keyed {@code key}. The table is shared by every test, so keys are matched exactly. */
    private static String rowFor(List<SyncState.Row> rows, String key) {
        return rows.stream().filter(r -> r.key().equals(key)).map(SyncState.Row::value).findFirst().orElse("");
    }

    /** What a test repository's rows are keyed under: its short root commit. */
    private static String namespaceOf(Path repo) throws Exception {
        return gitOut(repo, "rev-list", "--max-parents=0", "HEAD").substring(0, 12);
    }

    /** A fresh git repository for a tracked tree, with the server's state table in place. */
    private static Path trackedRepo(Path dir) throws Exception {
        assertEquals(0, run("init", "--create-state", "--url", HOST, "-u", USER, "-p", PASSWORD).exit);
        Path tree = Files.createDirectories(dir.resolve("tree"));
        assumeTrue(git(tree, "init", "-q", ".") == 0, "git not available — skipping");
        git(tree, "config", "user.email", "test@example.invalid");
        git(tree, "config", "user.name", "midgie test");
        return tree;
    }

    private static List<SyncState.Row> stateRows() throws Exception {
        PrismContext prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
        return SyncState.rows(SyncState.find(prismContext, client));
    }

    private static void commit(Path repo, String message) throws Exception {
        git(repo, "add", "-A");
        git(repo, "commit", "-qm", message);
    }

    /** git's trimmed stdout, for the commands whose answer a test needs. */
    private static String gitOut(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(repo.toFile()).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        process.waitFor();
        return out;
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

    /**
     * Run the CLI in-process with stdout and stderr in one stream, in the order a terminal or a CI log shows
     * them. Everything is in {@code out}; {@code err} is empty.
     */
    private static CliResult runInterleaved(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream both = new ByteArrayOutputStream();
        try {
            PrintStream stream = new PrintStream(both, true, StandardCharsets.UTF_8);
            System.setOut(stream);
            System.setErr(stream);
            int exit = new CommandLine(new Main()).execute(args);
            return new CliResult(exit, both.toString(StandardCharsets.UTF_8), "");
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static String lastLine(CliResult result) {
        List<String> lines = result.out.strip().lines().toList();
        return lines.isEmpty() ? "" : lines.get(lines.size() - 1);
    }

    private record CliResult(int exit, String out, String err) {
    }
}
