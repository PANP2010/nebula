package org.nebula.core.scheduler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the refactored {@link FastBuildStats} singleton
 * (P1.10.1d companion): verifies the public static API (reset, record, count,
 * avgGlobalTouching, avgTotal, summary) and the public instance accessor
 * methods (avgSortMs, totalCount, etc.).
 */
class FastBuildStatsTest {

    @Test
    void instanceIsSharedSingleton() {
        assertTrue(FastBuildStats.INSTANCE != null);
        assertEquals(FastBuildStats.INSTANCE, FastBuildStats.INSTANCE);
    }

    @Test
    void noSamplesMeansZeroAverages() {
        FastBuildStats.reset();
        assertEquals(0, FastBuildStats.count());
        assertEquals(0.0, FastBuildStats.INSTANCE.avgSortMs());
        assertEquals(0.0, FastBuildStats.INSTANCE.avgConflictMs());
        assertEquals(0.0, FastBuildStats.avgGlobalTouching());
        assertEquals(0.0, FastBuildStats.avgTotal());
    }

    @Test
    void recordAccumulatesAcrossMultipleBuilds() {
        FastBuildStats.reset();
        // 3 builds, each with distinct phase costs
        FastBuildStats.record(1_000_000, 500_000, 300_000, 200_000, 100_000, 3, 10);
        FastBuildStats.record(2_000_000, 500_000, 300_000, 200_000, 100_000, 5, 20);
        FastBuildStats.record(3_000_000, 500_000, 300_000, 200_000, 100_000, 7, 30);

        assertEquals(3, FastBuildStats.count());
        assertEquals(3, FastBuildStats.INSTANCE.totalCount());
        // sort avg = (1+2+3 ms) / 3 = 2 ms
        assertEquals(2.0, FastBuildStats.INSTANCE.avgSortMs(), 0.001);
        assertEquals(0.5, FastBuildStats.INSTANCE.avgPartitionMs(), 0.001);
        assertEquals(0.3, FastBuildStats.INSTANCE.avgConflictMs(), 0.001);
        assertEquals(0.2, FastBuildStats.INSTANCE.avgSccMs(), 0.001);
        assertEquals(0.1, FastBuildStats.INSTANCE.avgAssembleMs(), 0.001);
        // avg global touching = (3+5+7)/3 = 5
        assertEquals(5.0, FastBuildStats.avgGlobalTouching(), 0.001);
        // avg total = (10+20+30)/3 = 20
        assertEquals(20.0, FastBuildStats.avgTotal(), 0.001);
    }

    @Test
    void staticResetClearsCountAndAverages() {
        FastBuildStats.record(5_000_000, 1_000_000, 500_000, 200_000, 100_000, 10, 50);
        assertTrue(FastBuildStats.count() > 0);

        FastBuildStats.reset();
        assertEquals(0, FastBuildStats.count());
        assertEquals(0.0, FastBuildStats.INSTANCE.avgSortMs());
        assertEquals(0.0, FastBuildStats.avgGlobalTouching());
    }

    @Test
    void summaryNoSamples() {
        FastBuildStats.reset();
        assertEquals("Fast build stats: no samples", FastBuildStats.summary());
    }

    @Test
    void summaryWithSamples() {
        FastBuildStats.reset();
        FastBuildStats.record(1_000_000, 0, 0, 0, 0, 0, 5);
        String s = FastBuildStats.summary();
        assertTrue(s.contains("Fast build stats:"), "summary: " + s);
        assertTrue(s.contains("sort="), "summary: " + s);
        assertTrue(s.contains("ticks="), "summary: " + s);
    }
}
