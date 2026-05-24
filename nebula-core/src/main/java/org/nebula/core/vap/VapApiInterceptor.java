package org.nebula.core.vap;

import org.nebula.core.scheduler.TaskAction;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Level 0 API interceptor: suspends the calling virtual thread and enqueues
 * the operation as a {@link PluginTask} for deferred execution in the plugin
 * phase (arch doc §13.2).
 *
 * <p>When a plugin calls a Bukkit API method (e.g. entity.teleport()), the
 * JVM agent rewrites the call site to invoke this interceptor. The virtual
 * thread is parked until the plugin phase executes the task and completes
 * the future.
 *
 * <p>Thread model: the interceptor is called on the plugin's virtual thread.
 * The task executes on the tick thread during the plugin phase.
 */
public final class VapApiInterceptor {

    private static final long DEFAULT_TIMEOUT_MS = 10_000;

    private final PluginTaskQueue taskQueue;
    private final long timeoutMs;

    public VapApiInterceptor(PluginTaskQueue taskQueue, long timeoutMs) {
        this.taskQueue = Objects.requireNonNull(taskQueue);
        this.timeoutMs = timeoutMs;
    }

    public VapApiInterceptor(PluginTaskQueue taskQueue) {
        this(taskQueue, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Intercepts a Bukkit API call: wraps the operation as a task, submits it
     * to the plugin queue, and suspends the calling virtual thread until the
     * plugin phase executes it.
     *
     * @param pluginName  the calling plugin's name
     * @param description human-readable description of the intercepted call
     * @param action      the actual API operation to execute in the plugin phase
     * @param priority    task priority (lower = earlier)
     * @return a future that completes when the task is executed
     */
    public CompletableFuture<Void> intercept(String pluginName, String description,
                                              TaskAction action, int priority) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        TaskAction wrappedAction = () -> {
            try {
                action.execute();
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
                throw e;
            }
        };

        taskQueue.submit(new PluginTask(pluginName, description, wrappedAction, priority));
        return future;
    }

    /**
     * Synchronous intercept: suspends the calling virtual thread until the task
     * completes. This is the primary entry point used by the JVM agent's
     * rewritten call sites.
     *
     * @param pluginName  the calling plugin's name
     * @param description human-readable description of the intercepted call
     * @param action      the actual API operation
     * @throws VapInterceptException if the task fails or times out
     */
    public void interceptAndWait(String pluginName, String description,
                                  TaskAction action) throws VapInterceptException {
        interceptAndWait(pluginName, description, action, 0);
    }

    /**
     * Synchronous intercept with priority.
     */
    public void interceptAndWait(String pluginName, String description,
                                  TaskAction action, int priority) throws VapInterceptException {
        CompletableFuture<Void> future = intercept(pluginName, description, action, priority);
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new VapInterceptException(pluginName, description,
                "timed out after " + timeoutMs + "ms waiting for plugin phase execution");
        } catch (ExecutionException e) {
            throw new VapInterceptException(pluginName, description, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VapInterceptException(pluginName, description, "interrupted");
        }
    }

    /**
     * Returns the configured timeout in milliseconds.
     */
    public long timeoutMs() {
        return timeoutMs;
    }
}
