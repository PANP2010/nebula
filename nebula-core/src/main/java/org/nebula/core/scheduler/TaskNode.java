package org.nebula.core.scheduler;

import org.nebula.core.rw.RWSet;

import java.util.Objects;

public record TaskNode(String taskId, String taskType, RWSet declaredRWSet, TaskAction action, boolean parallelSafe) {
    public TaskNode(String taskId, String taskType, RWSet declaredRWSet, TaskAction action) {
        this(taskId, taskType, declaredRWSet, action, false);
    }

    public TaskNode {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(taskType, "taskType");
        Objects.requireNonNull(declaredRWSet, "declaredRWSet");
        action = action == null ? () -> { } : action;
        if (taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (taskType.isBlank()) {
            throw new IllegalArgumentException("taskType must not be blank");
        }
    }

    public static TaskNode inert(String taskId, String taskType, RWSet declaredRWSet) {
        return new TaskNode(taskId, taskType, declaredRWSet, () -> { });
    }

    /** Task with known-safe self-only RWSet, eligible for intra-layer parallel. */
    public static TaskNode parallelSafe(String taskId, String taskType, RWSet declaredRWSet, TaskAction action) {
        return new TaskNode(taskId, taskType, declaredRWSet, action, true);
    }
}
