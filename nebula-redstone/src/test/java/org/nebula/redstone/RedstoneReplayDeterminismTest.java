package org.nebula.redstone;

import org.junit.jupiter.api.Tag;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DG1-scaffold determinism tests (see docs/DEVELOPMENT_PLAN.md Milestone 5).
 *
 * <p>These are the first end-to-end tests wiring the redstone simulation
 * ({@link MicroStepScheduler} + live {@link RedstoneActions}) to the replay
 * verification harness ({@link StateHashComputer}, {@link ReplayRecorder},
 * {@link ReplayVerifier}). Each scenario runs the real tick pipeline, hashes
 * world state per tick, runs the scenario twice, and asserts the hash
 * sequences match bit-for-bit. They are small/fast proxies for the real DG1
 * gate (10k-tick zero-diff replay); scaling tick counts up is the remaining
 * work.
 */
class RedstoneReplayDeterminismTest {

    private static final int DIM = 0;

    // ── Scenario 1: wire line, re-seeded every tick ──────────────────────────

    /**
     * A straight redstone-wire line driven by a constant power source. Every
     * tick the whole line is re-evaluated; two independent runs must produce
     * identical per-tick state-hash sequences, and the state must actually
     * change over the run (liveness).
     */
    @Test
    void wireLinePropagationReplaysDeterministically() throws Exception {
        int ticks = 200;
        int lineLength = 12;

        List<ReplayFrame> first = recordWireLine(ticks, lineLength);
        List<ReplayFrame> second = recordWireLine(ticks, lineLength);

        ReplayVerifier.VerificationResult result = ReplayVerifier.verify(first, second);
        assertTrue(result.passed(), "Two identical wire-line runs diverged: " + describe(result));
        assertEquals(ticks, first.size(), "Recorder should capture one frame per tick");

        long distinctHashes = first.stream().map(ReplayFrame::stateHashHex).distinct().count();
        assertTrue(distinctHashes > 1,
            "Expected world state to change over the run (signal propagation), but all "
                + ticks + " ticks produced the same hash — simulation was inert");
    }

    private List<ReplayFrame> recordWireLine(int ticks, int lineLength) throws Exception {
        WorldPos source = new WorldPos(DIM, 0, 64, 0);
        Map<WorldPos, RedstoneComponentType> components = new LinkedHashMap<>();
        components.put(source, RedstoneComponentType.REDSTONE_BLOCK);
        List<WorldPos> wires = new ArrayList<>();
        for (int x = 1; x <= lineLength; x++) {
            WorldPos wire = new WorldPos(DIM, x, 64, 0);
            components.put(wire, RedstoneComponentType.REDSTONE_WIRE);
            wires.add(wire);
        }

        Scenario s = new Scenario(components);
        s.world.putPowerLevel(source, 15);
        for (WorldPos wire : wires) {
            s.world.putPowerLevel(wire, 0);
        }

        // Scenario.run re-seeds every actionable component (the wires) each
        // tick; microstep expansion handles intra-tick settling, so the line
        // converges and then stays stable.
        return s.run(ticks, tick -> {});
    }

    /**
     * DG1-scale replay: the full 10k-tick zero-diff redstone target. Tagged
     * "slow" and skipped by default; run with {@code ./gradlew test -Pslow}.
     * This is the actual DG1 acceptance criterion in miniature — once the
     * scenario is rich enough (more component types, player-driven inputs) it
     * becomes the real gate.
     */
    @Test
    @Tag("slow")
    void dg1ScaleWireLineReplaysDeterministically() throws Exception {
        int ticks = 10_000;
        int lineLength = 16;

        List<ReplayFrame> first = recordWireLine(ticks, lineLength);
        List<ReplayFrame> second = recordWireLine(ticks, lineLength);

        ReplayVerifier.VerificationResult result = ReplayVerifier.verify(first, second);
        assertTrue(result.passed(),
            "DG1-scale run diverged at " + ticks + " ticks: " + describe(result));
        assertEquals(ticks, first.size());

        long distinctHashes = first.stream().map(ReplayFrame::stateHashHex).distinct().count();
        assertTrue(distinctHashes > 1, "DG1-scale run was inert");
    }

    // ── Scenario 2: single-source microstep propagation in one tick ──────────

