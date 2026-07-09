package org.nebula.entity.actions;

import org.nebula.core.state.WorldPos;
import org.nebula.entity.EntityTaskAction;
import org.nebula.entity.EntityTaskContext;
import org.nebula.entity.TerrainView;
import org.nebula.entity.Vec3;

/**
 * Entity movement physics (arch doc §6.2, ENTITY_MOVE).
 *
 * <p>Deterministic ballistic integration with swept terrain collision. The
 * integration <em>order</em> mirrors vanilla's {@code LivingEntity.travelInAir}
 * (move-then-integrate), which is the crucial ordering for zero-diff — vanilla
 * calls {@code move(SELF, deltaMovement)} <em>first</em> (stepping position by
 * the <em>current</em> velocity) and only then applies {@code -= gravity} and
 * {@code * 0.98} to compute the velocity for the <em>next</em> tick
 * (decompiled {@code LivingEntity.java:2605} → {@code :2419} → {@code :2430}):
 * <ol>
 *   <li>Read current position and velocity.</li>
 *   <li>Step the position by the <em>current</em> velocity (the vanilla
 *       {@code move()} call), sweeping the descent for terrain collision.</li>
 *   <li>Sweep the descent in ≤1-block sub-steps, checking each cell along the
 *       path via the {@link TerrainView}. The entity lands on the top of the
 *       <em>first</em> solid block encountered — so a fast fall cannot tunnel
 *       through thin floors, and the collision read never skips a cell.</li>
 *   <li><em>Then</em> integrate gravity+drag into the velocity for the next
 *       tick, and write back both position and velocity.</li>
 * </ol>
 *
 * <p><b>Why order matters (B8 C1, 2026-07-09).</b> An earlier revision applied
 * gravity+drag to the velocity <em>before</em> stepping position, folding one
 * extra tick of deceleration into every step. Against Folia that produced a
 * systematic single-axis Y over-fall (predicted.y &lt; authoritative.y by
 * ~0.04–0.078/tick — see {@code EntityDivergenceTracker}). Moving by the
 * current velocity first eliminates that directional bias.
 *
 * <p>Swept (vs single-cell) collision keeps the block reads within the declared
 * RW-set even at high speed: each probed cell is one of the column cells the
 * MOVE RW-set covers, walked one block at a time rather than jumping to the
 * destination cell, which a &gt;1 block/tick fall would otherwise skip.
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

        // Grounded fixed point: an entity already sitting exactly on a solid
        // block top with no upward velocity stays put. Vanilla achieves the
        // same stable rest via move()'s internal vertical-collision cancel; we
        // model it explicitly so rest is a clean fixed point (vel.y == 0) rather
        // than a per-tick oscillation between 0 and -0.0784.
        if (vel.y() <= 0 && restedOn(ctx.terrain(), pos)) {
            ctx.writeVec(entityId, "velocity", new Vec3(vel.x() * DRAG, 0.0, vel.z() * DRAG));
            ctx.writeVec(entityId, "position", pos);
            return;
        }

        // Vanilla order: step the position by the CURRENT velocity first
        // (LivingEntity.move(SELF, deltaMovement)), sweeping for terrain
        // collision, THEN integrate gravity+drag for the next tick's velocity.
        Vec3 target = pos.add(vel);

        boolean landed = false;
        if (vel.y() < 0) {
            target = sweepDescent(ctx.terrain(), pos, target);
            // Landed iff the swept result sits on a solid block (collision
            // clamped the descent); zero the downward velocity then.
            landed = restedOn(ctx.terrain(), target);
        }

        // Integrate gravity+drag AFTER moving — this is the velocity carried
        // into the next tick (vanilla travelInAir: movementY -= gravity; * 0.98).
        Vec3 newVel = landed
            ? new Vec3(vel.x() * DRAG, 0.0, vel.z() * DRAG)
            : new Vec3(
                vel.x() * DRAG,
                (vel.y() + GRAVITY) * DRAG,
                vel.z() * DRAG);

        ctx.writeVec(entityId, "velocity", newVel);
        ctx.writeVec(entityId, "position", target);
    }

    /**
     * Sweeps the vertical descent from {@code from} to {@code to} one block at a
     * time. Returns the resting position on top of the first solid block in the
     * column, or {@code to} if the path is clear. Horizontal components are
     * applied at the final position (this model resolves only vertical terrain
     * collision, matching the declared column reads).
     */
    private Vec3 sweepDescent(TerrainView terrain, Vec3 from, Vec3 to) {
        int startFeet = (int) Math.floor(from.y());
        int endFeet = (int) Math.floor(to.y());
        int col_x = (int) Math.floor(to.x());
        int col_z = (int) Math.floor(to.z());

        // Walk each block cell from just below the start down to the target,
        // landing on the top face of the first solid one.
        for (int y = startFeet - 1; y >= endFeet; y--) {
            if (terrain.isSolid(new WorldPos(dimensionId, col_x, y, col_z))) {
                return new Vec3(to.x(), y + 1.0, to.z());
            }
        }
        return to;
    }

    /** True if a solid block sits directly beneath {@code pos}'s feet. */
    private boolean restedOn(TerrainView terrain, Vec3 pos) {
        int feetY = (int) Math.floor(pos.y());
        // After landing, feet sit exactly on an integer y == blockY+1.
        return pos.y() == feetY
            && terrain.isSolid(new WorldPos(dimensionId,
                (int) Math.floor(pos.x()), feetY - 1, (int) Math.floor(pos.z())));
    }
}
