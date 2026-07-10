package org.nebula.entity;

import org.nebula.core.scheduler.TaskNode;

/** Optional per-task boundary used to verify a fluid action's actual accesses. */
public interface FluidTaskGuardHook {

    void beforeTask(TaskNode task);

    void afterTask(TaskNode task);
}
