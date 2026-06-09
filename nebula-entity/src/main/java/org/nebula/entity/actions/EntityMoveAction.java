package org.nebula.entity.actions;

import org.nebula.entity.EntityTaskAction;
import org.nebula.entity.EntityTaskContext;
import org.nebula.entity.Vec3;

/**
 * Entity movement physics (arch doc §6.2, ENTITY_MOVE).
 *
 * <p>Deterministic ballistic integration:
 * <ol>
 *   <li>Read current position and velocity.</li>
 *   <li>Apply gravity to the vertical velocity component, then drag.</li>
 *   <li>Integrate position by the new velocity (Euler step).</li>
 *   <li>Write back both position and velocity.</li>
 * </ol>
 *
 * <p>This is a simplified, fully deterministic model — no terrain collision
 * resolution yet (the MOVE RW-set reads neighbouring blocks for a future
 * terrain-aware version). It is enough to drive a reproducible physics
 * simulation through the DAG executor and replay verifier.
 */
public final class EntityMoveAction implements EntityTaskAction {

    /** Blocks/tick^2 downward acceleration (sign: negative Y). */
    private static final double GRAVITY = -0.08;
    /** Velocity retained per tick (vanilla-ish air drag). */
    private static final double DRAG = 0.98;

    private final long entityId;

    public EntityMoveAction(long entityId) {
        this.entityId = entityId;
    }

    @Override
    public void execute(EntityTaskContext ctx) {
        Vec3 pos = ctx.readVec(entityId, "position");
        Vec3 vel = ctx.readVec(entityId, "velocity");

        Vec3 newVel = new Vec3(
            vel.x() * DRAG,
            (vel.y() + GRAVITY) * DRAG,
            vel.z() * DRAG);
        Vec3 newPos = pos.add(newVel);

        ctx.writeVec(entityId, "velocity", newVel);
        ctx.writeVec(entityId, "position", newPos);
    }
}
