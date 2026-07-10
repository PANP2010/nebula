package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.random.DeterministicRandom;
import org.nebula.core.random.LayeredRandomSource;
import org.nebula.core.random.RandomBudget;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.RandomUsage;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.actions.BlockEntityActions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layered-RNG determinism for the block-entity path — the pure prerequisite the
 * dropper/dispenser CAS action needs before it can run on the DAG (B8 C3).
 *
 * <p>{@link BlockEntityContext} previously had no {@code random()} at all, so the
 * dropper/dispenser RW-set declared a {@code WORLD_RANDOM} draw the runner could
 * never actually supply — the "declared RNG, never consumed" drift the prior
 * cycle (ece4f15) flagged. This threads a per-task {@link DeterministicRandom}
 * through {@link BlockEntityTaskRunner#runMember}, seeded from
 * {@code (tick, blockPos, instance)} exactly as {@code EntityTaskRunner} seeds
 * its context. These tests prove the central property: an RNG-declaring
 * block-entity task's result depends only on its coordinate, never on execution
 * order — the precondition for parallelising RNG-consuming block-entity ticks.
 *
 * <p>The tests use a stand-in dropper-style action (a faithful
 * {@link BlockEntityActions#selectDispenseSlot} call that records its chosen slot
 * into a self field) rather than the real CAS dropper, which is a later slice —
 * the point here is the RNG SEAM, not the item math.
 */
class BlockEntityRandomSeamTest {

    private static final int DIM = 0;
    private static final long WORLD_SEED = 0xBEEFCAFEL;
    private static final int CONTAINER_COUNT = 12;

    /** The task ID a dropper-style stand-in uses, matching the block-entity grammar. */
    private static String dropperId(WorldPos self) {
        return "DROPPER@" + DIM + ":" + self.x() + "," + self.y() + "," + self.z();
    }

    /**
     * An inert task whose RW-set declares WORLD_RANDOM usage so the runner seeds a
     * stream for it. The behaviour lives in the separately-registered
     * {@link BlockEntityAction}, exactly as the live path resolves it — the runner
     * ignores {@code TaskNode.action()} and uses its own resolver.
     */
    private static TaskNode dropperTask(WorldPos self, int slotCount) {
        RWSet.Builder b = RWSet.builder().readBlock(self);
        for (int i = 0; i < slotCount; i++) {
            b.readBlockEntity(new BlockEntityField(self, "inventory.slots[" + i + "]"));
        }
        b.writeBlockEntity(new BlockEntityField(self, "chosen_slot"));
        b.randomUsage(new RandomUsage(RandomInstance.WORLD_RANDOM, slotCount));
        return TaskNode.inert(dropperId(self), "DROPPER", b.build());
    }

    /**
     * The dropper-style RNG-consuming action: reads its slots, draws a slot via the
     * reservoir sample using {@code ctx.random()}, and writes the chosen index into a
     * {@code chosen_slot} field so the committed result is observable.
     */
    private static BlockEntityAction dropperAction(WorldPos self, int slotCount) {
        return ctx -> {
            int[] counts = new int[slotCount];
            for (int i = 0; i < slotCount; i++) {
                counts[i] = ctx.readSlot(self, i);
            }
            int chosen = BlockEntityActions.selectDispenseSlot(counts, ctx.random());
            ctx.write(self, "chosen_slot", chosen);
        };
    }

    private static WorldPos posOf(int i) {
        // Spread positions so their packed keys are distinct.
        return new WorldPos(DIM, i * 3, 64, i * 5);
    }

    private static int[] loadedSlots(int i) {
        // A per-container slot layout that varies so different containers make
        // different choices (some slots empty, some loaded, at least one non-empty).
        int[] slots = new int[BlockEntityActions.DISPENSER_CONTAINER_SIZE];
        for (int s = 0; s < slots.length; s++) {
            slots[s] = ((s + i) % 3 == 0) ? (s + 1) : 0;
        }
        slots[i % slots.length] = 5; // guarantee at least one non-empty slot
        return slots;
    }

    @Test
    void slotSelectionIsOrderIndependent() throws Exception {
        Map<WorldPos, Integer> natural = runSelection(false, 0L, 0L);

        Random shuffleRng = new Random(7);
        for (int trial = 0; trial < 5; trial++) {
            Map<WorldPos, Integer> shuffled = runSelection(true, shuffleRng.nextLong(), 0L);
            assertEquals(natural, shuffled,
                "Chosen slot must not depend on task execution order (trial " + trial + ")");
        }
    }

    @Test
    void slotSelectionReproducesAcrossRuns() throws Exception {
        assertEquals(runSelection(false, 0L, 0L), runSelection(false, 0L, 0L),
            "Two identical runs must produce identical slot choices");
    }

    @Test
    void differentTicksProduceIndependentStreams() throws Exception {
        Map<WorldPos, Integer> tick0 = runSelection(false, 0L, 0L);
        Map<WorldPos, Integer> tick1 = runSelection(false, 0L, 1L);
        assertTrue(!tick0.equals(tick1),
            "Slot selection should vary across ticks (tick is part of the RNG coordinate)");
    }

    @Test
    void rngTaskWithoutSourceThrows() {
        // An RNG-consuming block-entity action run without a random source must fail
        // loudly (undeclared/unsupplied RandomUsage), not silently diverge — the same
        // contract EntityTaskContext.random() enforces.
        BlockEntityState state = new BlockEntityState();
        WorldPos self = posOf(1);
        int[] slots = loadedSlots(1);
        for (int s = 0; s < slots.length; s++) {
            state.put(new BlockEntityField(self, "inventory.slots[" + s + "]"), slots[s]);
        }
        TaskNode task = dropperTask(self, slots.length);
        BlockEntityAction action = dropperAction(self, slots.length);

        // No LayeredRandomSource → ctx.random() throws inside the action.
        BlockEntityTaskRunner runner = new BlockEntityTaskRunner(
            state, id -> id.equals(task.taskId()) ? action : null);
        BlockEntityTickExecutor executor = new BlockEntityTickExecutor(runner);

        assertThrows(Exception.class, () -> executor.executeTick(0L, List.of(task)));
    }

    @Test
    void drawCountMatchesNonEmptySlotCount() throws Exception {
        // The reservoir sample draws exactly one nextInt per non-empty slot; the seam
        // must feed a real DeterministicRandom whose callsMade reflects that, so the
        // shadow's RNG stream position stays aligned with Folia's.
        int[] slots = loadedSlots(2);
        int nonEmpty = 0;
        for (int c : slots) if (c > 0) nonEmpty++;

        DeterministicRandom rng = new LayeredRandomSource(WORLD_SEED)
            .forTask(0L, 123L, RandomInstance.WORLD_RANDOM);
        BlockEntityActions.selectDispenseSlot(slots, rng);
        assertEquals(nonEmpty, rng.callsMade(),
            "one draw per non-empty slot — the budget the RW-set declares");
    }

    @Test
    void parseBlockPosKey_distinguishesPositionsAndParsesId() {
        long a = BlockEntityTaskRunner.parseBlockPosKey("DROPPER@0:3,64,5");
        long b = BlockEntityTaskRunner.parseBlockPosKey("DROPPER@0:6,64,10");
        assertTrue(a != b, "different positions must pack to different keys");
        assertEquals(a, BlockEntityTaskRunner.parseBlockPosKey("DROPPER@0:3,64,5"),
            "same id must pack to the same key");
        assertEquals(0L, BlockEntityTaskRunner.parseBlockPosKey("MALFORMED_no_at"),
            "unparseable id falls back to 0 (deterministic, shared stream)");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<WorldPos, Integer> runSelection(boolean shuffle, long shuffleSeed, long tick) throws Exception {
        BlockEntityState state = new BlockEntityState();
        Map<String, BlockEntityAction> actions = new LinkedHashMap<>();
        List<TaskNode> tasks = new ArrayList<>();
        List<WorldPos> positions = new ArrayList<>();

        for (int i = 0; i < CONTAINER_COUNT; i++) {
            WorldPos self = posOf(i);
            positions.add(self);
            int[] slots = loadedSlots(i);
            for (int s = 0; s < slots.length; s++) {
                state.put(new BlockEntityField(self, "inventory.slots[" + s + "]"), slots[s]);
            }
            TaskNode task = dropperTask(self, slots.length);
            actions.put(task.taskId(), dropperAction(self, slots.length));
            tasks.add(task);
        }

        if (shuffle) {
            Collections.shuffle(tasks, new Random(shuffleSeed));
        }

        LayeredRandomSource rngSource = new LayeredRandomSource(WORLD_SEED);
        BlockEntityTaskRunner runner = new BlockEntityTaskRunner(
            state, actions::get, null, null, rngSource, new RandomBudget());
        BlockEntityTickExecutor executor = new BlockEntityTickExecutor(runner);
        executor.executeTick(tick, tasks);

        Map<WorldPos, Integer> chosen = new TreeMap<>();
        for (WorldPos self : positions) {
            chosen.put(self, state.get(new BlockEntityField(self, "chosen_slot")));
        }
        return chosen;
    }
}
