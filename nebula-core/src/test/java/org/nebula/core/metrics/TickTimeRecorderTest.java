package org.nebula.core.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TickTimeRecorderTest {

    private static final long MS = 1_000_000L;

    @Test
    void emptyRecorderReportsZeroSnapshot() {
        TickTimeRecorder r = new TickTimeRecorder();
        TickTimeRecorder.Snapshot s = r.snapshot();
        assertEquals(0, s.count());
        assertEquals(0, s.windowSize());
        assertSame(TickTimeRecorder.Snapshot.EMPTY, s);
    }

    @Test
    void singleSampleReportsThatValueEverywhere() {
        TickTimeRecorder r = new TickTimeRecorder();
        r.record(5 * MS);
        TickTimeRecorder.Snapshot s = r.snapshot();
        assertEquals(1, s.count());
        assertEquals(5.0, s.minMs(), 1e-9);
        assertEquals(5.0, s.maxMs(), 1e-9);
        assertEquals(5.0, s.avgMs(), 1e-9);
        assertEquals(5.0, s.p50Ms(), 1e-9);
        assertEquals(5.0, s.p99Ms(), 1e-9);
    }

    @Test
    void percentilesUseNearestRankOverWindow() {
        TickTimeRecorder r = new TickTimeRecorder(1000);
        // 1..100 ms
        for (int i = 1; i <= 100; i++) {
            r.record((long) i * MS);
        }
        TickTimeRecorder.Snapshot s = r.snapshot();
        assertEquals(100, s.count());
        assertEquals(100, s.windowSize());
        assertEquals(1.0, s.minMs(), 1e-9);
        assertEquals(100.0, s.maxMs(), 1e-9);
        assertEquals(50.5, s.avgMs(), 1e-9);
        // nearest-rank: p50 -> rank 50 -> value 50; p95 -> 95; p99 -> 99
        assertEquals(50.0, s.p50Ms(), 1e-9);
        assertEquals(95.0, s.p95Ms(), 1e-9);
        assertEquals(99.0, s.p99Ms(), 1e-9);
    }

    @Test
    void ringBufferEvictsOldSamplesForPercentilesButKeepsLifetimeCounters() {
        TickTimeRecorder r = new TickTimeRecorder(4);
        // Record 100ms first (should be evicted from the window)...
        r.record(100 * MS);
        // ...then fill the window with 1ms samples.
        for (int i = 0; i < 4; i++) {
            r.record(1 * MS);
        }
        TickTimeRecorder.Snapshot s = r.snapshot();
        // Lifetime count spans all 5 records.
        assertEquals(5, s.count());
        // Window only holds the last 4 (all 1ms), so percentiles are 1ms.
        assertEquals(4, s.windowSize());
        assertEquals(1.0, s.p99Ms(), 1e-9);
        // Lifetime max still remembers the evicted 100ms spike.
        assertEquals(100.0, s.maxMs(), 1e-9);
        assertEquals(1.0, s.minMs(), 1e-9);
    }

    @Test
    void resetClearsEverything() {
        TickTimeRecorder r = new TickTimeRecorder();
        r.record(7 * MS);
        r.reset();
        assertEquals(0, r.snapshot().count());
    }

    @Test
    void rejectsNegativeDuration() {
        TickTimeRecorder r = new TickTimeRecorder();
        assertThrows(IllegalArgumentException.class, () -> r.record(-1));
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new TickTimeRecorder(0));
        assertThrows(IllegalArgumentException.class, () -> new TickTimeRecorder(-5));
    }

    @Test
    void percentileHelperClampsRank() {
        long[] sorted = {10, 20, 30};
        assertEquals(10, TickTimeRecorder.percentile(sorted, 0));
        assertEquals(30, TickTimeRecorder.percentile(sorted, 100));
        assertEquals(30, TickTimeRecorder.percentile(sorted, 99));
        assertEquals(0, TickTimeRecorder.percentile(new long[0], 50));
    }

    @Test
    void concurrentRecordingIsThreadSafe() throws InterruptedException {
        TickTimeRecorder r = new TickTimeRecorder(10_000);
        int threads = 8;
        int perThread = 1000;
        Thread[] pool = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            pool[t] = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    r.record(MS);
                }
            });
        }
        for (Thread th : pool) th.start();
        for (Thread th : pool) th.join();
        assertEquals((long) threads * perThread, r.snapshot().count());
    }
}
