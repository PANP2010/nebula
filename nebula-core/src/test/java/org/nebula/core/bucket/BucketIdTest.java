package org.nebula.core.bucket;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BucketIdTest {

    @Test
    void bucketIdForOrigin() {
        WorldPos origin = new WorldPos(0, 0, 0, 0);
        BucketId id = BucketId.of(origin);
        assertEquals(new BucketId(0, 0, 0, 0), id);
    }

    @Test
    void bucketIdFloorDivision() {
        // pos(31,0,31) → bucket(0,0,0) because floor(31/32)=0
        BucketId id = BucketId.of(new WorldPos(0, 31, 0, 31));
        assertEquals(new BucketId(0, 0, 0, 0), id);
    }

    @Test
    void bucketIdAtBoundary() {
        // pos(32,0,32) → bucket(1,0,1)
        BucketId id = BucketId.of(new WorldPos(0, 32, 0, 32));
        assertEquals(new BucketId(0, 1, 0, 1), id);
    }

    @Test
    void negativeCoordsUsesFloorDiv() {
        // pos(-1,0,0) → floor(-1/32) = -1 → bucket(-1, 0, 0)
        BucketId id = BucketId.of(new WorldPos(0, -1, 0, 0));
        assertEquals(new BucketId(0, -1, 0, 0), id);
    }

    @Test
    void dimensionIdPreserved() {
        BucketId id = BucketId.of(new WorldPos(2, 0, 0, 0));
        assertEquals(2, id.dimensionId());
    }

    @Test
    void differentDimensionsYieldDifferentBuckets() {
        BucketId b0 = BucketId.of(new WorldPos(0, 0, 0, 0));
        BucketId b1 = BucketId.of(new WorldPos(1, 0, 0, 0));
        assertNotEquals(b0, b1);
    }

    @Test
    void spatialBucketIndexAssignsAllPositions() {
        // Task reads pos(0) and writes pos(33): should appear in bucket(0,0,0,0) and bucket(0,1,0,0)
        RWSet rw = RWSet.builder()
            .readBlock(new WorldPos(0, 0, 0, 0))
            .writeBlock(new WorldPos(0, 33, 0, 0))
            .build();
        TaskNode task = TaskNode.inert("T", "test", rw);

        Set<BucketId> buckets = SpatialBucketIndex.bucketsFor(task);
        assertEquals(2, buckets.size());
        assertTrue(buckets.contains(new BucketId(0, 0, 0, 0)));
        assertTrue(buckets.contains(new BucketId(0, 1, 0, 0)));
    }

    @Test
    void taskWithNoPositionsGoesToGlobalBucket() {
        RWSet rw = RWSet.empty();
        TaskNode task = TaskNode.inert("T", "global", rw);

        SpatialBucketIndex index = new SpatialBucketIndex(java.util.List.of(task));
        assertTrue(index.tasksInBucket(SpatialBucketIndex.GLOBAL_BUCKET).contains(task));
    }
}
