package org.nebula.core.vap;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class VapApiInterceptorTest {

    @Test
    void interceptEnqueuesTaskAndCompletesWhenDrained() throws Exception {
        PluginTaskQueue queue = new PluginTaskQueue(List.of("testPlugin"));
        VapApiInterceptor interceptor = new VapApiInterceptor(queue);

        AtomicBoolean executed = new AtomicBoolean(false);
        var future = interceptor.intercept("testPlugin", "teleport",
            () -> executed.set(true), 0);

        assertFalse(future.isDone());
        assertEquals(1, queue.pendingCount());

        queue.drainAndExecute();

        assertTrue(future.isDone());
        assertTrue(executed.get());
    }

    @Test
    void interceptAndWaitBlocksUntilExecution() throws Exception {
        PluginTaskQueue queue = new PluginTaskQueue(List.of("myPlugin"));
        VapApiInterceptor interceptor = new VapApiInterceptor(queue, 5000);

        AtomicInteger counter = new AtomicInteger(0);
        CountDownLatch submitted = new CountDownLatch(1);

        Thread pluginThread = Thread.ofVirtual().start(() -> {
            try {
                submitted.countDown();
                interceptor.interceptAndWait("myPlugin", "setHealth",
                    () -> counter.incrementAndGet());
            } catch (VapInterceptException e) {
                fail("Should not throw: " + e.getMessage());
            }
        });

        submitted.await();
        Thread.sleep(50);
        assertEquals(0, counter.get());

        queue.drainAndExecute();
        pluginThread.join(2000);

        assertEquals(1, counter.get());
    }

    @Test
    void interceptAndWaitTimesOut() {
        PluginTaskQueue queue = new PluginTaskQueue();
        VapApiInterceptor interceptor = new VapApiInterceptor(queue, 100);

        assertThrows(VapInterceptException.class, () ->
            interceptor.interceptAndWait("slowPlugin", "bigQuery", () -> {})
        );
    }

    @Test
    void interceptPropagatesException() throws Exception {
        PluginTaskQueue queue = new PluginTaskQueue();
        VapApiInterceptor interceptor = new VapApiInterceptor(queue);

        interceptor.intercept("buggy", "crash",
            () -> { throw new RuntimeException("boom"); }, 0);

        assertThrows(PluginTaskException.class, () -> queue.drainAndExecute());
    }

    @Test
    void multipleInterceptsExecuteInOrder() throws Exception {
        PluginTaskQueue queue = new PluginTaskQueue(List.of("A", "B"));
        VapApiInterceptor interceptor = new VapApiInterceptor(queue);

        StringBuilder log = new StringBuilder();
        interceptor.intercept("B", "op1", () -> log.append("B1,"), 0);
        interceptor.intercept("A", "op1", () -> log.append("A1,"), 0);
        interceptor.intercept("A", "op2", () -> log.append("A2,"), 0);

        queue.drainAndExecute();

        assertEquals("A1,A2,B1,", log.toString());
    }
}
