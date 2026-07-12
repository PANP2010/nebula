package org.nebula.player;

import org.nebula.core.scheduler.DagBuilder;
import org.nebula.core.scheduler.TaskGraph;
import org.nebula.core.scheduler.TaskNode;

import java.util.List;

/**
 * Drives one tick of player tasks through the DAG executor.
 *
 * <p>Mirrors {@link org.nebula.entity.EntityTickExecutor}:
 * <ol>
 *   <li>Builds the conflict DAG via {@link DagBuilder}</li>
 *   <li>Executes each topological layer through the {@link PlayerTaskRunner}</li>
 *   <li>CAS-commits each layer's buffered writes, retrying stale-read losers</li>
 * </ol>
 */
public final class PlayerTickExecutor {

    public static final int MAX_CAS_RETRIES = 3;

    private final PlayerTaskRunner runner;

    public PlayerTickExecutor(PlayerTaskRunner runner) {
        this.runner = runner;
    }

    public int executeTick(long tick, List<TaskNode> dirtyTasks) throws Exception {
        runner.beginTick(tick);
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

    public int executeTick(List<TaskNode> dirtyTasks) throws Exception {
        return executeTick(0L, dirtyTasks);
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
