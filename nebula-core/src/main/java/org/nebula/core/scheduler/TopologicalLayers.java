package org.nebula.core.scheduler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

public final class TopologicalLayers {
    private TopologicalLayers() {
    }

    public static List<List<String>> compute(Collection<String> nodeIds, Collection<DependencyEdge> edges) {
        Map<String, Set<String>> outgoing = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        nodeIds.forEach(id -> {
            outgoing.put(id, new TreeSet<>());
            indegree.put(id, 0);
        });

        for (DependencyEdge edge : edges) {
            if (outgoing.computeIfAbsent(edge.sourceTaskId(), ignored -> new TreeSet<>()).add(edge.targetTaskId())) {
                indegree.merge(edge.targetTaskId(), 1, Integer::sum);
                indegree.putIfAbsent(edge.sourceTaskId(), 0);
            }
        }

        PriorityQueue<String> ready = new PriorityQueue<>(Comparator.naturalOrder());
        indegree.forEach((node, count) -> {
            if (count == 0) {
                ready.add(node);
            }
        });

        List<List<String>> layers = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        while (!ready.isEmpty()) {
            List<String> layer = new ArrayList<>();
            int layerSize = ready.size();
            for (int i = 0; i < layerSize; i++) {
                String node = ready.remove();
                if (!visited.add(node)) {
                    continue;
                }
                layer.add(node);
            }

            for (String node : layer) {
                for (String target : outgoing.getOrDefault(node, Set.of())) {
                    int nextDegree = indegree.merge(target, -1, Integer::sum);
                    if (nextDegree == 0) {
                        ready.add(target);
                    }
                }
            }

            if (!layer.isEmpty()) {
                layers.add(List.copyOf(layer));
            }
        }

        if (visited.size() != indegree.size()) {
            throw new IllegalStateException("graph contains a cycle; run SCC detection before layering");
        }
        return List.copyOf(layers);
    }
}
