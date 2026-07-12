package org.nebula.entity;

import org.nebula.core.math.Vec3;
import org.nebula.core.random.DeterministicRandom;
import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.EventType;
import org.nebula.core.state.WorldPos;

import java.util.Set;

/**
 * Execution context for block-entity tick actions. Provides typed access to
 * {@link BlockEntityState} through a per-task {@link BlockEntitySnapshotState},
 * so all reads are versioned and all writes buffer until the layer commits.
 *
 * <p>Access is by block position + field path, matching the coordinates that
 * {@link BlockEntityTaskFactory}'s RW-set templates declare — e.g.
 * {@code "inventory.slots[0]"}, {@code "cook_progress"}, {@code "fuel_time"}.
 *
 * <p>RNG-consuming actions (dropper/dispenser slot selection) draw from a
 * per-task {@link DeterministicRandom} seeded by the layered random source for
 * this block's coordinate — so the stream is identical across runs regardless of
 * execution order. The context is non-RNG by default; {@link #random()} throws
 * if no source was provided, catching an action that consumes RNG without
 * declaring {@code RandomUsage}.
 */
public final class BlockEntityContext {

    private final BlockEntityState state;
    private final BlockEntitySnapshotState snapshot;
    private final BlockEntityAccessTracer tracer;
    private final DeterministicRandom random;
    private final WorldPos selfPos;
    private final int facingX;
    private final int facingY;
    private final int facingZ;
    private final Set<EventType> firedEvents = new java.util.LinkedHashSet<>();

    BlockEntityContext(BlockEntityState state, BlockEntitySnapshotState snapshot) {
        this(state, snapshot, null, null, null, 0, 0, 0);
    }

    BlockEntityContext(BlockEntityState state, BlockEntitySnapshotState snapshot,
                      BlockEntityAccessTracer tracer) {
        this(state, snapshot, tracer, null, null, 0, 0, 0);
    }

    BlockEntityContext(BlockEntityState state, BlockEntitySnapshotState snapshot,
                      BlockEntityAccessTracer tracer, DeterministicRandom random) {
        this(state, snapshot, tracer, random, null, 0, 0, 0);
    }

    BlockEntityContext(BlockEntityState state, BlockEntitySnapshotState snapshot,
                      BlockEntityAccessTracer tracer, DeterministicRandom random, WorldPos selfPos) {
        this(state, snapshot, tracer, random, selfPos, 0, 0, 0);
    }

    BlockEntityContext(BlockEntityState state, BlockEntitySnapshotState snapshot,
                      BlockEntityAccessTracer tracer, DeterministicRandom random, WorldPos selfPos,
                      int facingX, int facingY, int facingZ) {
        this.state = state;
        this.snapshot = snapshot;
        this.tracer = tracer;
        this.random = random;
        this.selfPos = selfPos;
        this.facingX = facingX;
        this.facingY = facingY;
        this.facingZ = facingZ;
    }

    // ── Position ────────────────────────────────────────────────────────────

    /** Returns the block entity's position. May be null. */
    public WorldPos selfPos() { return selfPos; }

    // ── Facing ───────────────────────────────────────────────────────────────

    /** Returns (facingX, facingY, facingZ) direction offsets, or (0,0,0) if unknown. */
    public Vec3 facing() {
        return new Vec3(facingX, facingY, facingZ);
    }

    /** Returns the world position in the direction this block entity faces. */
    public WorldPos facingPos() {
        if (selfPos == null) return null;
        return new WorldPos(selfPos.dimensionId(),
            selfPos.x() + facingX, selfPos.y() + facingY, selfPos.z() + facingZ);
    }

    // ── Slot access (self-relative) ─────────────────────────────────────────

    /** Reads item count in slot {@code slot} of this block entity. */
    public int readSlot(int slot) { return read(selfPos, "inventory.slots[" + slot + "]"); }

    /** Reads item count in slot {@code slot} of the block at {@code pos}. */
    public int readSlot(WorldPos pos, int slot) { return read(pos, "inventory.slots[" + slot + "]"); }

    /** Writes item count to slot {@code slot} of this block entity. */
    public void writeSlot(int slot, int count) { write(selfPos, "inventory.slots[" + slot + "]", count); }

    /** Writes item count to slot {@code slot} of the block at {@code pos}. */
    public void writeSlot(WorldPos pos, int slot, int count) {
        write(pos, "inventory.slots[" + slot + "]", count);
    }

    // ── Scalar field access ──────────────────────────────────────────────────

    /** Reads a numeric field on this block entity. */
    public int read(String field) { return read(selfPos, field); }

    /** Reads a numeric field on the block at {@code pos}. */
    public int read(WorldPos pos, String field) {
        BlockEntityField f = new BlockEntityField(pos, field);
        if (tracer != null) tracer.onFieldRead(f);
        return snapshot.read(state, f);
    }

    /** Writes a numeric field on this block entity. */
    public void write(String field, int value) { write(selfPos, field, value); }

    /** Writes a numeric field on the block at {@code pos}. */
    public void write(WorldPos pos, String field, int value) {
        BlockEntityField f = new BlockEntityField(pos, field);
        if (tracer != null) tracer.onFieldWrite(f);
        snapshot.write(f, value);
    }

    // ── String field access (for item IDs) ───────────────────────────────────

    /** Reads a string field on this block entity (e.g. item ID at a slot). */
    public String readString(String field) { return readString(selfPos, field); }

    /** Reads a string field on the block at {@code pos}. */
    public String readString(WorldPos pos, String field) {
        BlockEntityField f = new BlockEntityField(pos, field);
        if (tracer != null) tracer.onFieldRead(f);
        return snapshot.readString(state, f);
    }

    /** Writes a string field on this block entity. */
    public void writeString(String field, String value) { writeString(selfPos, field, value); }

    /** Writes a string field on the block at {@code pos}. */
    public void writeString(WorldPos pos, String field, String value) {
        BlockEntityField f = new BlockEntityField(pos, field);
        if (tracer != null) tracer.onFieldWrite(f);
        snapshot.writeString(f, value);
    }

    // ── Event emission ──────────────────────────────────────────────────────

    /**
     * Records that this action fired the given event (e.g. ENTITY_SPAWNED,
     * INVENTORY_CHANGED, BLOCK_UPDATE).
     */
    public void writeEvent(EventType event) {
        firedEvents.add(event);
    }

    /** Returns all events fired by this action. */
    public Set<EventType> firedEvents() {
        return Set.copyOf(firedEvents);
    }

    // ── RNG ────────────────────────────────────────────────────────────────

    /**
     * The per-task deterministic RNG stream.
     *
     * @throws IllegalStateException if the action consumes RNG but the task was
     *         not given a random source (i.e. its RW-set declared no {@code RandomUsage}).
     */
    public DeterministicRandom random() {
        if (random == null) {
            throw new IllegalStateException(
                "Task consumed RNG but declared no RandomUsage in its RW-set");
        }
        return random;
    }

    BlockEntitySnapshotState snapshot() { return snapshot; }

    // ── Facing helpers ────────────────────────────────────────────────────────

    /** Returns the unit velocity vector for a facing direction offset. */
    public Vec3 facingVelocity(int facingX, int facingY, int facingZ, double speed) {
        double len = Math.sqrt((double) facingX * facingX + facingY * facingY + facingZ * facingZ);
        if (len < 0.001) return new Vec3(0, 0, speed);
        return new Vec3(facingX / len * speed, facingY / len * speed, facingZ / len * speed);
    }

    /** Returns the entity ID of the facing block's block entity. */
    public long readLinkedEntity() {
        return read("linked_entity");
    }
}
