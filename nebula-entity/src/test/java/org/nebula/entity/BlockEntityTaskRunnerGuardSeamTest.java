package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.actions.BlockEntityActions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two RW-guard callback seams on {@link BlockEntityTaskRunner} — the
 * block-entity analogue of {@code RedstoneRwGuardBridgeTest} (B8 C3).
 *
 * <p>The seams are what let the {@code nebula-plugin} guard bridge (next slice)
 * check a live block-entity task's ACTUAL field accesses against its declared
 * {@code RWSet}:
 * <ul>
 *   <li>{@link BlockEntityAccessTracer} — every {@code ctx.read/write} the action
 *       performs is reported by {@link BlockEntityField}, so an undeclared slot or
 *       timer access is observable.</li>
 *   <li>{@link BlockEntityTaskGuardHook} — brackets each dispatched task exactly
 *       once (before + after), the window a guard needs to reset and snapshot the
 *       per-thread trace.</li>
 * </ul>
 * These tests drive a REAL furnace tick through the runner (not a stub action) so
 * the traced field set is the action's genuine footprint, and prove that with no
 * tracer/hook installed the runner behaves exactly as before.
 */
class BlockEntityTaskRunnerGuardSeamTest {

    private static final WorldPos SELF = new WorldPos(0, 3, 64, 7);

    private static void putSlot(BlockEntityState s, WorldPos p, int i, int n) {
        s.put(new BlockEntityField(p, "inventory.slots[" + i + "]"), n);
    }

    /** Records every field the context reports, in access order. */
    private static final class RecordingTracer implements BlockEntityAccessTracer {
        final Set<BlockEntityField> reads = new LinkedHashSet<>();
        final Set<BlockEntityField> writes = new LinkedHashSet<>();

        @Override public void onFieldRead(BlockEntityField field) { reads.add(field); }
        @Override public void onFieldWrite(BlockEntityField field) { writes.add(field); }
    }

    /** Records the before/after bracket calls per task. */
    private static final class RecordingGuard implements BlockEntityTaskGuardHook {
        final List<String> events = new ArrayList<>();
        @Override public void beforeTask(TaskNode task) { events.add("before:" + task.taskId()); }
        @Override public void afterTask(TaskNode task) { events.add("after:" + task.taskId()); }
    }

    private static TaskNode furnaceTaskWithFuel(BlockEntityState state, Map<String, BlockEntitySnapshot> registry) {
        putSlot(state, SELF, 0, 1); // input
        putSlot(state, SELF, 1, 1); // fuel
        state.put(new BlockEntityField(SELF, "cook_progress"), BlockEntityActions.COOK_TOTAL - 1);
        state.put(new BlockEntityField(SELF, "fuel_time"), 10);
        BlockEntitySnapshot snap = BlockEntitySnapshot.furnace(SELF);
        TaskNode task = BlockEntityTaskFactory.furnaceInert(snap);
        registry.put(task.taskId(), snap);
        return task;
    }

    @Test
    void tracerObservesTheRealFurnaceFieldFootprint() throws Exception {
        BlockEntityState state = new BlockEntityState();
        Map<String, BlockEntitySnapshot> registry = new HashMap<>();
        TaskNode task = furnaceTaskWithFuel(state, registry);

        RecordingTracer tracer = new RecordingTracer();
        BlockEntityTaskRunner runner = new BlockEntityTaskRunner(
            state,
            id -> BlockEntityActionResolver.resolve(registry.get(id)),
            tracer, null);
        new BlockEntityTickExecutor(runner).executeTick(List.of(task));

        // The furnace action reads slots 0/1/2 + both timers; on a completed smelt
        // it writes slots 0/2, resets cook_progress and decrements fuel_time.
        assertTrue(tracer.reads.contains(new BlockEntityField(SELF, "inventory.slots[0]")), "reads input");
        assertTrue(tracer.reads.contains(new BlockEntityField(SELF, "inventory.slots[2]")), "reads output slot");
        assertTrue(tracer.reads.contains(new BlockEntityField(SELF, "cook_progress")), "reads cook_progress");
        assertTrue(tracer.reads.contains(new BlockEntityField(SELF, "fuel_time")), "reads fuel_time");
        assertTrue(tracer.writes.contains(new BlockEntityField(SELF, "inventory.slots[0]")), "writes input (consumed)");
        assertTrue(tracer.writes.contains(new BlockEntityField(SELF, "inventory.slots[2]")), "writes output (produced)");
        assertTrue(tracer.writes.contains(new BlockEntityField(SELF, "cook_progress")), "writes cook_progress");
    }

    @Test
    void guardHookBracketsEachTaskExactlyOnce() throws Exception {
        BlockEntityState state = new BlockEntityState();
        Map<String, BlockEntitySnapshot> registry = new HashMap<>();
        TaskNode task = furnaceTaskWithFuel(state, registry);

        RecordingGuard guard = new RecordingGuard();
        BlockEntityTaskRunner runner = new BlockEntityTaskRunner(
            state,
            id -> BlockEntityActionResolver.resolve(registry.get(id)),
            null, guard);
        new BlockEntityTickExecutor(runner).executeTick(List.of(task));

        assertEquals(List.of("before:" + task.taskId(), "after:" + task.taskId()), guard.events,
            "exactly one before/after bracket, in order, around the single task");
    }

    @Test
    void afterTaskFiresEvenWhenTheActionThrows() {
        BlockEntityState state = new BlockEntityState();
        BlockEntitySnapshot snap = BlockEntitySnapshot.furnace(SELF);
        TaskNode task = BlockEntityTaskFactory.furnaceInert(snap);

        RecordingGuard guard = new RecordingGuard();
        BlockEntityTaskRunner runner = new BlockEntityTaskRunner(
            state,
            id -> ctx -> { throw new IllegalStateException("boom"); },
            null, guard);

        try {
            runner.run(task);
        } catch (Exception expected) {
            // propagated after the bracket closed
        }

        assertEquals(List.of("before:" + task.taskId(), "after:" + task.taskId()), guard.events,
            "afterTask must fire in the finally block even when the action throws");
    }

    @Test
    void noSeamsInstalledBehavesExactlyAsBefore() throws Exception {
        BlockEntityState state = new BlockEntityState();
        Map<String, BlockEntitySnapshot> registry = new HashMap<>();
        TaskNode task = furnaceTaskWithFuel(state, registry);

        // No tracer, no guard — the two-arg constructor path.
        BlockEntityTaskRunner runner = new BlockEntityTaskRunner(
            state, id -> BlockEntityActionResolver.resolve(registry.get(id)));
        new BlockEntityTickExecutor(runner).executeTick(List.of(task));

        assertEquals(0, state.get(new BlockEntityField(SELF, "inventory.slots[0]")), "input consumed — real action still ran");
        assertEquals(1, state.get(new BlockEntityField(SELF, "inventory.slots[2]")), "output produced");
        assertFalse(state.get(new BlockEntityField(SELF, "cook_progress")) != 0, "progress reset");
    }
}
