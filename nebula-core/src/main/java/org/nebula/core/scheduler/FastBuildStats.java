package org.nebula.core.scheduler;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Per-phase timing for {@link DagBuilder#buildFast}. Used to break down DAG-build
 * cost into: sort, partition, conflict-scan, SCC, assemble. Surfaces in
 * /nebula dag-stats so we can spot regressions.
 *
 * <p>Uses a shared singleton ({@link #INSTANCE}) so all {@code BucketDagBuilder}
 * builds accumulate into the same global window. Thread-safe via {@code AtomicLong}.
 */
public final class FastBuildStats {

    private static final Logger LOG = Logger.getLogger(FastBuildStats.class.getName());

    /** Shared singleton for global accumulation across all BucketDagBuilder instances. */
    public static final FastBuildStats INSTANCE = new FastBuildStats();

    private final AtomicLong count = new AtomicLong();
    private final AtomicLong sortNs = new AtomicLong();
    private final AtomicLong partitionNs = new AtomicLong();
    private final AtomicLong conflictNs = new AtomicLong();
    private final AtomicLong sccNs = new AtomicLong();
    private final AtomicLong assembleNs = new AtomicLong();
    private final AtomicLong globalTouchingTasks = new AtomicLong();
    private final AtomicLong totalTasks = new AtomicLong();

    private FastBuildStats() {}

    /**
     * Records one DAG build's phase timings. Called by {@link BucketDagBuilder#buildFast}
     * via the static shorthand.
     */
    public static void record(long sortNsVal, long partitionNsVal, long conflictNsVal,
                              long sccNsVal, long assembleNsVal,
                              int globalTouchingSize, int totalSize) {
        INSTANCE.doRecord(sortNsVal, partitionNsVal, conflictNsVal, sccNsVal,
            assembleNsVal, globalTouchingSize, totalSize);
    }

    private void doRecord(long sortNsVal, long partitionNsVal, long conflictNsVal,
                          long sccNsVal, long assembleNsVal,
                          int globalTouchingSize, int totalSize) {
        count.incrementAndGet();
        sortNs.addAndGet(sortNsVal);
        partitionNs.addAndGet(partitionNsVal);
        conflictNs.addAndGet(conflictNsVal);
        sccNs.addAndGet(sccNsVal);
        assembleNs.addAndGet(assembleNsVal);
        globalTouchingTasks.addAndGet(globalTouchingSize);
        totalTasks.addAndGet(totalSize);
    }

    public static long count() { return INSTANCE.c(); }
    private long c() { return count.get(); }

    private double avgMs(AtomicLong ns) {
        long n = c();
        return n == 0 ? 0 : ns.get() / (double) n / 1_000_000.0;
    }

    public static double avgGlobalTouching() { return INSTANCE.avgGlobalTouchingInstance(); }
    private double avgGlobalTouchingInstance() {
        long n = c();
        return n == 0 ? 0 : globalTouchingTasks.get() / (double) n;
    }

    public static double avgTotal() { return INSTANCE.avgTotalInstance(); }
    private double avgTotalInstance() {
        long n = c();
        return n == 0 ? 0 : totalTasks.get() / (double) n;
    }

    public static void reset() { INSTANCE.doReset(); }
    private void doReset() {
        count.set(0);
        sortNs.set(0);
        partitionNs.set(0);
        conflictNs.set(0);
        sccNs.set(0);
        assembleNs.set(0);
        globalTouchingTasks.set(0);
        totalTasks.set(0);
    }

    public static String summary() { return INSTANCE.summaryInstance(); }
    private String summaryInstance() {
        if (c() == 0) return "Fast build stats: no samples";
        return String.format(
            "Fast build stats: ticks=%d, sort=%.3fms, partition=%.3fms, conflict=%.3fms, scc=%.3fms, assemble=%.3fms, avg-global-touching=%.1f, avg-total=%.1f",
            c(),
            avgMs(sortNs), avgMs(partitionNs), avgMs(conflictNs), avgMs(sccNs), avgMs(assembleNs),
            avgGlobalTouchingInstance(), avgTotalInstance()
        );
    }

    /** Per-phase avg ms accessors for /nebula dag-stats. */
    public double avgSortMs() { return avgMs(sortNs); }
    public double avgPartitionMs() { return avgMs(partitionNs); }
    public double avgConflictMs() { return avgMs(conflictNs); }
    public double avgSccMs() { return avgMs(sccNs); }
    public double avgAssembleMs() { return avgMs(assembleNs); }
    public long totalCount() { return c(); }
}
