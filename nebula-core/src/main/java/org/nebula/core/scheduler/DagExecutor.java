package org.nebula.core.scheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;

public final class DagExecutor {
    private DagExecutor() {
    }

    public static DagExecutionReport execute(TaskGraph graph) throws DagExecutionException {
        return execute(graph, TaskRunner.DIRECT);
    }

    public static DagExecutionReport execute(TaskGraph graph, TaskRunner runner) throws DagExecutionException {
        return execute(graph, runner, (ExecutorService) null);
    }

    /**
     * Executes the graph using a {@link WorkStealingExecutor} for CPU-affinity-aware
     * parallel dispatch (arch doc §4.4).
     */
    public static DagExecutionReport execute(TaskGraph graph, TaskRunner runner, WorkStealingExecutor executor) throws DagExecutionException {
        List<List<String>> layers = graph.topologicalLayers();
        List<String> completed = Collections.synchronizedList(new ArrayList<>());
        Map<String, TaskNode> tasks = graph.tasks();
        TaskRunner trackingRunner = task -> {
            runner.run(task);
            completed.add(task.taskId());
        };
        for (int layerIndex = 0; layerIndex < layers.size(); layerIndex++) {
            try {
                executor.executeLayer(layers.get(layerIndex), tasks, trackingRunner);
            } catch (DagExecutionException e) {
                throw new DagExecutionException(layerIndex, completed, e.failures());
            }
        }
        return new DagExecutionReport(layers.size(), tasks.size(), layers, completed);
    }

    public static DagExecutionReport execute(TaskGraph graph, TaskRunner runner, ExecutorService executorService) throws DagExecutionException {
        List<List<String>> layers = graph.topologicalLayers();
        List<String> completed = Collections.synchronizedList(new ArrayList<>());
        Map<String, TaskNode> tasks = graph.tasks();

        for (int layerIndex = 0; layerIndex < layers.size(); layerIndex++) {
            List<String> layer = layers.get(layerIndex);
            List<Throwable> failures = executorService == null
                ? executeLayerDirect(layer, tasks, runner, completed)
                : executeLayerParallel(layer, tasks, runner, completed, executorService);
            if (!failures.isEmpty()) {
                throw new DagExecutionException(layerIndex, completed, failures);
            }
        }

        return new DagExecutionReport(layers.size(), tasks.size(), layers, completed);
    }

    private static List<Throwable> executeLayerDirect(
        List<String> layer,
        Map<String, TaskNode> tasks,
        TaskRunner runner,
        List<String> completed
    ) {
        List<Throwable> failures = new ArrayList<>();
        for (String taskId : layer) {
            TaskNode task = requireTask(tasks, taskId);
            try {
                runner.run(task);
                completed.add(taskId);
            } catch (Throwable failure) {
                failures.add(failure);
            }
        }
        return failures;
    }

    private static List<Throwable> executeLayerParallel(
        List<String> layer,
        Map<String, TaskNode> tasks,
        TaskRunner runner,
        List<String> completed,
        ExecutorService executorService
    ) {
        List<Future<?>> futures = new ArrayList<>();
        for (String taskId : layer) {
            TaskNode task = requireTask(tasks, taskId);
            futures.add(executorService.submit((Callable<Void>) () -> {
                runner.run(task);
                completed.add(taskId);
                return null;
            }));
        }

        List<Throwable> failures = new ArrayList<>();
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                failures.add(ex);
            } catch (ExecutionException ex) {
                failures.add(ex.getCause());
            }
        }
        return failures;
    }

    private static TaskNode requireTask(Map<String, TaskNode> tasks, String taskId) {
        TaskNode task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("graph layer references missing task: " + taskId);
        }
        return task;
    }
}
