package org.nebula.entity;

import org.nebula.core.state.EntityField;

/**
 * Execution context passed to entity physics actions. Provides typed access to
 * {@link EntityPhysicsState} through a per-task {@link EntityStateSnapshot},
 * ensuring all reads are versioned and all writes are buffered until commit.
 *
 * <p>Field access is by entity ID + field path, matching the coordinates that
 * {@link EntityTaskFactory}'s RW-set templates declare (e.g. "position",
 * "velocity", "health").
 */
public final class EntityTaskContext {

    private final EntityPhysicsState state;
    private final EntityStateSnapshot snapshot;

    EntityTaskContext(EntityPhysicsState state, EntityStateSnapshot snapshot) {
        this.state = state;
        this.snapshot = snapshot;
    }

    public Vec3 readVec(long entityId, String field) {
        return snapshot.readVec(state, new EntityField(entityId, field));
    }

    public double readScalar(long entityId, String field) {
        return snapshot.readScalar(state, new EntityField(entityId, field));
    }

    public void writeVec(long entityId, String field, Vec3 value) {
        snapshot.write(new EntityField(entityId, field), value);
    }

    public void writeScalar(long entityId, String field, double value) {
        snapshot.write(new EntityField(entityId, field), value);
    }

    EntityStateSnapshot snapshot() {
        return snapshot;
    }
}
