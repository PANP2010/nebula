package org.nebula.core.bucket;

import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Assigns each TaskNode to every BucketId touched by its RW-set
 * (both read and write blocks).  Tasks with no block positions at all
 * are placed in a special GLOBAL bucket for later handling.
 */
public final class SpatialBucketIndex {

    /** Sentinel bucket for tasks that declare no spatial positions. */
    public static final BucketId GLOBAL_BUCKET = new BucketId(Integer.MIN_VALUE, 0, 0, 0);

    private final Map<BucketId, Set<TaskNode>> buckets = new HashMap<>();
    private final int bucketSize;

    public SpatialBucketIndex(Collection<TaskNode> tasks) {
        this(tasks, BucketId.DEFAULT_BUCKET_SIZE);
    }

    public SpatialBucketIndex(Collection<TaskNode> tasks, int bucketSize) {
        this.bucketSize = bucketSize;
        for (TaskNode task : tasks) {
            Set<BucketId> taskBuckets = bucketsFor(task, bucketSize);
            if (taskBuckets.isEmpty()) {
                buckets.computeIfAbsent(GLOBAL_BUCKET, k -> new LinkedHashSet<>()).add(task);
            } else {
                for (BucketId bid : taskBuckets) {
                    buckets.computeIfAbsent(bid, k -> new LinkedHashSet<>()).add(task);
                }
            }
        }
    }

    public int bucketSize() {
        return bucketSize;
    }

    /** All bucket IDs that have at least one task. */
    public Set<BucketId> bucketIds() {
        return Collections.unmodifiableSet(buckets.keySet());
    }

    /** Tasks assigned to the given bucket (may be empty). */
    public Set<TaskNode> tasksInBucket(BucketId bid) {
        return Collections.unmodifiableSet(buckets.getOrDefault(bid, Set.of()));
    }

    /** Returns all distinct BucketIds touched by a task's spatial RW-set at the default size. */
    public static Set<BucketId> bucketsFor(TaskNode task) {
        return bucketsFor(task, BucketId.DEFAULT_BUCKET_SIZE);
    }

    /** Returns all distinct BucketIds touched by a task's spatial RW-set at the given size. */
    public static Set<BucketId> bucketsFor(TaskNode task, int bucketSize) {
        Set<BucketId> result = new LinkedHashSet<>();
        for (WorldPos pos : task.declaredRWSet().readBlocks()) {
            result.add(BucketId.of(pos, bucketSize));
        }
        for (WorldPos pos : task.declaredRWSet().writtenBlocks()) {
            result.add(BucketId.of(pos, bucketSize));
        }
        return result;
    }
}
