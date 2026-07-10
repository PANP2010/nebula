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

    /**
     * Returns the underlying subsystem runner this runner ultimately delegates
     * to, or {@code this} if it is not a wrapper.
     *
     * <p>A parallel/decorating runner (e.g. {@link ParallelTaskRunner}) executes
     * a layer's tasks by delegating {@link #run} to a subsystem runner such as
     * the redstone runner. That subsystem runner owns the layer's write-buffer
     * and CAS-commit lifecycle (see {@link LayerCommitting}) and its state store,
     * so a scheduler must be able to locate it even when it is wrapped for
     * parallel execution — otherwise commits and change-detection are silently
     * skipped. This seam lets the scheduler resolve the real committer through
     * the wrapper. The default returns {@code this}, so a bare subsystem runner
     * (the serial path) is unchanged.
     */
    default TaskRunner unwrap() {
        return this;
    }
}
