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
 * <p><b>The tick coordinate.</b> {@link #executeTick(long, List)} forwards the
 * game tick to {@link BlockEntityTaskRunner#beginTick(long)} so an RNG-declaring
 * task (dropper/dispenser) is seeded from {@code (tick, blockPos, instance)} —
 * the same coordinate {@link EntityTickExecutor} uses, so the two subsystems'
 * layered-RNG determinism derives identically. The tick-less
 * {@link #executeTick(List)} overload defaults to tick 0, correct for the
 * RNG-free hopper/furnace path (and for callers that predate the RNG seam).
 */
public final class BlockEntityTickExecutor {

    public static final int MAX_CAS_RETRIES = 3;

    private final BlockEntityTaskRunner runner;

    public BlockEntityTickExecutor(BlockEntityTaskRunner runner) {
        this.runner = runner;
    }

    /**
     * Executes the given dirty block-entity tasks for one tick, seeding any
     * RNG-declaring task from the given tick coordinate. Returns the layer count.
     */
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

    /**
     * Executes the given dirty block-entity tasks with no tick coordinate (RNG-free
     * scenarios / legacy callers). Returns the layer count.
     */
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
