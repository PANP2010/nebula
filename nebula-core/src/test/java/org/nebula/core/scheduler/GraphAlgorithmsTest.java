package org.nebula.core.scheduler;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.state.WorldPos;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphAlgorithmsTest {
    @Test
    void tarjanIdentifiesStronglyConnectedComponents() {
        List<String> nodes = List.of("a", "b", "c", "d");
        List<DependencyEdge> edges = List.of(
            new DependencyEdge("a", "b", DependencyType.RAW),
            new DependencyEdge("b", "a", DependencyType.WAR),
            new DependencyEdge("c", "d", DependencyType.RAW)
        );

        List<Set<String>> components = TarjanScc.compute(nodes, edges);

        // Only {a, b} forms a cycle, c and d are linear
        assertEquals(1, components.size());
        assertTrue(components.get(0).containsAll(Set.of("a", "b")));
    }

    @Test
    void topologicalLayeringIsStable() {
        WorldPos pos = new WorldPos(0, 0, 64, 0);
        TaskNode write = TaskNode.inert("a-write", "WRITE", RWSet.builder().writeBlock(pos).build());
        TaskNode read = TaskNode.inert("b-read", "READ", RWSet.builder().readBlock(pos).build());
        TaskNode independent = TaskNode.inert("c-independent", "INDEPENDENT", RWSet.empty());

        TaskGraph graph = DagBuilder.build(List.of(read, independent, write));

        assertEquals(List.of(List.of("a-write", "c-independent"), List.of("b-read")), graph.topologicalLayers());
    }

    @Test
    void buildFastIsInputOrderIndependent() {
        // Pins the invariant that DagBuilder.buildFast no longer sorts the full
        // task list: layered output must be identical regardless of input order,
        // because TopologicalLayers re-sorts within layers and only the small
        // global-touching subset is sorted for edge determinism.
        java.util.List<TaskNode> base = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            base.add(TaskNode.parallelSafe("e-" + i, "ENTITY",
                RWSet.builder().writeEntity(new org.nebula.core.state.EntityField(i, "position")).build(),
                () -> {}));
        }
        for (int i = 0; i < 5; i++) {
            base.add(new TaskNode("g-" + i, "GLOBAL",
                RWSet.builder().readGlobal(org.nebula.core.state.GlobalKey.ALL)
                    .writeGlobal(org.nebula.core.state.GlobalKey.ALL).build(),
                () -> {}));
        }

        List<List<String>> reference = DagBuilder.buildFast(base, new SccContractor()).topologicalLayers();

        java.util.Random rng = new java.util.Random(42);
        for (int trial = 0; trial < 5; trial++) {
            java.util.List<TaskNode> shuffled = new java.util.ArrayList<>(base);
            java.util.Collections.shuffle(shuffled, rng);
            List<List<String>> layers = DagBuilder.buildFast(shuffled, new SccContractor()).topologicalLayers();
            assertEquals(reference, layers, "shuffled input trial " + trial + " produced different layers");
        }
    }
}
