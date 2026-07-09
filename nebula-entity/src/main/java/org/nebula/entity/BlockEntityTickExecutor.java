package org.nebula.entity;

import org.nebula.core.scheduler.DagBuilder;
import org.nebula.core.scheduler.TaskGraph;
import org.nebula.core.scheduler.TaskNode;

import java.util.List;

/**
 * Drives one tick of block-entity ticking (hopper transfers, furnace smelting,
 * …) through the DAG executor (arch doc §3.3, §14.3 Month 4-6) — the
 * block-entity analogue of {@link EntityTickExecutor}.
 *
 * <p>For a set of dirty tasks it:
 * <ol>
 *   <li>Builds the conflict DAG via {@link DagBuilder} (SCC-contracting any
 *       cycles, e.g. a ring of hoppers feeding each other into one compound).</li>
 *   <li>Executes each topological layer in order through the
 *       {@link BlockEntityTaskRunner}.</li>
 *   <li>CAS-commits each layer's buffered writes, re-executing stale-read
 *       losers up to {@link #MAX_CAS_RETRIES} times.</li>
 * </ol>
 *
 * <p>Like {@link EntityTickExecutor} and unlike the redstone
 * {@code MicroStepScheduler}, this does not regenerate downstream tasks within
 * the tick — block-entity ticking is a single pass over the dirty set supplied
 * up front by the caller (the {@code BlockEntityTickHook} drain). The layered
 * CAS commit is what makes a multi-container tick deterministic: two hoppers
 * that both push into one chest touch overlapping slot fields, so they conflict
 * and serialise; two hoppers over disjoint containers share a layer.
 *
 * <p><b>Why the block-entity executor has no {@code beginTick(long)}.</b>
 * {@link EntityTaskRunner#beginTick(long)} exists to seed the entity random
 * budget with the tick coordinate; {@link BlockEntityTaskRunner} carries no
 * per-tick RNG seed (the dropper/dispenser RNG usage is declared in the RW-set,
 * not consumed here in the inert path), so there is nothing to reset per tick.
 * This mirrors the runner's actual surface rather than inventing a hook the
 * subsystem does not have.
 */
public final class BlockEntityTickExecutor {

    public static final int MAX_CAS_RETRIES = 3;

    private final BlockEntityTaskRunner runner;

    public BlockEntityTickExecutor(BlockEntityTaskRunner runner) {
        this.runner = runner;
    }

    /** Executes the given dirty block-entity tasks for one tick. Returns the layer count. */
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
