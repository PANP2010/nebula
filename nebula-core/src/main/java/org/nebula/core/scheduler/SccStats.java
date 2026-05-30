package org.nebula.core.scheduler;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-tick SCC contraction telemetry for {@link SccContractor}. Records how many
 * strongly-connected components Tarjan found, how many were contracted into a
 * CompoundTask vs serialised because they exceeded the threshold, and the largest
 * SCC seen. Surfaces in {@code /nebula scc} so admins can see real cycle activity
 * (e.g. redstone loops, dense-collision SCCs) rather than just the static threshold.
 */
public final class SccStats {

    private static final AtomicLong BUILDS = new AtomicLong();
    private static final AtomicLong BUILDS_WITH_CYCLES = new AtomicLong();
    private static final AtomicLong TOTAL_SCCS = new AtomicLong();
    private static final AtomicLong CONTRACTED = new AtomicLong();
    private static final AtomicLong SERIALISED = new AtomicLong();
    private static final AtomicLong MAX_SCC_SIZE = new AtomicLong();

    private SccStats() {}

    /**
     * @param sccCount      number of multi-node SCCs Tarjan identified this build
     * @param contracted    how many were collapsed into a CompoundTask (size &lt;= threshold)
     * @param serialised    how many were serialised because they exceeded the threshold
     * @param maxSccSize    largest SCC member count this build (0 if acyclic)
     */
    public static void record(int sccCount, int contracted, int serialised, int maxSccSize) {
        BUILDS.incrementAndGet();
        if (sccCount > 0) {
            BUILDS_WITH_CYCLES.incrementAndGet();
        }
        TOTAL_SCCS.addAndGet(sccCount);
        CONTRACTED.addAndGet(contracted);
        SERIALISED.addAndGet(serialised);
        // Monotonic max — cheap CAS loop, contended at most once per build.
        long prev;
        while (maxSccSize > (prev = MAX_SCC_SIZE.get())) {
            if (MAX_SCC_SIZE.compareAndSet(prev, maxSccSize)) {
                break;
            }
        }
    }

    public static long builds() { return BUILDS.get(); }
    public static long buildsWithCycles() { return BUILDS_WITH_CYCLES.get(); }
    public static long totalSccs() { return TOTAL_SCCS.get(); }
    public static long contracted() { return CONTRACTED.get(); }
    public static long serialised() { return SERIALISED.get(); }
    public static long maxSccSize() { return MAX_SCC_SIZE.get(); }

    public static void reset() {
        BUILDS.set(0);
        BUILDS_WITH_CYCLES.set(0);
        TOTAL_SCCS.set(0);
        CONTRACTED.set(0);
        SERIALISED.set(0);
        MAX_SCC_SIZE.set(0);
    }

    public static String summary(int threshold) {
        long builds = BUILDS.get();
        if (builds == 0) {
            return "Nebula SCC: no builds sampled yet (threshold=" + threshold + ")";
        }
        return String.format(
            "Nebula SCC: builds=%d, builds-with-cycles=%d, total-sccs=%d, contracted=%d, serialised=%d, max-scc-size=%d, threshold=%d "
                + "(SCCs <= threshold collapse into a CompoundTask; larger ones are serialised with a warning)",
            builds, buildsWithCycles(), totalSccs(), contracted(), serialised(), maxSccSize(), threshold
        );
    }
}
