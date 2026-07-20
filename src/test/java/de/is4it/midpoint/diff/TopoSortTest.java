/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the generic Kahn sort used by both deploy tiers. */
class TopoSortTest {

    private static final Comparator<String> ALPHA = Comparator.naturalOrder();

    @Test
    void ordersPrerequisitesFirst() {
        // c depends on b, b depends on a  ->  a, b, c
        Map<String, List<String>> prereqs = Map.of("a", List.of(), "b", List.of("a"), "c", List.of("b"));
        TopoSort.Result<String> result = TopoSort.sort(List.of("c", "b", "a"), prereqs::get, ALPHA);
        assertEquals(List.of("a", "b", "c"), result.ordered());
        assertTrue(result.brokenAt().isEmpty(), "no cycle expected");
    }

    @Test
    void tiebreakIsDeterministicByComparator() {
        // Two independent nodes: the ready-order comparator decides. d depends on both a and b.
        Map<String, List<String>> prereqs = Map.of("a", List.of(), "b", List.of(), "d", List.of("a", "b"));
        TopoSort.Result<String> result = TopoSort.sort(List.of("d", "b", "a"), prereqs::get, ALPHA);
        assertEquals(List.of("a", "b", "d"), result.ordered());
    }

    @Test
    void outOfSetPrerequisitesAreIgnored() {
        // b's prerequisite "z" is not a node -> no edge, b is free.
        Map<String, List<String>> prereqs = Map.of("a", List.of(), "b", List.of("z"));
        TopoSort.Result<String> result = TopoSort.sort(List.of("b", "a"), prereqs::get, ALPHA);
        assertEquals(List.of("a", "b"), result.ordered());
        assertTrue(result.brokenAt().isEmpty());
    }

    @Test
    void cycleIsBrokenAndReportedButAllNodesEmitted() {
        // a <-> b mutual dependency.
        Map<String, List<String>> prereqs = Map.of("a", List.of("b"), "b", List.of("a"));
        TopoSort.Result<String> result = TopoSort.sort(List.of("a", "b"), prereqs::get, ALPHA);
        assertEquals(2, result.ordered().size(), "both nodes still emitted");
        assertTrue(result.ordered().containsAll(List.of("a", "b")));
        assertFalse(result.brokenAt().isEmpty(), "the cycle must be reported");
    }
}
