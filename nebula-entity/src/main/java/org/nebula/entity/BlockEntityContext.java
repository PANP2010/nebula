package org.nebula.entity;

import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.WorldPos;

/**
 * Execution context for block-entity tick actions. Provides slot- and
 * field-level access to {@link BlockEntityState} through a per-task
 * {@link BlockEntitySnapshotState}, so all reads are versioned and all writes
 * buffer until the layer commits.
 *
 * <p>Access is by block position + field path, matching the coordinates that
 * {@link BlockEntityTaskFactory}'s RW-set templates declare — e.g.
 * {@code "inventory.slots[0]"}, {@code "cook_progress"}, {@code "fuel_time"}.
 */
public final class BlockEntityContext {

    private final BlockEntityState state;
    private final BlockEntitySnapshotState snapshot;
    private final BlockEntityAccessTracer tracer;

    BlockEntityContext(BlockEntityState state, BlockEntitySnapshotState snapshot) {
        this(state, snapshot, null);
    }

    BlockEntityContext(BlockEntityState state, BlockEntitySnapshotState snapshot,
                       BlockEntityAccessTracer tracer) {
        this.state = state;
        this.snapshot = snapshot;
        this.tracer = tracer;
    }

    public int readSlot(WorldPos pos, int slot) {
        return read(pos, "inventory.slots[" + slot + "]");
    }

    public void writeSlot(WorldPos pos, int slot, int count) {
        write(pos, "inventory.slots[" + slot + "]", count);
    }

    public int read(WorldPos pos, String field) {
        BlockEntityField f = new BlockEntityField(pos, field);
        if (tracer != null) tracer.onFieldRead(f);
        return snapshot.read(state, f);
    }

    public void write(WorldPos pos, String field, int value) {
        BlockEntityField f = new BlockEntityField(pos, field);
        if (tracer != null) tracer.onFieldWrite(f);
        snapshot.write(f, value);
    }

    BlockEntitySnapshotState snapshot() {
        return snapshot;
    }
}
