package org.nebula.plugin;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Tracks server milliseconds-per-tick (MSPT) cost attributed to Nebula shadow execution.
 *
 * <p>Each tick records the wall-clock time spent inside the shadow DAG executor.
 * At every reporting interval a rolling average and peak are logged so we can
 * confirm that Phase 0 shadow overhead stays well under the 50 ms tick budget.
 */
public final class MsptMonitor {

    private static final int WINDOW = 200; // ticks (~10 s at 20 TPS)

    private final Logger log;
    private final long[] windowNs = new long[WINDOW];
    private int cursor = 0;
    private long peakNs = 0;
    private final AtomicLong totalSamples = new AtomicLong();

    public MsptMonitor(Logger log) {
        this.log = log;
    }

    /** Record one tick's shadow overhead. */
    public void record(long overheadNs) {
        windowNs[cursor % WINDOW] = overheadNs;
        cursor++;
        totalSamples.incrementAndGet();
        if (overheadNs > peakNs) peakNs = overheadNs;

        if (totalSamples.get() % WINDOW == 0) {
            log.info(String.format("MSPT shadow: avg %.2f ms / peak %.2f ms (last %d ticks)",
                avgMs(), peakNs / 1_000_000.0, WINDOW));
        }
    }

    public double avgMs() {
        int filled = (int) Math.min(totalSamples.get(), WINDOW);
        if (filled == 0) return 0;
        long sum = 0;
        for (int i = 0; i < filled; i++) sum += windowNs[i];
        return sum / 1_000_000.0 / filled;
    }

    public double peakMs() { return peakNs / 1_000_000.0; }
    public long samples()  { return totalSamples.get(); }
}
