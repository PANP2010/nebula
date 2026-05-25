package org.nebula.core.scheduler;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record TaskGraph(Map<String, TaskNode> tasks, Set<DependencyEdge> edges) {
    public TaskGraph {
        tasks = Map.copyOf(tasks);
        edges = Set.copyOf(edges);
    }

    public List<List<String>> topologicalLayers() {
        return TopologicalLayers.compute(tasks.keySet(), edges);
    }

    public List<Set<String>> stronglyConnectedComponents() {
        return TarjanScc.compute(tasks.keySet(), edges);
    }
}
