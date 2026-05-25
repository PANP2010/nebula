package org.nebula.core.scheduler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class TarjanScc {
    private TarjanScc() {
    }

    public static List<Set<String>> compute(Collection<String> nodeIds, Collection<DependencyEdge> edges) {
        Map<String, List<String>> adjacency = new HashMap<>();
        nodeIds.forEach(id -> adjacency.put(id, new ArrayList<>()));
        for (DependencyEdge edge : edges) {
            adjacency.computeIfAbsent(edge.sourceTaskId(), ignored -> new ArrayList<>()).add(edge.targetTaskId());
        }
        adjacency.values().forEach(list -> list.sort(Comparator.naturalOrder()));

        State state = new State(adjacency);
        nodeIds.stream().sorted().forEach(node -> {
            if (!state.indices.containsKey(node)) {
                strongConnect(node, state);
            }
        });
        return List.copyOf(state.components);
    }

    private static void strongConnect(String node, State state) {
        state.indices.put(node, state.nextIndex);
        state.lowLinks.put(node, state.nextIndex);
        state.nextIndex++;
        state.stack.push(node);
        state.onStack.add(node);

        for (String target : state.adjacency.getOrDefault(node, List.of())) {
            if (!state.indices.containsKey(target)) {
                strongConnect(target, state);
                state.lowLinks.put(node, Math.min(state.lowLinks.get(node), state.lowLinks.get(target)));
            } else if (state.onStack.contains(target)) {
                state.lowLinks.put(node, Math.min(state.lowLinks.get(node), state.indices.get(target)));
            }
        }

        if (state.lowLinks.get(node).equals(state.indices.get(node))) {
            Set<String> component = new TreeSet<>();
            String current;
            do {
                current = state.stack.pop();
                state.onStack.remove(current);
                component.add(current);
            } while (!current.equals(node));
            // Only report SCCs with actual cycles (>=2 nodes or self-loop)
            if (component.size() > 1 || state.adjacency.get(node).contains(node)) {
                state.components.add(component);
            }
        }
    }

    private static final class State {
        private final Map<String, List<String>> adjacency;
        private final Map<String, Integer> indices = new HashMap<>();
        private final Map<String, Integer> lowLinks = new HashMap<>();
        private final ArrayDeque<String> stack = new ArrayDeque<>();
        private final Set<String> onStack = new HashSet<>();
        private final List<Set<String>> components = new ArrayList<>();
        private int nextIndex;

        private State(Map<String, List<String>> adjacency) {
            this.adjacency = adjacency;
        }
    }
}
