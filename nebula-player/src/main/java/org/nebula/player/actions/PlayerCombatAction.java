package org.nebula.player.actions;

import org.nebula.core.math.Vec3;
import org.nebula.player.PlayerTaskAction;
import org.nebula.player.PlayerTaskContext;
import java.util.UUID;

/**
 * Player melee attack. Reads attack cooldown, target entity position,
 * attack damage; writes target health. Also writes player attack cooldown.
 *
 * Mechanics:
 * - Attack cooldown: 0.5s (10 ticks). Can't attack during cooldown.
 * - Base damage × strength multiplier × crit chance
 * - Knockback based on attack direction
 * - Fire aspect: sets target on fire
 */
public final class PlayerCombatAction implements PlayerTaskAction {

    private final UUID playerId;
    private final long targetEntityId;
    private final double baseDamage;

    public PlayerCombatAction(UUID playerId, long targetEntityId, double baseDamage) {
        this.playerId = playerId;
        this.targetEntityId = targetEntityId;
        this.baseDamage = baseDamage;
    }

    @Override
    public void execute(PlayerTaskContext ctx) {
        // Check cooldown
        double cooldown = ctx.readScalar(playerId, "attack_cooldown");
        if (cooldown > 0) return; // still on cooldown

        // Read target health
        double targetHealth = ctx.readScalar(targetEntityId, "health");
        if (targetHealth <= 0) return; // already dead

        // Calculate damage
        double speed = ctx.readScalar(playerId, "attack_speed");
        double critChance = ctx.random().nextDouble(); // 0.1 = 10% crit
        double damage = baseDamage;
        if (critChance < 0.1) damage *= 1.5; // crit!

        double newHealth = Math.max(0, targetHealth - damage);
        ctx.writeScalar(targetEntityId, "health", newHealth);

        // Set cooldown to 10 ticks (0.5s at 20TPS)
        ctx.writeScalar(playerId, "attack_cooldown", 10.0);

        // Knockback: write target velocity away from attacker
        Vec3 attackerPos = ctx.readVec(playerId, "position");
        Vec3 targetPos = ctx.readVec(targetEntityId, "position");
        double dx = targetPos.x() - attackerPos.x();
        double dz = targetPos.z() - attackerPos.z();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 0.01) {
            double knockbackStrength = 0.4;
            double kbx = (dx / len) * knockbackStrength;
            double kbz = (dz / len) * knockbackStrength;
            Vec3 targetVel = ctx.readVec(targetEntityId, "velocity");
            ctx.writeVec(targetEntityId, "velocity",
                new Vec3(targetVel.x() + kbx, targetVel.y() + 0.1, targetVel.z() + kbz));
        }

        // Fire aspect
        boolean fireAspect = ctx.readBool(playerId, "has_fire_aspect");
        if (fireAspect) {
            ctx.writeScalar(targetEntityId, "fire_ticks", 80.0); // 4 seconds
        }
    }
}
