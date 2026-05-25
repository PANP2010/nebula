package org.nebula.plugin;

import org.nebula.redstone.MicroStepScheduler;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Accumulates statistics from the DAG shadow executor running alongside Folia.
 *
 * <p>In OBSERVE mode the shadow executor fires {@link MicroStepScheduler#executeTick}
 * on every dirty-set without touching game state.  This proves the DAG pipeline
 * can process real inputs within one tick budget, and captures topology metrics
 * (tasks/tick, layers/tick, microsteps/tick) required by arch doc §14.3.
 */
public final class ShadowExecutionMonitor {

    private final Logger log;

    private final AtomicLong ticks          = new AtomicLong();
    private final AtomicLong totalTasks     = new AtomicLong();
    private final AtomicLong totalLayers    = new AtomicLong();
    private final AtomicLong totalMicros    = new AtomicLong();
    private final AtomicLong totalOverheadNs = new AtomicLong();

    // Peak values
    private volatile int peakTasks  = 0;
    private volatile int peakLayers = 0;
    private volatile int peakMicros = 0;

    // Mismatch tracking (future: compare inert result vs observed state)
    private final AtomicLong executionErrors = new AtomicLong();

    public ShadowExecutionMonitor(Logger log) {
        this.log = log;
    }

    /** Record one completed shadow tick. */
    public void record(MicroStepScheduler.TickResult result, long overheadNs) {
        long tick = ticks.incrementAndGet();
        totalTasks.addAndGet(result.totalTasks());
        totalLayers.addAndGet(result.totalLayers());
        totalMicros.addAndGet(result.microSteps());
        totalOverheadNs.addAndGet(overheadNs);

        if (result.totalTasks()  > peakTasks)  peakTasks  = result.totalTasks();
        if (result.totalLayers() > peakLayers) peakLayers = result.totalLayers();
        if (result.microSteps()  > peakMicros) peakMicros = result.microSteps();

        if (tick % 200 == 0) {
            log.info("Shadow DAG: " + summary());
        }
    }

    /** Record a shadow execution failure. */
    public void recordError(String regionId, Throwable t) {
        executionErrors.incrementAndGet();
        log.warning("Shadow DAG error in region " + regionId + ": " + t.getMessage());
    }

    public String summary() {
        long t = ticks.get();
        if (t == 0) return "no ticks yet";
        double avgTasks   = (double) totalTasks.get()      / t;
        double avgLayers  = (double) totalLayers.get()     / t;
        double avgMicros  = (double) totalMicros.get()     / t;
        double avgUs      = totalOverheadNs.get() / 1_000.0 / t;
        return String.format(
            "%d ticks | avg %.1f tasks/%.1f layers/%.1f micros | avg %.1f µs overhead" +
            " | peak %d tasks / %d layers / %d micros | %d errors",
            t, avgTasks, avgLayers, avgMicros, avgUs,
            peakTasks, peakLayers, peakMicros, executionErrors.get());
    }

    public long ticks()          { return ticks.get(); }
    public long executionErrors(){ return executionErrors.get(); }
}
