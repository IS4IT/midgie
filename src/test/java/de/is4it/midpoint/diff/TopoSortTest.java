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

    @Test
    void manyCyclesAreBrokenInLinearTimePerBreak() {
        // 300 two-node cycles, each depending on the one before, and a chain of 3000 objects hanging off the
        // last: every one of the 300 forced breaks has the whole chain still waiting. Asking each waiting node
        // whether it reached itself walked the rest of the chain from every link of it, at every break.
        int cycles = 300;
        int tail = 3000;
        Map<String, List<String>> prerequisites = new java.util.HashMap<>();
        List<String> nodes = new java.util.ArrayList<>();
        for (int i = 0; i < cycles; i++) {
            nodes.add("a" + i);
            nodes.add("b" + i);
            prerequisites.put("a" + i, i > 0 ? List.of("b" + i, "a" + (i - 1)) : List.of("b" + i));
            prerequisites.put("b" + i, List.of("a" + i));
        }
        for (int i = 0; i < tail; i++) {
            nodes.add("t" + i);
            prerequisites.put("t" + i, List.of(i > 0 ? "t" + (i - 1) : "a" + (cycles - 1)));
        }

        TopoSort.Result<String> result = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(10),
                () -> TopoSort.sort(nodes, prerequisites::get, Comparator.naturalOrder()));

        assertEquals(nodes.size(), result.ordered().size());
        assertEquals(cycles, result.brokenAt().size());
        assertTrue(result.brokenAt().stream().noneMatch(node -> node.startsWith("t")), "the chain is on no cycle");
        assertTrue(result.ordered().indexOf("t0") < result.ordered().indexOf("t1"), "and still orders");
    }

    @Test
    void breakingACycleDoesNotDisturbNodesOutsideIt() {
        // Once no node is ready every remaining node has in-degree >= 1, so a plain dependent of a cycle
        // member tied with the members themselves and could be forced first — applied before the object it
        // references, with a "broke the cycle" warning naming a node that is in no cycle.
        Map<String, List<String>> prerequisites = Map.of(
                "Mid1", List.of("Mid2"),
                "Mid2", List.of("Mid1"),
                "Aaa", List.of("Mid1"));

        TopoSort.Result<String> result = TopoSort.sort(List.of("Aaa", "Mid1", "Mid2"),
                node -> prerequisites.getOrDefault(node, List.of()), Comparator.naturalOrder());

        assertTrue(result.ordered().indexOf("Mid1") < result.ordered().indexOf("Aaa"),
                "Aaa depends on Mid1 and is on no cycle, so it must not be forced first: " + result.ordered());
        assertFalse(result.brokenAt().contains("Aaa"),
                "and must not be reported as the place a cycle was broken: " + result.brokenAt());
        assertFalse(result.brokenAt().isEmpty(), "the real cycle is still reported");
    }
}
