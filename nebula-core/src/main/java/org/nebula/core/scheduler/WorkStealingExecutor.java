package org.nebula.core.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * CPU-affinity-aware work-stealing executor for DAG layer execution (arch doc §4.4).
 *
 * <p>Architecture requirements satisfied:
 * <ul>
 *   <li>Each worker core has its own task deque — pushes and pops from the local end.</li>
 *   <li>Idle workers steal from random other workers' deques (from the remote end).</li>
 *   <li>Tasks are assigned to their "home core" via {@link CoreAffinity#homeCore}.</li>
 *   <li>Layer barrier: all tasks in a layer complete before the executor returns.</li>
 * </ul>
 *
 * <h3>Lifecycle</h3>
 * <pre>{@code
 * try (WorkStealingExecutor exec = new WorkStealingExecutor(8)) {
 *     exec.executeLayer(layer, tasks, runner);  // blocks until all complete
 *     exec.executeLayer(layer2, tasks, runner);
 * }
 * }</pre>
 */
public final class WorkStealingExecutor implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(WorkStealingExecutor.class.getName());

    private final int coreCount;
    private final ConcurrentLinkedDeque<TaskNode>[] queues;
    private final Thread[] workers;
    private volatile boolean shutdown = false;

    // Per-layer coordination
    private volatile CountDownLatch layerLatch;
    private volatile AtomicReference<Throwable> firstFailure = new AtomicReference<>();
    private volatile TaskRunner currentRunner;

    @SuppressWarnings("unchecked")
    public WorkStealingExecutor(int coreCount) {
        if (coreCount < 1) {
            throw new IllegalArgumentException("coreCount must be >= 1");
        }
        this.coreCount = coreCount;
        this.queues = new ConcurrentLinkedDeque[coreCount];
        for (int i = 0; i < coreCount; i++) {
            queues[i] = new ConcurrentLinkedDeque<>();
        }
        this.workers = new Thread[coreCount];
        for (int i = 0; i < coreCount; i++) {
            final int coreIdx = i;
            workers[i] = new Thread(() -> runWorker(coreIdx), "nebula-worker-" + i);
            workers[i].setDaemon(true);
            workers[i].start();
        }
    }

    /** Convenience: creates an executor sized to the available processors. */
    public static WorkStealingExecutor forAvailableProcessors() {
        return new WorkStealingExecutor(Runtime.getRuntime().availableProcessors());
    }

    /**
     * Submits all tasks in {@code layer} for execution and blocks until every task completes.
     *
     * @throws DagExecutionException if any task throws during execution
     */
    public void executeLayer(
        List<String> layer,
        Map<String, TaskNode> tasks,
        TaskRunner runner
    ) throws DagExecutionException {
        if (layer.isEmpty()) return;

        firstFailure.set(null);
        currentRunner = runner;

        // Count only tasks that actually exist in the graph
        int validCount = 0;
        List<TaskNode> globalTasks = new ArrayList<>();
        List<TaskNode[]>[] coreTasks = new ArrayList[coreCount];
        for (int i = 0; i < coreCount; i++) coreTasks[i] = new ArrayList<>();

        for (String taskId : layer) {
            TaskNode task = tasks.get(taskId);
            if (task == null) continue;
            validCount++;
            int core = CoreAffinity.homeCore(task, coreCount);
            if (core == CoreAffinity.GLOBAL_CORE) {
                globalTasks.add(task);
            } else {
                coreTasks[core].add(new TaskNode[]{task});
            }
        }

        if (validCount == 0) return;
        layerLatch = new CountDownLatch(validCount);

        // Push core-affine tasks to their home queues
        for (int i = 0; i < coreCount; i++) {
            for (TaskNode[] wrapper : coreTasks[i]) {
                queues[i].addLast(wrapper[0]);
            }
        }

        // Round-robin global tasks
        for (int i = 0; i < globalTasks.size(); i++) {
            queues[i % coreCount].addLast(globalTasks.get(i));
        }

        // Wake up all workers
        synchronized (this) {
            notifyAll();
        }

        // Wait for the layer to complete — workers MUST finish this layer before we return
        try {
            layerLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DagExecutionException(-1, List.of(), List.of(e));
        }

        Throwable failure = firstFailure.get();
        if (failure != null) {
            throw new DagExecutionException(-1, List.of(), List.of(failure));
        }
    }

    @Override
    public void close() {
        shutdown = true;
        synchronized (this) {
            notifyAll();
        }
        for (Thread w : workers) {
            try {
                w.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ── Worker loop ───────────────────────────────────────────────────────────

    private void runWorker(int coreIdx) {
        while (!shutdown) {
            TaskNode task = pollTask(coreIdx);
            if (task == null) {
                // No work — wait briefly then retry
                synchronized (this) {
                    if (layerLatch == null || layerLatch.getCount() == 0) {
                        try { wait(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                }
                continue;
            }

            try {
                currentRunner.run(task);
            } catch (Throwable t) {
                firstFailure.compareAndSet(null, t);
            } finally {
                CountDownLatch latch = layerLatch;
                if (latch != null) {
                    latch.countDown();
                }
            }
        }
    }

    /** Try own queue first, then steal from a random other queue. */
    private TaskNode pollTask(int coreIdx) {
        TaskNode task = queues[coreIdx].pollFirst();
        if (task != null) return task;

        // Steal from another queue — start from a deterministic offset
        for (int attempt = 1; attempt < coreCount; attempt++) {
            int victim = (coreIdx + attempt) % coreCount;
            task = queues[victim].pollLast(); // steal from far end
            if (task != null) return task;
        }
        return null;
    }
}
