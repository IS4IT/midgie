/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline tests for writing and reading a {@code --plan} directory. */
class PlanStoreTest {

    private static final String OID = "11111111-0000-0000-0d00-000000000002";

    @Test
    void writesThenReadsCreatesAndPatchesInOrder(@TempDir Path dir) throws IOException {
        File out = dir.toFile();
        assertEquals(PlanStore.Contents.EMPTY, PlanStore.inspect(out), "an empty dir holds no plan");

        PlanStore.write(out, 10, 3, "roles", OID, "Manager", PlanStore.Kind.CREATE, "<role oid=\"" + OID + "\"/>");
        PlanStore.write(out, 20, 3, "users", "33333333-0000-0000-0d00-000000000001", "jsmith",
                PlanStore.Kind.PATCH, "<objectModification/>");
        assertEquals(PlanStore.Contents.PLAN, PlanStore.inspect(out));

        List<PlanStore.Entry> entries = PlanStore.read(out);
        assertEquals(2, entries.size());

        PlanStore.Entry create = entries.get(0); // 010- sorts first
        assertEquals(PlanStore.Kind.CREATE, create.kind);
        assertEquals("roles", create.restType);
        assertEquals(OID, create.oid, "the dashed OID must survive the filename round-trip");
        assertTrue(create.body.contains("<role"), create.body);

        PlanStore.Entry patch = entries.get(1);
        assertEquals(PlanStore.Kind.PATCH, patch.kind);
        assertEquals("users", patch.restType);
        assertEquals("33333333-0000-0000-0d00-000000000001", patch.oid);
        assertTrue(patch.body.contains("objectModification"), patch.body);
    }

    @Test
    void aPatchWithoutAnOidIsRefusedRatherThanNamedAfterTheObject() {
        // A patch is addressed by OID. Falling back to the sanitised name produced a plan that reviewed as
        // valid and then issued PATCH /ws/rest/users/<Name> — not an address — at replay time.
        IOException ex = assertThrows(IOException.class, () -> PlanStore.write(new File("unused"), 1, 3,
                "users", null, "jsmith", PlanStore.Kind.PATCH, "<objectModification/>"));
        assertTrue(ex.getMessage().contains("without an OID"), ex.getMessage());
    }

    @Test
    void anUnfinishedPlanIsRefusedInsteadOfPartlyReplayed(@TempDir Path dir) throws IOException {
        // A --plan run that dies half way leaves valid-looking files behind. Replaying them applied part of
        // the tree and reported "Done: 2 applied, 0 failed", exit 0.
        File out = dir.toFile();
        PlanStore.markIncomplete(out);
        PlanStore.write(out, 1, 3, "roles", OID, "Manager", PlanStore.Kind.CREATE, "<role/>");

        assertTrue(PlanStore.isIncomplete(out));
        IOException ex = assertThrows(IOException.class, () -> PlanStore.read(out));
        assertTrue(ex.getMessage().contains("never finished"), ex.getMessage());

        PlanStore.markComplete(out);
        assertEquals(1, PlanStore.read(out).size(), "and it reads normally once finished");
    }

    @Test
    void aPlanFilenameThatWasTamperedWithIsRejected(@TempDir Path dir) throws IOException {
        // --apply-plan exists so that what was reviewed is what runs; the type and token go straight into
        // the request URI.
        File out = dir.toFile();
        java.nio.file.Files.writeString(dir.resolve("001-roles-" + OID + "#tail.patch.xml"), "<x/>");
        IOException ex = assertThrows(IOException.class, () -> PlanStore.read(out));
        assertTrue(ex.getMessage().contains("object token"), ex.getMessage());
    }

    @Test
    void planFilesSortIntoApplyOrderBeyondAThousandUnits(@TempDir Path dir) throws IOException {
        // At a fixed three digits, 1000- sorts before 999- and a large tree replays out of dependency order.
        File out = dir.toFile();
        PlanStore.write(out, 999, 4, "roles", OID, "A", PlanStore.Kind.CREATE, "<a/>");
        PlanStore.write(out, 1000, 4, "roles", "22222222-0000-0000-0d00-000000000003", "B",
                PlanStore.Kind.CREATE, "<b/>");

        List<PlanStore.Entry> entries = PlanStore.read(out);
        assertEquals(OID, entries.get(0).oid, "unit 999 still comes first");
    }

    @Test
    void anOidlessCreateUsesTheSanitisedName(@TempDir Path dir) throws IOException {
        File out = dir.toFile();
        PlanStore.write(out, 5, 3, "roles", null, "New Role!", PlanStore.Kind.CREATE, "<role/>");

        List<PlanStore.Entry> entries = PlanStore.read(out);
        assertEquals(1, entries.size());
        assertEquals("roles", entries.get(0).restType);
        assertEquals("NewRole", entries.get(0).oid, "name sanitised into the filename token");
    }

