package org.nebula.plugin;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Tracks INTERCEPT mode activity: how many neighbor updates were suppressed
 * (handed to Nebula DAG instead of Folia), and whether any errors occurred.
 *
 * <p>Phase 0 INTERCEPT validation criteria (arch doc §14.3):
 * <ul>
 *   <li>Server does not crash under INTERCEPT mode</li>
 *   <li>Suppression count is > 0 (interceptor is active)</li>
 *   <li>No DAG execution errors</li>
 *   <li>World state remains consistent (DG1 hash still passes after switch)</li>
 * </ul>
 */
public final class InterceptMonitor {

    private final Logger log;

    private final AtomicLong suppressedUpdates = new AtomicLong();
    private final AtomicLong interceptTicks     = new AtomicLong();
    private final AtomicLong dagErrors          = new AtomicLong();
    private volatile long startMs = 0;
    private volatile boolean active = false;

    public InterceptMonitor(Logger log) {
        this.log = log;
    }

    public void start() {
        suppressedUpdates.set(0);
        interceptTicks.set(0);
        dagErrors.set(0);
        startMs = System.currentTimeMillis();
        active = true;
        log.info("InterceptMonitor started");
    }

    public void stop() {
        active = false;
        log.info("InterceptMonitor stopped: " + summary());
    }

    public void recordSuppression(int count) {
        if (!active) return;
        suppressedUpdates.addAndGet(count);
        interceptTicks.incrementAndGet();
    }

    public void recordError() {
        dagErrors.incrementAndGet();
    }

    public String summary() {
        long ticks = interceptTicks.get();
        long updates = suppressedUpdates.get();
        long ms = System.currentTimeMillis() - startMs;
        return String.format(
            "%d intercept-ticks | %d suppressed updates (avg %.1f/tick) | %d DAG errors | %.1fs",
            ticks, updates, ticks > 0 ? (double) updates / ticks : 0, dagErrors.get(), ms / 1000.0);
    }

    public boolean isActive()             { return active; }
    public long suppressedUpdates()       { return suppressedUpdates.get(); }
    public long interceptTicks()          { return interceptTicks.get(); }
    public long dagErrors()               { return dagErrors.get(); }
}
