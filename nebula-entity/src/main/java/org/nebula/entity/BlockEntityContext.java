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

    BlockEntityContext(BlockEntityState state, BlockEntitySnapshotState snapshot) {
        this.state = state;
        this.snapshot = snapshot;
    }

    public int readSlot(WorldPos pos, int slot) {
        return read(pos, "inventory.slots[" + slot + "]");
    }

    public void writeSlot(WorldPos pos, int slot, int count) {
        write(pos, "inventory.slots[" + slot + "]", count);
    }

    public int read(WorldPos pos, String field) {
        return snapshot.read(state, new BlockEntityField(pos, field));
    }

    public void write(WorldPos pos, String field, int value) {
        snapshot.write(new BlockEntityField(pos, field), value);
    }

    BlockEntitySnapshotState snapshot() {
        return snapshot;
    }
}