    /**
     * Dirty only the wire adjacent to the source and run a single tick. The
     * microstep machinery (arch doc §5.3) must propagate the signal down the
     * entire line within that one tick, with correct per-block decay. This
     * proves the change-aware microstep expansion works end-to-end with live
     * actions — and that a single-seeded frontier does NOT contract into an
     * inert compound (a wire <em>line</em> submitted together does).
     */
    @Test
    void singleSourceMicrostepPropagatesWholeLineInOneTick() throws Exception {
        int lineLength = 10;
        WorldPos source = new WorldPos(DIM, 0, 64, 0);
        Map<WorldPos, RedstoneComponentType> components = new LinkedHashMap<>();
        components.put(source, RedstoneComponentType.REDSTONE_BLOCK);
        List<WorldPos> wires = new ArrayList<>();
        for (int x = 1; x <= lineLength; x++) {
            WorldPos wire = new WorldPos(DIM, x, 64, 0);
            components.put(wire, RedstoneComponentType.REDSTONE_WIRE);
            wires.add(wire);
        }

        Scenario s = new Scenario(components);
        s.world.putPowerLevel(source, 15);
        for (WorldPos wire : wires) {
            s.world.putPowerLevel(wire, 0);
        }

        // Seed ONLY the first wire; rely on microstep propagation for the rest.
        TaskNode seed = RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, wires.get(0));
        MicroStepScheduler.TickResult result = s.scheduler.executeTick(List.of(seed));

        assertTrue(result.microSteps() > 0,
            "Single-source seeding should trigger microstep expansion down the line");

