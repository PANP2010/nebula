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
}
