package org.nebula.player.actions;

import org.nebula.player.PlayerTaskAction;
import org.nebula.player.PlayerTaskContext;
import org.nebula.player.SurvivalAttributes;
import java.util.UUID;

/**
 * Player hunger drain tick.
 *
 * Called every game tick, models:
 * - Exhaustion accumulation from movement/sprinting/sprinting-jump
 * - Hunger drain when exhaustion >= 4.0
 * - Health drain when hunger = 0
 * - Saturation regeneration when hunger > 18 and health < max
 *
 * RW-set: reads player position, velocity, hunger, saturation, health
 *         writes hunger, saturation, exhaustion, health
 */
public final class PlayerHungerAction implements PlayerTaskAction {

    private final UUID playerId;
    private final SurvivalAttributes survival;

    public PlayerHungerAction(UUID playerId, SurvivalAttributes survival) {
        this.playerId = playerId;
        this.survival = survival;
    }

    @Override
    public void execute(PlayerTaskContext ctx) {
        // Movement-based exhaustion
        double speed = ctx.readVec(playerId, "velocity").distanceTo(
            new org.nebula.core.math.Vec3(0, 0, 0));
        double exhaustion = 0.0;
        if (speed > 0.01) {
            exhaustion += 0.01; // walking
            boolean sprinting = ctx.readBool(playerId, "is_sprinting");
            if (sprinting) exhaustion += 0.1; // sprinting
        }

        // Tick the survival model
        survival.addExhaustion(playerId, exhaustion);
        survival.tick(playerId);
    }
}