        // Wire k (1-indexed) sits k blocks from the source, so power decays to 15-k.
        for (int k = 1; k <= lineLength; k++) {
            int expected = 15 - k;
            int actual = s.world.getPowerLevel(wires.get(k - 1));
            assertEquals(expected, actual,
                "Wire at distance " + k + " should have power " + expected
                    + " after intra-tick propagation");
        }
    }

    // ── Scenario 3: torch + wire feedback loop (internal state + burnout) ────

    /**
     * A redstone torch whose attached block is a wire that reads the torch
     * back — a NOT-gate feedback loop. The torch toggles, accumulates toggle
     * count in its internal state, and eventually burns out (vanilla anti-spam
     * behaviour). Verifies that this internal-state-heavy, oscillating circuit
     * replays deterministically and settles into the burned-out steady state.
     */
    @Test
    void torchFeedbackBurnoutReplaysDeterministically() throws Exception {
        int ticks = 80;

        Run a = runTorchFeedback(ticks);
        Run b = runTorchFeedback(ticks);

        ReplayVerifier.VerificationResult result = ReplayVerifier.verify(a.frames, b.frames);
        assertTrue(result.passed(), "Two identical torch-feedback runs diverged: " + describe(result));

        long distinctHashes = a.frames.stream().map(ReplayFrame::stateHashHex).distinct().count();
        assertTrue(distinctHashes > 1,
            "Expected the torch to oscillate (state changes) before settling");

        assertEquals(Boolean.TRUE, a.burnedOut,
            "Torch should burn out after repeated toggling");
        assertEquals(0, a.finalTorchPower, "A burned-out torch holds power 0");
        assertEquals(a.burnedOut, b.burnedOut, "Burnout outcome must be reproducible");
        assertEquals(a.finalTorchPower, b.finalTorchPower, "Final torch power must be reproducible");
    }

    private record Run(List<ReplayFrame> frames, Object burnedOut, int finalTorchPower) {}

    private Run runTorchFeedback(int ticks) throws Exception {
        WorldPos torch = new WorldPos(DIM, 1, 64, 0);
        WorldPos wire = new WorldPos(DIM, 1, 63, 0); // attached block (below the torch)
        Map<WorldPos, RedstoneComponentType> components = new LinkedHashMap<>();
        components.put(torch, RedstoneComponentType.REDSTONE_TORCH);
        components.put(wire, RedstoneComponentType.REDSTONE_WIRE);

        Scenario s = new Scenario(components);
        s.world.putPowerLevel(torch, 0);
        s.world.putPowerLevel(wire, 0);

        List<ReplayFrame> frames = s.run(ticks, tick -> {});

        Object burnedOut = s.world.getInternalState(torch, "burned_out");
        int finalTorchPower = s.world.getPowerLevel(torch);
        return new Run(frames, burnedOut, finalTorchPower);
    }

    // ── Scenario 4: repeater delay line (DEFERRED, cross-tick propagation) ───

    /**
     * A repeater fed by a toggling input. A repeater is DEFERRED: its output
     * never changes in the same tick as the input change — the delay counter
     * carries the pending change across ticks. This exercises the cross-tick
     * deferred path (internal delay-counter state surviving between ticks) and
     * asserts the whole run replays deterministically, including the lag.
     */
    @Test
    void repeaterDelayLineReplaysDeterministically() throws Exception {
        int ticks = 60;

        List<ReplayFrame> first = recordRepeater(ticks);
        List<ReplayFrame> second = recordRepeater(ticks);

        ReplayVerifier.VerificationResult result = ReplayVerifier.verify(first, second);
        assertTrue(result.passed(), "Two identical repeater runs diverged: " + describe(result));

        long distinctHashes = first.stream().map(ReplayFrame::stateHashHex).distinct().count();
        assertTrue(distinctHashes > 1,
            "Expected the repeater output to change over the run (deferred propagation)");
    }

    private List<ReplayFrame> recordRepeater(int ticks) throws Exception {
        WorldPos input = new WorldPos(DIM, 0, 64, 0);      // input side (-Z of repeater)
        WorldPos repeater = new WorldPos(DIM, 0, 64, 1);   // repeater body
        WorldPos output = new WorldPos(DIM, 0, 64, 2);     // output side (+Z of repeater)

        Map<WorldPos, RedstoneComponentType> components = new LinkedHashMap<>();
        components.put(repeater, RedstoneComponentType.REPEATER);

        Scenario s = new Scenario(components);
        s.world.putPowerLevel(input, 0);
        s.world.putPowerLevel(repeater, 0);
        s.world.putPowerLevel(output, 0);
        // Delay setting of 2 ticks so the deferred counter is non-trivial.
        s.world.put(repeater, 0, Map.of("delay_setting", 2, "delay_counter", -1));

        // Drive the input high for the first half, low for the second half, so
        // the repeater must latch both a rising and a falling edge through its
        // delay counter across ticks. We re-seed the repeater each tick.
        return s.run(ticks, tick -> {
            int driven = (tick < ticks / 2) ? 15 : 0;
            if (s.world.getPowerLevel(input) != driven) {
                s.world.putPowerLevel(input, driven);
            }
        });
    }

    // ── Shared scenario harness ──────────────────────────────────────────────

    /**
     * Holds a world + scheduler wired with live actions, and re-seeds every
     * known component each tick (the common "evaluate everything" driver used
     * by the re-seeding scenarios).
     */
    private static final class Scenario {
        final RedstoneWorldState world = new RedstoneWorldState();
        final Map<WorldPos, RedstoneComponentType> components;
        final MicroStepScheduler scheduler;

        Scenario(Map<WorldPos, RedstoneComponentType> components) {
            this.components = components;
            Map<String, RedstoneTaskAction> actions = RedstoneActions.defaults();
            RedstoneTaskRunner runner = new RedstoneTaskRunner(world, actions);
            RedstoneTaskGenerator generator = new RedstoneTaskGenerator(components, actions);
            this.scheduler = new MicroStepScheduler(generator, runner);
        }

        /** Runs {@code ticks} ticks, re-seeding every actionable component each tick. */
        List<ReplayFrame> run(int ticks, Consumer<Long> perTickHook) throws Exception {
            ReplayRecorder recorder = new ReplayRecorder();
            recorder.start();
            for (long tick = 0; tick < ticks; tick++) {
                recorder.beginTick(tick);
                perTickHook.accept(tick);

                List<TaskNode> dirty = new ArrayList<>();
                for (var entry : components.entrySet()) {
                    RedstoneComponentType type = entry.getValue();
                    // Only re-seed components that have a live action; pure
                    // sources (REDSTONE_BLOCK) hold constant power.
                    if (RedstoneActions.defaults().containsKey(type.taskType())) {
                        dirty.add(RedstoneTaskFactory.inert(type, entry.getKey()));
                    }
                }
                scheduler.executeTick(dirty);

                recorder.endTick(hashWorld(world));
            }
            recorder.stop();
            return recorder.getFrames();
        }
    }

    /** Hashes power levels of all tracked positions via the real replay hasher. */
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
