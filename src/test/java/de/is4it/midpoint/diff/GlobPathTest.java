/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The glob grammar on its own, without a policy file around it. */
class GlobPathTest {

    @Test
    void aStarMatchesExactlyOneSegment() {
        GlobPath glob = GlobPath.parse("RoleType/*/assignment", 2);

        assertTrue(glob.matches(List.of("RoleType", "Manager", "assignment")));
        assertFalse(glob.matches(List.of("RoleType", "assignment")));
        assertFalse(glob.matches(List.of("RoleType", "A", "B", "assignment")));
    }

    @Test
    void aGlobstarMatchesAnyDepthIncludingNone() {
        GlobPath glob = GlobPath.parse("RoleType/**/description", 2);

        assertTrue(glob.matches(List.of("RoleType", "description")));
        assertTrue(glob.matches(List.of("RoleType", "Manager", "inducement", "description")));
        assertFalse(glob.matches(List.of("UserType", "description")));
    }

    @Test
    void escapedAsterisksStayLiteralAsAWholeSegment() {
        GlobPath one = GlobPath.parse("RoleType/\\*", 2);
        assertTrue(one.matches(List.of("RoleType", "*")));
        assertFalse(one.matches(List.of("RoleType", "Manager")), "\\* must not act as the wildcard");
        assertFalse(one.matchesPrefix(List.of("RoleType", "Manager")));

        GlobPath two = GlobPath.parse("RoleType/\\*\\*/description", 2);
        assertTrue(two.matches(List.of("RoleType", "**", "description")));
        assertFalse(two.matches(List.of("RoleType", "description")), "\\*\\* must not act as the globstar");
        assertFalse(two.containsGlobstar());
    }

    @Test
    void anEscapedAsteriskCountsAsALiteralForSpecificity() {
        // A literal outranks a wildcard; an escaped one is a literal.
        assertTrue(GlobPath.parse("RoleType/\\*", 2).compareSpecificity(GlobPath.parse("RoleType/*", 2)) > 0);
    }

    @Test
    void anItemPrefixIsStrippedButAnObjectNameKeepsItsColon() {
        GlobPath glob = GlobPath.parse("RoleType/IT:Helpdesk/c:description", 2);

        assertTrue(glob.matches(List.of("RoleType", "IT:Helpdesk", "description")));
        assertFalse(glob.matches(List.of("RoleType", "Helpdesk", "description")));
    }

    @Test
    void aWildcardMixedWithTextIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> GlobPath.parse("RoleType/App-*", 2));
    }
}
