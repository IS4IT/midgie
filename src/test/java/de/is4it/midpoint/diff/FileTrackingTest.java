/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileTracking}: which content counts as last applied for a run, and which rows a run writes — against a
 * real git repository, with the table's rows given as they would be read.
 */
class FileTrackingTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static PrismContext prismContext;

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @Test
    void aFileWithARowIsReadAsItsRowRecordsIt(@TempDir Path dir) throws Exception {
        Path file = write(dir, "roles/manager.xml", role("11111111-0000-0000-0000-000000000001", "Manager", "old"));
        Assumptions.assumeTrue(commit(dir, "v1"), "git is not usable here");
        String blob = git(dir).blobAtHead("roles/manager.xml");
        write(dir, "roles/manager.xml", role("11111111-0000-0000-0000-000000000001", "Manager", "new"));
        commit(dir, "v2");

        FileTracking tracking = tracking(dir, List.of(""), fileRow("roles/manager.xml", blob, "RoleType \"Manager\""));
        List<RemovalDetector.Snapshot> snapshots = tracking.history().snapshots(units(file));

        assertEquals(1, snapshots.size(), snapshots.toString());
        assertTrue(snapshots.get(0).content().contains("old"), "the recorded content, not the current one");
    }

    @Test
    void anEarlierVersionsTreeRowIsReadForAFileWithoutARowOfItsOwn(@TempDir Path dir) throws Exception {
        Path file = write(dir, "roles/manager.xml", role("11111111-0000-0000-0000-000000000001", "Manager", "old"));
        Assumptions.assumeTrue(commit(dir, "v1"), "git is not usable here");
        String v1 = git(dir).head();
        write(dir, "roles/manager.xml", role("11111111-0000-0000-0000-000000000001", "Manager", "new"));
        commit(dir, "v2");

        FileTracking tracking = tracking(dir, List.of("roles"), new SyncState.Row(1L, ns(dir), v1, "repo"));
        List<RemovalDetector.Snapshot> snapshots = tracking.history().snapshots(units(file));

        assertEquals(1, snapshots.size(), snapshots.toString());
        assertTrue(snapshots.get(0).content().contains("old"), "read at the tree row's commit");
    }

    @Test
    void aGoneFileCountsInScopeOnlyInsideTheRunAndAnExistingOneOutsideNotAtAll(@TempDir Path dir) throws Exception {
        write(dir, "roles/gone.xml", role("11111111-0000-0000-0000-000000000002", "Gone", "g"));
        write(dir, "other/gone-too.xml", role("11111111-0000-0000-0000-000000000003", "Gone Too", "g"));
        Path kept = write(dir, "other/kept.xml", role("11111111-0000-0000-0000-000000000004", "Kept", "k"));
        Path here = write(dir, "roles/here.xml", role("11111111-0000-0000-0000-000000000005", "Here", "h"));
        Assumptions.assumeTrue(commit(dir, "v1"), "git is not usable here");
        GitRevision git = git(dir);
        String goneBlob = git.blobAtHead("roles/gone.xml");
        String goneTooBlob = git.blobAtHead("other/gone-too.xml");
        String keptBlob = git.blobAtHead("other/kept.xml");
        Files.delete(dir.resolve("roles/gone.xml"));
        Files.delete(dir.resolve("other/gone-too.xml"));
        commit(dir, "v2");

        FileTracking tracking = tracking(dir, List.of("roles"),
                fileRow("roles/gone.xml", goneBlob, "RoleType \"Gone\""),
                fileRow("other/gone-too.xml", goneTooBlob, "RoleType \"Gone Too\""),
                fileRow("other/kept.xml", keptBlob, "RoleType \"Kept\""));
        List<RemovalDetector.Snapshot> snapshots = tracking.history().snapshots(units(here));

        assertEquals(Set.of("roles/gone.xml true", "other/gone-too.xml false"),
                snapshots.stream().map(s -> s.path() + " " + s.inScope()).collect(Collectors.toSet()),
                "a file that still exists outside the run is on neither side");
        assertTrue(kept.toFile().exists());
    }

    @Test
    void aBlobTheRepositoryNoLongerHoldsIsReportedAsUnreadable(@TempDir Path dir) throws Exception {
        Path file = write(dir, "roles/manager.xml", role("11111111-0000-0000-0000-000000000001", "Manager", "x"));
        Assumptions.assumeTrue(commit(dir, "v1"), "git is not usable here");

        FileTracking tracking = tracking(dir, List.of(""),
                fileRow("roles/manager.xml", "0123456789abcdef0123456789abcdef01234567", "RoleType \"Manager\""));
        tracking.history().snapshots(units(file));

        assertEquals(1, tracking.unreadable().size(), tracking.unreadable().toString());
    }

    @Test
    void aRunWritesARowPerFileAndDeletesWhatItHasDealtWith(@TempDir Path dir) throws Exception {
        Path file = write(dir, "roles/manager.xml", role("11111111-0000-0000-0000-000000000001", "Manager", "x"));
        write(dir, "roles/gone.xml", role("11111111-0000-0000-0000-000000000002", "Gone", "g"));
        Assumptions.assumeTrue(commit(dir, "v1"), "git is not usable here");
        String v1 = git(dir).head();
        String goneBlob = git(dir).blobAtHead("roles/gone.xml");
        Files.delete(dir.resolve("roles/gone.xml"));
        commit(dir, "v2");

        FileTracking tracking = tracking(dir, List.of("roles"),
                fileRow("roles/gone.xml", goneBlob, "RoleType \"Gone\""),
                new SyncState.Row(2L, ns(dir) + "/roles", v1, "tree row under the run"),
                new SyncState.Row(3L, ns(dir), v1, "tree row above the run"));
        List<String> notes = new ArrayList<>();
        SyncState.Changes changes = tracking.changesAfter(units(file), Set.of("roles/gone.xml"), notes);

        String[] row = changes.set.get(ns(dir) + "/roles/manager.xml");
        assertTrue(row != null && row[0].contains("\"blob\":\"" + git(dir).blobAtHead("roles/manager.xml") + "\""),
                changes.set.toString());
        assertTrue(row[0].contains("\"oid\":\"11111111-0000-0000-0000-000000000001\""), row[0]);
        assertFalse(row[0].contains("dirty"), "a committed, unchanged file is not dirty: " + row[0]);
        assertEquals("RoleType \"Manager\"", row[1]);
        assertEquals(Set.of(ns(dir) + "/roles/gone.xml", ns(dir) + "/roles"), changes.delete,
                "the gone file this run dealt with and the tree row it covers; the one above stays");
        assertTrue(notes.isEmpty(), notes.toString());
    }

    @Test
    void anUnchangedFileKeepsItsTimestampAndAnEditedOneIsMarkedDirty(@TempDir Path dir) throws Exception {
        Path file = write(dir, "roles/manager.xml", role("11111111-0000-0000-0000-000000000001", "Manager", "x"));
        Assumptions.assumeTrue(commit(dir, "v1"), "git is not usable here");
        String blob = git(dir).blobAtHead("roles/manager.xml");
        SyncState.Row existing = new SyncState.Row(1L, ns(dir) + "/roles/manager.xml",
                "{\"blob\":\"" + blob + "\",\"at\":\"2026-01-01T00:00:00Z\"}", "RoleType \"Manager\"");

        String unchanged = tracking(dir, List.of(""), existing).changesAfter(units(file), Set.of(), new ArrayList<>())
                .set.get(ns(dir) + "/roles/manager.xml")[0];
        assertTrue(unchanged.contains("2026-01-01T00:00:00Z"), "its content reached the server then: " + unchanged);

        write(dir, "roles/manager.xml", role("11111111-0000-0000-0000-000000000001", "Manager", "edited"));
        String dirty = tracking(dir, List.of(""), existing).changesAfter(units(file), Set.of(), new ArrayList<>())
                .set.get(ns(dir) + "/roles/manager.xml")[0];
        assertTrue(dirty.contains("\"dirty\":true") && !dirty.contains("2026-01-01"), dirty);
    }

    @Test
    void aFileNeverCommittedGetsNoRowAndANote(@TempDir Path dir) throws Exception {
        write(dir, "roles/manager.xml", role("11111111-0000-0000-0000-000000000001", "Manager", "x"));
        Assumptions.assumeTrue(commit(dir, "v1"), "git is not usable here");
        Path fresh = write(dir, "roles/fresh.xml", role("11111111-0000-0000-0000-000000000009", "Fresh", "f"));

        List<String> notes = new ArrayList<>();
        SyncState.Changes changes = tracking(dir, List.of("")).changesAfter(units(fresh), Set.of(), notes);

        assertTrue(changes.set.isEmpty(), changes.set.toString());
        assertTrue(notes.get(0).contains("roles/fresh.xml"), notes.toString());
    }

    @Test
    void theRowsSayWhereAnObjectMovedAndWhichOfItsFilesAreOutsideTheRun(@TempDir Path dir) throws Exception {
        Path here = write(dir, "roles/manager.xml", role("11111111-0000-0000-0000-000000000001", "Manager", "x"));
        write(dir, "extra/manager-extra.xml", role("11111111-0000-0000-0000-000000000001", "Manager", "y"));
        Assumptions.assumeTrue(commit(dir, "v1"), "git is not usable here");
        FileTracking tracking = tracking(dir, List.of("roles"),
                fileRow("extra/manager-extra.xml", git(dir).blobAtHead("extra/manager-extra.xml"),
                        "RoleType \"Manager\"", "11111111-0000-0000-0000-000000000001"));

        DeployObject unit = units(here).get(0);
        assertEquals(List.of("extra/manager-extra.xml"), tracking.otherFilesOf(unit, Set.of("roles/manager.xml")));
        assertEquals("extra/manager-extra.xml",
                tracking.movedTo("11111111-0000-0000-0000-000000000001", List.of("roles/old.xml")));
        assertNull(tracking.movedTo("99999999-0000-0000-0000-000000000000", List.of("roles/old.xml")));
    }

    // --- helpers ---

    private static FileTracking tracking(Path dir, List<String> scopes, SyncState.Row... rows) {
        return new FileTracking(git(dir), ns(dir), scopes, List.of(rows));
    }

    private static SyncState.Row fileRow(String path, String blob, String label) {
        return fileRow(path, blob, label, null);
    }

    private static SyncState.Row fileRow(String path, String blob, String label, String oid) {
        return new SyncState.Row(null, "ns/" + path, "{\"blob\":\"" + blob + "\",\"at\":\"2026-01-01T00:00:00Z\""
                + (oid != null ? ",\"oid\":\"" + oid + "\"" : "") + "}", label);
    }

    /** The namespace these tests key rows under: fixed, since the rows are written by hand here. */
    private static String ns(Path dir) {
        return "ns";
    }

    private static GitRevision git(Path dir) {
        return GitRevision.of(dir.toFile());
    }

    private static List<DeployObject> units(Path... files) throws Exception {
        List<Fragment> fragments = new ArrayList<>();
        for (Path file : files) {
            fragments.add(Fragment.of(prismContext.parseObject(file.toFile()), file.toFile()));
        }
        return DeployObject.group(fragments);
    }

    private static Path write(Path dir, String path, String content) throws Exception {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static String role(String oid, String name, String description) {
        return "<role xmlns=\"" + NS + "\" oid=\"" + oid + "\">\n  <name>" + name + "</name>\n  <description>"
                + description + "</description>\n</role>\n";
    }

    private static boolean commit(Path dir, String message) throws Exception {
        if (!Files.exists(dir.resolve(".git"))) {
            if (run(dir, "git", "init", "-q", ".") != 0) {
                return false;
            }
            run(dir, "git", "config", "user.email", "test@example.invalid");
            run(dir, "git", "config", "user.name", "midgie test");
        }
        run(dir, "git", "add", "-A");
        return run(dir, "git", "commit", "-qm", message) == 0;
    }

    private static int run(Path dir, String... command) throws Exception {
        Process process = null;
        try {
            process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) ? process.exitValue() : -1;
        } catch (java.io.IOException e) {
            return -1;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
