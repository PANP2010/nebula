package org.nebula.core.vap;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class PluginTaskQueueTest {

    @Test
    void executesInPluginRegistrationOrder() throws Exception {
        List<String> executed = new ArrayList<>();
        PluginTaskQueue queue = new PluginTaskQueue(List.of("PluginA", "PluginB", "PluginC"));

        // Submit out of order
        queue.submit(new PluginTask("PluginC", "c-op", () -> executed.add("C")));
        queue.submit(new PluginTask("PluginA", "a-op", () -> executed.add("A")));
        queue.submit(new PluginTask("PluginB", "b-op", () -> executed.add("B")));

        int count = queue.drainAndExecute();
        assertEquals(3, count);
        assertEquals(List.of("A", "B", "C"), executed);
    }

    @Test
    void preservesSubmissionOrderWithinSamePlugin() throws Exception {
        List<String> executed = new ArrayList<>();
        PluginTaskQueue queue = new PluginTaskQueue(List.of("MyPlugin"));

        queue.submit(new PluginTask("MyPlugin", "first", () -> executed.add("1")));
        queue.submit(new PluginTask("MyPlugin", "second", () -> executed.add("2")));
        queue.submit(new PluginTask("MyPlugin", "third", () -> executed.add("3")));

        queue.drainAndExecute();
        assertEquals(List.of("1", "2", "3"), executed);
    }

    @Test
    void emptyQueueReturnsZero() throws Exception {
        PluginTaskQueue queue = new PluginTaskQueue();
        assertEquals(0, queue.drainAndExecute());
    }

    @Test
    void pendingCountTracksSubmissions() {
        PluginTaskQueue queue = new PluginTaskQueue();
        assertEquals(0, queue.pendingCount());
        queue.submit(new PluginTask("P", "op", () -> {}));
        assertEquals(1, queue.pendingCount());
        queue.submit(new PluginTask("P", "op2", () -> {}));
        assertEquals(2, queue.pendingCount());
    }

    @Test
    void drainClearsQueue() throws Exception {
        PluginTaskQueue queue = new PluginTaskQueue();
        queue.submit(new PluginTask("P", "op", () -> {}));
        queue.drainAndExecute();
        assertEquals(0, queue.pendingCount());
    }

    @Test
    void failingTaskThrowsPluginTaskException() {
        PluginTaskQueue queue = new PluginTaskQueue();
        queue.submit(new PluginTask("BadPlugin", "crash", () -> {
            throw new RuntimeException("boom");
        }));

        PluginTaskException ex = assertThrows(PluginTaskException.class, queue::drainAndExecute);
        assertEquals("BadPlugin", ex.pluginName());
        assertEquals("crash", ex.taskDescription());
        assertInstanceOf(RuntimeException.class, ex.getCause());
    }

    @Test
    void clearDiscardsAllPending() {
        PluginTaskQueue queue = new PluginTaskQueue();
        queue.submit(new PluginTask("P", "op1", () -> {}));
        queue.submit(new PluginTask("P", "op2", () -> {}));
        queue.clear();
        assertEquals(0, queue.pendingCount());
    }

    @Test
    void concurrentSubmitsAreSafe() throws Exception {
        PluginTaskQueue queue = new PluginTaskQueue(List.of("A", "B"));
        int threads = 8;
        int tasksPerThread = 100;
        CountDownLatch latch = new CountDownLatch(threads);

        try (var executor = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                String plugin = t % 2 == 0 ? "A" : "B";
                executor.submit(() -> {
                    for (int i = 0; i < tasksPerThread; i++) {
                        queue.submit(new PluginTask(plugin, "op", () -> {}));
                    }
                    latch.countDown();
                });
            }
            latch.await();
        }

        assertEquals(threads * tasksPerThread, queue.pendingCount());
        int executed = queue.drainAndExecute();
        assertEquals(threads * tasksPerThread, executed);
        assertEquals(0, queue.pendingCount());
    }

    @Test
    void unknownPluginsSortedLast() throws Exception {
        List<String> executed = new ArrayList<>();
        PluginTaskQueue queue = new PluginTaskQueue(List.of("Known"));

        queue.submit(new PluginTask("Unknown", "u-op", () -> executed.add("unknown")));
        queue.submit(new PluginTask("Known", "k-op", () -> executed.add("known")));

        queue.drainAndExecute();
        assertEquals(List.of("known", "unknown"), executed);
    }
}
