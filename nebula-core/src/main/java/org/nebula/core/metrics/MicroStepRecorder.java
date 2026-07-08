package org.nebula.core.metrics;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Records the per-tick microstep count and exposes distribution statistics
 * (DG1 Criterion 2).
 *
 * <p>DG1 Criterion 2 requires that a single DAG tick never expand beyond
 * {@code MAX_MICRO_STEPS} (256) microsteps. The scheduler already throws when
 * that hard cap is exceeded mid-tick; this recorder is the honest observability
 * surface that lets us confirm the bound holds <em>at scale</em> over a long
 * run rather than trusting a single log line. Every completed DAG tick reports
 * its microstep count via {@link #record}; the running {@link #max()} is the
 * value Criterion 2 grades against.
 *
 * <p>Mirrors {@link TickTimeRecorder}: a bounded ring buffer retains the most
 * recent {@code capacity} samples for percentiles, while lifetime counters
 * (count, sum, max) span the whole run and are never evicted — so an early
 * microstep spike is still remembered in {@link #max()} long after it ages out
 * of the percentile window.
 *
 * <p>Thread-safe: Folia invokes the DAG executor from region threads, so
 * {@link #record} may be called concurrently. A single short lock guards the
 * ring buffer and counters — contention is negligible next to a tick's work.
 */
public final class MicroStepRecorder {

    /** Default number of recent samples retained for percentile computation. */
    public static final int DEFAULT_CAPACITY = 512;

    private final ReentrantLock lock = new ReentrantLock();
    private final int[] ring;
    private int size;
    private int next;

    // Lifetime counters (never reset by eviction).
    private long lifetimeCount;
    private long lifetimeSum;
    private int lifetimeMin = Integer.MAX_VALUE;
    private int lifetimeMax;

    public MicroStepRecorder() {
        this(DEFAULT_CAPACITY);
    }

    public MicroStepRecorder(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.ring = new int[capacity];
    }

    /**
     * Records one tick's microstep count.
     *
     * @param microSteps microsteps expanded in a single DAG tick (must be non-negative)
     */
    public void record(int microSteps) {
        if (microSteps < 0) {
            throw new IllegalArgumentException("microSteps must be non-negative: " + microSteps);
        }
        lock.lock();
        try {
            ring[next] = microSteps;
            next = (next + 1) % ring.length;
            if (size < ring.length) {
                size++;
            }
            lifetimeCount++;
            lifetimeSum += microSteps;
            if (microSteps < lifetimeMin) {
                lifetimeMin = microSteps;
            }
            if (microSteps > lifetimeMax) {
                lifetimeMax = microSteps;
            }
        } finally {
            lock.unlock();
        }
    }

    /** Lifetime maximum microstep count seen in any single tick — the DG1 Criterion 2 signal. */
    public int max() {
        lock.lock();
        try {
            return lifetimeMax;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an immutable snapshot of current statistics. Percentiles are
     * computed over the recent-sample window; lifetime counters span all ticks.
     *
     * @return a snapshot (all-zero if no samples have been recorded)
     */
    public Snapshot snapshot() {
        lock.lock();
        try {
            if (lifetimeCount == 0) {
                return Snapshot.EMPTY;
            }
            int[] window = Arrays.copyOf(ring, size);
            Arrays.sort(window);
            int p50 = percentile(window, 50);
            int p95 = percentile(window, 95);
            int p99 = percentile(window, 99);
            double avg = (double) lifetimeSum / lifetimeCount;
            return new Snapshot(
                lifetimeCount, window.length,
                lifetimeMin, lifetimeMax, avg,
                p50, p95, p99);
        } finally {
            lock.unlock();
        }
    }

    /** Clears all samples and lifetime counters. */
    public void reset() {
        lock.lock();
        try {
            size = 0;
            next = 0;
            lifetimeCount = 0;
            lifetimeSum = 0;
            lifetimeMin = Integer.MAX_VALUE;
            lifetimeMax = 0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Nearest-rank percentile over a pre-sorted array.
     *
     * @param sorted a non-empty ascending array
     * @param pct    percentile in [0, 100]
     */
    static int percentile(int[] sorted, int pct) {
        if (sorted.length == 0) {
            return 0;
        }
        // Nearest-rank: rank = ceil(pct/100 * n), clamped to [1, n].
        int rank = (int) Math.ceil(pct / 100.0 * sorted.length);
        if (rank < 1) {
            rank = 1;
        }
        if (rank > sorted.length) {
            rank = sorted.length;
        }
        return sorted[rank - 1];
    }

    /** Immutable statistics snapshot. All values are raw microstep counts. */
    public record Snapshot(
        long count,
        int windowSize,
        int min,
        int max,
        double avg,
        int p50,
        int p95,
        int p99) {

        public static final Snapshot EMPTY =
            new Snapshot(0, 0, 0, 0, 0.0, 0, 0, 0);
    }
}
