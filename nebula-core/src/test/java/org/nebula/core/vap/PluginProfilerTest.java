package org.nebula.core.vap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PluginProfilerTest {

    private PluginProfiler profiler;

    @BeforeEach
    void setUp() {
        profiler = new PluginProfiler(10);
    }

    @Test
    void recordsOperationMetrics() {
        profiler.tickStart();
        long token = profiler.beginOperation("myPlugin");
        busyWait(1_000_000); // ~1ms
        profiler.endOperation("myPlugin", token);

        var stats = profiler.currentStats("myPlugin");
        assertEquals(1, stats.callCount());
        assertTrue(stats.totalNs() > 500_000, "Should record at least 0.5ms");
    }

    @Test
    void tickEndCreatesHistory() {
        profiler.tickStart();
        long t = profiler.beginOperation("A");
        profiler.endOperation("A", t);
        profiler.tickEnd();

        assertEquals(1, profiler.history().size());
        var summary = profiler.history().get(0);
        assertNotNull(summary.pluginStats().get("A"));
        assertEquals(1, summary.pluginStats().get("A").callCount());
    }

    @Test
    void tickEndResetsCurrent() {
        profiler.tickStart();
        long t = profiler.beginOperation("B");
        profiler.endOperation("B", t);
        profiler.tickEnd();

        var stats = profiler.currentStats("B");
        assertEquals(0, stats.callCount());
    }

    @Test
    void multipleOperationsAccumulate() {
        profiler.tickStart();
        for (int i = 0; i < 5; i++) {
            long t = profiler.beginOperation("multi");
            profiler.endOperation("multi", t);
        }

        var stats = profiler.currentStats("multi");
        assertEquals(5, stats.callCount());
    }

    @Test
    void historyRespectMaxSize() {
        for (int i = 0; i < 15; i++) {
            profiler.tickStart();
            long t = profiler.beginOperation("plugin");
            profiler.endOperation("plugin", t);
            profiler.tickEnd();
        }

        assertTrue(profiler.history().size() <= 10);
    }

    @Test
    void trackedPluginsReturnsAllNames() {
        profiler.beginOperation("X");
        profiler.beginOperation("Y");
        profiler.beginOperation("Z");

        var tracked = profiler.trackedPlugins();
        assertEquals(3, tracked.size());
        assertTrue(tracked.contains("X"));
        assertTrue(tracked.contains("Y"));
        assertTrue(tracked.contains("Z"));
    }

    @Test
    void resetClearsEverything() {
        profiler.tickStart();
        long t = profiler.beginOperation("p");
        profiler.endOperation("p", t);
        profiler.tickEnd();

        profiler.reset();
        assertTrue(profiler.history().isEmpty());
        assertTrue(profiler.trackedPlugins().isEmpty());
    }

    @Test
    void unknownPluginReturnsZeroStats() {
        var stats = profiler.currentStats("nonexistent");
        assertEquals(0, stats.callCount());
        assertEquals(0, stats.totalNs());
        assertEquals(0, stats.maxCallNs());
        assertEquals(0.0, stats.averageNs());
    }

    @Test
    void maxCallNsTracksLongest() {
        profiler.tickStart();

        long t1 = profiler.beginOperation("P");
        profiler.endOperation("P", t1);

        long t2 = profiler.beginOperation("P");
        busyWait(2_000_000); // ~2ms
        profiler.endOperation("P", t2);

        var stats = profiler.currentStats("P");
        assertTrue(stats.maxCallNs() > 1_000_000);
        assertTrue(stats.maxCallNs() >= stats.averageNs());
    }

    @Test
    void averageNsComputation() {
        profiler.tickStart();
        for (int i = 0; i < 4; i++) {
            long t = profiler.beginOperation("avg");
            busyWait(500_000);
            profiler.endOperation("avg", t);
        }

        var stats = profiler.currentStats("avg");
        double avg = stats.averageNs();
        assertTrue(avg > 0);
        assertEquals((double) stats.totalNs() / stats.callCount(), avg, 0.01);
    }

    private void busyWait(long nanos) {
        long start = System.nanoTime();
        while (System.nanoTime() - start < nanos) {
            Thread.onSpinWait();
        }
    }
}
