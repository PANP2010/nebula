package org.nebula.plugin;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.BlockEntityActionResolver;
import org.nebula.entity.BlockEntitySnapshot;
import org.nebula.entity.BlockEntityState;
import org.nebula.entity.BlockEntityTaskFactory;
import org.nebula.entity.BlockEntityTaskRunner;
import org.nebula.entity.actions.BlockEntityActions;
import org.nebula.guard.AccessTarget;
import org.nebula.guard.ActualAccessTrace;
import org.nebula.guard.RWSetConsistencyChecker;
import org.nebula.guard.RWSetViolation;
import org.nebula.guard.ThreadLocalAccessTrace;
import org.nebula.guard.ViolationType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B8 C3, pure-unit slice: prove the {@link BlockEntityRwGuardTracer} bridge makes the
 * RW-guard actually see the live block-entity actions' field accesses, and that the
 * corrected block-entity RW-sets in {@link BlockEntityTaskFactory} trace <em>clean</em>
 * against those real accesses through {@link RWSetConsistencyChecker}. The block-entity
 * analogue of {@code RedstoneRwGuardBridgeTest}.
 *
 * <p>This is the honest de-risk before the live-Folia guard run: it converts "the C3
 * block-entity RW-sets are now complete" from an asserted claim into a checker-verified
 * one, using the exact tracer the plugin wiring installs. Without this bridge the guard
 * traces nothing on live block-entities (the actions record through
 * {@code BlockEntityAccessTracer}, not {@link ThreadLocalAccessTrace}) and would report
 * a false "zero violations".
 */
final class BlockEntityRwGuardBridgeTest {

    private static final WorldPos SELF = new WorldPos(0, 3, 64, 7);

    private static void putSlot(BlockEntityState s, int i, int n) {
        s.put(new BlockEntityField(SELF, "inventory.slots[" + i + "]"), n);
    }

    /** Seeds a furnace mid-smelt (input+fuel present, one tick from completion). */
    private static TaskNode seededFurnace(BlockEntityState state) {
        putSlot(state, 0, 1); // input
        putSlot(state, 1, 1); // fuel
        state.put(new BlockEntityField(SELF, "cook_progress"), BlockEntityActions.COOK_TOTAL - 1);
        state.put(new BlockEntityField(SELF, "fuel_time"), 10);
        BlockEntitySnapshot snap = BlockEntitySnapshot.furnace(SELF);
        return BlockEntityTaskFactory.furnace(snap,
            () -> BlockEntityActionResolver.resolve(snap));
    }

    @Test
    void furnaceActionTracesCleanAgainstItsDeclaredRwSet() throws Exception {
        BlockEntityState state = new BlockEntityState();
        BlockEntitySnapshot snap = BlockEntitySnapshot.furnace(SELF);
        putSlot(state, 0, 1);
        putSlot(state, 1, 1);
        state.put(new BlockEntityField(SELF, "cook_progress"), BlockEntityActions.COOK_TOTAL - 1);
        state.put(new BlockEntityField(SELF, "fuel_time"), 10);

        TaskNode task = BlockEntityTaskFactory.furnace(snap, () -> { });
        BlockEntityTaskRunner runner = BlockEntityTaskRunner.withSnapshotResolver(
            state, id -> snap, BlockEntityRwGuardTracer.INSTANCE, null);

        ThreadLocalAccessTrace.reset();
        runner.run(task);
        ActualAccessTrace actual = ThreadLocalAccessTrace.snapshot();

        List<RWSetViolation> violations =
            RWSetConsistencyChecker.check(0L, task, task.declaredRWSet(), actual);
        assertTrue(violations.isEmpty(),
            "furnace action's real field accesses must all be declared in furnaceRw(); got: " + violations);
        // Corroborate the trace was NOT silently empty (the honesty trap): a real smelt
        // touches slots + timers, so the snapshot must hold block-entity field accesses.
        assertTrue(!actual.readBlockEntities().isEmpty() && !actual.writtenBlockEntities().isEmpty(),
            "trace must hold the furnace's real field reads/writes, not be empty");
    }

    @Test
    void bridgeHasRealDetectionPowerWhenAFieldIsUndeclared() throws Exception {
        // Negative control: prove the bridge+checker actually catch drift, so the clean
        // result above is meaningful and not a silently-empty trace. Declare a furnace
        // RW-set that OMITS one slot the action really writes (the output slot 2), then
        // confirm the checker flags exactly that undeclared field write.
        BlockEntityState state = new BlockEntityState();
        putSlot(state, 0, 1);
        putSlot(state, 1, 1);
        state.put(new BlockEntityField(SELF, "cook_progress"), BlockEntityActions.COOK_TOTAL - 1);
        state.put(new BlockEntityField(SELF, "fuel_time"), 10);
        BlockEntitySnapshot snap = BlockEntitySnapshot.furnace(SELF);

        BlockEntityField outputSlot = new BlockEntityField(SELF, "inventory.slots[2]");
        RWSet incomplete = RWSet.builder()
            .readBlockEntity(new BlockEntityField(SELF, "inventory.slots[0]"))
            .readBlockEntity(new BlockEntityField(SELF, "inventory.slots[1]"))
            .readBlockEntity(new BlockEntityField(SELF, "inventory.slots[2]"))
            .readBlockEntity(new BlockEntityField(SELF, "cook_progress"))
            .readBlockEntity(new BlockEntityField(SELF, "fuel_time"))
            .writeBlockEntity(new BlockEntityField(SELF, "inventory.slots[0]"))
            // inventory.slots[2] write intentionally omitted
            .writeBlockEntity(new BlockEntityField(SELF, "cook_progress"))
            .writeBlockEntity(new BlockEntityField(SELF, "fuel_time"))
            .build();
        TaskNode task = new TaskNode(snap.taskId(), "BLOCK_ENTITY_FURNACE", incomplete, () -> { });

        BlockEntityTaskRunner runner = BlockEntityTaskRunner.withSnapshotResolver(
            state, id -> snap, BlockEntityRwGuardTracer.INSTANCE, null);

        ThreadLocalAccessTrace.reset();
        runner.run(task);
        ActualAccessTrace actual = ThreadLocalAccessTrace.snapshot();

        List<RWSetViolation> violations =
            RWSetConsistencyChecker.check(0L, task, incomplete, actual);

        assertEquals(1, violations.size(),
            "exactly the one omitted output-slot write must be flagged; got: " + violations);
        RWSetViolation v = violations.getFirst();
        assertEquals(ViolationType.UNDECLARED_WRITE, v.violationType());
        assertEquals(AccessTarget.blockEntityField(outputSlot), v.accessTarget(),
            "the flagged access must be the output slot the RW-set dropped");
    }
}
