package org.nebula.core.scheduler;

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
 *   <li>Build initial DAG from dirty task set</li>
 *   <li>For each topological layer: execute tasks, then invoke MicroStepExtender</li>
 *   <li>If extension produced new tasks, re-layer the graph and continue</li>
 *   <li>Collect deferred tasks for next tick's seed set</li>
 * </ol>
 */
public final class TickPipeline {

    private final TaskGenerator generator;
    private final TaskRunner runner;
    private final int maxMicroSteps;

    public TickPipeline(TaskGenerator generator, TaskRunner runner) {
        this(generator, runner, MicroStepExtender.MAX_MICRO_STEPS);
    }

    public TickPipeline(TaskGenerator generator, TaskRunner runner, int maxMicroSteps) {
        this.generator = Objects.requireNonNull(generator);
        this.runner = Objects.requireNonNull(runner);
        this.maxMicroSteps = maxMicroSteps;
    }

    /**
     * Executes a full tick given the initial dirty task set.
     *
     * @param dirtyTasks tasks to schedule for this tick
     * @return report containing execution stats and deferred tasks for next tick
     * @throws DagExecutionException if a task action fails
     */
    public TickResult execute(Collection<TaskNode> dirtyTasks) throws DagExecutionException {
        TaskGraph graph = DagBuilder.build(dirtyTasks);
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
                // Execute this layer
                for (String taskId : layer) {
                    TaskNode task = current.tasks().get(taskId);
                    if (task == null) continue;
                    try {
                        runner.run(task);
                    } catch (Exception e) {
                        throw new DagExecutionException(totalLayers, allCompleted, List.of(e));
                    }
                    extender.markExecuted(taskId);
                    allCompleted.add(taskId);
                }
                totalLayers++;

                // Extend DAG with microsteps from this layer
                moreWork = extender.extend(layer);
                if (moreWork) {
                    break; // re-layer from the extended graph
                }
            }
        }

        return new TickResult(
            allCompleted,
            extender.deferredToNextTick(),
            totalLayers,
            extender.microStepCount()
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
        int microStepRounds
    ) {}
}
