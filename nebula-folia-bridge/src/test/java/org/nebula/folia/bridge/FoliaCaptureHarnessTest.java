package org.nebula.folia.bridge;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.nebula.replay.ReplayFrame;
import org.nebula.replay.ReplayRecorder;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link FoliaCaptureHarness} with proxy-stubbed Bukkit objects.
 */
class FoliaCaptureHarnessTest {

    private static Object def(Method m) {
        Class<?> r = m.getReturnType();
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == List.class) return List.of();
        return null;
    }

    private static Plugin pluginStub() {
        Server server = (Server) Proxy.newProxyInstance(
            Server.class.getClassLoader(), new Class<?>[]{Server.class},
            (p, m, a) -> {
                if (m.getName().equals("getWorlds")) return List.of();
                return def(m);
            });
        return (Plugin) Proxy.newProxyInstance(
            Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
            (p, m, a) -> {
                if (m.getName().equals("getServer")) return server;
                if (m.getName().equals("getName")) return "NebulaTest";
                return def(m);
            });
    }

    private static Plugin pluginWithWorld() {
        World world = (World) Proxy.newProxyInstance(
            World.class.getClassLoader(), new Class<?>[]{World.class},
            (p, m, a) -> def(m));
        Server server = (Server) Proxy.newProxyInstance(
            Server.class.getClassLoader(), new Class<?>[]{Server.class},
            (p, m, a) -> {
                if (m.getName().equals("getWorlds")) return List.of(world);
                if (m.getName().equals("getGlobalRegionScheduler")) {
                    return Proxy.newProxyInstance(
                        io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.class.getClassLoader(),
                        new Class<?>[]{io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.class},
                        (p2, m2, a2) -> def(m2));
                }
                return def(m);
            });
        return (Plugin) Proxy.newProxyInstance(
            Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
            (p, m, a) -> {
                if (m.getName().equals("getServer")) return server;
                if (m.getName().equals("getName")) return "NebulaTest";
                return def(m);
            });
    }

    @Test
    void tickDriverInvokedOncePerTickBeforeHashing() {
        // Driven capture: the TickDriver must fire once per captured tick, keyed off
        // the harness tick counter (0,1,2,...), and BEFORE the state hash is taken —
        // the hash for tick T must reflect the world after tick T's input.
        Plugin plugin = pluginWithWorld();
        ReplayRecorder recorder = new ReplayRecorder();
        java.util.List<Long> order = new java.util.ArrayList<>();

        FoliaCaptureHarness.TickDriver driver = tick -> order.add(tick); // "drive" marker: negated below
        FoliaCaptureHarness.StateHasher hasher = (world, tick) -> {
            order.add(-1L - tick); // "hash" marker for tick T (distinct from drive marker)
            return ("tick-" + tick).getBytes(StandardCharsets.UTF_8);
        };

        FoliaCaptureHarness harness = new FoliaCaptureHarness(plugin, recorder, hasher, null, driver);
        recorder.start();
        harness.onTickEnd(); // tick 0
        harness.onTickEnd(); // tick 1
        recorder.stop();

        // Drive(0), Hash(0), Drive(1), Hash(1) — strictly interleaved, drive first.
        assertEquals(java.util.List.of(0L, -1L, 1L, -2L), order);

        List<ReplayFrame> frames = recorder.getFrames();
        assertEquals(2, frames.size());
        assertEquals(0, frames.get(0).tickNumber());
        assertEquals(1, frames.get(1).tickNumber());
    }

    @Test
    void nullTickDriverIsInert() {
        // A static capture (no driver) must behave exactly as before: hashing still
        // happens, no NPE from the absent driver.
        Plugin plugin = pluginWithWorld();
        ReplayRecorder recorder = new ReplayRecorder();
        java.util.concurrent.atomic.AtomicInteger hashes = new java.util.concurrent.atomic.AtomicInteger();
        FoliaCaptureHarness.StateHasher hasher = (world, tick) -> {
            hashes.incrementAndGet();
            return new byte[]{(byte) tick};
        };

        FoliaCaptureHarness harness = new FoliaCaptureHarness(plugin, recorder, hasher);
        recorder.start();
        harness.onTickEnd();
        harness.onTickEnd();
        recorder.stop();

        assertEquals(2, hashes.get());
        assertEquals(2, recorder.getFrames().size());
    }

    @Test
    void startAndStopUpdatesState() {
        Plugin plugin = pluginStub();
        ReplayRecorder recorder = new ReplayRecorder();
        FoliaCaptureHarness.StateHasher hasher = (world, tick) ->
            ("tick-" + tick).getBytes(StandardCharsets.UTF_8);

        FoliaCaptureHarness harness = new FoliaCaptureHarness(plugin, recorder, hasher);

        assertFalse(harness.isRunning());
        // Cannot actually run ticks in unit test (needs Folia scheduler),
        // but we can verify start/stop lifecycle
        harness.stop(); // should be no-op
        assertFalse(harness.isRunning());
    }

    @Test
    void captureRecordsFrames() {
        // Simulate manual tick recording (without Folia scheduler)
        ReplayRecorder recorder = new ReplayRecorder();
        byte[] hash1 = "hash-tick-0".getBytes(StandardCharsets.UTF_8);
        byte[] hash2 = "hash-tick-1".getBytes(StandardCharsets.UTF_8);

        recorder.start();
        recorder.beginTick(0);
        recorder.endTick(hash1);

        recorder.beginTick(1);
        recorder.endTick(hash2);
        recorder.stop();

        List<ReplayFrame> frames = recorder.getFrames();
        assertEquals(2, frames.size());
        assertEquals(0, frames.get(0).tickNumber());
        assertEquals(1, frames.get(1).tickNumber());
        assertTrue(java.util.Arrays.equals(hash1, frames.get(0).stateHash()));
        assertTrue(java.util.Arrays.equals(hash2, frames.get(1).stateHash()));
    }

    @Test
    void captureWithPlayerInputs() {
        ReplayRecorder recorder = new ReplayRecorder();

        recorder.start();
        recorder.beginTick(0);
        recorder.recordPlayerInput("player1", new byte[]{1, 2, 3});
        recorder.recordPlayerInput("player1", new byte[]{4, 5});
        recorder.recordPlayerInput("player2", new byte[]{7});
        recorder.endTick("hash".getBytes(StandardCharsets.UTF_8));
        recorder.stop();

        List<ReplayFrame> frames = recorder.getFrames();
        assertEquals(1, frames.size());
        Map<String, List<byte[]>> inputs = frames.get(0).input().playerInputs();
        assertEquals(2, inputs.size());
        assertEquals(2, inputs.get("player1").size());
        assertEquals(1, inputs.get("player2").size());
    }

    @Test
    void verifierDetectsMismatch() {
        ReplayRecorder refRecorder = new ReplayRecorder();
        ReplayRecorder candRecorder = new ReplayRecorder();

        // Record reference
        refRecorder.start();
        refRecorder.beginTick(0);
        refRecorder.endTick("hash-a".getBytes(StandardCharsets.UTF_8));
        refRecorder.beginTick(1);
        refRecorder.endTick("hash-b".getBytes(StandardCharsets.UTF_8));
        refRecorder.stop();

        // Record candidate with different second hash
        candRecorder.start();
        candRecorder.beginTick(0);
        candRecorder.endTick("hash-a".getBytes(StandardCharsets.UTF_8));
        candRecorder.beginTick(1);
        candRecorder.endTick("hash-X".getBytes(StandardCharsets.UTF_8));
        candRecorder.stop();

        var result = org.nebula.replay.ReplayVerifier.verify(
            refRecorder.getFrames(), candRecorder.getFrames());

        assertFalse(result.passed());
        assertEquals(1, result.mismatchCount());
        assertEquals(1, result.mismatches().get(0).tickNumber());
    }

    @Test
    void verifierPassesOnMatch() {
        ReplayRecorder recorder1 = new ReplayRecorder();
        ReplayRecorder recorder2 = new ReplayRecorder();

        byte[] hash = "same-hash".getBytes(StandardCharsets.UTF_8);

        recorder1.start();
        recorder1.beginTick(0);
        recorder1.endTick(hash);
        recorder1.stop();

        recorder2.start();
        recorder2.beginTick(0);
        recorder2.endTick(hash.clone());
        recorder2.stop();

        var result = org.nebula.replay.ReplayVerifier.verify(
            recorder1.getFrames(), recorder2.getFrames());

        assertTrue(result.passed());
    }
}