package org.nebula.player.ai;

import org.nebula.core.state.WorldPos;
import org.nebula.core.math.Vec3;

import java.util.*;

/**
 * Mob AI pipeline (arch doc ch.7). 
 * Per tick: SENSE → GOAL_SELECT → PATHFIND → ACT
 * 
 * All RNG uses the deterministic random from the task context so behavior is
 * reproducible across runs.
 */
public final class AiPipeline {

    /** Sense output: what the mob perceives about its environment. */
    public record SenseData(
        double nearestPlayerDist,
        Vec3 nearestPlayerPos,
        boolean isDay,
        boolean isInWater,
        boolean isOnGround,
        double healthFraction,
        int nearestHostileCount
    ) {}

    /** Goal selected by GOAL_SELECT. */
    public enum Goal {
        WANDER,   // Random walking
        FLEE,      // Run from nearest player
        ENGAGE,    // Chase and attack nearest player
        EAT,       // Find and eat food
        IDLE       // Stand still
    }

    /** Pathfinding result. */
    public record PathResult(Vec3 target, List<Vec3> waypoints, boolean reachable) {}

    /**
     * SENSE stage: builds the SenseData from environment readings.
     * This is the only stage that reads from the terrain/world.
     */
    public static SenseData sense(
            Vec3 mobPos, int dimensionId,
            java.util.function.Function<WorldPos, Boolean> isOpaque,
            java.util.function.Function<Vec3, Double> distanceToNearestPlayer,
            java.util.function.Function<Vec3, Vec3> nearestPlayerPos,
            java.util.function.Function<Vec3, Boolean> isPlayerHostile,
            java.util.function.DoubleSupplier mobHealthSupplier,
            java.util.function.DoubleSupplier mobMaxHealthSupplier) {
        
        double dist = distanceToNearestPlayer.apply(mobPos);
        Vec3 playerPos = nearestPlayerPos.apply(mobPos);
        
        int hostileCount = 0;
        // Count nearby hostile entities (simplified: within 16 blocks)
        // Real impl would scan entity list
        
        double healthFrac = 1.0;
        if (mobMaxHealthSupplier.getAsDouble() > 0) {
            healthFrac = mobHealthSupplier.getAsDouble() / mobMaxHealthSupplier.getAsDouble();
        }
        
        // Check if water (simplified: block below is water)
        WorldPos feet = new WorldPos(dimensionId, (int) mobPos.x(), (int) mobPos.y(), (int) mobPos.z());
        WorldPos below = new WorldPos(dimensionId, (int) mobPos.x(), (int) mobPos.y() - 1, (int) mobPos.z());
        boolean inWater = isOpaque.apply(below); // water is not opaque in MC but we treat as "liquid"
        
        return new SenseData(dist, playerPos, true, inWater,
            !inWater, healthFrac, hostileCount);
    }

    /**
     * GOAL_SELECT stage: picks a Goal based on SENSE data and RNG.
     */
    public static Goal goalSelect(SenseData sense, Random rng) {
        // Priority order (simplified):
        // 1. If health < 20% and player is near → FLEE
        // 2. If aggressive and player is within aggro range → ENGAGE
        // 3. If health < 50% and food nearby → EAT
        // 4. If water and not on ground → WANDER (swim)
        // 5. Otherwise → WANDER or IDLE
        
        if (sense.healthFraction() < 0.2 && sense.nearestPlayerDist() < 8.0) {
            return Goal.FLEE;
        }
        if (sense.nearestPlayerDist() < 16.0 && sense.nearestHostileCount() > 0) {
            return Goal.ENGAGE;
        }
        if (rng.nextDouble() < 0.02) { // 2% chance to idle each tick
            return Goal.IDLE;
        }
        if (sense.isInWater() && !sense.isOnGround()) {
            return Goal.WANDER;
        }
        return Goal.WANDER;
    }

    /**
     * PATHFIND stage: computes waypoints to the goal.
     * Uses A* on the block grid. Simplified for MVP: straight-line to target.
     */
    public static PathResult pathfind(
            Vec3 from, Vec3 target, int dimensionId,
            java.util.function.Function<WorldPos, Boolean> isOpaque,
            java.util.function.Function<Vec3, Vec3> nearestPlayerPos,
            Goal goal,
            int maxIterations) {
        
        if (goal == Goal.IDLE) {
            return new PathResult(from, List.of(from), true);
        }

        // Simplified: 8-point reachability check
        boolean reachable = isReachable(from, target, dimensionId, isOpaque);
        List<Vec3> waypoints = reachable ? List.of(target) : List.of();
        return new PathResult(target, waypoints, reachable);
    }

    private static boolean isReachable(Vec3 from, Vec3 to, int dimId,
                                      java.util.function.Function<WorldPos, Boolean> isOpaque) {
        // Simplified: straight line, check a few points
        double dist = from.distanceTo(to);
        if (dist < 0.1) return true;
        int steps = (int) Math.min(dist * 2, 32); // check every 0.5 blocks, max 32
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            double mx = from.x() + (to.x() - from.x()) * t;
            double my = from.y() + (to.y() - from.y()) * t;
            double mz = from.z() + (to.z() - from.z()) * t;
            WorldPos wp = new WorldPos(dimId, (int) mx, (int) my, (int) mz);
            if (isOpaque.apply(wp)) return false; // blocked
        }
        return true;
    }

    /**
     * ACT stage: advances the mob one tick toward the current waypoint.
     * Returns the velocity vector to write.
     */
    public static Vec3 act(
            Vec3 currentPos, Vec3 target, Goal goal,
            double speed, boolean inWater, boolean onGround,
            double yaw, Random rng) {
        
        double dx = target.x() - currentPos.x();
        double dz = target.z() - currentPos.z();
        double dist = Math.sqrt(dx * dx + dz * dz);
        
        if (dist < 0.1) return Vec3.ZERO;
        
        // Speed modifiers
        double effectiveSpeed = speed;
        if (inWater) effectiveSpeed *= 0.5;
        if (!onGround) effectiveSpeed *= 0.8; // air resistance
        
        // Normalize to speed
        double nx = dx / dist * effectiveSpeed;
        double nz = dz / dist * effectiveSpeed;
        
        double vy = 0.0;
        if (inWater) {
            vy = 0.1; // swim up
        } else if (goal == Goal.FLEE) {
            vy = onGround ? 0.42 : -0.08; // jump if on ground, gravity if in air
        }
        
        return new Vec3(nx, vy, nz);
    }
}
