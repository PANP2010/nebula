package org.nebula.core.player;

import org.nebula.core.math.Vec3;
import org.nebula.core.state.WorldPos;

import java.util.UUID;

/**
 * Immutable snapshot of a player's state at the start of a tick.
 * Mirrors {@link org.nebula.entity.EntitySnapshot} for the player subsystem.
 */
public record PlayerSnapshot(
    UUID playerId,
    int x,
    int y,
    int z,
    int bucketX,
    int bucketZ,
    int dimensionId
) {
    private static final int BUCKET_SIZE = 32;

    public static PlayerSnapshot of(UUID playerId, double x, double y, double z, int dimensionId) {
        return new PlayerSnapshot(
            playerId,
            (int) Math.floor(x),
            (int) Math.floor(y),
            (int) Math.floor(z),
            Math.floorDiv((int) Math.floor(x), BUCKET_SIZE),
            Math.floorDiv((int) Math.floor(z), BUCKET_SIZE),
            dimensionId
        );
    }

    public String taskId(String taskType) {
        return taskType + "@" + dimensionId + ":" + playerId + ":" + x + "," + y + "," + z;
    }

    public WorldPos worldPos() {
        return new WorldPos(dimensionId, x, y, z);
    }

    public Vec3 vec3() {
        return new Vec3(x + 0.5, y + 0.5, z + 0.5);
    }
}