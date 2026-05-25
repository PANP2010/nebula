package org.nebula.redstone;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RedstoneWorldStateTest {

    private static final WorldPos POS_A = new WorldPos(0, 10, 64, 20);
    private static final WorldPos POS_B = new WorldPos(0, 11, 64, 20);
    private static final WorldPos POS_C = new WorldPos(0, 12, 64, 20);

    private RedstoneWorldState world;

    @BeforeEach
    void setUp() {
        world = new RedstoneWorldState();
    }

    @Test
    void emptyStateReturnsDefaults() {
        assertEquals(-1, world.getPowerLevel(POS_A));
        assertNull(world.getInternalState(POS_A, "counter"));
        assertEquals(0, world.getVersion(POS_A));
        assertEquals(0, world.size());
    }

    @Test
    void putAndRead() {
        world.putPowerLevel(POS_A, 15);
        assertEquals(15, world.getPowerLevel(POS_A));
        assertTrue(world.getVersion(POS_A) > 0);
        assertEquals(1, world.size());
    }

    @Test
    void putWithInternalState() {
        world.put(POS_A, 10, Map.of("delay", 3, "locked", true));
        assertEquals(10, world.getPowerLevel(POS_A));
        assertEquals(3, world.getInternalState(POS_A, "delay"));
        assertEquals(true, world.getInternalState(POS_A, "locked"));
    }

    @Test
    void casCommitSucceedsWithCorrectVersion() {
        world.putPowerLevel(POS_A, 5);
        long version = world.getVersion(POS_A);

        boolean success = world.casCommit(POS_A, version, 15, Map.of());
        assertTrue(success);
        assertEquals(15, world.getPowerLevel(POS_A));
    }

    @Test
    void casCommitFailsWithStaleVersion() {
        world.putPowerLevel(POS_A, 5);
        long staleVersion = world.getVersion(POS_A);

        // Another write advances the version
        world.putPowerLevel(POS_A, 10);

        boolean success = world.casCommit(POS_A, staleVersion, 15, Map.of());
        assertFalse(success);
        assertEquals(10, world.getPowerLevel(POS_A), "Value unchanged after failed CAS");
    }

    @Test
    void casCommitOnAbsentPositionSucceeds() {
        boolean success = world.casCommit(POS_A, 0, 7, Map.of("counter", 1));
        assertTrue(success);
        assertEquals(7, world.getPowerLevel(POS_A));
        assertEquals(1, world.getInternalState(POS_A, "counter"));
    }

    @Test
    void casCommitOnAbsentPositionFailsIfSomebodyInsertedFirst() {
        world.putPowerLevel(POS_A, 3);

        // expectedVersion=0 means "I read it as absent"
        boolean success = world.casCommit(POS_A, 0, 7, Map.of());
        assertFalse(success);
        assertEquals(3, world.getPowerLevel(POS_A));
    }

    @Test
    void versionMonotonicallyIncreases() {
        world.putPowerLevel(POS_A, 1);
        long v1 = world.getVersion(POS_A);

        world.putPowerLevel(POS_A, 2);
        long v2 = world.getVersion(POS_A);

        world.putPowerLevel(POS_B, 3);
        long v3 = world.getVersion(POS_B);

        assertTrue(v2 > v1);
        assertTrue(v3 > v2);
    }

    @Test
    void concurrentDisjointWritesAllSucceed() throws InterruptedException {
        int threadCount = 8;
        WorldPos[] positions = new WorldPos[threadCount];
        for (int i = 0; i < threadCount; i++) {
            positions[i] = new WorldPos(0, i, 64, 0);
            world.putPowerLevel(positions[i], 0);
        }

        long[] versions = new long[threadCount];
        for (int i = 0; i < threadCount; i++) {
            versions[i] = world.getVersion(positions[i]);
        }

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successes = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                boolean ok = world.casCommit(positions[idx], versions[idx], idx + 1, Map.of());
                if (ok) successes.incrementAndGet();
                latch.countDown();
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(threadCount, successes.get(), "All disjoint CAS commits should succeed");
        for (int i = 0; i < threadCount; i++) {
            assertEquals(i + 1, world.getPowerLevel(positions[i]));
        }
    }

    @Test
    void concurrentConflictingWritesOnlyOneWins() throws InterruptedException {
        world.putPowerLevel(POS_A, 0);
        long version = world.getVersion(POS_A);

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successes = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int val = i + 1;
            executor.submit(() -> {
                boolean ok = world.casCommit(POS_A, version, val, Map.of());
                if (ok) successes.incrementAndGet();
                latch.countDown();
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(1, successes.get(), "Only one conflicting CAS should succeed");
        int finalValue = world.getPowerLevel(POS_A);
        assertTrue(finalValue >= 1 && finalValue <= threadCount);
    }

    @Test
    void clearResetsEverything() {
        world.putPowerLevel(POS_A, 10);
        world.putPowerLevel(POS_B, 5);

        world.clear();
        assertEquals(0, world.size());
        assertEquals(-1, world.getPowerLevel(POS_A));
        assertEquals(0, world.getVersion(POS_A));
    }
}
