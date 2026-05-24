package org.nebula.entity;

import org.nebula.core.state.WorldPos;

/**
 * Immutable snapshot of a fluid block's state at tick start (arch doc §8.2).
 *
 * @param pos       world position of the fluid source/flow block
 * @param type      fluid task type (water or lava)
 * @param depth     current fluid depth (0-7 for water, 0-3 for lava)
 * @param isSource  whether this is a source block (infinite supply)
 */
public record FluidSnapshot(
    WorldPos pos,
    FluidTaskType type,
    int depth,
    boolean isSource
) {
    public static FluidSnapshot water(WorldPos pos, int depth, boolean isSource) {
        return new FluidSnapshot(pos, FluidTaskType.WATER_FLOW, depth, isSource);
    }

    public static FluidSnapshot lava(WorldPos pos, int depth, boolean isSource) {
        return new FluidSnapshot(pos, FluidTaskType.LAVA_FLOW, depth, isSource);
    }

    public String taskId() {
        return type.taskType() + "@" + pos.dimensionId() + ":" + pos.x() + "," + pos.y() + "," + pos.z();
    }

    public WorldPos down() {
        return new WorldPos(pos.dimensionId(), pos.x(), pos.y() - 1, pos.z());
    }

    public WorldPos north() {
        return new WorldPos(pos.dimensionId(), pos.x(), pos.y(), pos.z() - 1);
    }

    public WorldPos south() {
        return new WorldPos(pos.dimensionId(), pos.x(), pos.y(), pos.z() + 1);
    }

    public WorldPos east() {
        return new WorldPos(pos.dimensionId(), pos.x() + 1, pos.y(), pos.z());
    }

    public WorldPos west() {
        return new WorldPos(pos.dimensionId(), pos.x() - 1, pos.y(), pos.z());
    }
}
