package org.nebula.core.metrics;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Records DAG tick execution times and exposes percentile statistics (B4).
 *
 * <p>This is the honest measurement surface for Nebula's per-tick cost. Every
 * call to {@link #record} adds one sample (in nanoseconds) to a bounded ring
 * buffer of the most recent {@code capacity} ticks; percentiles are computed
 * over that window on demand. Running totals (count, sum, min, max) span the
 * whole lifetime and are never evicted.
 *
 * <p>Thread-safe: Folia invokes the DAG executor from region threads, so
 * {@link #record} may be called concurrently. A single short lock guards the
 * ring buffer and counters — contention is negligible next to a tick's work.
 */
public final class TickTimeRecorder {

    /** Default number of recent samples retained for percentile computation. */
    public static final int DEFAULT_CAPACITY = 512;

    private final ReentrantLock lock = new ReentrantLock();
    private final long[] ring;
    private int size;
    private int next;

    // Lifetime counters (never reset by eviction).
    private long lifetimeCount;
    private long lifetimeSumNs;
    private long lifetimeMinNs = Long.MAX_VALUE;
    private long lifetimeMaxNs;

    // Last-recorded sample (in nanoseconds). Updated atomically with the ring
    // under lock; read by the fidelity downgrade hook without taking the lock
    // (a stale read here only delays a downgrade by one tick).
    private volatile long lastSampleNs;

    public TickTimeRecorder() {
        this(DEFAULT_CAPACITY);
    }

    public TickTimeRecorder(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.ring = new long[capacity];
    }

    /**
     * Records one tick's execution time.
     *
     * @param durationNs elapsed time in nanoseconds (must be non-negative)
     */
    public void record(long durationNs) {
        if (durationNs < 0) {
            throw new IllegalArgumentException("durationNs must be non-negative: " + durationNs);
        }
        lock.lock();
        try {
            ring[next] = durationNs;
            next = (next + 1) % ring.length;
            if (size < ring.length) {
                size++;
            }
            lifetimeCount++;
            lifetimeSumNs += durationNs;
            if (durationNs < lifetimeMinNs) {
                lifetimeMinNs = durationNs;
            }
            if (durationNs > lifetimeMaxNs) {
                lifetimeMaxNs = durationNs;
            }
        } finally {
            lock.unlock();
        }
        lastSampleNs = durationNs;
    }

    /**
     * Last recorded tick time in nanoseconds (0 if nothing has been recorded
     * yet). Lock-free read so the per-tick fidelity hook can sample it cheaply.
     */
    public long lastNs() {
        return lastSampleNs;
    }

    /** Convenience: last tick time in milliseconds (0 if none recorded). */
    public long lastMs() {
        return lastSampleNs / 1_000_000L;
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
            long[] window = Arrays.copyOf(ring, size);
            Arrays.sort(window);
            long p50 = percentile(window, 50);
            long p95 = percentile(window, 95);
            long p99 = percentile(window, 99);
            double avgNs = (double) lifetimeSumNs / lifetimeCount;
            return new Snapshot(
                lifetimeCount, window.length,
                lifetimeMinNs, lifetimeMaxNs, avgNs,
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
            lifetimeSumNs = 0;
            lifetimeMinNs = Long.MAX_VALUE;
            lifetimeMaxNs = 0;
        } finally {
            lock.unlock();
        }
        lastSampleNs = 0L;
    }

    /**
     * Nearest-rank percentile over a pre-sorted array.
     *
     * @param sorted a non-empty ascending array
     * @param pct    percentile in [0, 100]
     */
    static long percentile(long[] sorted, int pct) {
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

    /**
     * Immutable statistics snapshot. Durations are in nanoseconds; helper
     * accessors convert the common ones to milliseconds for display.
     */
    public record Snapshot(
        long count,
        int windowSize,
        long minNs,
        long maxNs,
        double avgNs,
        long p50Ns,
        long p95Ns,
        long p99Ns) {

        public static final Snapshot EMPTY =
            new Snapshot(0, 0, 0, 0, 0.0, 0, 0, 0);

        public double avgMs()  { return avgNs / 1_000_000.0; }
        public double minMs()  { return minNs / 1_000_000.0; }
        public double maxMs()  { return maxNs / 1_000_000.0; }
        public double p50Ms()  { return p50Ns / 1_000_000.0; }
        public double p95Ms()  { return p95Ns / 1_000_000.0; }
        public double p99Ms()  { return p99Ns / 1_000_000.0; }
    }
}
