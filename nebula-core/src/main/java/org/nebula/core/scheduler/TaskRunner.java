package org.nebula.core.scheduler;

import java.util.List;

@FunctionalInterface
public interface TaskRunner {
    TaskRunner DIRECT = task -> task.action().execute();

    void run(TaskNode task) throws Exception;

    /**
     * Executes one DAG layer. Default: serial fallback that calls {@link #run}
     * once per task in declaration order. Implementations targeting
     * intra-layer parallelism (e.g. ForkJoin) override this to fan tasks out.
     *
     * <p>By construction (DagBuilder + SccContractor), tasks within a single
     * topological layer have no read/write conflicts, so it is safe to run
     * them concurrently.
     */
    default void runLayer(List<TaskNode> layer) throws Exception {
        for (TaskNode task : layer) {
            run(task);
        }
    }
}
