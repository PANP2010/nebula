package org.nebula.core.scheduler;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-phase timing for {@link DagBuilder#buildFast}. Used to break down DAG-build
 * cost into: sort, partition, conflict-scan, SCC, assemble. Surfaces in
 * /nebula bench so we can spot regressions.
 */
public final class FastBuildStats {

    private static final AtomicLong COUNT = new AtomicLong();
    private static final AtomicLong SORT_NS = new AtomicLong();
    private static final AtomicLong PARTITION_NS = new AtomicLong();
    private static final AtomicLong CONFLICT_NS = new AtomicLong();
    private static final AtomicLong SCC_NS = new AtomicLong();
    private static final AtomicLong ASSEMBLE_NS = new AtomicLong();
    private static final AtomicLong GLOBAL_TOUCHING_TASKS = new AtomicLong();
    private static final AtomicLong TOTAL_TASKS = new AtomicLong();

    private FastBuildStats() {}

    public static void record(long sortNs, long partitionNs, long conflictNs, long sccNs, long assembleNs,
                               int globalTouchingSize, int totalSize) {
        COUNT.incrementAndGet();
        SORT_NS.addAndGet(sortNs);
        PARTITION_NS.addAndGet(partitionNs);
        CONFLICT_NS.addAndGet(conflictNs);
        SCC_NS.addAndGet(sccNs);
        ASSEMBLE_NS.addAndGet(assembleNs);
        GLOBAL_TOUCHING_TASKS.addAndGet(globalTouchingSize);
        TOTAL_TASKS.addAndGet(totalSize);
    }

    public static long count() { return COUNT.get(); }

    public static double avgMs(AtomicLong ns) {
        long c = COUNT.get();
        return c == 0 ? 0 : ns.get() / (double) c / 1_000_000.0;
    }

    public static double avgGlobalTouching() {
        long c = COUNT.get();
        return c == 0 ? 0 : GLOBAL_TOUCHING_TASKS.get() / (double) c;
    }

    public static double avgTotal() {
        long c = COUNT.get();
        return c == 0 ? 0 : TOTAL_TASKS.get() / (double) c;
    }

    public static void reset() {
        COUNT.set(0);
        SORT_NS.set(0);
        PARTITION_NS.set(0);
        CONFLICT_NS.set(0);
        SCC_NS.set(0);
        ASSEMBLE_NS.set(0);
        GLOBAL_TOUCHING_TASKS.set(0);
        TOTAL_TASKS.set(0);
    }

    public static String summary() {
        if (COUNT.get() == 0) return "Fast build stats: no samples";
        return String.format(
            "Fast build stats: ticks=%d, sort=%.3fms, partition=%.3fms, conflict=%.3fms, scc=%.3fms, assemble=%.3fms, avg-global-touching=%.1f, avg-total=%.1f",
            count(),
            avgMs(SORT_NS), avgMs(PARTITION_NS), avgMs(CONFLICT_NS), avgMs(SCC_NS), avgMs(ASSEMBLE_NS),
            avgGlobalTouching(), avgTotal()
        );
    }
}
