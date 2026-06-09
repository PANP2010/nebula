package org.nebula.entity;

import org.nebula.core.scheduler.DagBuilder;
import org.nebula.core.scheduler.TaskGraph;
import org.nebula.core.scheduler.TaskNode;

import java.util.List;

/**
 * Drives one tick of entity physics through the DAG executor (arch doc §6.3).
 *
 * <p>For a set of dirty tasks it:
 * <ol>
 *   <li>Builds the conflict DAG via {@link DagBuilder} (SCC-contracting any
 *       cycles, e.g. mutual collision-response pairs).</li>
 *   <li>Executes each topological layer in order through the
 *       {@link EntityTaskRunner}.</li>
 *   <li>CAS-commits each layer's buffered writes, re-executing stale-read
 *       losers up to {@link #MAX_CAS_RETRIES} times.</li>
 * </ol>
 *
 * <p>Unlike the redstone {@code MicroStepScheduler}, this does not regenerate
 * downstream tasks within the tick — entity physics here is a single pass
 * (move → resolve), with collision pairs supplied up front by the caller. The
 * layered commit is what makes a multi-entity tick deterministic.
 */
public final class EntityTickExecutor {

    public static final int MAX_CAS_RETRIES = 3;

    private final EntityTaskRunner runner;

    public EntityTickExecutor(EntityTaskRunner runner) {
        this.runner = runner;
    }

    /** Executes the given dirty tasks for one tick. Returns the layer count. */
    public int executeTick(List<TaskNode> dirtyTasks) throws Exception {
        if (dirtyTasks.isEmpty()) {
            return 0;
        }
        TaskGraph graph = DagBuilder.build(dirtyTasks);
        List<List<String>> layers = graph.topologicalLayers();

        for (List<String> layer : layers) {
            for (String taskId : layer) {
                TaskNode task = graph.tasks().get(taskId);
                if (task != null) {
                    runner.run(task);
                }
            }
            commitLayerWithRetry(graph, layer);
        }
        return layers.size();
    }

    private void commitLayerWithRetry(TaskGraph graph, List<String> layer) throws Exception {
        List<String> failed = runner.commitLayer();
        int retries = 0;
        while (!failed.isEmpty() && retries < MAX_CAS_RETRIES) {
            runner.resetLayer();
            for (String taskId : failed) {
                TaskNode task = graph.tasks().get(taskId);
                if (task != null) {
                    runner.run(task);
                }
            }
            failed = runner.commitLayer();
            retries++;
        }
        runner.resetLayer();
    }
}
