/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tracking rows a plan carries for {@code --apply-plan} to write. */
class PlanStateTest {

    @Test
    void writtenRowsReadBackAsWritten(@TempDir Path dir) throws Exception {
        RollbackPlan.RowChange changed = new RollbackPlan.RowChange("root/role.xml", "{\"blob\":\"a<b\"}",
                "RoleType/R", "{\"blob\":\"c\"}", "RoleType/R \"new\"");
        RollbackPlan.RowChange added = new RollbackPlan.RowChange("root/new.xml", null, null, "{\"blob\":\"d\"}", null);
        RollbackPlan.RowChange gone = new RollbackPlan.RowChange("root/gone.xml", "{\"blob\":\"e\"}", null, null, null);
        PlanState.Contents contents = new PlanState.Contents("root/tree", "abc123", List.of("deploy:root/tree"),
                List.of(new PlanState.Unit("001-roles-x.patch.xml", "x", List.of(changed)),
                        new PlanState.Unit(null, null, List.of(added))),
                List.of(gone));

        PlanState.write(dir.toFile(), contents);

        assertEquals(contents, PlanState.read(dir.toFile()));
        assertTrue(Files.readString(dir.resolve(PlanState.FILE)).contains(PlanStore.GENERATED_MARKER),
                "it must never be discovered as deploy input");
    }

    @Test
    void aPlanWithoutTrackingHasNone(@TempDir Path dir) throws Exception {
        assertNull(PlanState.read(dir.toFile()));
    }
}
