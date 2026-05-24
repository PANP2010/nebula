package org.nebula.entity;

import org.nebula.core.state.WorldPos;

import java.util.List;
import java.util.Set;

/**
 * Immutable snapshot of an explosion event at tick start (arch doc §9.1).
 *
 * @param center      detonation center position
 * @param power       explosion power (TNT=4.0, creeper=3.0, charged creeper=6.0)
 * @param sourceId    entity ID of the explosion source (-1 for block explosions)
 * @param affectedBlocks pre-computed set of block positions within blast radius
 * @param affectedEntities entity IDs within blast radius
 */
public record ExplosionSnapshot(
    WorldPos center,
    float power,
    long sourceId,
    Set<WorldPos> affectedBlocks,
    List<Long> affectedEntities
) {
    public ExplosionSnapshot {
        affectedBlocks = Set.copyOf(affectedBlocks);
        affectedEntities = List.copyOf(affectedEntities);
    }

    public String explosionId() {
        return "explosion@" + center.dimensionId() + ":"
            + center.x() + "," + center.y() + "," + center.z();
    }

    public int rayCount() {
        return Math.max(1, (int) (power * power * 25));
    }
}
