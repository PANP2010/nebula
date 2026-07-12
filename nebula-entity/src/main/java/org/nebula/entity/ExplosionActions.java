package org.nebula.entity;

import org.nebula.core.math.Vec3;
import org.nebula.core.state.WorldPos;

import java.util.List;

/** Pure explosion actions used to checker-test declared footprints before live NMS wiring. */
public final class ExplosionActions {

    private ExplosionActions() {}

    /** Reads every candidate block in one ray group. */
    public static ExplosionAction rayTrace(List<WorldPos> blocks) {
        List<WorldPos> footprint = List.copyOf(blocks);
        return ctx -> footprint.forEach(ctx::readBlock);
    }

    /** Reads and clears every block in one destruction group, consuming one world RNG call per block. */
    public static ExplosionAction blockDestroy(List<WorldPos> blocks) {
        List<WorldPos> footprint = List.copyOf(blocks);
        return ctx -> {
            for (WorldPos pos : footprint) {
                ctx.readBlock(pos);
                ctx.nextRandomInt(Integer.MAX_VALUE);
                ctx.writeBlock(pos, null);
            }
        };
    }

    /** Reads position/health and writes health/velocity for one affected entity. */
    public static ExplosionAction entityDamage(long entityId) {
        return ctx -> {
            Vec3 position = ctx.readEntityVec(entityId, "position");
            double health = ctx.readEntityScalar(entityId, "health");
            ctx.nextRandomInt(Integer.MAX_VALUE);
            ctx.nextRandomInt(Integer.MAX_VALUE);
            ctx.writeEntityScalar(entityId, "health", Math.max(0.0, health - 1.0));
            ctx.writeEntityVec(entityId, "velocity", position.scale(0.01));
        };
    }
}
