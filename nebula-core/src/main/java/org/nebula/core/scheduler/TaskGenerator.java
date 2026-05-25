package org.nebula.core.scheduler;

/**
 * Called during microstep extension to generate the next wave of tasks
 * triggered by a completed task's output events.
 *
 * <p>Implementations should inspect the task's written events / RW-set and
 * return any new downstream TaskNodes that should be scheduled in the same tick.
 * Return an empty list if no propagation is needed.
 */
@FunctionalInterface
public interface TaskGenerator {
    /**
     * @param completedTask the task whose execution may trigger new work
     * @return downstream tasks to add to the current tick's DAG;
     *         must not be null, may be empty
     */
    java.util.List<TaskNode> generateFrom(TaskNode completedTask);
}
