package org.nebula.core.scheduler;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class DagExecutorTest {
    @Test
    void executesTasksByTopologicalLayer() throws Exception {
        WorldPos pos = new WorldPos(0, 0, 64, 0);
        List<String> events = new ArrayList<>();
        TaskNode writer = new TaskNode("a-write", "WRITE", RWSet.builder().writeBlock(pos).build(), () -> events.add("write"));
        TaskNode reader = new TaskNode("b-read", "READ", RWSet.builder().readBlock(pos).build(), () -> events.add("read"));
        TaskNode independent = new TaskNode("c-independent", "INDEPENDENT", RWSet.empty(), () -> events.add("independent"));

        DagExecutionReport report = DagExecutor.execute(DagBuilder.build(List.of(reader, independent, writer)));

        assertEquals(List.of(List.of("a-write", "c-independent"), List.of("b-read")), report.layers());
        assertTrue(events.indexOf("write") < events.indexOf("read"));
        assertTrue(events.indexOf("independent") < events.indexOf("read"));
        assertEquals(3, report.taskCount());
    }

    @Test
    void waitsForParallelLayerBeforeAdvancing() throws Exception {
        CountDownLatch layerStarted = new CountDownLatch(2);
        CountDownLatch releaseLayer = new CountDownLatch(1);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        TaskNode first = new TaskNode("a-first", "INDEPENDENT", RWSet.empty(), () -> {
            layerStarted.countDown();
            assertTrue(releaseLayer.await(2, TimeUnit.SECONDS));
            events.add("first");
        });
        TaskNode second = new TaskNode("b-second", "INDEPENDENT", RWSet.empty(), () -> {
            layerStarted.countDown();
            assertTrue(releaseLayer.await(2, TimeUnit.SECONDS));
            events.add("second");
        });
        TaskNode finalTask = new TaskNode("c-final", "FINAL", RWSet.builder()
            .readBlock(new WorldPos(0, 1, 64, 1))
            .build(), () -> {
            assertTrue(events.contains("first"));
            assertTrue(events.contains("second"));
            events.add("final");
        });

        TaskGraph graph = new TaskGraph(
            java.util.Map.of(
                first.taskId(), first,
                second.taskId(), second,
                finalTask.taskId(), finalTask
            ),
            java.util.Set.of(
                new DependencyEdge(first.taskId(), finalTask.taskId(), DependencyType.RAW),
                new DependencyEdge(second.taskId(), finalTask.taskId(), DependencyType.RAW)
            )
        );

        try (var executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<DagExecutionReport> execution = CompletableFuture.supplyAsync(() -> {
                try {
                    return DagExecutor.execute(graph, TaskRunner.DIRECT, executor);
                } catch (DagExecutionException ex) {
                    throw new CompletionException(ex);
                }
            });
            assertTrue(layerStarted.await(2, TimeUnit.SECONDS));
            releaseLayer.countDown();
            DagExecutionReport report = execution.join();

            assertEquals("final", events.getLast());
            assertEquals(3, report.completedTaskIds().size());
        }
    }

    @Test
    void reportsLayerFailureAndStopsLaterLayers() throws Exception {
        WorldPos pos = new WorldPos(0, 3, 64, 3);
        List<String> events = new ArrayList<>();
        TaskNode failingWriter = new TaskNode("a-write", "WRITE", RWSet.builder().writeBlock(pos).build(), () -> {
            events.add("write");
            throw new IllegalStateException("boom");
        });
        TaskNode reader = new TaskNode("b-read", "READ", RWSet.builder().readBlock(pos).build(), () -> events.add("read"));

        try {
            DagExecutor.execute(DagBuilder.build(List.of(reader, failingWriter)));
            fail("expected execution failure");
        } catch (DagExecutionException ex) {
            assertEquals(0, ex.failedLayerIndex());
            assertTrue(ex.completedTaskIds().isEmpty());
            assertEquals(1, ex.failures().size());
            assertEquals(List.of("write"), events);
        }
    }
}
