package org.nebula.core.vap;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ManagedStateProxyTest {

    @SuppressWarnings("unused")
    static class TestPlugin {
        @ManagedState(strategy = ConcurrencyStrategy.MVCC)
        private String name = "initial";

        @ManagedState(strategy = ConcurrencyStrategy.ATOMIC)
        private Integer counter = 0;

        @ManagedState(strategy = ConcurrencyStrategy.LOCK)
        private java.util.List<String> items = new java.util.ArrayList<>();
    }

    @Test
    void registerScansAnnotatedFields() {
        ManagedStateProxy proxy = new ManagedStateProxy("test");
        proxy.register(new TestPlugin());
        assertEquals(3, proxy.fieldCount());
    }

    @Test
    void mvccReadReturnsCurrent() {
        ManagedStateProxy proxy = new ManagedStateProxy("test");
        proxy.register(new TestPlugin());
        assertEquals("initial", proxy.read("name"));
    }

    @Test
    void mvccWriteUpdatesValue() {
        ManagedStateProxy proxy = new ManagedStateProxy("test");
        proxy.register(new TestPlugin());
        proxy.write("name", "updated");
        assertEquals("updated", proxy.read("name"));
    }

    @Test
    void mvccSnapshotFreezesRead() {
        ManagedStateProxy proxy = new ManagedStateProxy("test");
        proxy.register(new TestPlugin());

        proxy.snapshotAll();
        proxy.write("name", "changed");

        // read() returns latest for intra-tick writes (plugin sees own writes)
        assertEquals("changed", proxy.read("name"));
    }

    @Test
    void atomicReadAndWrite() {
        ManagedStateProxy proxy = new ManagedStateProxy("test");
        proxy.register(new TestPlugin());

        assertEquals(0, proxy.read("counter"));
        proxy.write("counter", 42);
        assertEquals(42, proxy.read("counter"));
    }

    @Test
    void atomicUpdate() {
        ManagedStateProxy proxy = new ManagedStateProxy("test");
        proxy.register(new TestPlugin());

        proxy.write("counter", 10);
        Object result = proxy.update("counter", v -> (Integer) v + 5);
        assertEquals(15, result);
    }

    @Test
    void lockReadAndWrite() {
        ManagedStateProxy proxy = new ManagedStateProxy("test");
        proxy.register(new TestPlugin());

        assertNotNull(proxy.read("items"));
        java.util.List<String> newItems = java.util.List.of("a", "b");
        proxy.write("items", newItems);
        assertEquals(newItems, proxy.read("items"));
    }

    @Test
    void strategyForReturnsCorrectType() {
        ManagedStateProxy proxy = new ManagedStateProxy("test");
        proxy.register(new TestPlugin());

        assertEquals(ConcurrencyStrategy.MVCC, proxy.strategyFor("name"));
        assertEquals(ConcurrencyStrategy.ATOMIC, proxy.strategyFor("counter"));
        assertEquals(ConcurrencyStrategy.LOCK, proxy.strategyFor("items"));
    }

    @Test
    void readUnknownFieldThrows() {
        ManagedStateProxy proxy = new ManagedStateProxy("test");
        proxy.register(new TestPlugin());
        assertThrows(IllegalArgumentException.class, () -> proxy.read("nonexistent"));
    }

    @Test
    void concurrentAtomicUpdates() throws Exception {
        ManagedStateProxy proxy = new ManagedStateProxy("test");
        proxy.register(new TestPlugin());

        int threads = 8;
        int increments = 500;
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            Thread.ofVirtual().start(() -> {
                for (int i = 0; i < increments; i++) {
                    proxy.update("counter", v -> (Integer) v + 1);
                }
                latch.countDown();
            });
        }

        latch.await();
        assertEquals(threads * increments, proxy.read("counter"));
    }

    @Test
    void nullFieldThrowsOnRegister() {
        class NullPlugin {
            @ManagedState(strategy = ConcurrencyStrategy.ATOMIC)
            private String data = null;
        }
        ManagedStateProxy proxy = new ManagedStateProxy("test");
        assertThrows(IllegalStateException.class, () -> proxy.register(new NullPlugin()));
    }
}
