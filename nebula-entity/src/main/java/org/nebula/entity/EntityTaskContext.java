package org.nebula.entity;

import org.nebula.core.random.DeterministicRandom;
import org.nebula.core.state.EntityField;

/**
 * Execution context passed to entity physics actions. Provides typed access to
 * {@link EntityPhysicsState} through a per-task {@link EntityStateSnapshot},
 * ensuring all reads are versioned and all writes are buffered until commit.
 *
 * <p>Field access is by entity ID + field path, matching the coordinates that
 * {@link EntityTaskFactory}'s RW-set templates declare (e.g. "position",
 * "velocity", "health").
 *
 * <p>RNG-consuming actions (AI goal selection, damage rolls) draw from a
 * per-task {@link DeterministicRandom} seeded by the layered random source for
 * this task's coordinate — so the stream is identical across runs regardless of
 * execution order. The context is non-RNG by default; {@link #random()} throws
 * if no source was provided, catching actions that consume RNG without
 * declaring {@code RandomUsage}.
 */
public final class EntityTaskContext {

    private final EntityPhysicsState state;
    private final EntityStateSnapshot snapshot;
    private final DeterministicRandom random;
    private final TerrainView terrain;
    private final EntityAccessTracer tracer;

    EntityTaskContext(EntityPhysicsState state, EntityStateSnapshot snapshot) {
        this(state, snapshot, null, TerrainView.EMPTY, null);
    }

    EntityTaskContext(EntityPhysicsState state, EntityStateSnapshot snapshot, DeterministicRandom random) {
        this(state, snapshot, random, TerrainView.EMPTY, null);
    }

    EntityTaskContext(EntityPhysicsState state, EntityStateSnapshot snapshot,
                      DeterministicRandom random, TerrainView terrain) {
        this(state, snapshot, random, terrain, null);
    }

    EntityTaskContext(EntityPhysicsState state, EntityStateSnapshot snapshot,
                      DeterministicRandom random, TerrainView terrain, EntityAccessTracer tracer) {
        this.state = state;
        this.snapshot = snapshot;
        this.random = random;
        TerrainView delegate = terrain == null ? TerrainView.EMPTY : terrain;
        this.terrain = tracer == null ? delegate : pos -> {
            tracer.onBlockRead(pos);
            return delegate.isSolid(pos);
        };
        this.tracer = tracer;
    }

    public Vec3 readVec(long entityId, String field) {
        EntityField target = new EntityField(entityId, field);
        if (tracer != null) tracer.onFieldRead(target);
        return snapshot.readVec(state, target);
    }

    public double readScalar(long entityId, String field) {
        EntityField target = new EntityField(entityId, field);
        if (tracer != null) tracer.onFieldRead(target);
        return snapshot.readScalar(state, target);
    }

    public void writeVec(long entityId, String field, Vec3 value) {
        EntityField target = new EntityField(entityId, field);
        if (tracer != null) tracer.onFieldWrite(target);
        snapshot.write(target, value);
    }

    public void writeScalar(long entityId, String field, double value) {
        EntityField target = new EntityField(entityId, field);
        if (tracer != null) tracer.onFieldWrite(target);
        snapshot.write(target, value);
    }

    /** Read-only terrain oracle for collision checks (defaults to open void). */
    public TerrainView terrain() {
        return terrain;
    }

    /**
     * The per-task deterministic RNG stream for this task's coordinate.
     *
     * @throws IllegalStateException if the action consumes RNG but the task was
     *         not given a random source (i.e. its RW-set declared no
     *         {@code RandomUsage}) — surfacing an undeclared-RNG bug rather than
     *         silently diverging.
     */
    public DeterministicRandom random() {
        if (random == null) {
            throw new IllegalStateException(
                "Task consumed RNG but declared no RandomUsage in its RW-set");
        }
        return random;
    }

    EntityStateSnapshot snapshot() {
        return snapshot;
    }
}
