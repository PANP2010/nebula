package org.nebula.core.scheduler;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-tick fine-grained timing stats. Used by /nebula bench to break down
 * where time goes inside TickPipeline.execute (DAG build vs layer run vs
 * micro-step extension vs plugin phase).
 *
 * <p>All counters are nanoseconds (cumulative) and counts (number of ticks
 * that contributed). Average can be computed by caller as ns / count.
 */
public final class TickStats {

    private static final AtomicLong BUILD_NS = new AtomicLong();
    private static final AtomicLong BUILD_COUNT = new AtomicLong();
    private static final AtomicLong RUN_NS = new AtomicLong();
    private static final AtomicLong RUN_COUNT = new AtomicLong();

    private TickStats() {}

    public static void recordBuild(long ns) {
        BUILD_NS.addAndGet(ns);
        BUILD_COUNT.incrementAndGet();
    }

    public static void recordRun(long ns) {
        RUN_NS.addAndGet(ns);
        RUN_COUNT.incrementAndGet();
    }

    public static long buildNs() { return BUILD_NS.get(); }
    public static long buildCount() { return BUILD_COUNT.get(); }
    public static long runNs() { return RUN_NS.get(); }
    public static long runCount() { return RUN_COUNT.get(); }

    public static double avgBuildMs() {
        long c = BUILD_COUNT.get();
        return c == 0 ? 0.0 : BUILD_NS.get() / (double) c / 1_000_000.0;
    }

    public static double avgRunMs() {
        long c = RUN_COUNT.get();
        return c == 0 ? 0.0 : RUN_NS.get() / (double) c / 1_000_000.0;
    }

    public static void reset() {
        BUILD_NS.set(0);
        BUILD_COUNT.set(0);
        RUN_NS.set(0);
        RUN_COUNT.set(0);
    }

    public static String summary() {
        return String.format(
            "Tick stats: build=%.3fms (%d ticks), run=%.3fms (%d ticks)",
            avgBuildMs(), buildCount(), avgRunMs(), runCount()
        );
    }
}
