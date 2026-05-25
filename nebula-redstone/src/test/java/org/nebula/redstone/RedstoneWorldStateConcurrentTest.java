package org.nebula.redstone;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrent stress tests for the CAS-versioned world state.
 *
 * <p>These exercise the contention path that single-threaded snapshot tests
 * cannot: multiple threads racing to commit at the same version, and
 * read-modify-write retry loops converging on the correct final value.
 */
class RedstoneWorldStateConcurrentTest {

    private static final WorldPos POS = new WorldPos(0, 0, 64, 0);

    @Test
    void exactlyOneWinsWhenAllReadSameVersion() throws Exception {
        RedstoneWorldState world = new RedstoneWorldState();
        world.putPowerLevel(POS, 0);
        long initialVersion = world.getVersion(POS);

        int threads = 32;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger wins = new AtomicInteger();

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int id = i;
                exec.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();
                        if (world.casCommit(POS, initialVersion, id, Map.of())) {
                            wins.incrementAndGet();
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally {
            exec.shutdown();
        }

        assertEquals(1, wins.get(),
            "Exactly one CAS should succeed when all threads read the same initial version");
    }

    @Test
    void readModifyWriteConvergesWithRetry() throws Exception {
        RedstoneWorldState world = new RedstoneWorldState();
        world.putPowerLevel(POS, 0);

        int threads = 16;
        int incrementsPerThread = 50;
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger totalAttempts = new AtomicInteger();

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                exec.submit(() -> {
                    try {
                        for (int k = 0; k < incrementsPerThread; k++) {
                            while (true) {
                                totalAttempts.incrementAndGet();
                                long ver = world.getVersion(POS);
                                int cur = world.getPowerLevel(POS);
                                if (world.casCommit(POS, ver, cur + 1, Map.of())) break;
                            }
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            exec.shutdown();
        }

        assertEquals(threads * incrementsPerThread, world.getPowerLevel(POS),
            "Read-modify-write with retry should never lose an update");
        assertTrue(totalAttempts.get() >= threads * incrementsPerThread,
            "Total attempts should be at least the number of successful commits");
    }

    @RepeatedTest(3)
    void snapshotCommitUnderContention() throws Exception {
        RedstoneWorldState world = new RedstoneWorldState();
        world.putPowerLevel(POS, 0);

        int threads = 8;
        int attemptsPerThread = 25;
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger fails = new AtomicInteger();

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                exec.submit(() -> {
                    try {
                        for (int k = 0; k < attemptsPerThread; k++) {
                            RedstoneStateSnapshot snap = new RedstoneStateSnapshot();
                            int cur = snap.readPowerLevel(world, POS);
                            snap.setPowerLevel(POS, cur + 1);
                            RedstoneStateSnapshot.CommitResult r = snap.commit(world);
                            if (r.success()) commits.incrementAndGet();
                            else fails.incrementAndGet();
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            exec.shutdown();
        }

        int totalAttempts = commits.get() + fails.get();
        assertEquals(threads * attemptsPerThread, totalAttempts,
            "Every snapshot must finish (success or failure)");
        // Every successful commit increments POS by exactly 1 → invariant
        assertEquals(commits.get(), world.getPowerLevel(POS),
            "Final power level should equal the number of successful commits");
    }

    @Test
    void disjointPositionsCommitConcurrentlyWithoutInterference() throws Exception {
        RedstoneWorldState world = new RedstoneWorldState();
        int positionCount = 64;
        WorldPos[] positions = new WorldPos[positionCount];
        for (int i = 0; i < positionCount; i++) {
            positions[i] = new WorldPos(0, i, 64, 0);
            world.putPowerLevel(positions[i], 0);
        }

        int threads = positionCount;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                exec.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();
                        RedstoneStateSnapshot snap = new RedstoneStateSnapshot();
                        snap.readPowerLevel(world, positions[idx]);
                        snap.setPowerLevel(positions[idx], idx + 100);
                        snap.commit(world);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            exec.shutdown();
        }

        for (int i = 0; i < positionCount; i++) {
            assertEquals(i + 100, world.getPowerLevel(positions[i]),
                "Disjoint positions should each retain their writer's value");
        }
    }
}
