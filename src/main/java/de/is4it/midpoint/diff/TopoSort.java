/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
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
                // A cycle: no node is free. Force one — but only a node that is actually *on* a cycle.
                // Every remaining node has in-degree >= 1 at this point, so a plain dependent of a cycle
                // member ties with the members themselves and could be picked: it would then be applied
                // before the object it references, and the "broke the cycle at ..." warning would name an
                // object that is in no cycle at all. Degrading the order inside a cycle is unavoidable;
                // letting that leak to acyclic nodes is not.
                Set<T> remaining = new LinkedHashSet<>(nodeSet);
                remaining.removeAll(emitted);
                Set<T> onCycle = new LinkedHashSet<>();
                for (T candidate : remaining) {
                    if (reachesItself(candidate, successors, remaining)) {
                        onCycle.add(candidate);
                    }
                }
                Set<T> candidates = onCycle.isEmpty() ? remaining : onCycle;
                next = candidates.stream()
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

    /** Whether {@code start} is reachable from itself through {@code scope} — i.e. it lies on a cycle. */
    private static <T> boolean reachesItself(T start, Map<T, List<T>> successors, Set<T> scope) {
        Set<T> seen = new LinkedHashSet<>();
        Deque<T> queue = new ArrayDeque<>(successors.getOrDefault(start, List.of()));
        while (!queue.isEmpty()) {
            T node = queue.poll();
            if (!scope.contains(node) || !seen.add(node)) {
                continue;
            }
            if (node.equals(start)) {
                return true;
            }
            queue.addAll(successors.getOrDefault(node, List.of()));
        }
        return false;
    }
}
