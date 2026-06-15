package org.nebula.core.scheduler;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Tarjan SCC (Strongly Connected Components) detection.
 * Validates handling of cycles in task dependency graphs.
 */
class TarjanSccTest {

    @Test
    void emptyGraphHasNoComponents() {
        List<Set<String>> components = TarjanScc.compute(List.of("A", "B"), Set.of());
        assertTrue(components.isEmpty(), "Empty graph should have no SCCs");
    }

    @Test
    void linearGraphHasNoComponents() {
        // A -> B -> C (no cycles)
        Set<DependencyEdge> edges = Set.of(
            new DependencyEdge("A", "B", DependencyType.RAW),
            new DependencyEdge("B", "C", DependencyType.RAW)
        );

        List<Set<String>> components = TarjanScc.compute(List.of("A", "B", "C"), edges);
        assertTrue(components.isEmpty(), "Linear graph should have no SCCs");
    }

    @Test
    void selfLoopIsComponent() {
        // A -> A (self-loop is a cycle)
        Set<DependencyEdge> edges = Set.of(
            new DependencyEdge("A", "A", DependencyType.RAW)
        );

        List<Set<String>> components = TarjanScc.compute(List.of("A"), edges);
        assertEquals(1, components.size());
        assertEquals(Set.of("A"), components.get(0));
    }

    @Test
    void twoNodeCycleIsSingleComponent() {
        // A <-> B (mutual dependency)
        Set<DependencyEdge> edges = Set.of(
            new DependencyEdge("A", "B", DependencyType.RAW),
            new DependencyEdge("B", "A", DependencyType.RAW)
        );

        List<Set<String>> components = TarjanScc.compute(List.of("A", "B"), edges);
        assertEquals(1, components.size());
        assertEquals(Set.of("A", "B"), components.get(0));
    }

    @Test
    void multipleCyclesAreSeparated() {
        // Cycle 1: A <-> B
        // Cycle 2: C <-> D
        // E is independent
        Set<DependencyEdge> edges = Set.of(
            new DependencyEdge("A", "B", DependencyType.RAW),
            new DependencyEdge("B", "A", DependencyType.RAW),
            new DependencyEdge("C", "D", DependencyType.RAW),
            new DependencyEdge("D", "C", DependencyType.RAW)
        );

        List<Set<String>> components = TarjanScc.compute(List.of("A", "B", "C", "D", "E"), edges);
        assertEquals(2, components.size());

        // Should have two 2-element components
        long twoElementComponents = components.stream()
            .filter(c -> c.size() == 2)
            .count();
        assertEquals(2, twoElementComponents);
    }

    @Test
    void complexCycleIsSingleComponent() {
        // A -> B -> C -> A (3-node cycle)
        // D is attached to B but not part of cycle
        Set<DependencyEdge> edges = Set.of(
            new DependencyEdge("A", "B", DependencyType.RAW),
            new DependencyEdge("B", "C", DependencyType.RAW),
            new DependencyEdge("C", "A", DependencyType.RAW),
            new DependencyEdge("D", "B", DependencyType.RAW)
        );

        List<Set<String>> components = TarjanScc.compute(List.of("A", "B", "C", "D"), edges);
        assertEquals(1, components.size());
        assertEquals(Set.of("A", "B", "C"), components.get(0));
    }

    @Test
    void graphWithDiamondPattern() {
        //     -> B ->
        // A -|      |-> D
        //     -> C ->
        // No cycles in diamond pattern
        Set<DependencyEdge> edges = Set.of(
            new DependencyEdge("A", "B", DependencyType.RAW),
            new DependencyEdge("A", "C", DependencyType.RAW),
            new DependencyEdge("B", "D", DependencyType.RAW),
            new DependencyEdge("C", "D", DependencyType.RAW)
        );

        List<Set<String>> components = TarjanScc.compute(List.of("A", "B", "C", "D"), edges);
        assertTrue(components.isEmpty(), "Diamond pattern has no cycles");
    }

    @Test
    void graphWithCycleInDiamond() {
        //     -> B ->
        // A -|      |-> D
        //     -> C -+
        //          ^|
        // Add edge D -> C to create cycle: C <-> D
        Set<DependencyEdge> edges = Set.of(
            new DependencyEdge("A", "B", DependencyType.RAW),
            new DependencyEdge("A", "C", DependencyType.RAW),
            new DependencyEdge("B", "D", DependencyType.RAW),
            new DependencyEdge("C", "D", DependencyType.RAW),
            new DependencyEdge("D", "C", DependencyType.RAW)
        );

        List<Set<String>> components = TarjanScc.compute(List.of("A", "B", "C", "D"), edges);
        // C -> D -> C forms a cycle, so {C, D} is the SCC
        assertEquals(1, components.size());
        assertTrue(components.get(0).containsAll(Set.of("C", "D")));
        assertFalse(components.get(0).contains("B"));
    }

    @Test
    void deepLinearChainDoesNotOverflowStack() {
        // A 20k-node linear chain n0 -> n1 -> ... would recurse ~20k deep in a
        // naive recursive Tarjan and overflow the JVM stack. The iterative
        // implementation must handle it and report no cycles.
        int n = 20_000;
        List<String> nodes = new ArrayList<>(n);
        List<DependencyEdge> edges = new ArrayList<>(n - 1);
        for (int i = 0; i < n; i++) {
            nodes.add("n" + i);
            if (i > 0) {
                edges.add(new DependencyEdge("n" + (i - 1), "n" + i, DependencyType.RAW));
            }
        }
        List<Set<String>> components = TarjanScc.compute(nodes, edges);
        assertTrue(components.isEmpty(), "Deep linear chain has no cycles");
    }

    @Test
    void deepCycleIsSingleComponent() {
        // A large cycle n0 -> n1 -> ... -> n(N-1) -> n0 must be reported as one
        // SCC containing every node, without overflowing the stack.
        int n = 20_000;
        List<String> nodes = new ArrayList<>(n);
        List<DependencyEdge> edges = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            nodes.add("n" + i);
            edges.add(new DependencyEdge("n" + i, "n" + ((i + 1) % n), DependencyType.RAW));
        }
        List<Set<String>> components = TarjanScc.compute(nodes, edges);
        assertEquals(1, components.size());
        assertEquals(n, components.get(0).size());
    }
}
