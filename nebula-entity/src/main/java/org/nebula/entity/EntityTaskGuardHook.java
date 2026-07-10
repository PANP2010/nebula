package org.nebula.entity;

import org.nebula.core.scheduler.TaskNode;

/**
 * Per-task observation hook fired by {@link EntityTaskRunner} around the exact
 * dispatched-task execution window. This lets the plugin reset and check a
 * thread-local RW trace against the same task's declared RW-set.
 */
public interface EntityTaskGuardHook {

    void beforeTask(TaskNode task);

    void afterTask(TaskNode task);
}
