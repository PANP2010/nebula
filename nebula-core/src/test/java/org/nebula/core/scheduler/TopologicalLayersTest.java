package org.nebula.core.scheduler;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.state.WorldPos;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for TopologicalLayers computation.
 * Validates layer assignment correctness for DAG execution.
 */
class TopologicalLayersTest {

    @Test
    void emptyGraphHasNoLayers() {
        List<List<String>> layers = TopologicalLayers.compute(List.of(), List.of());
        assertTrue(layers.isEmpty());
    }

    @Test
    void singleNodeIsSingleLayer() {
        List<List<String>> layers = TopologicalLayers.compute(
            List.of("A"),
            List.of()
        );
        assertEquals(1, layers.size());
        assertEquals(List.of("A"), layers.get(0));
    }

    @Test
    void independentNodesSameLayer() {
        List<List<String>> layers = TopologicalLayers.compute(
            List.of("A", "B", "C"),
            List.of()
        );
        assertEquals(1, layers.size());
        assertEquals(3, layers.get(0).size());
        assertTrue(layers.get(0).containsAll(List.of("A", "B", "C")));
    }

    @Test
    void simpleDependencyCreatesTwoLayers() {
        List<List<String>> layers = TopologicalLayers.compute(
            List.of("A", "B"),
            List.of(new DependencyEdge("A", "B", DependencyType.RAW))
        );
        assertEquals(2, layers.size());
        assertTrue(layers.get(0).contains("A"));
        assertTrue(layers.get(1).contains("B"));
    }

    @Test
    void layerOrderPreservesDependencies() {
        List<List<String>> layers = TopologicalLayers.compute(
            List.of("A", "B", "C"),
            List.of(
                new DependencyEdge("A", "B", DependencyType.RAW),
                new DependencyEdge("B", "C", DependencyType.RAW)
            )
        );
        assertEquals(3, layers.size());
        // Verify: A before B, B before C
        int aIndex = layers.indexOf(List.of("A"));
        int bIndex = layers.indexOf(List.of("B"));
        int cIndex = layers.indexOf(List.of("C"));
        assertTrue(aIndex < bIndex, "A must be before B");
        assertTrue(bIndex < cIndex, "B must be before C");
    }

    @Test
    void diamondPatternHasThreeLayers() {
        //     -> B ->
        // A -|      |-> D
        //     -> C ->
        List<List<String>> layers = TopologicalLayers.compute(
            List.of("A", "B", "C", "D"),
            List.of(
                new DependencyEdge("A", "B", DependencyType.RAW),
                new DependencyEdge("A", "C", DependencyType.RAW),
                new DependencyEdge("B", "D", DependencyType.RAW),
                new DependencyEdge("C", "D", DependencyType.RAW)
            )
        );
        assertEquals(3, layers.size());
        // Layer 0: A
        assertTrue(layers.get(0).contains("A"));
        // Layer 1: B, C (both depend only on A)
        assertTrue(layers.get(1).containsAll(List.of("B", "C")));
        // Layer 2: D (depends on B and C)
        assertTrue(layers.get(2).contains("D"));
    }

    @Test
    void nodesSortedWithinLayer() {
        List<List<String>> layers = TopologicalLayers.compute(
            List.of("C", "A", "B"),
            List.of()
        );
        // All in same layer, should be sorted
        List<String> layer0 = layers.get(0);
        assertEquals("A", layer0.get(0));
        assertEquals("B", layer0.get(1));
        assertEquals("C", layer0.get(2));
    }

    @Test
    void multipleSourcesConverge() {
        // A -> C
        // B -> C
        List<List<String>> layers = TopologicalLayers.compute(
            List.of("A", "B", "C"),
            List.of(
                new DependencyEdge("A", "C", DependencyType.RAW),
                new DependencyEdge("B", "C", DependencyType.RAW)
            )
        );
        assertEquals(2, layers.size());
        assertTrue(layers.get(0).containsAll(List.of("A", "B")));
        assertTrue(layers.get(1).contains("C"));
    }

    @Test
    void wawsCreateDeterministicOrder() {
        // Two tasks writing same position should be ordered
        WorldPos pos = new WorldPos(0, 0, 64, 0);
        TaskNode taskA = new TaskNode("A", "WRITE", RWSet.builder().writeBlock(pos).build(), () -> {});
        TaskNode taskB = new TaskNode("B", "WRITE", RWSet.builder().writeBlock(pos).build(), () -> {});

        // WAW edge: A -> B (because "A" < "B" lexicographically)
        List<DependencyEdge> edges = RWConflictDetector.edgesFor(taskA, taskB);
        assertEquals(1, edges.size());
        assertEquals("A", edges.get(0).sourceTaskId());
        assertEquals("B", edges.get(0).targetTaskId());
        assertEquals(DependencyType.WAW, edges.get(0).type());
    }
}
