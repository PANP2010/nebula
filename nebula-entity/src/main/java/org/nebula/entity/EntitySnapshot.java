package org.nebula.entity;

/**
 * Immutable snapshot of an entity's physical state at the start of a tick (arch doc §6.2).
 *
 * <p>Used to construct RW-set templates without holding a live Bukkit entity reference,
 * so task nodes can be built on any thread.
 *
 * @param entityId   unique entity ID (Minecraft network entity ID)
 * @param x          position x (block coordinate, truncated)
 * @param y          position y
 * @param z          position z
 * @param bucketX    32-chunk bucket x index (derived from x)
 * @param bucketZ    32-chunk bucket z index (derived from z)
 * @param dimensionId dimension ordinal (0 = overworld, -1 = nether, 1 = end)
 */
public record EntitySnapshot(
    long entityId,
    int x,
    int y,
    int z,
    int bucketX,
    int bucketZ,
    int dimensionId
) {
    private static final int BUCKET_SIZE = 32;

    /** Constructs a snapshot, computing bucket indices from position. */
    public static EntitySnapshot of(long entityId, int x, int y, int z, int dimensionId) {
        return new EntitySnapshot(
            entityId, x, y, z,
            Math.floorDiv(x, BUCKET_SIZE),
            Math.floorDiv(z, BUCKET_SIZE),
            dimensionId
        );
    }

    /** Canonical task ID for this entity: {@code <taskType>@<dim>:<entityId>:<x>,<y>,<z>} */
    public String taskId(EntityTaskType type) {
        return type.taskType() + "@" + dimensionId + ":" + entityId + ":" + x + "," + y + "," + z;
    }
}
