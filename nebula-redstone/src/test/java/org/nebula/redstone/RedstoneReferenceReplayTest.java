package org.nebula.redstone;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;
import org.nebula.redstone.actions.RedstoneActions;
import org.nebula.replay.ReplayFrame;
import org.nebula.replay.ReplayPlayer;
import org.nebula.replay.ReplayRecorder;
import org.nebula.replay.ReplayVerifier;
import org.nebula.replay.StateHashComputer;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reference-capture harness (see docs/DEVELOPMENT_PLAN.md Milestone 5).
 *
 * <p>Records a redstone simulation to a {@code .nrpl} reference file on disk
 * via {@link ReplayRecorder#save}, loads it back with {@link ReplayPlayer#load},
 * and verifies that a fresh run reproduces the saved per-tick state hashes.
 *
 * <p>This is the reusable scaffold for the real DG1 gate: today the reference
 * is captured from Nebula itself (proving the save → load → re-run → verify
 * loop and the binary format round-trip end to end). When a Folia test server
 * is available, the same reference file can instead be captured from vanilla
 * output, turning this into a true zero-diff DG1 check with no harness changes.
 *
 * <p>It complements {@link RedstoneReplayDeterminismTest} (in-memory
 * self-consistency) by exercising the on-disk serialisation path
 * ({@code ReplayRecorder.save} / {@code ReplayPlayer.load}) that an external
 * reference capture would depend on.
 */
class RedstoneReferenceReplayTest {

    private static final int DIM = 0;
    private static final int TICKS = 100;
    private static final int LINE_LENGTH = 14;

    @Test
    void capturedReferenceFileVerifiesAgainstAFreshRun(@TempDir Path tmp) throws Exception {
        // 1. Capture a reference run to disk.
        ReplayRecorder reference = simulate();
        Path referenceFile = tmp.resolve("redstone-reference.nrpl");
        reference.save(referenceFile);

        // 2. Load it back — exercises the binary save/load round-trip.
        List<ReplayFrame> loaded = ReplayPlayer.load(referenceFile);
        assertEquals(TICKS, loaded.size(), "Loaded reference should have one frame per tick");
        assertEquals(reference.getFrames().size(), loaded.size(),
            "Round-trip must preserve frame count");

        // 3. Run the scenario fresh and verify against the loaded reference.
        List<ReplayFrame> fresh = simulate().getFrames();
        ReplayVerifier.VerificationResult result = ReplayVerifier.verify(loaded, fresh);
        assertTrue(result.passed(),
            "Fresh run diverged from the captured reference file: " + describe(result));

        // 4. Liveness: the reference must not be a no-op.
        long distinct = loaded.stream().map(ReplayFrame::stateHashHex).distinct().count();
        assertTrue(distinct > 1, "Reference capture was inert (no state change)");
    }

    @Test
    void corruptedReferenceIsDetected(@TempDir Path tmp) throws Exception {
        // A reference whose hashes have been tampered with must be flagged by
        // the verifier — guards against silently passing on a broken capture.
        ReplayRecorder reference = simulate();
        List<ReplayFrame> good = reference.getFrames();

        // Build a tampered copy: flip one byte of one tick's hash.
        List<ReplayFrame> tampered = new ArrayList<>(good);
        ReplayFrame mid = good.get(good.size() / 2);
        byte[] badHash = mid.stateHash().clone();
        badHash[0] ^= 0x01;
        tampered.set(good.size() / 2, new ReplayFrame(mid.tickNumber(), mid.input(), badHash));

        ReplayVerifier.VerificationResult result = ReplayVerifier.verify(good, tampered);
        assertTrue(!result.passed(), "Verifier must detect a tampered reference hash");
        assertEquals(1, result.mismatchCount(), "Exactly one tick should mismatch");
    }

    /** Simulates a wire line driven by a constant source and records every tick. */
    private ReplayRecorder simulate() throws Exception {
        WorldPos source = new WorldPos(DIM, 0, 64, 0);
        Map<WorldPos, RedstoneComponentType> components = new LinkedHashMap<>();
        components.put(source, RedstoneComponentType.REDSTONE_BLOCK);
        List<WorldPos> wires = new ArrayList<>();
        for (int x = 1; x <= LINE_LENGTH; x++) {
            WorldPos wire = new WorldPos(DIM, x, 64, 0);
            components.put(wire, RedstoneComponentType.REDSTONE_WIRE);
            wires.add(wire);
        }

        RedstoneWorldState world = new RedstoneWorldState();
        world.putPowerLevel(source, 15);
        for (WorldPos wire : wires) {
            world.putPowerLevel(wire, 0);
        }

        Map<String, RedstoneTaskAction> actions = RedstoneActions.defaults();
        RedstoneTaskRunner runner = new RedstoneTaskRunner(world, actions);
        RedstoneTaskGenerator generator = new RedstoneTaskGenerator(components, actions);
        MicroStepScheduler scheduler = new MicroStepScheduler(generator, runner);

        ReplayRecorder recorder = new ReplayRecorder();
        recorder.start();
        for (long tick = 0; tick < TICKS; tick++) {
            recorder.beginTick(tick);
            List<TaskNode> dirty = new ArrayList<>();
            for (WorldPos wire : wires) {
                dirty.add(RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, wire));
            }
            scheduler.executeTick(dirty);
            recorder.endTick(hashWorld(world));
        }
        recorder.stop();
        return recorder;
    }

    private static byte[] hashWorld(RedstoneWorldState world) {
        Map<String, byte[]> blocks = new TreeMap<>();
        for (WorldPos pos : world.positions()) {
            String key = pos.dimensionId() + ":" + pos.x() + "," + pos.y() + "," + pos.z();
            blocks.put(key, ByteBuffer.allocate(4).putInt(world.getPowerLevel(pos)).array());
        }
        return StateHashComputer.compute(blocks, Map.of(), Map.of(), Map.of());
    }

    private static String describe(ReplayVerifier.VerificationResult result) {
        StringBuilder sb = new StringBuilder();
        for (ReplayVerifier.Mismatch m : result.mismatches()) {
            sb.append("\n  tick=").append(m.tickNumber())
              .append(" field=").append(m.field())
              .append(" expected=").append(m.expected())
              .append(" actual=").append(m.actual());
        }
        return sb.toString();
    }
}
