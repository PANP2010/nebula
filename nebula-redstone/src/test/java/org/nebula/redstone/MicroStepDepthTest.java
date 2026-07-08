package org.nebula.redstone;

import org.junit.jupiter.api.Test;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;
import org.nebula.redstone.actions.RedstoneActions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins down what actually governs a tick's microstep count, correcting a
 * long-standing misconception in the DG1 Criterion 2 notes.
 *
 * <p>The docs claimed the live perf-harness observed {@code max microsteps = 1}
 * "because straight-wire circuits resolve in a single propagation wave" and that
 * "a high-fan-out feedback circuit would drive the count higher". This test
 * shows that framing is wrong: the microstep count is governed by the
 * <em>shape of the initial dirty set</em>, not by the circuit topology.
 *
 * <ul>
 *   <li><b>Narrow frontier</b> (seed only the leading edge, let propagation
 *       cascade): a plain straight wire expands over <em>many</em> microsteps —
 *       one wave per block of signal decay. Straight wire is NOT single-wave.</li>
 *   <li><b>Broad dirty set</b> (seed the whole circuit at once, which is what the
 *       live observe-only pipeline does — Folia touches every wire and the agent
 *       records them all): the line SCC-contracts into one compound task and
 *       resolves in a single layer, so microsteps stays at 0–1 <em>regardless of
 *       topology</em>.</li>
 * </ul>
 *
 * <p>So swapping the perf-harness's straight wire for a torch oscillator would
 * NOT lift the live max above 1 on its own; only a narrow-frontier seeding
 * discipline would. This is a pure {@code nebula-redstone} scheduler property,
 * verifiable deterministically without a live server.
 */
class MicroStepDepthTest {

    private static final int DIM = 0;

    private static final class Rig {
        final RedstoneWorldState world = new RedstoneWorldState();
        final Map<WorldPos, RedstoneComponentType> components;
        final MicroStepScheduler scheduler;

        Rig(Map<WorldPos, RedstoneComponentType> components) {
            this.components = components;
            Map<String, RedstoneTaskAction> actions = RedstoneActions.defaults();
            RedstoneTaskRunner runner = new RedstoneTaskRunner(world, actions);
            RedstoneTaskGenerator gen = new RedstoneTaskGenerator(components, actions);
            this.scheduler = new MicroStepScheduler(gen, runner);
        }
    }

    /** A source block driving a {@code len}-block straight wire line, all wires at 0. */
    private static Rig wireLine(int len) {
        WorldPos src = new WorldPos(DIM, 0, 64, 0);
        Map<WorldPos, RedstoneComponentType> c = new LinkedHashMap<>();
        c.put(src, RedstoneComponentType.REDSTONE_BLOCK);
        for (int x = 1; x <= len; x++) {
            c.put(new WorldPos(DIM, x, 64, 0), RedstoneComponentType.REDSTONE_WIRE);
        }
        Rig r = new Rig(c);
        r.world.putPowerLevel(src, 15);
        for (int x = 1; x <= len; x++) {
            r.world.putPowerLevel(new WorldPos(DIM, x, 64, 0), 0);
        }
        return r;
    }

    @Test
    void narrowFrontierStraightWireExpandsOverManyMicrosteps() throws Exception {
        int len = 32;
        Rig rig = wireLine(len);

        // Seed ONLY the wire adjacent to the source; propagation must cascade.
        TaskNode seed = RedstoneTaskFactory.inert(
            RedstoneComponentType.REDSTONE_WIRE, new WorldPos(DIM, 1, 64, 0));
        MicroStepScheduler.TickResult result = rig.scheduler.executeTick(List.of(seed));

        // A straight wire is emphatically NOT a single-wave circuit under narrow
        // seeding: signal decays one level per block, so each block is a fresh
        // wave. This is the direct counter-example to the "straight-wire =>
        // single wave => max 1" claim.
        assertTrue(result.microSteps() > 1,
            "Narrow-seeded straight wire should expand over many microsteps, got "
                + result.microSteps());
        // Signal (15) decays to 0 after 15 blocks, so ~14 propagation waves reach
        // steady state; the exact figure matches the live "16 tasks, 14 microsteps"
        // capture the docs recorded. Bound it generously to stay robust.
        assertTrue(result.microSteps() >= 10 && result.microSteps() <= 15,
            "Expected ~14 microsteps for a 15-decay wire, got " + result.microSteps());

        // And it must always respect the hard cap (DG1 Criterion 2).
        assertTrue(result.microSteps() <= MicroStepScheduler.MAX_MICRO_STEPS,
            "Microstep count must stay within the cap");
    }

    @Test
    void broadDirtySetCollapsesToOneLayerRegardlessOfLength() throws Exception {
        // Seeding the whole circuit at once (what the live observe-only pipeline
        // does) contracts the wire line into a single compound task, so there is
        // no microstep expansion — this is why the live perf-harness saw max 1,
        // NOT because the topology was "straight wire".
        for (int len : new int[]{8, 16, 32}) {
            Rig rig = wireLine(len);
            List<TaskNode> all = new ArrayList<>();
            for (var e : rig.components.entrySet()) {
                if (RedstoneActions.defaults().containsKey(e.getValue().taskType())) {
                    all.add(RedstoneTaskFactory.inert(e.getValue(), e.getKey()));
                }
            }
            MicroStepScheduler.TickResult result = rig.scheduler.executeTick(all);
            assertTrue(result.microSteps() <= 1,
                "Broad dirty set (len=" + len + ") should collapse to <=1 microstep, got "
                    + result.microSteps());
        }
    }
}