    // --- the directory a plan is written to ---

    @Test
    void theDefaultDirectorySitsBesideTheDeploymentsRollbackPlans() {
        File tree = new File("/irrelevant/config");
        assertEquals(new File(RollbackPlan.defaultBase("abc123/config", tree), "plan"),
                PlanStore.defaultDir("abc123/config", tree));
    }

    @Test
    void aMissingDirectoryIsEmpty(@TempDir Path dir) throws IOException {
        assertEquals(PlanStore.Contents.EMPTY, PlanStore.inspect(dir.resolve("not-yet").toFile()));
    }

    @Test
    void aPlanWithItsStateAndAnUnfinishedOneAreTold(@TempDir Path dir) throws IOException {
        File out = dir.toFile();
        PlanStore.write(out, 1, 3, "roles", OID, "Manager", PlanStore.Kind.CREATE, "<role/>");
        Files.writeString(dir.resolve(PlanState.FILE), PlanStore.markGenerated("<planState/>"));
        assertEquals(PlanStore.Contents.PLAN, PlanStore.inspect(out));

        PlanStore.markIncomplete(out);
        assertEquals(PlanStore.Contents.UNFINISHED_PLAN, PlanStore.inspect(out));
    }

    @Test
    void anEmptyPlanFileLeftByADeadRunStillCountsAsThePlans(@TempDir Path dir) throws IOException {
        // write() creates the file before it writes the body; a run that died between the two left it empty.
        PlanStore.markIncomplete(dir.toFile());
        Files.createFile(dir.resolve("001-roles-" + OID + ".patch.xml"));
        assertEquals(PlanStore.Contents.UNFINISHED_PLAN, PlanStore.inspect(dir.toFile()));
    }

    @Test
    void anythingButAPlanIsRefusedAndNamed(@TempDir Path dir) throws IOException {
        File out = dir.toFile();
        PlanStore.write(out, 1, 3, "roles", OID, "Manager", PlanStore.Kind.CREATE, "<role/>");

        assertRefused(dir, "notes.txt", () -> Files.writeString(dir.resolve("notes.txt"), "reviewed"));
        assertRefused(dir, ".DS_Store", () -> Files.writeString(dir.resolve(".DS_Store"), "x"));
        assertRefused(dir, "sub/", () -> Files.createDirectory(dir.resolve("sub")));
        assertRefused(dir, "002-roles-x.patch.xml",
                () -> Files.writeString(dir.resolve("002-roles-x.patch.xml"), "<objectModification/>"));
        assertRefused(dir, PlanState.FILE, () -> Files.writeString(dir.resolve(PlanState.FILE), "<planState/>"));
    }

    @Test
    void aFileWhereTheDirectoryShouldBeIsRefused(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("plan"), "x");
        IOException ex = assertThrows(IOException.class, () -> PlanStore.inspect(file.toFile()));
        assertTrue(ex.getMessage().contains("not a directory"), ex.getMessage());
    }

    @Test
    void clearRemovesThePlanAndLeavesItMarkedUnfinished(@TempDir Path dir) throws IOException {
        File out = dir.toFile();
        PlanStore.write(out, 1, 3, "roles", OID, "Manager", PlanStore.Kind.CREATE, "<role/>");
        PlanStore.write(out, 2, 3, "users", "33333333-0000-0000-0d00-000000000001", "jsmith",
                PlanStore.Kind.PATCH, "<objectModification/>");
        Files.writeString(dir.resolve(PlanState.FILE), PlanStore.markGenerated("<planState/>"));

        assertEquals(2, PlanStore.clear(out), "the plan files are counted, state.xml goes with them");
        assertEquals(PlanStore.Contents.UNFINISHED_PLAN, PlanStore.inspect(out),
                "a run that dies now leaves a plan --apply-plan refuses, not an empty one that reads as done");
        try (var left = Files.list(dir)) {
            assertEquals(List.of(".midgie-plan-incomplete"),
                    left.map(p -> p.getFileName().toString()).toList(), "nothing of the old plan is left");
        }
    }

    private interface Change {
        void apply() throws IOException;
    }

    /** Make {@code change} to a directory holding a plan, check it is refused naming {@code entry}, undo it. */
    private static void assertRefused(Path dir, String entry, Change change) throws IOException {
        change.apply();
        IOException ex = assertThrows(IOException.class, () -> PlanStore.inspect(dir.toFile()));
        assertTrue(ex.getMessage().contains(entry), ex.getMessage());
        assertTrue(Files.exists(dir.resolve(entry.replace("/", ""))), "inspect touches nothing");
        Files.delete(dir.resolve(entry.replace("/", "")));
        assertEquals(PlanStore.Contents.PLAN, PlanStore.inspect(dir.toFile()), "and is fine again without it");
    }
}
