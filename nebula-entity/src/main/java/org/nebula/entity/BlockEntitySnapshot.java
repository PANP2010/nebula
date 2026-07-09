package org.nebula.entity;

import org.nebula.core.state.WorldPos;

/**
 * Immutable snapshot of a block entity's position and type at tick start (arch doc §3.3).
 *
 * <p>Used to construct RW-set templates for block entity tasks without holding live
 * CraftBukkit references, enabling task-node creation on any thread.
 *
 * @param pos          world position of the block entity
 * @param type         block entity task type
 * @param slotCount    number of inventory slots (hopper=5, furnace=3, chest=27, etc.)
 * @param facingX      directional offset X (hopper output direction)
 * @param facingY      directional offset Y
 * @param facingZ      directional offset Z
 */
public record BlockEntitySnapshot(
    WorldPos pos,
    BlockEntityTaskType type,
    int slotCount,
    int facingX,
    int facingY,
    int facingZ
) {
    public static BlockEntitySnapshot hopper(WorldPos pos, int facingX, int facingY, int facingZ) {
        return new BlockEntitySnapshot(pos, BlockEntityTaskType.HOPPER, 5, facingX, facingY, facingZ);
    }

    public static BlockEntitySnapshot furnace(WorldPos pos) {
        return new BlockEntitySnapshot(pos, BlockEntityTaskType.FURNACE, 3, 0, 0, 0);
    }

    public static BlockEntitySnapshot brewingStand(WorldPos pos) {
        return new BlockEntitySnapshot(pos, BlockEntityTaskType.BREWING_STAND, 5, 0, 0, 0);
    }

    public static BlockEntitySnapshot dropper(WorldPos pos, int facingX, int facingY, int facingZ) {
        return new BlockEntitySnapshot(pos, BlockEntityTaskType.DROPPER, 9, facingX, facingY, facingZ);
    }

    public static BlockEntitySnapshot dispenser(WorldPos pos, int facingX, int facingY, int facingZ) {
        return new BlockEntitySnapshot(pos, BlockEntityTaskType.DISPENSER, 9, facingX, facingY, facingZ);
    }

    /**
     * Builds the snapshot with the correct slot count for a classified
     * {@link BlockEntityTaskType}, applying the given facing only to the directional
     * types (hopper/dropper/dispenser); furnace and brewing stand ignore facing.
     *
     * <p>This is the seam the live seed listener uses once it has classified a block
     * (via {@link BlockEntityClassifier}): it turns a type + position + facing into the
     * right per-type snapshot, instead of stamping every endpoint as a hopper.
     *
     * @throws NullPointerException if {@code type} is null
     */
    public static BlockEntitySnapshot forType(BlockEntityTaskType type, WorldPos pos,
                                              int facingX, int facingY, int facingZ) {
        return switch (type) {
            case HOPPER -> hopper(pos, facingX, facingY, facingZ);
            case DROPPER -> dropper(pos, facingX, facingY, facingZ);
            case DISPENSER -> dispenser(pos, facingX, facingY, facingZ);
            case FURNACE -> furnace(pos);
            case BREWING_STAND -> brewingStand(pos);
        };
    }

    public WorldPos outputPos() {
        return new WorldPos(pos.dimensionId(), pos.x() + facingX, pos.y() + facingY, pos.z() + facingZ);
    }

    public WorldPos abovePos() {
        return new WorldPos(pos.dimensionId(), pos.x(), pos.y() + 1, pos.z());
    }

    public String taskId() {
        return type.taskType() + "@" + pos.dimensionId() + ":" + pos.x() + "," + pos.y() + "," + pos.z();
    }
}
