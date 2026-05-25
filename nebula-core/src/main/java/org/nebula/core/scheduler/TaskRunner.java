package org.nebula.core.scheduler;

@FunctionalInterface
public interface TaskRunner {
    TaskRunner DIRECT = task -> task.action().execute();

    void run(TaskNode task) throws Exception;
}
