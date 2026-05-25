package org.nebula.core.scheduler;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.state.WorldPos;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for DAG build and execution pipeline.
 * Validates the core assumptions for Phase -1.
 */
class DagPipelineIntegrationTest {

    @Test
    void dagBuilderResolvesAllDependencies() {
        WorldPos pos1 = new WorldPos(0, 0, 64, 0);
        WorldPos pos2 = new WorldPos(0, 0, 64, 1);

        // Task A writes pos1
        TaskNode taskA = new TaskNode("A", "WRITE",
            RWSet.builder().writeBlock(pos1).build(),
            () -> {});

        // Task B reads pos1 (RAW dependency on A)
        TaskNode taskB = new TaskNode("B", "READ",
            RWSet.builder().readBlock(pos1).build(),
            () -> {});

        // Task C writes pos2 (no dependency on A or B)
        TaskNode taskC = new TaskNode("C", "WRITE",
            RWSet.builder().writeBlock(pos2).build(),
            () -> {});

        // Task D reads pos2 (RAW dependency on C)
        TaskNode taskD = new TaskNode("D", "READ",
            RWSet.builder().readBlock(pos2).build(),
            () -> {});

        TaskGraph graph = DagBuilder.build(List.of(taskD, taskC, taskB, taskA));

        // Verify layer structure
        List<List<String>> layers = graph.topologicalLayers();
        assertTrue(layers.size() >= 2, "Should have at least 2 layers");

        // Layer 0 should contain A and C (no dependencies)
        assertTrue(layers.get(0).containsAll(List.of("A", "C")),
            "First layer should contain A and C (independent tasks)");

        // Layer 1 should contain B and D
        assertTrue(layers.get(1).containsAll(List.of("B", "D")),
            "Second layer should contain B and D");
    }

    @Test
    void dagBuilderDetectsWriteWriteConflict() {
        WorldPos pos = new WorldPos(0, 1, 64, 1);

        // Two tasks write to same position
        TaskNode taskA = new TaskNode("A", "WRITE",
            RWSet.builder().writeBlock(pos).build(),
            () -> {});
        TaskNode taskB = new TaskNode("B", "WRITE",
            RWSet.builder().writeBlock(pos).build(),
            () -> {});

        TaskGraph graph = DagBuilder.build(List.of(taskA, taskB));

        // Should have exactly one edge (WAW)
        assertEquals(1, graph.edges().size());

        // Verify deterministic ordering (lexicographic by taskId)
        DependencyEdge edge = graph.edges().iterator().next();
        assertEquals("A", edge.sourceTaskId());
        assertEquals(DependencyType.WAW, edge.type());
    }

    @Test
    void dagBuilderHandlesNoConflicts() {
        WorldPos pos1 = new WorldPos(0, 0, 64, 0);
        WorldPos pos2 = new WorldPos(0, 10, 64, 0); // Different location

        TaskNode taskA = new TaskNode("A", "OP",
            RWSet.builder().writeBlock(pos1).build(),
            () -> {});
        TaskNode taskB = new TaskNode("B", "OP",
            RWSet.builder().writeBlock(pos2).build(),
            () -> {});

        TaskGraph graph = DagBuilder.build(List.of(taskA, taskB));

        // Should have no edges
        assertTrue(graph.edges().isEmpty(),
            "Tasks with non-overlapping RW sets should have no dependencies");
    }

    @Test
    void dagExecutorPreservesExecutionOrder() throws Exception {
        StringBuilder order = new StringBuilder();

        WorldPos pos = new WorldPos(0, 0, 64, 0);

        TaskNode taskA = new TaskNode("A", "WRITE",
            RWSet.builder().writeBlock(pos).build(),
            () -> order.append("A"));
        TaskNode taskB = new TaskNode("B", "READ",
            RWSet.builder().readBlock(pos).build(),
            () -> order.append("B"));
        TaskNode taskC = new TaskNode("C", "INDEPENDENT",
            RWSet.empty(),
            () -> order.append("C"));

        TaskGraph graph = DagBuilder.build(List.of(taskA, taskB, taskC));
        DagExecutor.execute(graph);

        String result = order.toString();
        // A must execute before B (RAW dependency)
        assertTrue(result.indexOf('A') < result.indexOf('B'),
            "A must execute before B due to RAW dependency");
        // C is independent, can be anywhere
    }

    @Test
    void dagExecutorParallelizesIndependentTasks() throws Exception {
        WorldPos pos1 = new WorldPos(0, 0, 64, 0);
        WorldPos pos2 = new WorldPos(0, 10, 64, 0);

        TaskNode taskA = new TaskNode("A", "WRITE",
            RWSet.builder().writeBlock(pos1).build(),
            () -> {});
        TaskNode taskB = new TaskNode("B", "WRITE",
            RWSet.builder().writeBlock(pos2).build(),
            () -> {});

        TaskGraph graph = DagBuilder.build(List.of(taskA, taskB));

        // Both should be in first layer (no dependencies)
        List<List<String>> layers = graph.topologicalLayers();
        assertEquals(1, layers.size());
        assertTrue(layers.get(0).containsAll(List.of("A", "B")));
    }
}
