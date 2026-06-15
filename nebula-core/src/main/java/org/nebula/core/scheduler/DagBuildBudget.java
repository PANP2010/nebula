package org.nebula.core.scheduler;

import java.util.Arrays;
import java.util.logging.Logger;

/**
 * DAG build-time budget tracker and degrade policy (arch doc §4.3.1, §16.1;
 * NEBULA-PATCH-2026-001 §变更二).
 *
 * <p>The patch mandates a hard build-time budget so a single slow build (e.g. a
 * redstone computer or dense entity farm) cannot snowball into consecutive
 * per-tick timeouts (an avalanche). This tracker:
 * <ul>
 *   <li>holds the budget thresholds ({@code BUILD_BUDGET}=2ms total,
 *       {@code BUCKET_DEGRADE} at 1.5ms);</li>
 *   <li>records each build's elapsed time and whether it degraded;</li>
 *   <li>raises a warning after {@value #DEGRADE_WARNING_STREAK} consecutive
 *       degraded ticks;</li>
 *   <li>exposes p50/p99/max build time over the last {@value #WINDOW} ticks and
 *       the degraded-tick ratio (for {@code /nebula dag-stats}).</li>
 * </ul>
 *
 * <p>Degradation never affects correctness: a degraded tick still produces a
 * legal DAG (via {@link CoarseDagBuilder}), only with reduced parallelism. The
 * caller decides the build strategy; this class owns the budget accounting.
 */
public final class DagBuildBudget {

    private static final Logger LOG = Logger.getLogger(DagBuildBudget.class.getName());

    /** Total build budget: 2ms (4% of a 50ms tick). */
    public static final long BUILD_BUDGET_NS = 2_000_000L;
    /** Per-bucket degrade trigger: 1.5ms — past this, unfinished buckets go coarse. */
    public static final long BUCKET_DEGRADE_NS = 1_500_000L;
    /** Global-merge degrade trigger: 0.5ms — past this, skip transitive-edge reduction. */
    public static final long GLOBAL_MERGE_DEGRADE_NS = 500_000L;

    /** Consecutive degraded ticks before an admin warning is logged. */
    public static final int DEGRADE_WARNING_STREAK = 10;

    /** Rolling window of recent builds used for percentile reporting. */
    public static final int WINDOW = 100;

    private final long[] recentNs = new long[WINDOW];
    private int sampleCount;     // number of valid entries (caps at WINDOW)
    private int writeIndex;      // ring-buffer cursor

    private long totalTicks;
    private long degradedTicks;
    private int consecutiveDegraded;
    private boolean warningActive;

    /** Whether a build exceeding {@code BUILD_BUDGET_NS} counts as degraded. */
    public boolean isOverBudget(long buildNs) {
        return buildNs > BUILD_BUDGET_NS;
    }

    /** True once the per-bucket coarse-path threshold (1.5ms) is reached. */
    public boolean shouldCoarsenBuckets(long elapsedNs) {
        return elapsedNs >= BUCKET_DEGRADE_NS;
    }

    /** True once the global-merge optimisation-skip threshold (0.5ms) is reached. */
    public boolean shouldSkipMergeOptimisation(long mergeElapsedNs) {
        return mergeElapsedNs >= GLOBAL_MERGE_DEGRADE_NS;
    }

    /**
     * Records one tick's DAG build.
     *
     * @param buildNs  total build time in nanoseconds
     * @param degraded whether this build took a degrade path (coarse buckets or
     *                 skipped merge optimisation)
     * @return true if this record pushed the consecutive-degrade streak to the
     *         warning threshold (caller may surface it; a log line is also emitted)
     */
    public boolean record(long buildNs, boolean degraded) {
        totalTicks++;
        recentNs[writeIndex] = buildNs;
        writeIndex = (writeIndex + 1) % WINDOW;
        if (sampleCount < WINDOW) sampleCount++;

        boolean warnTriggeredNow = false;
        if (degraded) {
            degradedTicks++;
            consecutiveDegraded++;
            if (consecutiveDegraded >= DEGRADE_WARNING_STREAK && !warningActive) {
                warningActive = true;
                warnTriggeredNow = true;
                LOG.warning(() -> "DAG build degraded for " + consecutiveDegraded
                    + " consecutive ticks — check for abnormally dense structures "
                    + "(large redstone machines or entity farms).");
            }
        } else {
            consecutiveDegraded = 0;
            warningActive = false;
        }
        return warnTriggeredNow;
    }

    public long totalTicks() { return totalTicks; }
    public long degradedTicks() { return degradedTicks; }
    public int consecutiveDegraded() { return consecutiveDegraded; }
    public boolean warningActive() { return warningActive; }

    /** Fraction of all observed ticks that took a degrade path. */
    public double degradedTicksRatio() {
        return totalTicks == 0 ? 0.0 : (double) degradedTicks / totalTicks;
    }

    /** Build-time percentile (ms) over the recent window. {@code q} in [0,1]. */
    public double percentileMs(double q) {
        if (sampleCount == 0) return 0.0;
        long[] sorted = Arrays.copyOf(recentNs, sampleCount);
        Arrays.sort(sorted);
        int idx = (int) Math.ceil(q * sorted.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.length) idx = sorted.length - 1;
        return sorted[idx] / 1_000_000.0;
    }

    public double p50Ms() { return percentileMs(0.50); }
    public double p99Ms() { return percentileMs(0.99); }

    public double maxMs() {
        if (sampleCount == 0) return 0.0;
        long max = 0;
        for (int i = 0; i < sampleCount; i++) max = Math.max(max, recentNs[i]);
        return max / 1_000_000.0;
    }

    public void reset() {
        Arrays.fill(recentNs, 0L);
        sampleCount = 0;
        writeIndex = 0;
        totalTicks = 0;
        degradedTicks = 0;
        consecutiveDegraded = 0;
        warningActive = false;
    }

    /** Diagnostic summary for {@code /nebula dag-stats}. */
    public String summary() {
        if (totalTicks == 0) return "DAG build budget: no samples";
        return String.format(
            "DAG build budget: ticks=%d, build_time_p50=%.3fms, p99=%.3fms, max=%.3fms, "
                + "degraded_ticks_ratio=%.3f, consecutive_degraded=%d",
            totalTicks, p50Ms(), p99Ms(), maxMs(), degradedTicksRatio(), consecutiveDegraded);
    }
}
