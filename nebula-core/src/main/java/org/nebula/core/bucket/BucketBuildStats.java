package org.nebula.core.bucket;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-phase timing for {@link BucketDagBuilder}. Used to break down DAG-build
 * cost into: spatial-index construction, per-bucket conflict detection,
 * global-task fan-out, SCC contraction, and final result assembly.
 *
 * <p>This is the diagnostic answer to "where does build time go?" — exposed
 * via {@link #summary()} which the /nebula bench command can call.
 */
public final class BucketBuildStats {

    private static final AtomicLong COUNT = new AtomicLong();
    private static final AtomicLong INDEX_NS = new AtomicLong();
    private static final AtomicLong BUCKET_NS = new AtomicLong();
    private static final AtomicLong GLOBAL_NS = new AtomicLong();
    private static final AtomicLong SCC_NS = new AtomicLong();
    private static final AtomicLong ASSEMBLE_NS = new AtomicLong();

    private BucketBuildStats() {}

    public static void record(long indexNs, long bucketNs, long globalNs, long sccNs, long assembleNs) {
        COUNT.incrementAndGet();
        INDEX_NS.addAndGet(indexNs);
        BUCKET_NS.addAndGet(bucketNs);
        GLOBAL_NS.addAndGet(globalNs);
        SCC_NS.addAndGet(sccNs);
        ASSEMBLE_NS.addAndGet(assembleNs);
    }

    public static long count() { return COUNT.get(); }

    public static double avgIndexMs()   { long c = COUNT.get(); return c == 0 ? 0 : INDEX_NS.get() / (double) c / 1_000_000.0; }
    public static double avgBucketMs()  { long c = COUNT.get(); return c == 0 ? 0 : BUCKET_NS.get() / (double) c / 1_000_000.0; }
    public static double avgGlobalMs()  { long c = COUNT.get(); return c == 0 ? 0 : GLOBAL_NS.get() / (double) c / 1_000_000.0; }
    public static double avgSccMs()     { long c = COUNT.get(); return c == 0 ? 0 : SCC_NS.get() / (double) c / 1_000_000.0; }
    public static double avgAssembleMs(){ long c = COUNT.get(); return c == 0 ? 0 : ASSEMBLE_NS.get() / (double) c / 1_000_000.0; }

    public static void reset() {
        COUNT.set(0);
        INDEX_NS.set(0);
        BUCKET_NS.set(0);
        GLOBAL_NS.set(0);
        SCC_NS.set(0);
        ASSEMBLE_NS.set(0);
    }

    public static String summary() {
        if (COUNT.get() == 0) return "Bucket build stats: no samples";
        return String.format(
            "Bucket build stats: ticks=%d, index=%.3fms, per-bucket=%.3fms, global=%.3fms, scc=%.3fms, assemble=%.3fms",
            count(), avgIndexMs(), avgBucketMs(), avgGlobalMs(), avgSccMs(), avgAssembleMs()
        );
    }
}
