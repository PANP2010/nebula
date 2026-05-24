package org.nebula.core.vap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe queue for plugin tasks submitted during a tick (arch doc §13.2).
 *
 * <p>Plugin operations are collected throughout the tick as plugins interact
 * with the Bukkit API. At the end of the DAG execution (after all kernel tasks),
 * the plugin phase drains this queue and executes tasks in deterministic order
 * (by plugin registration order, then by submission order within each plugin).
 *
 * <p>Virtual thread suspension/resumption is handled at the API interception
 * layer (nebula-agent), not here. This class only manages the task queue.
 */
public final class PluginTaskQueue {

    private final ConcurrentLinkedQueue<PluginTask> queue = new ConcurrentLinkedQueue<>();
    private final ReentrantLock drainLock = new ReentrantLock();
    private final List<String> pluginOrder;

    /**
     * @param pluginOrder registration order of plugins — determines execution order
     *                    within the plugin phase for determinism
     */
    public PluginTaskQueue(List<String> pluginOrder) {
        this.pluginOrder = List.copyOf(pluginOrder);
    }

    public PluginTaskQueue() {
        this(List.of());
    }

    /**
     * Submits a plugin task for later execution in the plugin phase.
     * Thread-safe — may be called from any virtual thread.
     */
    public void submit(PluginTask task) {
        queue.add(task);
    }

    /**
     * Returns the number of pending tasks.
     */
    public int pendingCount() {
        return queue.size();
    }

    /**
     * Drains and executes all pending plugin tasks in deterministic order.
     * Called once per tick after all kernel DAG layers complete.
     *
     * <p>Execution order: plugins are executed in registration order. Within
     * each plugin, tasks execute in submission order. This guarantees the same
     * observable behaviour as sequential Bukkit plugin execution.
     *
     * @return number of tasks executed
     * @throws PluginTaskException if any task action throws
     */
    public int drainAndExecute() throws PluginTaskException {
        drainLock.lock();
        try {
            List<PluginTask> batch = new ArrayList<>();
            PluginTask task;
            while ((task = queue.poll()) != null) {
                batch.add(task);
            }

            if (batch.isEmpty()) return 0;

            // Sort by plugin registration order, then by priority, preserving submission order
            batch.sort(Comparator
                .<PluginTask, Integer>comparing(t -> pluginIndex(t.pluginName()))
                .thenComparingInt(PluginTask::priority));

            int executed = 0;
            for (PluginTask t : batch) {
                try {
                    t.action().execute();
                    executed++;
                } catch (Exception e) {
                    throw new PluginTaskException(t.pluginName(), t.description(), e);
                }
            }
            return executed;
        } finally {
            drainLock.unlock();
        }
    }

    /**
     * Clears all pending tasks without executing them (tick abort scenario).
     */
    public void clear() {
        queue.clear();
    }

    private int pluginIndex(String pluginName) {
        int idx = pluginOrder.indexOf(pluginName);
        return idx >= 0 ? idx : Integer.MAX_VALUE;
    }
}
