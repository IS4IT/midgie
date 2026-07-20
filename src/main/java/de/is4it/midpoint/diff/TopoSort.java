/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Function;

/**
 * Generic Kahn topological sort, reused by {@code deploy} for both the schema tier (nodes = schemas,
 * edges = {@code <xsd:import>}s) and the object tier (nodes = objects, edges = reference OIDs). Each node
 * declares its <em>prerequisites</em> — the nodes that must come before it; prerequisites outside the node
 * set (or a self-reference) are ignored. A {@code readyOrder} comparator breaks ties among nodes that are
 * simultaneously ready, so the output is deterministic. Cycles are not fatal: when no node is ready but
 * some remain, the node with the fewest unmet prerequisites is forced next and recorded in
 * {@link Result#brokenAt()} so the caller can warn.
 */
final class TopoSort {

    private TopoSort() {
    }

    /** The ordering result: the full node order, plus the nodes that had to be forced to break a cycle. */
    static final class Result<T> {
        private final List<T> ordered;
        private final List<T> brokenAt;

        Result(List<T> ordered, List<T> brokenAt) {
            this.ordered = ordered;
            this.brokenAt = brokenAt;
        }

        List<T> ordered() {
            return ordered;
        }

        /** Non-empty when the graph had at least one cycle; lists the node forced at each break. */
        List<T> brokenAt() {
            return brokenAt;
        }
    }

    static <T> Result<T> sort(List<T> nodes, Function<T, ? extends Collection<T>> prerequisites,
            Comparator<T> readyOrder) {
        Set<T> nodeSet = new LinkedHashSet<>(nodes);
        Map<T, Integer> inDegree = new LinkedHashMap<>();
        Map<T, List<T>> successors = new LinkedHashMap<>();
        for (T node : nodeSet) {
            inDegree.put(node, 0);
            successors.put(node, new ArrayList<>());
        }
        for (T node : nodeSet) {
            Set<T> deps = new LinkedHashSet<>(prerequisites.apply(node)); // dedupe so in-degree can't overcount
            deps.remove(node);                                            // ignore self-reference
            for (T dep : deps) {
                if (nodeSet.contains(dep)) {                              // ignore out-of-set prerequisites
                    successors.get(dep).add(node);
                    inDegree.merge(node, 1, Integer::sum);
                }
            }
        }

        PriorityQueue<T> ready = new PriorityQueue<>(readyOrder);
        for (T node : nodeSet) {
            if (inDegree.get(node) == 0) {
                ready.add(node);
            }
        }

        List<T> ordered = new ArrayList<>(nodeSet.size());
        List<T> brokenAt = new ArrayList<>();
        Set<T> emitted = new LinkedHashSet<>();
        while (ordered.size() < nodeSet.size()) {
            T next = ready.poll();
            if (next == null) {
                // A cycle: no node is free. Force the remaining node with the fewest unmet prerequisites.
                next = nodeSet.stream()
                        .filter(n -> !emitted.contains(n))
                        .min(Comparator.<T>comparingInt(inDegree::get).thenComparing(readyOrder))
                        .orElseThrow();
                brokenAt.add(next);
            }
            if (!emitted.add(next)) {
                continue;
            }
            ordered.add(next);
            for (T successor : successors.get(next)) {
                if (emitted.contains(successor)) {
                    continue;
                }
                if (inDegree.merge(successor, -1, Integer::sum) <= 0 && !ready.contains(successor)) {
                    ready.add(successor);
                }
            }
        }
        return new Result<>(ordered, brokenAt);
    }
}
