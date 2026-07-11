package org.nebula.entity;

import org.nebula.core.scheduler.TaskNode;

/** Optional per-task boundary used to verify an explosion action's actual accesses. */
public interface ExplosionTaskGuardHook {

    void beforeTask(TaskNode task);

    void afterTask(TaskNode task);
}
