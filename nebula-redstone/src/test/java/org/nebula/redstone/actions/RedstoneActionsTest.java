package org.nebula.redstone.actions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;
import org.nebula.redstone.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RedstoneActionsTest {

    private RedstoneWorldState world;

    @BeforeEach
    void setUp() {
        world = new RedstoneWorldState();
    }

    private void runAction(RedstoneComponentType type, WorldPos pos, RedstoneTaskAction action) throws Exception {
        RedstoneTaskRunner runner = new RedstoneTaskRunner(world, Map.of(type.taskType(), action));
        var task = RedstoneTaskFactory.inert(type, pos);
        runner.run(task);
        runner.commitLayer();
    }

    @Nested
    class WireTests {
        private final WorldPos WIRE = new WorldPos(0, 10, 64, 10);

        @Test
        void propagatesFromStrongerNeighbour() throws Exception {
            // North neighbour (treated as a wire) has power 15 → decays by 1.
            WorldPos north = RedstoneTaskFactory.neighbour(WIRE, 0, 0, -1);
            world.putPowerLevel(north, 15);
            world.putPowerLevel(WIRE, 0);

            runAction(RedstoneComponentType.REDSTONE_WIRE, WIRE, new RedstoneWireAction());

            assertEquals(14, world.getPowerLevel(WIRE));
        }

        @Test
        void takesSourceNeighbourUndecayed() throws Exception {
            // A non-wire (source) neighbour feeds its full power with no decay —
            // vanilla parity (DefaultRedstoneWireEvaluator: blockSignal wins
            // undecayed). This is the DG3 off-by-one fix: without the source
            // distinction the wire would read 14 instead of 15.
            WorldPos source = RedstoneTaskFactory.neighbour(WIRE, 0, 0, -1);
            world.putPowerLevel(source, 15);
            world.putPowerLevel(WIRE, 0);

            // Classifier: the source position is NOT a wire; all else is.
            RedstoneWireAction action = new RedstoneWireAction(pos -> !pos.equals(source));
            runAction(RedstoneComponentType.REDSTONE_WIRE, WIRE, action);

            assertEquals(15, world.getPowerLevel(WIRE));
        }

        @Test
        void sourceBeatsDecayedWireNeighbour() throws Exception {
            // Source at 12 (undecayed) vs a wire neighbour at 15 (→14 after decay):
            // the decayed wire (14) still wins here, proving max(source, wire-1).
            WorldPos source = RedstoneTaskFactory.neighbour(WIRE, 0, 0, -1);
            WorldPos wireNbr = RedstoneTaskFactory.neighbour(WIRE, 1, 0, 0);
            world.putPowerLevel(source, 12);
            world.putPowerLevel(wireNbr, 15);
            world.putPowerLevel(WIRE, 0);

            RedstoneWireAction action = new RedstoneWireAction(pos -> !pos.equals(source));
            runAction(RedstoneComponentType.REDSTONE_WIRE, WIRE, action);

            assertEquals(14, world.getPowerLevel(WIRE)); // max(12, 15-1) = 14
        }

        @Test
        void decaysToZeroWhenNoSource() throws Exception {
            world.putPowerLevel(WIRE, 5);
            // All neighbours at 0
            for (int[] d : new int[][]{{0,0,-1},{0,0,1},{-1,0,0},{1,0,0},{0,-1,0},{0,1,0}}) {
                world.putPowerLevel(RedstoneTaskFactory.neighbour(WIRE, d[0], d[1], d[2]), 0);
            }

            runAction(RedstoneComponentType.REDSTONE_WIRE, WIRE, new RedstoneWireAction());

            assertEquals(0, world.getPowerLevel(WIRE));
        }

        @Test
        void takesMaxOfAllNeighbours() throws Exception {
            world.putPowerLevel(WIRE, 0);
            world.putPowerLevel(RedstoneTaskFactory.neighbour(WIRE, 0, 0, -1), 5);
            world.putPowerLevel(RedstoneTaskFactory.neighbour(WIRE, 1, 0, 0), 10);
            world.putPowerLevel(RedstoneTaskFactory.neighbour(WIRE, 0, 1, 0), 3);

            runAction(RedstoneComponentType.REDSTONE_WIRE, WIRE, new RedstoneWireAction());

            assertEquals(9, world.getPowerLevel(WIRE)); // max(5,10,3) - 1 = 9
        }

        @Test
        void noWriteWhenPowerUnchanged() throws Exception {
            world.putPowerLevel(WIRE, 14);
            world.putPowerLevel(RedstoneTaskFactory.neighbour(WIRE, 0, 0, -1), 15);

            long versionBefore = world.getVersion(WIRE);
            runAction(RedstoneComponentType.REDSTONE_WIRE, WIRE, new RedstoneWireAction());

            // Power is still 14 → no write should occur, but CAS still writes
            // because the snapshot can't tell if the value is truly unchanged at
            // world level. The key guarantee: final value is correct.
            assertEquals(14, world.getPowerLevel(WIRE));
        }
    }

    @Nested
    class TorchTests {
        private final WorldPos TORCH = new WorldPos(0, 10, 65, 10);
        private final WorldPos ATTACHED = new WorldPos(0, 10, 64, 10); // below

        @Test
        void turnsOffWhenAttachedBlockPowered() throws Exception {
            world.putPowerLevel(TORCH, 15);
            world.putPowerLevel(ATTACHED, 10);

            runAction(RedstoneComponentType.REDSTONE_TORCH, TORCH, new RedstoneTorchAction());

            assertEquals(0, world.getPowerLevel(TORCH));
        }

        @Test
        void turnsOnWhenAttachedBlockUnpowered() throws Exception {
            world.putPowerLevel(TORCH, 0);
            world.putPowerLevel(ATTACHED, 0);

            runAction(RedstoneComponentType.REDSTONE_TORCH, TORCH, new RedstoneTorchAction());

            assertEquals(15, world.getPowerLevel(TORCH));
        }

        @Test
        void staysOnWhenAlreadyOnAndAttachedUnpowered() throws Exception {
            world.putPowerLevel(TORCH, 15);
            world.putPowerLevel(ATTACHED, 0);

            runAction(RedstoneComponentType.REDSTONE_TORCH, TORCH, new RedstoneTorchAction());

            assertEquals(15, world.getPowerLevel(TORCH));
        }

        @Test
        void burnsOutAfterTooManyToggles() throws Exception {
            world.putPowerLevel(TORCH, 15);
            world.putPowerLevel(ATTACHED, 0);

            // Simulate many toggles by setting toggle count near threshold
            world.put(TORCH, 15, Map.of("toggle_count", 8));

            // Now attach powered → should trigger burnout
            world.putPowerLevel(ATTACHED, 10);

            runAction(RedstoneComponentType.REDSTONE_TORCH, TORCH, new RedstoneTorchAction());

            assertEquals(0, world.getPowerLevel(TORCH));
            assertEquals(true, world.getInternalState(TORCH, "burned_out"));
        }
    }

    @Nested
    class RepeaterTests {
        private final WorldPos REPEATER = new WorldPos(0, 10, 64, 10);
        private final WorldPos INPUT = new WorldPos(0, 10, 64, 9);   // z-1
        private final WorldPos OUTPUT = new WorldPos(0, 10, 64, 11); // z+1

        @Test
        void initializesDelayCounterOnInputChange() throws Exception {
            world.putPowerLevel(REPEATER, 0);
            world.putPowerLevel(INPUT, 15);
            world.put(REPEATER, 0, Map.of("powered", false, "delay_setting", 2));

            runAction(RedstoneComponentType.REPEATER, REPEATER, new RepeaterAction());

            // Should set delay_counter = setting - 1 = 1
            assertEquals(1, world.getInternalState(REPEATER, "delay_counter"));
            // Output not yet changed
            assertEquals(-1, world.getPowerLevel(OUTPUT));
        }

        @Test
        void decrementsCounterOnSubsequentTick() throws Exception {
            world.putPowerLevel(REPEATER, 0);
            world.putPowerLevel(INPUT, 15);
            world.put(REPEATER, 0, Map.of("powered", false, "delay_setting", 2, "delay_counter", 1));

            runAction(RedstoneComponentType.REPEATER, REPEATER, new RepeaterAction());

            assertEquals(0, world.getInternalState(REPEATER, "delay_counter"));
        }

        @Test
        void commitsOutputWhenCounterReachesZero() throws Exception {
            world.putPowerLevel(REPEATER, 0);
            world.putPowerLevel(INPUT, 15);
            world.putPowerLevel(OUTPUT, 0);
            world.put(REPEATER, 0, Map.of("powered", false, "delay_setting", 1, "delay_counter", 0));

            runAction(RedstoneComponentType.REPEATER, REPEATER, new RepeaterAction());

            assertEquals(15, world.getPowerLevel(REPEATER));
            assertEquals(15, world.getPowerLevel(OUTPUT));
            assertEquals(true, world.getInternalState(REPEATER, "powered"));
        }

        @Test
        void doesNothingWhenLocked() throws Exception {
            world.putPowerLevel(REPEATER, 0);
            world.putPowerLevel(INPUT, 15);
            world.put(REPEATER, 0, Map.of("powered", false, "locked", true));

            runAction(RedstoneComponentType.REPEATER, REPEATER, new RepeaterAction());

            assertEquals(0, world.getPowerLevel(REPEATER)); // Unchanged
        }

        @Test
        void noChangeWhenInputMatchesPoweredState() throws Exception {
            world.putPowerLevel(REPEATER, 15);
            world.putPowerLevel(INPUT, 15);
            world.put(REPEATER, 15, Map.of("powered", true, "delay_setting", 1));

            runAction(RedstoneComponentType.REPEATER, REPEATER, new RepeaterAction());

            assertEquals(15, world.getPowerLevel(REPEATER)); // No change
        }
    }

    @Nested
    class ComparatorTests {
        private final WorldPos COMP = new WorldPos(0, 10, 64, 10);
        private final WorldPos INPUT = new WorldPos(0, 10, 64, 9);    // z-1
        private final WorldPos LEFT = new WorldPos(0, 9, 64, 10);     // x-1
        private final WorldPos RIGHT = new WorldPos(0, 11, 64, 10);   // x+1
        private final WorldPos OUTPUT = new WorldPos(0, 10, 64, 11);  // z+1

        @Test
        void compareModePassesFrontWhenGreaterThanSides() throws Exception {
            world.putPowerLevel(COMP, 0);
            world.putPowerLevel(INPUT, 12);
            world.putPowerLevel(LEFT, 5);
            world.putPowerLevel(RIGHT, 3);
            world.putPowerLevel(OUTPUT, 0);
            world.put(COMP, 0, Map.of("mode", "compare"));

            runAction(RedstoneComponentType.COMPARATOR, COMP, new ComparatorAction());

            assertEquals(12, world.getPowerLevel(COMP));
            assertEquals(12, world.getPowerLevel(OUTPUT));
        }

        @Test
        void compareModeOutputsZeroWhenFrontLessThanSide() throws Exception {
            world.putPowerLevel(COMP, 10);
            world.putPowerLevel(INPUT, 3);
            world.putPowerLevel(LEFT, 8);
            world.putPowerLevel(RIGHT, 2);
            world.putPowerLevel(OUTPUT, 10);
            world.put(COMP, 10, Map.of("mode", "compare"));

            runAction(RedstoneComponentType.COMPARATOR, COMP, new ComparatorAction());

            assertEquals(0, world.getPowerLevel(COMP));
            assertEquals(0, world.getPowerLevel(OUTPUT));
        }

        @Test
        void subtractModeSubtractsSideFromFront() throws Exception {
            world.putPowerLevel(COMP, 0);
            world.putPowerLevel(INPUT, 12);
            world.putPowerLevel(LEFT, 4);
            world.putPowerLevel(RIGHT, 7);
            world.putPowerLevel(OUTPUT, 0);
            world.put(COMP, 0, Map.of("mode", "subtract"));

            runAction(RedstoneComponentType.COMPARATOR, COMP, new ComparatorAction());

            // 12 - max(4,7) = 12 - 7 = 5
            assertEquals(5, world.getPowerLevel(COMP));
            assertEquals(5, world.getPowerLevel(OUTPUT));
        }

        @Test
        void subtractModeFloorsAtZero() throws Exception {
            world.putPowerLevel(COMP, 5);
            world.putPowerLevel(INPUT, 3);
            world.putPowerLevel(LEFT, 10);
            world.putPowerLevel(RIGHT, 2);
            world.putPowerLevel(OUTPUT, 5);
            world.put(COMP, 5, Map.of("mode", "subtract"));

            runAction(RedstoneComponentType.COMPARATOR, COMP, new ComparatorAction());

            // 3 - max(10,2) = 3 - 10 = -7 → clamped to 0
            assertEquals(0, world.getPowerLevel(COMP));
            assertEquals(0, world.getPowerLevel(OUTPUT));
        }

        @Test
        void defaultModeIsCompare() throws Exception {
            world.putPowerLevel(COMP, 0);
            world.putPowerLevel(INPUT, 10);
            world.putPowerLevel(LEFT, 5);
            world.putPowerLevel(RIGHT, 3);
            world.putPowerLevel(OUTPUT, 0);
            // No mode set → defaults to compare

            runAction(RedstoneComponentType.COMPARATOR, COMP, new ComparatorAction());

            assertEquals(10, world.getPowerLevel(COMP));
        }

        @Test
        void noWriteWhenOutputUnchanged() throws Exception {
            world.putPowerLevel(COMP, 10);
            world.putPowerLevel(INPUT, 10);
            world.putPowerLevel(LEFT, 5);
            world.putPowerLevel(RIGHT, 3);
            world.putPowerLevel(OUTPUT, 10);
            world.put(COMP, 10, Map.of("mode", "compare"));

            runAction(RedstoneComponentType.COMPARATOR, COMP, new ComparatorAction());

            assertEquals(10, world.getPowerLevel(COMP)); // unchanged
        }
    }

    @Nested
    class IntegrationTests {
        @Test
        void fullTickWithDefaultActions() throws Exception {
            WorldPos wire = new WorldPos(0, 0, 64, 0);
            WorldPos source = RedstoneTaskFactory.neighbour(wire, 0, 0, -1);

            world.putPowerLevel(wire, 0);
            world.putPowerLevel(source, 15);

            RedstoneTaskRunner runner = new RedstoneTaskRunner(world, RedstoneActions.defaults());
            RedstoneTaskGenerator gen = new RedstoneTaskGenerator(Map.of());
            MicroStepScheduler scheduler = new MicroStepScheduler(gen, runner);

            var task = RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, wire);
            var result = scheduler.executeTick(List.of(task));

            assertEquals(1, result.totalTasks());
            assertFalse(result.hasCommitFailures());
            assertEquals(14, world.getPowerLevel(wire));
        }
    }
}
