package org.nebula.core.scheduler;

import org.nebula.core.bucket.BucketDagBuilder;
import org.nebula.core.vap.PluginTaskException;
import org.nebula.core.vap.PluginTaskQueue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates a single tick's execution: builds the DAG, executes layer by layer,
 * and extends with microsteps between layers until a fixed point (arch doc §4.4, §2.3).
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Build initial DAG from dirty task set (via {@link BucketDagBuilder} when
 *       set; falls back to {@link DagBuilder} for tests / small graphs)</li>
 *   <li>For each topological layer: execute tasks, then invoke MicroStepExtender</li>
 *   <li>If extension produced new tasks, re-layer the graph and continue</li>
 *   <li>Collect deferred tasks for next tick's seed set</li>
 * </ol>
 */
public final class TickPipeline {

    private final TaskGenerator generator;
    private final TaskRunner runner;
    private final int maxMicroSteps;
    private final PluginTaskQueue pluginQueue;
    private final BucketDagBuilder bucketBuilder;

    public TickPipeline(TaskGenerator generator, TaskRunner runner) {
        this(generator, runner, MicroStepExtender.MAX_MICRO_STEPS, null, null);
    }

    public TickPipeline(TaskGenerator generator, TaskRunner runner, int maxMicroSteps) {
        this(generator, runner, maxMicroSteps, null, null);
    }

    public TickPipeline(TaskGenerator generator, TaskRunner runner, int maxMicroSteps, PluginTaskQueue pluginQueue) {
        this(generator, runner, maxMicroSteps, pluginQueue, null);
    }

    /**
     * Full constructor — pass a non-null {@code bucketBuilder} to use the
     * spatial-bucket O(N×K) DAG construction (arch §4.1-§4.3). Pass {@code null}
     * to fall back to the O(N²) {@link DagBuilder} (acceptable for unit tests
     * with small task sets).
     */
    public TickPipeline(TaskGenerator generator, TaskRunner runner, int maxMicroSteps,
                        PluginTaskQueue pluginQueue, BucketDagBuilder bucketBuilder) {
        this.generator = Objects.requireNonNull(generator);
        this.runner = Objects.requireNonNull(runner);
        this.maxMicroSteps = maxMicroSteps;
        this.pluginQueue = pluginQueue;
        this.bucketBuilder = bucketBuilder;
    }

    private TaskGraph buildInitialGraph(Collection<TaskNode> tasks) {
        // For very small graphs the bucket builder's overhead (parallel pool
        // submit, ConcurrentHashMap, sub-pass timing recording) outweighs its
        // benefit. Fall through to the plain O(N²) DagBuilder, which is
        // ~5µs flat for ≤32 tasks vs ~7ms for the bucket builder cold path.
        if (bucketBuilder != null && tasks.size() >= 64) {
            return bucketBuilder.build(tasks);
        }
        return DagBuilder.build(tasks);
    }

    /**
     * Executes a full tick given the initial dirty task set.
     *
     * @param dirtyTasks tasks to schedule for this tick
     * @return report containing execution stats and deferred tasks for next tick
     * @throws DagExecutionException if a task action fails
     */
    public TickResult execute(Collection<TaskNode> dirtyTasks) throws DagExecutionException {
        long buildStart = System.nanoTime();
        TaskGraph graph = buildInitialGraph(dirtyTasks);
        TickStats.recordBuild(System.nanoTime() - buildStart);
        MicroStepExtender extender = new MicroStepExtender(generator, maxMicroSteps);
        extender.seed(graph);

        List<String> allCompleted = new ArrayList<>();
        int totalLayers = 0;

        boolean moreWork = true;
        while (moreWork) {
            TaskGraph current = extender.currentGraph();
            List<List<String>> layers = current.topologicalLayers();

            // Find layers not yet executed
            List<List<String>> pending = pendingLayers(layers, allCompleted);
            if (pending.isEmpty()) {
                break;
            }

            for (List<String> layer : pending) {
                List<TaskNode> layerNodes = new ArrayList<>(layer.size());
                for (String taskId : layer) {
                    TaskNode task = current.tasks().get(taskId);
                    if (task != null) {
                        layerNodes.add(task);
                    }
                }
                try {
                    long runStart = System.nanoTime();
                    runner.runLayer(layerNodes);
                    TickStats.recordRun(System.nanoTime() - runStart);
                } catch (Exception e) {
                    throw new DagExecutionException(totalLayers, allCompleted, List.of(e));
                }
                for (TaskNode task : layerNodes) {
                    extender.markExecuted(task.taskId());
                    allCompleted.add(task.taskId());
                }
                totalLayers++;

                // Extend DAG with microsteps from this layer
                moreWork = extender.extend(layer);
                if (moreWork) {
                    break; // re-layer from the extended graph
                }
            }
        }

        // Plugin phase: execute all queued plugin tasks after kernel DAG completes
        int pluginTasksExecuted = 0;
        if (pluginQueue != null && pluginQueue.pendingCount() > 0) {
            try {
                pluginTasksExecuted = pluginQueue.drainAndExecute();
            } catch (PluginTaskException e) {
                throw new DagExecutionException(totalLayers, allCompleted,
                    List.of(e));
            }
        }

        return new TickResult(
            allCompleted,
            extender.deferredToNextTick(),
            totalLayers,
            extender.microStepCount(),
            pluginTasksExecuted
        );
    }

    private static List<List<String>> pendingLayers(List<List<String>> allLayers, List<String> alreadyDone) {
        List<List<String>> pending = new ArrayList<>();
        for (List<String> layer : allLayers) {
            List<String> filtered = layer.stream()
                .filter(id -> !alreadyDone.contains(id))
                .toList();
            if (!filtered.isEmpty()) {
                pending.add(filtered);
            }
        }
        return pending;
    }

    public record TickResult(
        List<String> completedTaskIds,
        List<TaskNode> deferredToNextTick,
        int layersExecuted,
        int microStepRounds,
        int pluginTasksExecuted
    ) {}
}
