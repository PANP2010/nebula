package org.nebula.core.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MicroStepRecorderTest {

    @Test
    void emptyRecorderReportsZeroSnapshot() {
        MicroStepRecorder r = new MicroStepRecorder();
        MicroStepRecorder.Snapshot s = r.snapshot();
        assertEquals(0, s.count());
        assertEquals(0, s.windowSize());
        assertSame(MicroStepRecorder.Snapshot.EMPTY, s);
        assertEquals(0, r.max());
    }

    @Test
    void singleSampleReportsThatValueEverywhere() {
        MicroStepRecorder r = new MicroStepRecorder();
        r.record(14);
        MicroStepRecorder.Snapshot s = r.snapshot();
        assertEquals(1, s.count());
        assertEquals(14, s.min());
        assertEquals(14, s.max());
        assertEquals(14.0, s.avg(), 1e-9);
        assertEquals(14, s.p50());
        assertEquals(14, s.p99());
        assertEquals(14, r.max());
    }

    @Test
    void percentilesUseNearestRankOverWindow() {
        MicroStepRecorder r = new MicroStepRecorder(1000);
        for (int i = 1; i <= 100; i++) {
            r.record(i);
        }
        MicroStepRecorder.Snapshot s = r.snapshot();
        assertEquals(100, s.count());
        assertEquals(100, s.windowSize());
        assertEquals(1, s.min());
        assertEquals(100, s.max());
        assertEquals(50.5, s.avg(), 1e-9);
        assertEquals(50, s.p50());
        assertEquals(95, s.p95());
        assertEquals(99, s.p99());
    }

    @Test
    void ringBufferEvictsOldSamplesForPercentilesButKeepsLifetimeMax() {
        MicroStepRecorder r = new MicroStepRecorder(4);
        // A big early spike (e.g. a near-cap tick) then a quiet window of 1s.
        r.record(200);
        for (int i = 0; i < 4; i++) {
            r.record(1);
        }
        MicroStepRecorder.Snapshot s = r.snapshot();
        assertEquals(5, s.count());
        assertEquals(4, s.windowSize());
        // Window only holds the last four (all 1), so percentiles are 1...
        assertEquals(1, s.p99());
        // ...but the lifetime max still remembers the evicted spike — this is
        // exactly the DG1 Criterion 2 guarantee: an early spike is not forgotten.
        assertEquals(200, s.max());
        assertEquals(200, r.max());
        assertEquals(1, s.min());
    }

    @Test
    void maxTracksLargestAcrossManyTicks() {
        MicroStepRecorder r = new MicroStepRecorder();
        r.record(3);
        r.record(17);
        r.record(9);
        r.record(255);
        r.record(12);
        assertEquals(255, r.max());
    }

    @Test
    void zeroMicrostepTicksAreValid() {
        // A tick with no cascading (e.g. a single flat wire) can report 0.
        MicroStepRecorder r = new MicroStepRecorder();
        r.record(0);
        assertEquals(1, r.snapshot().count());
        assertEquals(0, r.max());
    }

    @Test
    void resetClearsEverything() {
        MicroStepRecorder r = new MicroStepRecorder();
        r.record(7);
        r.reset();
        assertEquals(0, r.snapshot().count());
        assertEquals(0, r.max());
    }

    @Test
    void rejectsNegativeCount() {
        MicroStepRecorder r = new MicroStepRecorder();
        assertThrows(IllegalArgumentException.class, () -> r.record(-1));
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new MicroStepRecorder(0));
        assertThrows(IllegalArgumentException.class, () -> new MicroStepRecorder(-5));
    }

    @Test
    void percentileHelperClampsRank() {
        int[] sorted = {10, 20, 30};
        assertEquals(10, MicroStepRecorder.percentile(sorted, 0));
        assertEquals(30, MicroStepRecorder.percentile(sorted, 100));
        assertEquals(30, MicroStepRecorder.percentile(sorted, 99));
        assertEquals(0, MicroStepRecorder.percentile(new int[0], 50));
    }

    @Test
    void concurrentRecordingIsThreadSafe() throws InterruptedException {
        MicroStepRecorder r = new MicroStepRecorder(10_000);
        int threads = 8;
        int perThread = 1000;
        Thread[] pool = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            pool[t] = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    r.record(5);
                }
            });
        }
        for (Thread th : pool) th.start();
        for (Thread th : pool) th.join();
        assertEquals((long) threads * perThread, r.snapshot().count());
        assertEquals(5, r.max());
    }
}
