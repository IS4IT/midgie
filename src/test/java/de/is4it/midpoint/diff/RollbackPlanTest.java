/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the rollback net's on-disk shape. The client is {@code null} throughout: version reads are
 * best-effort and must degrade to "no version recorded" rather than break the net.
 */
class RollbackPlanTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";

    private static PrismContext prismContext;

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @Test
    void writesInversesInReverseApplyOrder(@TempDir Path dir) throws Exception {
        // Undoing a sequence means replaying the inverses backwards, and --apply-plan replays in filename
        // order, so the LAST thing applied has to sort FIRST.
        RollbackPlan plan = new RollbackPlan(prismContext, null);
        plan.recordPatch("roles", oid(1), "first", deltaChangingDescription(oid(1)));
        plan.recordPatch("roles", oid(2), "second", deltaChangingDescription(oid(2)));
        plan.recordPatch("roles", oid(3), "third", deltaChangingDescription(oid(3)));

        File run = plan.write(dir.toFile(), "http://server/midpoint", "deploy-key", "abc123");

        List<String> names = planFileNames(run);
        assertEquals(3, names.size(), names.toString());
        assertTrue(names.get(0).contains(oid(3)), "the last applied unit must sort first: " + names);
        assertTrue(names.get(1).contains(oid(2)), names.toString());
        assertTrue(names.get(2).contains(oid(1)), names.toString());
    }

    @Test
    void manifestRecordsProvenanceAndCreatedObjects(@TempDir Path dir) throws Exception {
        RollbackPlan plan = new RollbackPlan(prismContext, null);
        plan.recordPatch("roles", oid(1), "patched-one", deltaChangingDescription(oid(1)));
        plan.recordApplied(Reconciler.Action.CREATE, createOutcome(oid(2)), oid(2), "made-one");

        File run = plan.write(dir.toFile(), "http://server/midpoint", "deploy-key", "abc123");
        String manifest = Files.readString(new File(run, RollbackPlan.MANIFEST).toPath(), StandardCharsets.UTF_8);

        assertTrue(manifest.contains("<server>http://server/midpoint</server>"), manifest);
        assertTrue(manifest.contains("<deployment>deploy-key</deployment>"), manifest);
        assertTrue(manifest.contains("<revision>abc123</revision>"), manifest);
        assertTrue(manifest.contains("action=\"patched\""), manifest);
        assertTrue(manifest.contains("action=\"created\""), manifest);
        assertTrue(manifest.startsWith("<!--"), "the manifest is generated output, not deploy input:\n" + manifest);

        // A created object is named but never undone: the inverse of an add is an object delete.
        assertEquals(1, planFileNames(run).size(), "only the patch is replayable");
        assertEquals(1, plan.undoableCount());
    }

    @Test
    void aFinishedPlanIsNoLongerMarkedIncomplete(@TempDir Path dir) throws Exception {
        RollbackPlan plan = new RollbackPlan(prismContext, null);
        plan.recordPatch("roles", oid(1), "one", deltaChangingDescription(oid(1)));
        File run = plan.write(dir.toFile(), "http://server/midpoint", null, null);

        assertFalse(PlanStore.isIncomplete(run), "a completed net must be replayable");
        assertEquals(run, RollbackPlan.newestUnder(dir.toFile()));
    }

    @Test
    void newestUnderSkipsAnUnfinishedRun(@TempDir Path dir) throws Exception {
        RollbackPlan plan = new RollbackPlan(prismContext, null);
        plan.recordPatch("roles", oid(1), "one", deltaChangingDescription(oid(1)));
        File good = plan.write(dir.toFile(), "http://server/midpoint", null, null);

        // A later run that died half way through: newer by name, but not replayable.
        File broken = new File(dir.toFile(), "29991231-235959");
        PlanStore.markIncomplete(broken);
        Files.writeString(new File(broken, RollbackPlan.MANIFEST).toPath(), "<rollbackManifest/>",
                StandardCharsets.UTF_8);

        assertEquals(good, RollbackPlan.newestUnder(dir.toFile()),
                "an unfinished net must never be picked as the one to replay");
    }

    @Test
    void rotationKeepsOnlyTheNewestRuns(@TempDir Path dir) throws Exception {
        for (String stamp : List.of("20260101-000000", "20260102-000000", "20260103-000000", "20260104-000000")) {
            File run = new File(dir.toFile(), stamp);
            Files.createDirectories(run.toPath());
            Files.writeString(new File(run, RollbackPlan.MANIFEST).toPath(), "<rollbackManifest/>",
                    StandardCharsets.UTF_8);
        }

        RollbackPlan.rotate(dir.toFile(), 2);

        assertFalse(new File(dir.toFile(), "20260101-000000").exists(), "the oldest run must be rotated out");
        assertFalse(new File(dir.toFile(), "20260102-000000").exists(), "the oldest run must be rotated out");
        assertTrue(new File(dir.toFile(), "20260103-000000").exists());
        assertTrue(new File(dir.toFile(), "20260104-000000").exists());
    }

    @Test
    void rotationLeavesEverythingThatIsNotARunAlone(@TempDir Path dir) throws Exception {
        // --rollback-plan and a tree-local config may name any directory, the tree itself included. Its
        // folders sort after the timestamps, so they used to be the first thing rotated away.
        for (String folder : List.of("roles", "users", "archetypes")) {
            File sub = new File(dir.toFile(), folder);
            Files.createDirectories(sub.toPath());
            Files.writeString(new File(sub, "object.xml").toPath(), "<role/>", StandardCharsets.UTF_8);
        }
        // Named like a run, but nothing midgie wrote.
        Files.createDirectories(dir.resolve("20250101-000000"));
        for (String stamp : List.of("20260101-000000", "20260102-000000")) {
            File run = new File(dir.toFile(), stamp);
            Files.createDirectories(run.toPath());
            Files.writeString(new File(run, RollbackPlan.MANIFEST).toPath(), "<rollbackManifest/>",
                    StandardCharsets.UTF_8);
        }

        RollbackPlan.rotate(dir.toFile(), 1);

        for (String folder : List.of("roles", "users", "archetypes")) {
            assertTrue(new File(dir.toFile(), folder + "/object.xml").exists(), folder + " must survive");
        }
        assertTrue(new File(dir.toFile(), "20250101-000000").exists(),
                "a timestamp name without a manifest is not proof of a run");
        assertFalse(new File(dir.toFile(), "20260101-000000").exists(), "the older run must be rotated out");
        assertTrue(new File(dir.toFile(), "20260102-000000").exists());
    }

    @Test
    void rotationNeverFollowsLinks(@TempDir Path dir) throws Exception {
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve("keep.xml"), "<role/>", StandardCharsets.UTF_8);
        Files.writeString(elsewhere.resolve(RollbackPlan.MANIFEST), "<rollbackManifest/>",
                StandardCharsets.UTF_8);
        Path base = Files.createDirectories(dir.resolve("base"));

        // An old run with a link inside it, and a link named like a run pointing at the same directory.
        Path old = Files.createDirectories(base.resolve("20260101-000000"));
        Files.writeString(old.resolve(RollbackPlan.MANIFEST), "<rollbackManifest/>", StandardCharsets.UTF_8);
        Files.createSymbolicLink(old.resolve("link"), elsewhere);
        Files.createSymbolicLink(base.resolve("20250101-000000"), elsewhere);
        Path newer = Files.createDirectories(base.resolve("20260102-000000"));
        Files.writeString(newer.resolve(RollbackPlan.MANIFEST), "<rollbackManifest/>", StandardCharsets.UTF_8);

        RollbackPlan.rotate(base.toFile(), 1);

        assertFalse(Files.exists(old, java.nio.file.LinkOption.NOFOLLOW_LINKS), "the old run must go");
        assertTrue(Files.exists(elsewhere.resolve("keep.xml")), "a link's target must never be emptied");
        assertTrue(Files.isSymbolicLink(base.resolve("20250101-000000")), "a linked directory is not a run");
    }

    @Test
    void aDotKeyStaysInsideTheStateRoot() {
        // An --app of "." or ".." (a tree-local config may set it) must not resolve to the state root or
        // the directory above it, both of which hold other deployments' nets.
        File root = RollbackPlan.defaultBase("x", null).getParentFile();
        for (String key : List.of(".", "..", ".hidden")) {
            File base = RollbackPlan.defaultBase(key, null);
            assertEquals(root, base.getParentFile(), key + " resolved to " + base);
            assertFalse(base.getName().startsWith("."), key + " resolved to " + base);
        }
    }

    @Test
    void theManifestRecordsTheStateRowsTheRunMoved(@TempDir Path dir) throws Exception {
        // A rollback has to move them back, or a redeploy of the rolled-back revision finds nothing dropped.
        RollbackPlan plan = new RollbackPlan(prismContext, null);
        plan.recordPatch("roles", oid(1), "first", deltaChangingDescription(oid(1)));
        plan.recordPatch("roles", oid(1), "first", deltaChangingDescription(oid(1))); // a retry: same object
        plan.recordRows(oid(1), List.of(
                new RollbackPlan.RowChange("root/role.xml", "{\"blob\":\"a&b\"}", "RoleType/first", "{\"blob\":\"c\"}", "RoleType/first"),
                new RollbackPlan.RowChange("root/new.xml", null, null, "{\"blob\":\"d\"}", null)));
        plan.recordRunRows(List.of(new RollbackPlan.RowChange("root/gone.xml", "{\"blob\":\"e\"}", null, null, null)));

        File run = plan.write(dir.toFile(), "http://server/midpoint", "deploy-key", "abc123");
        RollbackPlan.Manifest manifest = RollbackPlan.Manifest.read(run);

        assertEquals(2, manifest.objects.size());
        List<RollbackPlan.RowChange> rows = manifest.objects.get(0).rows;
        assertEquals(2, rows.size(), rows.toString());
        assertEquals(new RollbackPlan.RowChange("root/role.xml", "{\"blob\":\"a&b\"}", "RoleType/first",
                "{\"blob\":\"c\"}", "RoleType/first"), rows.get(0));
        assertEquals(new RollbackPlan.RowChange("root/new.xml", null, null, "{\"blob\":\"d\"}", null), rows.get(1),
                "a row the run added has no earlier value");
        assertTrue(manifest.objects.get(1).rows.isEmpty(), "rows are written once per object");
        assertEquals(List.of(new RollbackPlan.RowChange("root/gone.xml", "{\"blob\":\"e\"}", null, null, null)),
                manifest.runRows);
    }

    @Test
    void twoNetsWrittenAtOnceGetADirectoryEach(@TempDir Path dir) throws Exception {
        // Named to the second, two deploys finishing within one shared a directory, and a rollback replayed the
        // inverses of both.
        RollbackPlan first = new RollbackPlan(prismContext, null);
        first.recordPatch("roles", oid(1), "first", deltaChangingDescription(oid(1)));
        RollbackPlan second = new RollbackPlan(prismContext, null);
        second.recordPatch("roles", oid(2), "second", deltaChangingDescription(oid(2)));

        File one = first.write(dir.toFile(), "s", "d", "r");
        File two = second.write(dir.toFile(), "s", "d", "r");

        assertNotEquals(one, two);
        assertEquals(1, planFileNames(one).size(), planFileNames(one).toString());
        assertEquals(1, planFileNames(two).size(), planFileNames(two).toString());
        assertTrue(RollbackPlan.isRunDirectory(two) && two.getName().matches("\\d{8}-\\d{6}-\\d{3}"), two.getName());
    }

    @Test
    void rotationAndTheNewestPlanKeepToTheirOwnDeployment(@TempDir Path dir) throws Exception {
        // Two deployment keys that flatten to one directory name share it; each must keep its own nets.
        for (String[] run : new String[][] {{"20260101-000000", "root/a/b_c"}, {"20260102-000000", "root/a_b/c"},
                {"20260103-000000", "root/a/b_c"}, {"20260104-000000", "root/a_b/c"}}) {
            File runDir = new File(dir.toFile(), run[0]);
            Files.createDirectories(runDir.toPath());
            Files.writeString(new File(runDir, RollbackPlan.MANIFEST).toPath(),
                    "<rollbackManifest><deployment>" + run[1] + "</deployment></rollbackManifest>",
                    StandardCharsets.UTF_8);
        }

        assertEquals(new File(dir.toFile(), "20260103-000000"), RollbackPlan.newestUnder(dir.toFile(), "root/a/b_c"),
                "the newest of this deployment, not the newest of the directory");

        RollbackPlan.rotate(dir.toFile(), 1, "root/a/b_c");

        assertFalse(new File(dir.toFile(), "20260101-000000").exists(), "this deployment's older net goes");
        assertTrue(new File(dir.toFile(), "20260102-000000").exists(), "the other deployment's stay");
        assertTrue(new File(dir.toFile(), "20260104-000000").exists());
    }

    @Test
    void emptyPlanWritesNothingAndReportsItself() {
        RollbackPlan plan = new RollbackPlan(prismContext, null);
        assertTrue(plan.isEmpty(), "a run that wrote nothing has nothing to undo");
        assertEquals(0, plan.undoableCount());
    }

    @Test
    void defaultBaseFlattensTheDeploymentKeyIntoOneDirectory() {
        // A key is a commit prefix plus the tree's path within the repository, so it carries slashes.
        File base = RollbackPlan.defaultBase("abc123def456/config/prod", new File("/irrelevant"));
        assertEquals("abc123def456_config_prod", base.getName(), base.getPath());
    }

    @Test
    void aTreeWithNoIdentityGetsItsOwnDirectoryAnyway() {
        // Not a shared "unidentified" bucket: rotation keeps a fixed number of runs per directory, so
        // unrelated trees sharing one would quietly delete each other's nets.
        String one = RollbackPlan.defaultBase(null, new File("/tmp/alpha/config")).getName();
        String other = RollbackPlan.defaultBase(null, new File("/tmp/beta/config")).getName();

        assertNotEquals(one, other, "two trees must not share a net directory");
        assertTrue(one.startsWith("config-"), one);
        assertEquals("unidentified", RollbackPlan.defaultBase(null, null).getName(),
                "with nothing at all to key on, one bucket is still better than no net");
    }

    // --- fixtures ---

    private static String oid(int n) {
        return "11111111-1111-1111-1111-00000000000" + n;
    }

    /** A minimal real delta, so the inverse goes through prism rather than a hand-built stub. */
    private static ObjectDelta<? extends Objectable> deltaChangingDescription(String oid) throws Exception {
        return new DiffEngine(prismContext).diff(
                        prismContext.parseObject(role(oid, "desired")),
                        prismContext.parseObject(role(oid, "live")),
                        EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.PRESENT,
                        DiffEngine.MultiValueStrategy.replaceAll())
                .delta();
    }

    private static Reconciler.Outcome createOutcome(String oid) throws Exception {
        return new Reconciler(prismContext, null, true, false)
                .planAgainst(prismContext.parseObject(role(oid, "new")), null,
                        new StrategyOptions.Resolved(DiffEngine.Scope.PRESENT,
                                DiffEngine.EquivalencePlan.of(EquivalenceStrategy.REAL_VALUE),
                                DiffEngine.MultiValueStrategy.replaceAll(), false));
    }

    private static String role(String oid, String description) {
        return "<role xmlns=\"" + NS + "\" oid=\"" + oid + "\">\n"
                + "  <name>Role " + oid + "</name>\n"
                + "  <description>" + description + "</description>\n"
                + "</role>";
    }

    private static List<String> planFileNames(File dir) {
        String[] names = dir.list((d, name) -> name.endsWith(".patch.xml") || name.endsWith(".create.xml"));
        assertNotNull(names, "no plan directory at " + dir);
        java.util.Arrays.sort(names);
        return List.of(names);
    }
}
