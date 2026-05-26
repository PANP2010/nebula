package org.nebula.core.scheduler;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
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
 * <p>Single-task layers (or layers below {@code parallelThreshold}) fall back
 * to inline execution to avoid submission overhead.
 */
public final class ParallelTaskRunner implements TaskRunner {

    private final TaskRunner delegate;
    private final Executor executor;
    private final int parallelThreshold;

    public ParallelTaskRunner(TaskRunner delegate, Executor executor, int parallelThreshold) {
        this.delegate = delegate;
        this.executor = executor;
        this.parallelThreshold = Math.max(2, parallelThreshold);
    }

    public ParallelTaskRunner(TaskRunner delegate, Executor executor) {
        this(delegate, executor, 2);
    }

    @Override
    public void run(TaskNode task) throws Exception {
        delegate.run(task);
    }

    @Override
    public void runLayer(List<TaskNode> layer) throws Exception {
        if (layer.size() < parallelThreshold) {
            for (TaskNode task : layer) {
                delegate.run(task);
            }
            return;
        }

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CompletableFuture<?>[] futures = new CompletableFuture<?>[layer.size()];
        for (int i = 0; i < layer.size(); i++) {
            TaskNode task = layer.get(i);
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    delegate.run(task);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
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
