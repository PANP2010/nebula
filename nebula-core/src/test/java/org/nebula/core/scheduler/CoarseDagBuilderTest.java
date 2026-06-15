package org.nebula.core.scheduler;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.state.WorldPos;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoarseDagBuilderTest {

    private static final int DIM = 0;

    private static TaskNode task(String id, int readX, int writeX) {
        RWSet rw = RWSet.builder()
            .readBlock(new WorldPos(DIM, readX, 64, 0))
            .writeBlock(new WorldPos(DIM, writeX, 64, 0))
            .build();
        return TaskNode.inert(id, "test", rw);
    }

    @Test
    void emptyInputProducesEmptyGraph() {
        TaskGraph g = CoarseDagBuilder.serialChain(List.of());
        assertTrue(g.tasks().isEmpty());
        assertTrue(g.edges().isEmpty());
    }

    @Test
    void singleTaskHasNoEdges() {
        TaskGraph g = CoarseDagBuilder.serialChain(List.of(task("A", 0, 1)));
        assertEquals(1, g.tasks().size());
        assertTrue(g.edges().isEmpty());
    }

    @Test
    void chainsAllTasksWithNMinusOneEdges() {
        // The chain follows DeterministicOrdering (a stable hash order, not
        // lexicographic), so assert the structural invariant rather than a
        // specific sequence: N tasks ⇒ N-1 chain edges forming a single path.
        TaskNode a = task("A", 0, 1);
        TaskNode b = task("B", 10, 11);
        TaskNode c = task("C", 20, 21);
        TaskGraph g = CoarseDagBuilder.serialChain(List.of(c, a, b));

        assertEquals(3, g.tasks().size());
        assertEquals(2, g.edges().size(), "N tasks ⇒ N-1 chain edges");
        // Every edge is a WAW order-only edge.
        for (DependencyEdge e : g.edges()) {
            assertEquals(DependencyType.WAW, e.type());
        }
        // It is a single path: exactly one node has no incoming edge (head) and
        // exactly one has no outgoing edge (tail).
        long heads = g.tasks().keySet().stream()
            .filter(id -> g.edges().stream().noneMatch(e -> e.targetTaskId().equals(id)))
            .count();
        long tails = g.tasks().keySet().stream()
            .filter(id -> g.edges().stream().noneMatch(e -> e.sourceTaskId().equals(id)))
            .count();
        assertEquals(1, heads, "a chain has exactly one head");
        assertEquals(1, tails, "a chain has exactly one tail");
    }

    @Test
    void chainIsAcyclicAndFullySerial() {
        // A coarse chain must be a legal DAG: one task per topological layer.
        TaskGraph g = CoarseDagBuilder.serialChain(List.of(
            task("A", 0, 1), task("B", 2, 3), task("C", 4, 5), task("D", 6, 7)));

        // No SCCs (acyclic).
        assertTrue(g.stronglyConnectedComponents().isEmpty(), "coarse chain must be acyclic");

        // Fully serial: each topological layer holds exactly one task.
        List<List<String>> layers = g.topologicalLayers();
        assertEquals(4, layers.size(), "fully serial chain ⇒ one task per layer");
        for (List<String> layer : layers) {
            assertEquals(1, layer.size(), "each layer must hold exactly one task");
        }
    }

    @Test
    void conflictingTasksAreStillLegallyOrdered() {
        // Two tasks that genuinely conflict (A writes pos(1), B reads pos(1)).
        // The coarse chain orders them regardless — correctness is preserved
        // because ALL pairs are ordered, conflicting or not. The point: a legal
        // acyclic order exists, with one task per layer.
        TaskNode a = task("A", 5, 1);   // writes pos(1)
        TaskNode b = task("B", 1, 9);   // reads pos(1)
        TaskGraph g = CoarseDagBuilder.serialChain(List.of(b, a));
        assertEquals(1, g.edges().size());
        assertTrue(g.stronglyConnectedComponents().isEmpty());
        List<List<String>> layers = g.topologicalLayers();
        assertEquals(2, layers.size());
        assertEquals(1, layers.get(0).size());
        assertEquals(1, layers.get(1).size());
    }

    @Test
    void isDeterministicAcrossRuns() {
        List<TaskNode> tasks = List.of(task("T3", 0, 1), task("T1", 2, 3), task("T2", 4, 5));
        assertEquals(
            CoarseDagBuilder.serialChain(tasks).edges(),
            CoarseDagBuilder.serialChain(tasks).edges());
    }

    @Test
    void rejectsDuplicateTaskIds() {
        assertThrows(IllegalArgumentException.class, () ->
            CoarseDagBuilder.serialChain(List.of(task("X", 0, 1), task("X", 2, 3))));
    }

    @Test
    void edgesAreNotEmptyForMultipleTasks() {
        assertFalse(CoarseDagBuilder.serialChain(
            List.of(task("A", 0, 1), task("B", 2, 3))).edges().isEmpty());
    }
}
