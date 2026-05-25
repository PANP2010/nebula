package org.nebula.core.bucket;

import org.nebula.core.state.WorldPos;

/**
 * Identifies a spatial build bucket (arch doc §4.1).
 * BucketID(p, size) = (⌊x/size⌋, ⌊y/size⌋, ⌊z/size⌋, dim)
 *
 * <p>Bucket size is tunable per subsystem: redstone uses ~16 (signal range 15+1)
 * while physics/AI may use larger. Within a single {@link SpatialBucketIndex}
 * all tasks must share the same size for the IDs to be comparable.
 */
public record BucketId(int dimensionId, int bx, int by, int bz) implements Comparable<BucketId> {

    /** Default bucket edge length (blocks) when none specified. */
    public static final int DEFAULT_BUCKET_SIZE = 32;

    /** Returns the bucket that contains the given position at the default size. */
    public static BucketId of(WorldPos pos) {
        return of(pos, DEFAULT_BUCKET_SIZE);
    }

    /** Returns the bucket that contains {@code pos} at the given edge length. */
    public static BucketId of(WorldPos pos, int bucketSize) {
        if (bucketSize < 1) {
            throw new IllegalArgumentException("bucketSize must be >= 1: " + bucketSize);
        }
        return new BucketId(
            pos.dimensionId(),
            Math.floorDiv(pos.x(), bucketSize),
            Math.floorDiv(pos.y(), bucketSize),
            Math.floorDiv(pos.z(), bucketSize)
        );
    }

    @Override
    public int compareTo(BucketId other) {
        int d = Integer.compare(dimensionId, other.dimensionId);
        if (d != 0) return d;
        int x = Integer.compare(bx, other.bx);
        if (x != 0) return x;
        int y = Integer.compare(by, other.by);
        if (y != 0) return y;
        return Integer.compare(bz, other.bz);
    }
}
