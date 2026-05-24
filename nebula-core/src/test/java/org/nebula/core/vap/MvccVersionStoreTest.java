package org.nebula.core.vap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MvccVersionStoreTest {

    @Test
    void snapshotFreezesCurrent() {
        MvccVersionStore<Integer> store = new MvccVersionStore<>(10);
        store.snapshot();
        store.set(20);
        assertEquals(10, store.read(), "snapshot should return tick-start value");
        assertEquals(20, store.readLatest(), "readLatest should return live value");
    }

    @Test
    void updateAppliesTransform() {
        MvccVersionStore<Integer> store = new MvccVersionStore<>(5);
        Integer result = store.update(v -> v + 3);
        assertEquals(8, result);
        assertEquals(8, store.readLatest());
    }

    @Test
    void mergeResolvesConflict() {
        MvccVersionStore<Integer> store = new MvccVersionStore<>(0, Integer::sum);
        Integer merged = store.merge(10, 20);
        assertEquals(30, merged);
        assertEquals(30, store.readLatest());
    }

    @Test
    void defaultMergeIsLastWriterWins() {
        MvccVersionStore<String> store = new MvccVersionStore<>("initial");
        String merged = store.merge("first", "second");
        assertEquals("second", merged);
    }

    @Test
    void concurrentUpdatesAreAtomic() throws Exception {
        MvccVersionStore<Integer> store = new MvccVersionStore<>(0);
        int threads = 10;
        int increments = 1000;
        Thread[] workers = new Thread[threads];

        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                for (int i = 0; i < increments; i++) {
                    store.update(v -> v + 1);
                }
            });
            workers[t].start();
        }
        for (Thread w : workers) w.join();

        assertEquals(threads * increments, store.readLatest());
    }
}
