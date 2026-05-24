package org.nebula.core.vap;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * VAP debug tool: per-plugin performance profiler (arch doc §13.5).
 *
 * <p>Tracks execution time and call counts for each plugin's API operations.
 * Data is accumulated per tick and can be queried for profiling dashboards
 * or automatic fidelity downgrade decisions.
 */
public final class PluginProfiler {

    private final Map<String, PluginMetrics> metrics = new ConcurrentHashMap<>();
    private final List<TickSummary> history = new CopyOnWriteArrayList<>();
    private final int maxHistory;
    private volatile long tickStartNs;

    public PluginProfiler(int maxHistory) {
        this.maxHistory = maxHistory;
    }

    public PluginProfiler() {
        this(100);
    }

    /**
     * Marks the start of a new tick for timing purposes.
     */
    public void tickStart() {
        tickStartNs = System.nanoTime();
    }

    /**
     * Records the start of an operation. Returns a token to pass to {@link #endOperation}.
     *
     * @param pluginName the plugin performing the operation
     * @return start timestamp (nanos)
     */
    public long beginOperation(String pluginName) {
        metrics.computeIfAbsent(pluginName, PluginMetrics::new);
        return System.nanoTime();
    }

    /**
     * Records the completion of an operation.
     *
     * @param pluginName the plugin that performed the operation
     * @param startNs    the token from {@link #beginOperation}
     */
    public void endOperation(String pluginName, long startNs) {
        long elapsed = System.nanoTime() - startNs;
        PluginMetrics m = metrics.computeIfAbsent(pluginName, PluginMetrics::new);
        m.totalNs.add(elapsed);
        m.callCount.increment();
        m.maxNs.updateAndGet(prev -> Math.max(prev, elapsed));
    }

    /**
     * Completes the current tick, creates a summary, and resets per-tick counters.
     */
    public void tickEnd() {
        long tickDuration = System.nanoTime() - tickStartNs;
        Map<String, PluginTickStats> stats = new ConcurrentHashMap<>();

        for (var entry : metrics.entrySet()) {
            PluginMetrics m = entry.getValue();
            long total = m.totalNs.sumThenReset();
            long calls = m.callCount.sumThenReset();
            long max = m.maxNs.getAndSet(0);
            if (calls > 0) {
                stats.put(entry.getKey(), new PluginTickStats(calls, total, max));
            }
        }

        if (!stats.isEmpty()) {
            history.add(new TickSummary(tickDuration, Map.copyOf(stats)));
            while (history.size() > maxHistory) {
                history.remove(0);
            }
        }
    }

    /**
     * Returns the current accumulated metrics for a plugin (within the active tick).
     */
    public PluginTickStats currentStats(String pluginName) {
        PluginMetrics m = metrics.get(pluginName);
        if (m == null) return new PluginTickStats(0, 0, 0);
        return new PluginTickStats(m.callCount.sum(), m.totalNs.sum(), m.maxNs.get());
    }

    /**
     * Returns the tick history.
     */
    public List<TickSummary> history() {
        return Collections.unmodifiableList(history);
    }

    /**
     * Returns the set of tracked plugin names.
     */
    public java.util.Set<String> trackedPlugins() {
        return Collections.unmodifiableSet(metrics.keySet());
    }

    /**
     * Resets all metrics and history.
     */
    public void reset() {
        metrics.clear();
        history.clear();
    }

    /**
     * Per-tick statistics for a single plugin.
     */
    public record PluginTickStats(long callCount, long totalNs, long maxCallNs) {
        public double averageNs() {
            return callCount == 0 ? 0 : (double) totalNs / callCount;
        }
    }

    /**
     * Summary of all plugin activity within a single tick.
     */
    public record TickSummary(long tickDurationNs, Map<String, PluginTickStats> pluginStats) {}

    private static final class PluginMetrics {
        final String pluginName;
        final LongAdder totalNs = new LongAdder();
        final LongAdder callCount = new LongAdder();
        final AtomicLong maxNs = new AtomicLong(0);

        PluginMetrics(String pluginName) {
            this.pluginName = Objects.requireNonNull(pluginName);
        }
    }
}
