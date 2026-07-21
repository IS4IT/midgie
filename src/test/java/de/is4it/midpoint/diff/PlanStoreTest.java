/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline tests for writing and reading a {@code --plan} directory. */
class PlanStoreTest {

    private static final String OID = "11111111-0000-0000-0d00-000000000002";

    @Test
    void writesThenReadsCreatesAndPatchesInOrder(@TempDir Path dir) throws IOException {
        File out = dir.toFile();
        assertFalse(PlanStore.hasPlanFiles(out), "empty dir has no plan files");

        PlanStore.write(out, 10, "roles", OID, "Manager", PlanStore.Kind.CREATE, "<role oid=\"" + OID + "\"/>");
        PlanStore.write(out, 20, "users", "33333333-0000-0000-0d00-000000000001", "jsmith",
                PlanStore.Kind.PATCH, "<objectModification/>");
        assertTrue(PlanStore.hasPlanFiles(out));

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
    void anOidlessCreateUsesTheSanitisedName(@TempDir Path dir) throws IOException {
        File out = dir.toFile();
        PlanStore.write(out, 5, "roles", null, "New Role!", PlanStore.Kind.CREATE, "<role/>");

        List<PlanStore.Entry> entries = PlanStore.read(out);
        assertEquals(1, entries.size());
        assertEquals("roles", entries.get(0).restType);
        assertEquals("NewRole", entries.get(0).oid, "name sanitised into the filename token");
    }
}
