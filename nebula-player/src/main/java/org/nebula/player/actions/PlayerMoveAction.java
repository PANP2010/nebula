package org.nebula.player.actions;

import org.nebula.core.math.Vec3;
import org.nebula.core.state.WorldPos;
import org.nebula.player.PlayerTaskAction;
import org.nebula.player.PlayerTaskContext;

import java.util.UUID;

/**
 * Deterministic player movement physics. Mirrors {@link org.nebula.entity.actions.EntityMoveAction}
 * but scoped to player entities — reads/writes player position and velocity through
 * {@link PlayerTaskContext}, using the Bukkit-backed terrain view for collision.
 *
 * <p>Physics model:
 * <ol>
 *   <li>Read current position + velocity</li>
 *   <li>Step position by current velocity (vanilla travelInAir order)</li>
 *   <li>Sweep vertical descent one block at a time</li>
 *   <li>Integrate gravity + drag into velocity for next tick</li>
 *   <li>Write back position + velocity</li>
 * </ol>
 */
public final class PlayerMoveAction implements PlayerTaskAction {

    private static final double GRAVITY = -0.08;
    private static final double DRAG = 0.98;

    private final UUID playerId;
    private final int dimensionId;

    public PlayerMoveAction(UUID playerId) {
        this(playerId, 0);
    }

    public PlayerMoveAction(UUID playerId, int dimensionId) {
        this.playerId = playerId;
        this.dimensionId = dimensionId;
    }

    @Override
    public void execute(PlayerTaskContext ctx) {
        Vec3 pos = ctx.readVec(playerId, "position");
        Vec3 vel = ctx.readVec(playerId, "velocity");

        if (vel.y() <= 0 && restedOn(ctx.terrain(), pos)) {
            ctx.writeVec(playerId, "velocity", new Vec3(vel.x() * DRAG, 0.0, vel.z() * DRAG));
            ctx.writeVec(playerId, "position", pos);
            return;
        }

        Vec3 target = pos.add(vel);

        boolean landed = false;
        if (vel.y() < 0) {
            target = sweepDescent(ctx.terrain(), pos, target);
            landed = restedOn(ctx.terrain(), target);
        }

        Vec3 newVel = landed
            ? new Vec3(vel.x() * DRAG, 0.0, vel.z() * DRAG)
            : new Vec3(vel.x() * DRAG, (vel.y() + GRAVITY) * DRAG, vel.z() * DRAG);

        ctx.writeVec(playerId, "velocity", newVel);
        ctx.writeVec(playerId, "position", target);
    }

    private Vec3 sweepDescent(org.nebula.entity.TerrainView terrain, Vec3 from, Vec3 to) {
        int startFeet = (int) Math.floor(from.y());
        int endFeet = (int) Math.floor(to.y());
        int col_x = (int) Math.floor(to.x());
        int col_z = (int) Math.floor(to.z());

        for (int y = startFeet - 1; y >= endFeet; y--) {
            if (terrain.isSolid(new WorldPos(dimensionId, col_x, y, col_z))) {
                return new Vec3(to.x(), y + 1.0, to.z());
            }
        }
        return to;
    }

    private boolean restedOn(org.nebula.entity.TerrainView terrain, Vec3 pos) {
        int feetY = (int) Math.floor(pos.y());
        return pos.y() == feetY
            && terrain.isSolid(new WorldPos(dimensionId,
                (int) Math.floor(pos.x()), feetY - 1, (int) Math.floor(pos.z())));
    }
}
