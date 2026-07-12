package org.nebula.entity.actions;

import org.nebula.entity.EntityTaskAction;
import org.nebula.entity.EntityTaskContext;

/**
 * Entity damage computation (arch doc §6.2, ENTITY_DAMAGE).
 *
 * <p>Reads the attacker's attack damage attribute and the defender's current health and
 * armor. Writes the defender's health after applying damage reduction. Also writes
 * the knockback velocity to the defender. Uses the entity's deterministic RNG for
 * critical hit rolls.
 *
 * <p>Damage formula (simplified vanilla):
 * - base = attacker base damage
 * - armor reduction = floor((armor * 0.04) * base)
 * - final = max(1, base - armor reduction)
 * - crit = rng.nextDouble() < 0.1 → final *= 1.5
 * - health = max(0, health - final)
 */
public final class EntityDamageAction implements EntityTaskAction {

    private final long attackerId;
    private final long defenderId;

    public EntityDamageAction(long attackerId, long defenderId) {
        this.attackerId = attackerId;
        this.defenderId = defenderId;
    }

    @Override
    public void execute(EntityTaskContext ctx) {
        // Read attacker base damage
        double baseDamage = ctx.readScalar(attackerId, "attack_damage");

        // Read defender health and armor
        double health = ctx.readScalar(defenderId, "health");
        if (health <= 0) return; // already dead

        double armor = ctx.readScalar(defenderId, "armor");

        // Armor damage reduction
        double armorReduction = Math.floor(armor * 0.04 * baseDamage);
        double damage = Math.max(1.0, baseDamage - armorReduction);

        // Critical hit roll (10% chance)
        if (ctx.random() != null && ctx.random().nextDouble() < 0.1) {
            damage *= 1.5;
        }

        // Apply damage
        double newHealth = Math.max(0.0, health - damage);
        ctx.writeScalar(defenderId, "health", newHealth);

        // Knockback: push defender away from attacker
        double dx = ctx.readScalar(defenderId, "position_x")
                  - ctx.readScalar(attackerId, "position_x");
        double dz = ctx.readScalar(defenderId, "position_z")
                  - ctx.readScalar(attackerId, "position_z");
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 0.001) {
            double kb = 0.4; // vanilla knockback strength
            ctx.writeScalar(defenderId, "velocity_x", ctx.readScalar(defenderId, "velocity_x") + (dx / len) * kb);
            ctx.writeScalar(defenderId, "velocity_z", ctx.readScalar(defenderId, "velocity_z") + (dz / len) * kb);
            ctx.writeScalar(defenderId, "velocity_y", 0.2); // slight upward knock
        }

        // Fire aspect: if attacker has fire aspect, set defender fire ticks
        double fireAspect = ctx.readScalar(attackerId, "fire_aspect");
        if (fireAspect > 0) {
            ctx.writeScalar(defenderId, "fire_ticks", 80.0 * fireAspect);
        }
    }
}
