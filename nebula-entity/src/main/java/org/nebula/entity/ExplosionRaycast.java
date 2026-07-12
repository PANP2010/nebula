package org.nebula.entity;

import org.nebula.core.math.Vec3;
import org.nebula.core.state.WorldPos;

/**
 * Vanilla-style raycast for explosion propagation (arch doc §9.1, P1.8.1).
 *
 * <p>Performs a step-by-step raycast from explosion center outward, stopping at the
 * first solid occluder. Each step reads one block. The raycast is deterministic and
 * reads only the blocks along the ray path, matching the declared RW-set footprint.
 *
 * <p>The raycast returns:
 * <ul>
 *   <li>All blocks that are reached before hitting an occluder</li>
 *   <li>The final occluder block (if any)</li>
 *   <li>Whether the raycast was stopped by an entity (hitscan)</li>
 * </ul>
 */
public final class ExplosionRaycast {

    /** Maximum ray steps before giving up. */
    private static final int MAX_RAY_STEPS = 256;

    /** Fraction of explosion radius that determines step size. */
    private static final double STEP_SIZE = 0.3;

    private ExplosionRaycast() {}

    /**
     * Result of a raycast operation.
     * @param reached   blocks the ray passed through (before hitting occluder)
     * @param hitBlock  the first solid occluder, or null if ray escaped
     * @param hitEntity whether an entity was hit
     */
    public record RaycastResult(
        WorldPos[] reached,
        WorldPos hitBlock,
        boolean hitEntity
    ) {}

    /**
     * Casts a ray from {@code origin} in {@code direction} at the given power level.
     * Reads blocks along the path (one per step). Deterministic: same inputs always
     * produce the same path.
     *
     * @param origin    explosion center (world position + dimension)
     * @param direction unit direction vector (not null, not necessarily normalised)
     * @param power     explosion power (determines max distance)
     * @param solidTest oracle: returns true if the block at the given position is solid
     *                  (opaque, blocks explosion ray); null means nothing is solid
     * @return raycast result
     */
    public static RaycastResult cast(WorldPos origin, Vec3 direction, double power,
                                     java.util.function.Predicate<WorldPos> solidTest) {
        java.util.List<WorldPos> reached = new java.util.ArrayList<>();
        int maxSteps = Math.min(MAX_RAY_STEPS, (int) Math.ceil(power / STEP_SIZE) + 1);

        // Normalise the direction vector
        double len = direction.distanceTo(Vec3.ZERO);
        if (len < 1e-9) return new RaycastResult(new WorldPos[0], null, false);
        double ndx = direction.x() / len;
        double ndy = direction.y() / len;
        double ndz = direction.z() / len;

        double x = origin.x() + 0.5;
        double y = origin.y() + 0.5;
        double z = origin.z() + 0.5;
        int dim = origin.dimensionId();

        for (int step = 0; step < maxSteps; step++) {
            x += ndx * STEP_SIZE;
            y += ndy * STEP_SIZE;
            z += ndz * STEP_SIZE;

            int bx = (int) Math.floor(x);
            int by = (int) Math.floor(y);
            int bz = (int) Math.floor(z);
            WorldPos pos = new WorldPos(dim, bx, by, bz);

            if (solidTest != null && solidTest.test(pos)) {
                // Solid occluder — explosion stops here
                return new RaycastResult(
                    reached.toArray(new WorldPos[0]),
                    pos,
                    false
                );
            }
            reached.add(pos);
        }

        return new RaycastResult(
            reached.toArray(new WorldPos[0]),
            null,
            false
        );
    }

    /**
     * Casts multiple rays in a hemisphere around the explosion center.
     * Used to generate the full affected-block list for an explosion.
     *
     * @param center          explosion center (world position + dimension)
     * @param directionBias   upward bias for the hemisphere (e.g. 0.5,0.5,0.5)
     * @param power           explosion power
     * @param raysPerFullCircle number of azimuthal angles (vanilla uses 8)
     * @param solidTest       oracle for solid blocks (null = nothing blocks)
     * @return all blocks affected by any ray
     */
    public static java.util.List<WorldPos> castHemisphere(
            WorldPos center, Vec3 directionBias, double power,
            int raysPerFullCircle,
            java.util.function.Predicate<WorldPos> solidTest) {
        java.util.Set<WorldPos> affected = new java.util.HashSet<>();

        // Vanilla-style: 8 azimuthal angles × 3 elevation bands = 24 rays
        for (int a = 0; a < raysPerFullCircle; a++) {
            double angle = 2 * Math.PI * a / raysPerFullCircle;
            for (int v = 0; v <= 2; v++) {
                // Elevation from -30° to +30° relative to bias
                double phi = Math.PI / 6.0 - (v * Math.PI / 6.0);
                double dx = Math.cos(angle) * Math.cos(phi) + directionBias.x();
                double dy = Math.sin(phi) + directionBias.y();
                double dz = Math.sin(angle) * Math.cos(phi) + directionBias.z();
                Vec3 dir = new Vec3(dx, dy, dz);
                RaycastResult result = cast(center, dir, power, solidTest);

                for (WorldPos p : result.reached()) {
                    affected.add(p);
                }
                if (result.hitBlock() != null) {
                    affected.add(result.hitBlock());
                }
            }
        }

        return new java.util.ArrayList<>(affected);
    }
}
