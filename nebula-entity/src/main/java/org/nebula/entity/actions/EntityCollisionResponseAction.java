package org.nebula.entity.actions;

import org.nebula.entity.EntityTaskAction;
import org.nebula.entity.EntityTaskContext;
import org.nebula.entity.Vec3;

/**
 * Elastic collision response between two entities (arch doc §6.2,
 * ENTITY_COLLISION_RESPONSE).
 *
 * <p>Reads both entities' velocities and writes both back after a
 * deterministic equal-mass elastic exchange along the line of contact. For
 * simplicity (and determinism) this swaps the velocity components — the
 * canonical equal-mass 1-D elastic result — which conserves momentum and
 * energy and is fully reproducible.
 *
 * <p>The member order is fixed by the factory (lo, hi entity IDs), so the
 * write order is deterministic regardless of which task triggered it.
 */
public final class EntityCollisionResponseAction implements EntityTaskAction {

    private final long entityA;
    private final long entityB;

    public EntityCollisionResponseAction(long entityA, long entityB) {
        this.entityA = entityA;
        this.entityB = entityB;
    }

    @Override
    public void execute(EntityTaskContext ctx) {
        Vec3 va = ctx.readVec(entityA, "velocity");
        Vec3 vb = ctx.readVec(entityB, "velocity");

        // Equal-mass elastic collision: exchange velocities.
        ctx.writeVec(entityA, "velocity", vb);
        ctx.writeVec(entityB, "velocity", va);
    }
}
