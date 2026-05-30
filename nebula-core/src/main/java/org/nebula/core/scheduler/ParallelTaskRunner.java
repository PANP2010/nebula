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
    private final int parallelism;
    private final AtomicLong degradedLayers = new AtomicLong();

    public ParallelTaskRunner(TaskRunner delegate, Executor executor, int parallelThreshold) {
        this(delegate, executor, parallelThreshold, Math.max(1, Runtime.getRuntime().availableProcessors()));
    }

    /**
     * @param parallelism number of worker threads in {@code executor}. The layer is split into
     *                    at most {@code parallelism + 1} slices (the +1 is the caller-runs slice),
     *                    so chunk count matches the real pool size + the coordinator that joins.
     */
    public ParallelTaskRunner(TaskRunner delegate, Executor executor, int parallelThreshold, int parallelism) {
        this.delegate = delegate;
        this.executor = executor;
        this.parallelThreshold = Math.max(2, parallelThreshold);
        this.parallelism = Math.max(1, parallelism);
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

        // Batched fan-out with a CALLER-RUNS last chunk.
        //
        // Partition the layer into K chunks (K = executor parallelism). Submit
        // K-1 chunks to the executor and run the final chunk on THIS (calling)
        // thread, then join the rest. This is the key to actually winning from
        // parallelism: the previous design submitted all K chunks and then had
        // the caller block idle on allOf().get(), so K worker threads + 1 idle
        // coordinator contended for K cores (oversubscription) — measured as a
        // net pessimization (run-phase 3.3ms parallel vs 0.5ms serial). By
        // having the coordinator execute a chunk itself, we use exactly K cores
        // with no idle thread, and a layer that doesn't actually need fan-out
        // (K==1) runs fully inline with zero executor traffic.
        final int n = layer.size();
        // K worker threads + the caller participating = parallelism + 1 executors.
        final int maxChunks = this.parallelism + 1;
        // Don't fan out into more chunks than tasks — pointless.
        final int chunks = Math.min(maxChunks, n);
        // Ceiling division so any leftover lands in the chunks, last is smallest.
        final int chunkSize = (n + chunks - 1) / chunks;

        AtomicReference<Throwable> failure = new AtomicReference<>();

        // Submit chunks [1..chunks) to the executor; chunk 0 runs on this thread.
        final int offloaded = chunks - 1;
        CompletableFuture<?>[] futures = offloaded > 0 ? new CompletableFuture<?>[offloaded] : EMPTY_FUTURES;
        for (int c = 1; c < chunks; c++) {
            final int from = c * chunkSize;
            final int to = Math.min(n, from + chunkSize);
            futures[c - 1] = CompletableFuture.runAsync(() -> runSlice(layer, from, to, failure), executor);
        }

        // Caller runs chunk 0 instead of idling on the join.
        runSlice(layer, 0, Math.min(n, chunkSize), failure);

        if (offloaded > 0) {
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
        }

        Throwable f = failure.get();
        if (f != null) {
            if (f instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(f);
        }
    }

    private static final CompletableFuture<?>[] EMPTY_FUTURES = new CompletableFuture<?>[0];

    /** Runs layer tasks [from, to) serially, recording the first failure and bailing early. */
    private void runSlice(List<TaskNode> layer, int from, int to, AtomicReference<Throwable> failure) {
        for (int i = from; i < to; i++) {
            if (failure.get() != null) return;
            try {
                delegate.run(layer.get(i));
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
                return;
            }
        }
    }
}
