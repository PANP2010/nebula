package org.nebula.entity.actions;

import org.nebula.core.state.WorldPos;
import org.nebula.entity.EntityTaskAction;
import org.nebula.entity.EntityTaskContext;
import org.nebula.entity.TerrainView;
import org.nebula.entity.Vec3;

/**
 * Entity movement physics (arch doc §6.2, ENTITY_MOVE).
 *
 * <p>Deterministic ballistic integration with terrain collision:
 * <ol>
 *   <li>Read current position and velocity.</li>
 *   <li>Apply gravity to the vertical velocity component, then drag.</li>
 *   <li>Integrate position by the new velocity (Euler step).</li>
 *   <li>If the block at the new position's feet is solid (read via the
 *       {@link TerrainView}), clamp the entity to rest on the block top and zero
 *       the downward velocity — terrain collision.</li>
 *   <li>Write back both position and velocity.</li>
 * </ol>
 *
 * <p>The collision read uses the block cell the MOVE RW-set already declares
 * (the destination cell and the one below it), so declared and actual access
 * stay consistent — the invariant the RW-Set Integrity Checker enforces.
 */
public final class EntityMoveAction implements EntityTaskAction {

    /** Blocks/tick^2 downward acceleration (sign: negative Y). */
    private static final double GRAVITY = -0.08;
    /** Velocity retained per tick (vanilla-ish air drag). */
    private static final double DRAG = 0.98;

    private final long entityId;
    private final int dimensionId;

    public EntityMoveAction(long entityId) {
        this(entityId, 0);
    }

    public EntityMoveAction(long entityId, int dimensionId) {
        this.entityId = entityId;
        this.dimensionId = dimensionId;
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

        // Terrain collision: if descending into a solid block, rest on its top.
        TerrainView terrain = ctx.terrain();
        if (newVel.y() < 0) {
            int feetBlockY = (int) Math.floor(newPos.y());
            WorldPos below = new WorldPos(dimensionId,
                (int) Math.floor(newPos.x()), feetBlockY, (int) Math.floor(newPos.z()));
            if (terrain.isSolid(below)) {
                // Clamp feet to the top surface of the solid block and stop the
                // downward component. (Block at integer y occupies [y, y+1).)
                newPos = new Vec3(newPos.x(), feetBlockY + 1.0, newPos.z());
                newVel = new Vec3(newVel.x(), 0.0, newVel.z());
            }
        }

        ctx.writeVec(entityId, "velocity", newVel);
        ctx.writeVec(entityId, "position", newPos);
    }
}
