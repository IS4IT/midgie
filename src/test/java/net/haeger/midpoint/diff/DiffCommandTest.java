package net.haeger.midpoint.diff;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Placeholder tests for the diff engine. Implement per {@code DESIGN.md} ("Verification"), modelling
 * the fixtures on midPoint's own {@code TestParseDiffPatch} / {@code TestDiffEquals}
 * (see /Volumes/Daten/git/midpoint/infra/schema/src/test).
 */
class DiffCommandTest {

    @Test
    @Disabled("TODO: implement — two RoleType files differing in one property must yield one 'replace' itemDelta")
    void diffOfTwoRolesEmitsReplaceForChangedProperty() {
        assertTrue(true);
    }

    @Test
    @Disabled("TODO: implement — --scope present must emit deltas only for items present in the desired object")
    void scopePresentLeavesUnmanagedItemsUntouched() {
        assertTrue(true);
    }

    @Test
    @Disabled("TODO: implement — --multivalue merge unions assignments by key; replace is authoritative")
    void multivalueMergePreservesExistingValues() {
        assertTrue(true);
    }
}
