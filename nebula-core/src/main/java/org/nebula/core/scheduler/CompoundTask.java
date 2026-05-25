package org.nebula.core.scheduler;

import org.nebula.core.rw.RWSet;

import java.util.List;
import java.util.Set;

/**
 * A synthetic TaskNode representing a contracted SCC.
 * Its action executes the original members in deterministic (sorted) ID order.
 */
public final class CompoundTask {

    public static final String TYPE = "COMPOUND_SCC";

    private CompoundTask() {
    }

    /**
     * Builds a compound TaskNode from a set of member nodes.
     * The compound ID is the sorted, pipe-joined member IDs so it is
     * deterministic and unique within the graph.
     */
    public static TaskNode of(List<TaskNode> members) {
        if (members.isEmpty()) {
            throw new IllegalArgumentException("compound task requires at least one member");
        }
        List<TaskNode> ordered = members.stream()
            .sorted((a, b) -> DeterministicOrdering.compareTaskIds(a.taskId(), b.taskId()))
            .toList();

        String compoundId = "SCC[" + String.join("|", ordered.stream().map(TaskNode::taskId).toList()) + "]";

        RWSet mergedRW = ordered.stream()
            .map(TaskNode::declaredRWSet)
            .reduce(RWSet.empty(), RWSet::merge);

        TaskAction action = () -> {
            for (TaskNode member : ordered) {
                member.action().execute();
            }
        };

        return new TaskNode(compoundId, TYPE, mergedRW, action);
    }

    /** Returns true if this task was created by SCC contraction. */
    public static boolean isCompound(TaskNode task) {
        return TYPE.equals(task.taskType());
    }

    /** Returns the member ID set encoded in a compound task's ID, or empty set if not compound. */
    public static Set<String> memberIds(TaskNode task) {
        if (!isCompound(task)) {
            return Set.of();
        }
        String inner = task.taskId().substring("SCC[".length(), task.taskId().length() - 1);
        return Set.of(inner.split("\\|"));
    }
}
