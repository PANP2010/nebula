package org.nebula.core.scheduler;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Layer-parallel TaskRunner. Each call to {@link #runLayer} fans the layer's
 * tasks out across an {@link Executor}; the call returns when every task has
 * either completed or thrown.
 *
 * <p>Layer membership is computed by {@link DagBuilder} from non-conflicting
 * RW-sets, so concurrent execution within a layer is data-race free by
 * construction.
 *
 * <p>Single-task layers, layers below {@code parallelThreshold}, or layers
 * containing any task without the {@code parallelSafe} flag fall back to
 * inline execution to avoid submission overhead.
 */
public final class ParallelTaskRunner implements TaskRunner {

    private final TaskRunner delegate;
    private final Executor executor;
    private final int parallelThreshold;
    private final AtomicLong degradedLayers = new AtomicLong();

    public ParallelTaskRunner(TaskRunner delegate, Executor executor, int parallelThreshold) {
        this.delegate = delegate;
        this.executor = executor;
        this.parallelThreshold = Math.max(2, parallelThreshold);
    }

    public ParallelTaskRunner(TaskRunner delegate, Executor executor) {
        this(delegate, executor, 2);
    }

    public long degradedLayers() { return degradedLayers.get(); }

    @Override
    public void run(TaskNode task) throws Exception {
        delegate.run(task);
    }

    @Override
    public void runLayer(List<TaskNode> layer) throws Exception {
        // Degradation check: fall back to serial if any task lacks parallelSafe flag.
        // This handles layers where NMS annotations haven't been verified yet —
        // the task builder marks known-safe tasks (self-only RWSet) as parallelSafe.
        boolean allParallelSafe = layer.size() >= parallelThreshold
            && layer.stream().allMatch(TaskNode::parallelSafe);

        if (!allParallelSafe) {
            degradedLayers.incrementAndGet();
            for (TaskNode task : layer) {
                delegate.run(task);
            }
            return;
        }

        // Batched fan-out: instead of one CompletableFuture per task (which
        // allocates 3000+ futures + lambdas for entity-heavy layers and floods
        // the executor with tiny submissions), partition the layer into K
        // chunks where K = number of executor threads, and submit one
        // future per chunk. Each chunk runs its slice serially.  This keeps
        // task-submission overhead O(K) instead of O(N).
        final int n = layer.size();
        final int parallelism = Math.max(1, Runtime.getRuntime().availableProcessors());
        // Don't fan out into more chunks than tasks — pointless.
        final int chunks = Math.min(parallelism, n);
        // Floor + 1 so any leftover lands in the last chunk.
        final int chunkSize = (n + chunks - 1) / chunks;

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CompletableFuture<?>[] futures = new CompletableFuture<?>[chunks];
        for (int c = 0; c < chunks; c++) {
            final int from = c * chunkSize;
            final int to = Math.min(n, from + chunkSize);
            futures[c] = CompletableFuture.runAsync(() -> {
                for (int i = from; i < to; i++) {
                    if (failure.get() != null) return;
                    try {
                        delegate.run(layer.get(i));
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        return;
                    }
                }
            }, executor);
        }

        try {
            CompletableFuture.allOf(futures).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(cause);
        }

        Throwable f = failure.get();
        if (f != null) {
            if (f instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(f);
        }
    }
}
