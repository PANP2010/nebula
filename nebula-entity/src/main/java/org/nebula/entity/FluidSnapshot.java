package org.nebula.entity;

import org.nebula.core.state.WorldPos;

/**
 * Immutable snapshot of a fluid block's state at tick start (arch doc §8.2).
 *
 * <p>{@code passable} is the vanilla fluid passability flag: {@code true} for any fluid block
 * (water/lava), since fluids themselves allow further flow into them. Solid neighbours expose
 * {@code passable == false} through their non-{@code FluidSnapshot} values.
 *
 * @param pos       world position of the fluid source/flow block
 * @param type      fluid task type (water or lava)
 * @param depth     Bukkit legacy fluid level: source 0, horizontal flow 1-7, falling 8-15
 * @param isSource  whether this is a source block (infinite supply)
 * @param passable  whether flow may enter this block from a neighbour
 */
public record FluidSnapshot(
    WorldPos pos,
    FluidTaskType type,
    int depth,
    boolean isSource,
    boolean passable
) {
    public static FluidSnapshot water(WorldPos pos, int depth, boolean isSource) {
        return new FluidSnapshot(pos, FluidTaskType.WATER_FLOW, depth, isSource, true);
    }

    public static FluidSnapshot lava(WorldPos pos, int depth, boolean isSource) {
        return new FluidSnapshot(pos, FluidTaskType.LAVA_FLOW, depth, isSource, true);
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