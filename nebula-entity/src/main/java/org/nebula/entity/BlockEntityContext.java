package org.nebula.entity;

import org.nebula.core.random.DeterministicRandom;
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
 *
 * <p>RNG-consuming actions (dropper/dispenser slot selection) draw from a
 * per-task {@link DeterministicRandom} seeded by the layered random source for
 * this block's coordinate — so the stream is identical across runs regardless
 * of execution order. The context is non-RNG by default; {@link #random()}
 * throws if no source was provided, catching an action that consumes RNG
 * without declaring {@code RandomUsage} (the same contract
 * {@code EntityTaskContext} enforces).
 */
public final class BlockEntityContext {

    private final BlockEntityState state;
    private final BlockEntitySnapshotState snapshot;
    private final BlockEntityAccessTracer tracer;
    private final DeterministicRandom random;

    BlockEntityContext(BlockEntityState state, BlockEntitySnapshotState snapshot) {
        this(state, snapshot, null, null);
    }

    BlockEntityContext(BlockEntityState state, BlockEntitySnapshotState snapshot,
                       BlockEntityAccessTracer tracer) {
        this(state, snapshot, tracer, null);
    }

    BlockEntityContext(BlockEntityState state, BlockEntitySnapshotState snapshot,
                       BlockEntityAccessTracer tracer, DeterministicRandom random) {
        this.state = state;
        this.snapshot = snapshot;
        this.tracer = tracer;
        this.random = random;
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

    /**
     * The per-task deterministic RNG stream for this block's coordinate.
     *
     * @throws IllegalStateException if the action consumes RNG but the task was
     *         not given a random source (i.e. its RW-set declared no
     *         {@code RandomUsage}) — surfacing an undeclared-RNG bug rather than
     *         silently diverging, matching {@code EntityTaskContext.random()}.
     */
    public DeterministicRandom random() {
        if (random == null) {
            throw new IllegalStateException(
                "Task consumed RNG but declared no RandomUsage in its RW-set");
        }
        return random;
    }

    BlockEntitySnapshotState snapshot() {
        return snapshot;
    }
}
