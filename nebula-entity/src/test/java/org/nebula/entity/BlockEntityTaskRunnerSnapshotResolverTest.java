package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.actions.BlockEntityActions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link BlockEntityTaskRunner#withSnapshotResolver} — the canonical
 * {@code taskId → snapshot → action} composition the live plugin uses to swap the
 * inert block-entity resolver for real hopper/furnace item math (B8 C3).
 *
 * <p>The composition is the whole point: a block-entity {@code taskId}
 * ({@code TYPE@dim:x,y,z}) drops the hopper's facing/slot count, so the runner must
 * resolve the action from the {@link BlockEntitySnapshot} the hook accumulated, not
 * from the ID. These tests run a real furnace tick through the runner to prove the
 * looked-up snapshot actually reaches {@link BlockEntityActionResolver} and mutates
 * CAS — and that a task with no registered snapshot is a clean no-op, not an NPE.
 */
class BlockEntityTaskRunnerSnapshotResolverTest {

    private static final WorldPos SELF = new WorldPos(0, 3, 64, 7);

    private static void putSlot(BlockEntityState s, WorldPos p, int i, int n) {
        s.put(new BlockEntityField(p, "inventory.slots[" + i + "]"), n);
    }

    private static int slot(BlockEntityState s, WorldPos p, int i) {
        return s.get(new BlockEntityField(p, "inventory.slots[" + i + "]"));
    }

    @Test
    void resolvesRealFurnaceMathThroughTheSnapshotRegistry() throws Exception {
        BlockEntityState state = new BlockEntityState();
        putSlot(state, SELF, 0, 1); // input
        putSlot(state, SELF, 1, 1); // fuel
        state.put(new BlockEntityField(SELF, "cook_progress"), BlockEntityActions.COOK_TOTAL - 1);
        state.put(new BlockEntityField(SELF, "fuel_time"), 10);

        BlockEntitySnapshot snap = BlockEntitySnapshot.furnace(SELF);
        TaskNode task = BlockEntityTaskFactory.furnaceInert(snap);

        // Registry keyed by the live task ID, exactly as the plugin will back it.
        Map<String, BlockEntitySnapshot> registry = new HashMap<>();
        registry.put(task.taskId(), snap);

        BlockEntityTaskRunner runner = BlockEntityTaskRunner.withSnapshotResolver(state, registry::get);
        BlockEntityTickExecutor executor = new BlockEntityTickExecutor(runner);

        assertEquals(1, executor.executeTick(List.of(task)), "one furnace, one layer");

        assertEquals(0, slot(state, SELF, 0), "input consumed — the REAL action ran, not the inert no-op");
        assertEquals(1, slot(state, SELF, 2), "output produced");
        assertEquals(0, state.get(new BlockEntityField(SELF, "cook_progress")), "progress reset");
    }

    @Test
    void unregisteredTaskIsACleanNoOp() throws Exception {
        BlockEntityState state = new BlockEntityState();
        putSlot(state, SELF, 0, 5);
        putSlot(state, SELF, 1, 5);

        BlockEntitySnapshot snap = BlockEntitySnapshot.furnace(SELF);
        TaskNode task = BlockEntityTaskFactory.furnaceInert(snap);

        // Empty registry: the lookup returns null, resolve(null) → null → no mutation.
        BlockEntityTaskRunner runner =
            BlockEntityTaskRunner.withSnapshotResolver(state, id -> null);
        BlockEntityTickExecutor executor = new BlockEntityTickExecutor(runner);

        executor.executeTick(List.of(task));

        assertEquals(5, slot(state, SELF, 0), "no snapshot → no action → input untouched");
        assertEquals(5, slot(state, SELF, 1), "fuel untouched too");
    }

    @Test
    void nullRegistryDoesNotThrow() throws Exception {
        BlockEntityState state = new BlockEntityState();
        BlockEntitySnapshot snap = BlockEntitySnapshot.furnace(SELF);
        TaskNode task = BlockEntityTaskFactory.furnaceInert(snap);

        BlockEntityTaskRunner runner = BlockEntityTaskRunner.withSnapshotResolver(state, null);
        BlockEntityTickExecutor executor = new BlockEntityTickExecutor(runner);

        // A null registry defaults to id -> null internally; must be a no-op, not an NPE.
        assertEquals(1, executor.executeTick(List.of(task)),
            "null registry resolves every task to a no-op action without throwing");
    }
}
