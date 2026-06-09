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

    /**
     * Iterative Tarjan SCC traversal.
     *
     * <p>Uses an explicit work stack instead of native recursion so that long
     * dependency chains (e.g. a redstone line or collision chain of several
     * thousand tasks) cannot overflow the JVM call stack. The visit order,
     * low-link propagation, and component-emission semantics are identical to
     * the textbook recursive formulation.
     */
    private static void strongConnect(String start, State state) {
        ArrayDeque<Frame> callStack = new ArrayDeque<>();
        visitNode(start, state);
        callStack.push(new Frame(start));

        while (!callStack.isEmpty()) {
            Frame frame = callStack.peek();
            String node = frame.node;
            List<String> neighbors = state.adjacency.getOrDefault(node, List.of());

            boolean descended = false;
            while (frame.next < neighbors.size()) {
                String target = neighbors.get(frame.next);
                frame.next++;
                if (!state.indices.containsKey(target)) {
                    // Equivalent to the recursive call: descend into target,
                    // resuming this frame afterwards.
                    visitNode(target, state);
                    callStack.push(new Frame(target));
                    descended = true;
                    break;
                } else if (state.onStack.contains(target)) {
                    state.lowLinks.put(node,
                        Math.min(state.lowLinks.get(node), state.indices.get(target)));
                }
            }
            if (descended) {
                continue;
            }

            // All neighbors processed: this node is fully explored.
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

            callStack.pop();
            // Propagate low-link to the parent frame, mirroring the post-return
            // update `lowLinks[parent] = min(lowLinks[parent], lowLinks[node])`.
            Frame parent = callStack.peek();
            if (parent != null) {
                state.lowLinks.put(parent.node,
                    Math.min(state.lowLinks.get(parent.node), state.lowLinks.get(node)));
            }
        }
    }

    private static void visitNode(String node, State state) {
        state.indices.put(node, state.nextIndex);
        state.lowLinks.put(node, state.nextIndex);
        state.nextIndex++;
        state.stack.push(node);
        state.onStack.add(node);
    }

    /** A pending DFS frame: the node being explored and its next neighbor index. */
    private static final class Frame {
        private final String node;
        private int next;

        private Frame(String node) {
            this.node = node;
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
