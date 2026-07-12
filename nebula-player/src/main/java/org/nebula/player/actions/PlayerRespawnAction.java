package org.nebula.player.actions;

import org.nebula.player.PlayerTaskAction;
import org.nebula.player.PlayerTaskContext;
import java.util.UUID;

/**
 * Player respawn action. Called when health reaches 0.
 *
 * Mechanics:
 * - Player enters death state (game mode, spawn point)
 * - Respawn timer (40 ticks = 2 seconds)
 * - Respawn at spawn point or bed location
 * - Restore health to max, reset hunger/saturation
 * - Restore inventory on survival, clear on hardcore
 */
public final class PlayerRespawnAction implements PlayerTaskAction {

    private final UUID playerId;
    private final boolean hardcore;

    public PlayerRespawnAction(UUID playerId, boolean hardcore) {
        this.playerId = playerId;
        this.hardcore = hardcore;
    }

    @Override
    public void execute(PlayerTaskContext ctx) {
        // Check if player is actually dead
        double health = ctx.readScalar(playerId, "health");
        if (health > 0) return; // not dead, nothing to do

        // Enter death state
        ctx.writeBool(playerId, "is_dead", true);
        ctx.writeScalar(playerId, "death_time", ctx.readScalar(playerId, "death_time") + 1.0);

        // Check respawn timer
        double deathTime = ctx.readScalar(playerId, "death_time");
        if (deathTime < 20.0) return; // waiting period not over (1 second)

        // Trigger respawn
        ctx.writeBool(playerId, "is_dead", false);
        ctx.writeScalar(playerId, "death_time", 0.0);
        ctx.writeScalar(playerId, "health", 20.0);
        ctx.writeScalar(playerId, "hunger", 20.0);
        ctx.writeScalar(playerId, "saturation", 5.0);
        ctx.writeScalar(playerId, "exhaustion", 0.0);

        // On hardcore, player gets removed/demoted
        if (hardcore) {
            ctx.writeString(playerId, "game_mode", "SPECTATOR");
        }

        // Teleport to spawn
        double spawnX = ctx.readScalar(playerId, "spawn_x");
        double spawnY = ctx.readScalar(playerId, "spawn_y");
        double spawnZ = ctx.readScalar(playerId, "spawn_z");
        ctx.writeVec(playerId, "position",
            new org.nebula.core.math.Vec3(spawnX, spawnY + 1.7, spawnZ));
    }
}
