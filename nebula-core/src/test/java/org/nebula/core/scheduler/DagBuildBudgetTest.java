package org.nebula.core.scheduler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DagBuildBudgetTest {

    private static final long MS = 1_000_000L;

    @Test
    void overBudgetThresholdIsTwoMs() {
        DagBuildBudget b = new DagBuildBudget();
        assertFalse(b.isOverBudget(2 * MS), "exactly 2ms is within budget");
        assertTrue(b.isOverBudget(2 * MS + 1), "past 2ms is over budget");
    }

    @Test
    void bucketAndMergeThresholds() {
        DagBuildBudget b = new DagBuildBudget();
        assertFalse(b.shouldCoarsenBuckets((long) (1.4 * MS)));
        assertTrue(b.shouldCoarsenBuckets((long) (1.5 * MS)));
        assertFalse(b.shouldSkipMergeOptimisation((long) (0.4 * MS)));
        assertTrue(b.shouldSkipMergeOptimisation((long) (0.5 * MS)));
    }

    @Test
    void warningFiresAfterTenConsecutiveDegradedTicks() {
        DagBuildBudget b = new DagBuildBudget();
        for (int i = 0; i < 9; i++) {
            assertFalse(b.record(3 * MS, true), "no warning before the 10th degrade");
        }
        assertTrue(b.record(3 * MS, true), "10th consecutive degrade fires the warning");
        assertTrue(b.warningActive());
        assertEquals(10, b.consecutiveDegraded());
    }

    @Test
    void cleanTickResetsConsecutiveDegradeStreak() {
        DagBuildBudget b = new DagBuildBudget();
        for (int i = 0; i < 5; i++) b.record(3 * MS, true);
        assertEquals(5, b.consecutiveDegraded());
        b.record(1 * MS, false); // clean tick
        assertEquals(0, b.consecutiveDegraded());
        assertFalse(b.warningActive());
    }

    @Test
    void warningFiresOnlyOncePerStreak() {
        DagBuildBudget b = new DagBuildBudget();
        for (int i = 0; i < 10; i++) b.record(3 * MS, true);
        assertTrue(b.warningActive());
        // Further degraded ticks in the same streak don't re-fire.
        assertFalse(b.record(3 * MS, true), "warning should not re-fire within a streak");
        assertEquals(11, b.consecutiveDegraded());
    }

    @Test
    void degradedRatioTracksAcrossTicks() {
        DagBuildBudget b = new DagBuildBudget();
        for (int i = 0; i < 8; i++) b.record(1 * MS, false);
        for (int i = 0; i < 2; i++) b.record(3 * MS, true);
        assertEquals(0.2, b.degradedTicksRatio(), 1e-9);
        assertEquals(10, b.totalTicks());
        assertEquals(2, b.degradedTicks());
    }

    @Test
    void percentilesComputedOverWindow() {
        DagBuildBudget b = new DagBuildBudget();
        // Build times 1ms..100ms.
        for (int i = 1; i <= 100; i++) {
            b.record((long) i * MS, false);
        }
        // p50 of 1..100 (ceil(0.5*100)-1 = idx 49 → value 50ms).
        assertEquals(50.0, b.p50Ms(), 1e-9);
        // p99 → idx 98 → 99ms.
        assertEquals(99.0, b.p99Ms(), 1e-9);
        assertEquals(100.0, b.maxMs(), 1e-9);
    }

    @Test
    void windowEvictsOldestSamples() {
        DagBuildBudget b = new DagBuildBudget();
        // Fill window with 100ms builds, then overwrite with 100 fast 1ms builds.
        for (int i = 0; i < DagBuildBudget.WINDOW; i++) b.record(100 * MS, false);
        for (int i = 0; i < DagBuildBudget.WINDOW; i++) b.record(1 * MS, false);
        // Window now holds only the 1ms builds.
        assertEquals(1.0, b.maxMs(), 1e-9);
        // ...but cumulative totalTicks keeps counting.
        assertEquals(200, b.totalTicks());
    }

    @Test
    void emptyBudgetReportsZeroes() {
        DagBuildBudget b = new DagBuildBudget();
        assertEquals(0.0, b.p50Ms());
        assertEquals(0.0, b.maxMs());
        assertEquals(0.0, b.degradedTicksRatio());
        assertTrue(b.summary().contains("no samples"));
    }

    @Test
    void resetClearsEverything() {
        DagBuildBudget b = new DagBuildBudget();
        for (int i = 0; i < 20; i++) b.record(3 * MS, true);
        b.reset();
        assertEquals(0, b.totalTicks());
        assertEquals(0, b.consecutiveDegraded());
        assertFalse(b.warningActive());
        assertEquals(0.0, b.maxMs());
    }
}
