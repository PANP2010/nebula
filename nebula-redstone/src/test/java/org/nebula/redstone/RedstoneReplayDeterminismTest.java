package org.nebula.redstone;

import org.junit.jupiter.api.Test;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;
import org.nebula.redstone.actions.RedstoneActions;
import org.nebula.replay.ReplayFrame;
import org.nebula.replay.ReplayRecorder;
import org.nebula.replay.ReplayVerifier;
import org.nebula.replay.StateHashComputer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DG1-scaffold determinism test (see docs/DEVELOPMENT_PLAN.md Milestone 5).
 *
 * <p>This is the first end-to-end test that wires the redstone simulation
 * ({@link MicroStepScheduler} + live {@link RedstoneActions}) to the replay
 * verification harness ({@link StateHashComputer}, {@link ReplayRecorder},
 * {@link ReplayVerifier}). It runs a redstone scenario for many ticks, hashes
 * the world state after each tick, then runs the identical scenario a second
 * time and asserts the two per-tick hash sequences match bit-for-bit.
 *
 * <p>It is deliberately a small, fast proxy for the real DG1 gate (10k-tick
 * zero-diff redstone replay) — the tick count is modest so it runs in CI, but
 * the pipeline exercised is the real one. Scaling the tick count up is the
 * remaining work toward DG1.
 *
 * <p>The test also asserts <em>liveness</em>: the recorded hash sequence must
 * actually change over the run (signal propagated). Without this guard a
 * no-op simulation would trivially "replay deterministically".
 */
class RedstoneReplayDeterminismTest {

    private static final int DIM = 0;
    private static final int TICKS = 200;
    private static final int LINE_LENGTH = 12;

    /**
     * A straight line of redstone wire along +X with a constant power source
     * (a redstone block) at one end. Each tick we mark the source dirty and let
     * the scheduler propagate. Two independent runs must produce identical
     * per-tick state-hash sequences.
     */
    @Test
    void wireLinePropagationReplaysDeterministically() throws Exception {
        List<ReplayFrame> first = runScenario();
        List<ReplayFrame> second = runScenario();

        ReplayVerifier.VerificationResult result = ReplayVerifier.verify(first, second);
        assertTrue(result.passed(),
            "Two identical redstone runs diverged: " + describe(result));
        assertEquals(TICKS, first.size(), "Recorder should capture one frame per tick");

        // Liveness: the run must not be a no-op — at least one tick's hash
        // differs from the initial state, proving signal actually moved.
        long distinctHashes = first.stream()
            .map(ReplayFrame::stateHashHex)
            .distinct()
            .count();
        assertTrue(distinctHashes > 1,
            "Expected the world state to change over the run (signal propagation), "
                + "but all " + TICKS + " ticks produced the same hash — simulation was inert");
    }

    private List<ReplayFrame> runScenario() throws Exception {
        RedstoneWorldState world = new RedstoneWorldState();

        // Component layout: a redstone block source at x=0, wire along x=1..L.
        WorldPos source = new WorldPos(DIM, 0, 64, 0);
        Map<WorldPos, RedstoneComponentType> components = new java.util.LinkedHashMap<>();
        components.put(source, RedstoneComponentType.REDSTONE_BLOCK);
        List<WorldPos> wirePositions = new ArrayList<>();
        for (int x = 1; x <= LINE_LENGTH; x++) {
            WorldPos wire = new WorldPos(DIM, x, 64, 0);
            components.put(wire, RedstoneComponentType.REDSTONE_WIRE);
            wirePositions.add(wire);
        }

        // Source emits full power (15); wires start unpowered.
        world.putPowerLevel(source, 15);
        for (WorldPos wire : wirePositions) {
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

            // Each tick, re-evaluate every wire in the line (the initial dirty
            // set). The scheduler's microstep expansion handles propagation
            // within the tick; we re-seed every tick so the line converges and
            // then stays stable, which is exactly the behaviour we want to
            // verify is reproducible.
            List<TaskNode> dirty = new ArrayList<>();
            for (WorldPos wire : wirePositions) {
                dirty.add(RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, wire));
            }
            scheduler.executeTick(dirty);

            recorder.endTick(hashWorld(world));
        }

        recorder.stop();
        return recorder.getFrames();
    }

    /**
     * Serialises the redstone world state into the {@link StateHashComputer}
     * block category. Power level per position is the observable state.
     */
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
