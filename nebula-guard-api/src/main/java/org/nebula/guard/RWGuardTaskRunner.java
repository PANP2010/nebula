package org.nebula.guard;

import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.scheduler.TaskRunner;

public final class RWGuardTaskRunner implements TaskRunner {
    private final long tickNumber;

    public RWGuardTaskRunner(long tickNumber) {
        this.tickNumber = tickNumber;
    }

    @Override
    public void run(TaskNode task) throws Exception {
        RWGuard.executeTaskWithTracing(tickNumber, task);
    }
}
