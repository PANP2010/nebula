package org.nebula.entity.actions;

import org.nebula.entity.EntityTaskAction;
import org.nebula.entity.EntityTaskContext;

/**
 * AI act execution (arch doc §7.2, AI_ACT).
 *
 * <p>Reads the entity's current goal, path waypoints, and movement state. Writes
 * updated velocity and position based on the active goal. Handles movement speeds
 * for different goal types (WANDER, FLEE, ENGAGE, EAT, IDLE) and terrain modifiers
 * (water slows movement, air applies gravity).
 *
 * <p>RW-set covers: entity position/velocity (read+write), goal target (read).
 */
public final class EntityActAction implements EntityTaskAction {

    private final long entityId;
    private final int dimensionId;

    public EntityActAction(long entityId, int dimensionId) {
        this.entityId = entityId;
        this.dimensionId = dimensionId;
    }

    @Override
    public void execute(EntityTaskContext ctx) {
        // Read current position and velocity
        double px = ctx.readScalar(entityId, "position_x");
        double py = ctx.readScalar(entityId, "position_y");
        double pz = ctx.readScalar(entityId, "position_z");
        double vx = ctx.readScalar(entityId, "velocity_x");
        double vy = ctx.readScalar(entityId, "velocity_y");
        double vz = ctx.readScalar(entityId, "velocity_z");

        // Read active goal
        int goal = (int) ctx.readScalar(entityId, "active_goal");

        // Read path data
        double pathReachable = ctx.readScalar(entityId, "path_reachable");
        double pathCost = ctx.readScalar(entityId, "path_cost");
        int waypointCount = (int) ctx.readScalar(entityId, "path_waypoints");

        // Read terrain flags
        double inWater = ctx.readScalar(entityId, "in_water");
        double onGround = ctx.readScalar(entityId, "on_ground");

        // Movement speed by goal type
        double speed = 0.1; // default wander speed
        double targetVx = 0, targetVz = 0, targetVy = 0;

        switch (goal) {
            case 0: // WANDER
                speed = 0.1;
                // Simple random movement if no path
                if (waypointCount <= 0 && ctx.random() != null) {
                    double angle = ctx.random().nextDouble() * 2 * Math.PI;
                    targetVx = Math.cos(angle) * speed;
                    targetVz = Math.sin(angle) * speed;
                }
                break;
            case 1: // FLEE
                speed = 0.15;
                // Move away from threat (stored in goal_target_*)
                {
                    double tx = ctx.readScalar(entityId, "goal_target_x");
                    double tz = ctx.readScalar(entityId, "goal_target_z");
                    double dx = px - tx, dz = pz - tz;
                    double len = Math.sqrt(dx * dx + dz * dz);
                    if (len > 0.001) {
                        targetVx = (dx / len) * speed;
                        targetVz = (dz / len) * speed;
                    }
                    // Jump if blocked
                    if (onGround > 0.5) {
                        targetVy = 0.42;
                    }
                }
                break;
            case 2: // ENGAGE
                speed = 0.12;
                // Move toward target
                {
                    double tx = ctx.readScalar(entityId, "goal_target_x");
                    double tz = ctx.readScalar(entityId, "goal_target_z");
                    double dx = tx - px, dz = tz - pz;
                    double len = Math.sqrt(dx * dx + dz * dz);
                    if (len > 0.001) {
                        targetVx = (dx / len) * speed;
                        targetVz = (dz / len) * speed;
                    }
                }
                break;
            case 3: // EAT
                speed = 0.05;
                break;
            case 4: // IDLE
            default:
                speed = 0.0;
                targetVx = 0;
                targetVz = 0;
                break;
        }

        // Water modifier: slower in water
        if (inWater > 0.5) {
            speed *= 0.5;
            targetVx *= 0.5;
            targetVz *= 0.5;
            targetVy = 0.1; // swim upward
        } else if (onGround <= 0.5) {
            // Gravity in air
            targetVy = vy - 0.08;
        }

        // Write updated velocity
        ctx.writeScalar(entityId, "velocity_x", targetVx);
        ctx.writeScalar(entityId, "velocity_y", targetVy);
        ctx.writeScalar(entityId, "velocity_z", targetVz);

        // Write updated position (integration)
        ctx.writeScalar(entityId, "position_x", px + targetVx);
        ctx.writeScalar(entityId, "position_y", py + targetVy);
        ctx.writeScalar(entityId, "position_z", pz + targetVz);
    }
}
