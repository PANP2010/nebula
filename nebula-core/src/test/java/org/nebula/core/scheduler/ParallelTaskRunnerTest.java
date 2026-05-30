package org.nebula.core.scheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the caller-runs {@link ParallelTaskRunner#runLayer}: every task runs
 * exactly once, exceptions surface, and parallelSafe degradation falls back to
 * serial. The caller-runs design means the calling thread executes one slice
 * itself rather than idling — these tests confirm correctness of that path.
 */
class ParallelTaskRunnerTest {

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private static TaskNode safeTask(String id, Runnable body) {
        WorldPos pos = new WorldPos(0, id.hashCode() & 1023, 64, 0);
        return TaskNode.parallelSafe(id, "test",
            RWSet.builder().writeBlock(pos).build(),
            body::run);
    }

    @Test
    void everyTaskRunsExactlyOnce() throws Exception {
        ParallelTaskRunner runner = new ParallelTaskRunner(TaskRunner.DIRECT, pool, 2, 4);
        ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();

        List<TaskNode> layer = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String id = "t" + i;
            layer.add(safeTask(id, () -> counts.merge(id, 1, Integer::sum)));
        }

        runner.runLayer(layer);

        assertEquals(200, counts.size(), "all distinct tasks ran");
        assertTrue(counts.values().stream().allMatch(c -> c == 1), "each task ran exactly once");
    }

    @Test
    void singleTaskLayerRunsInline() throws Exception {
        // Below threshold → degrade to serial, but must still run.
        ParallelTaskRunner runner = new ParallelTaskRunner(TaskRunner.DIRECT, pool, 2, 4);
        AtomicInteger ran = new AtomicInteger();
        runner.runLayer(List.of(safeTask("solo", ran::incrementAndGet)));
        assertEquals(1, ran.get());
        assertEquals(1, runner.degradedLayers(), "single-task layer degraded to serial");
    }

    @Test
    void exceptionInOffloadedChunkSurfaces() {
        ParallelTaskRunner runner = new ParallelTaskRunner(TaskRunner.DIRECT, pool, 2, 4);
        List<TaskNode> layer = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            layer.add(safeTask("ok" + i, () -> {}));
        }
        // A failing task near the end is likely to land in an offloaded chunk.
        layer.add(safeTask("boom", () -> { throw new IllegalStateException("kaboom"); }));

        Exception ex = assertThrows(Exception.class, () -> runner.runLayer(layer));
        assertTrue(rootMessage(ex).contains("kaboom"), "the task exception propagated: " + rootMessage(ex));
    }

    @Test
    void exceptionInCallerChunkSurfaces() {
        // With chunks sized so chunk 0 (caller-run) contains the failure.
        ParallelTaskRunner runner = new ParallelTaskRunner(TaskRunner.DIRECT, pool, 2, 4);
        List<TaskNode> layer = new ArrayList<>();
        layer.add(safeTask("boom", () -> { throw new IllegalStateException("first-chunk-fail"); }));
        for (int i = 0; i < 50; i++) {
            layer.add(safeTask("ok" + i, () -> {}));
        }
        Exception ex = assertThrows(Exception.class, () -> runner.runLayer(layer));
        assertTrue(rootMessage(ex).contains("first-chunk-fail"), rootMessage(ex));
    }

    @Test
    void nonParallelSafeLayerDegradesToSerial() throws Exception {
        ParallelTaskRunner runner = new ParallelTaskRunner(TaskRunner.DIRECT, pool, 2, 4);
        AtomicInteger ran = new AtomicInteger();
        WorldPos pos = new WorldPos(0, 1, 64, 1);
        // Plain TaskNode (parallelSafe=false).
        TaskNode unsafe = new TaskNode("unsafe", "test",
            RWSet.builder().writeBlock(pos).build(), ran::incrementAndGet);
        TaskNode safe = safeTask("safe", ran::incrementAndGet);

        runner.runLayer(List.of(unsafe, safe));

        assertEquals(2, ran.get(), "both tasks ran");
        assertEquals(1, runner.degradedLayers(), "mixed-safety layer degraded to serial");
    }

    @Test
    void manyLayersAllConsistent() throws Exception {
        ParallelTaskRunner runner = new ParallelTaskRunner(TaskRunner.DIRECT, pool, 2, 4);
        for (int iter = 0; iter < 100; iter++) {
            AtomicInteger ran = new AtomicInteger();
            List<TaskNode> layer = new ArrayList<>();
            int size = 1 + (iter % 32);
            for (int i = 0; i < size; i++) {
                layer.add(safeTask("t" + iter + "_" + i, ran::incrementAndGet));
            }
            runner.runLayer(layer);
            assertEquals(size, ran.get(), "iter " + iter + " ran all " + size + " tasks");
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getMessage() == null ? "" : c.getMessage();
    }
}
