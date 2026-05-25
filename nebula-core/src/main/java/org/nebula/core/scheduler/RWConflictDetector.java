package org.nebula.core.scheduler;

import java.util.ArrayList;
import java.util.List;

public final class RWConflictDetector {
    private RWConflictDetector() {
    }

    /**
     * Returns dependency edges between {@code left} and {@code right}.
     *
     * <p>Dependency rules (arch doc §2.1):
     * <ul>
     *   <li><b>RAW</b>: W(left) ∩ R(right) ≠ ∅ → left must precede right.</li>
     *   <li><b>WAW</b>: W(left) ∩ W(right) ≠ ∅ → deterministic order by task-ID hash.</li>
     *   <li><b>Symmetric RAW</b>: W(right) ∩ R(left) ≠ ∅ → right must precede left.
     *       This is the reverse-direction check needed to detect cycles (e.g. redstone
     *       loops, collision chains).</li>
     * </ul>
     *
     * <p>WAR (anti-dependency) is intentionally omitted.  WAR and symmetric RAW cover
     * the same positional intersection and always appear together, making them mutually
     * contradictory for simple write-then-read pairs (RAW A→B + WAR B→A = false cycle).
     * The write-buffer / snapshot commit mechanism preserves anti-dependency correctness
     * without DAG edges, so WAR edges are not needed in the graph.
     */
    public static List<DependencyEdge> edgesFor(TaskNode left, TaskNode right) {
        List<DependencyEdge> edges = new ArrayList<>();

        // RAW: left writes something right reads → left must execute before right
        if (left.declaredRWSet().hasReadWriteConflictWith(right.declaredRWSet())) {
            edges.add(new DependencyEdge(left.taskId(), right.taskId(), DependencyType.RAW));
        }

        // WAW: both write the same key → deterministic ordering resolves conflict
        if (left.declaredRWSet().hasWriteWriteConflictWith(right.declaredRWSet())) {
            if (DeterministicOrdering.compareTaskIds(left.taskId(), right.taskId()) <= 0) {
                edges.add(new DependencyEdge(left.taskId(), right.taskId(), DependencyType.WAW));
            } else {
                edges.add(new DependencyEdge(right.taskId(), left.taskId(), DependencyType.WAW));
            }
        }

        // Symmetric RAW: right writes something left reads → right must execute before left.
        // This is what allows cycle detection (e.g. A reads X writes Y, B reads Y writes X →
        // both A→B and B→A exist, forming an SCC that SccContractor will contract).
        if (right.declaredRWSet().hasReadWriteConflictWith(left.declaredRWSet())) {
            edges.add(new DependencyEdge(right.taskId(), left.taskId(), DependencyType.RAW));
        }

        return List.copyOf(edges);
    }
}
