package org.nebula.core.bucket;

import org.nebula.core.scheduler.DagBuilder;
import org.nebula.core.scheduler.DependencyEdge;
import org.nebula.core.scheduler.RWConflictDetector;
import org.nebula.core.scheduler.SccContractor;
import org.nebula.core.scheduler.TaskGraph;
import org.nebula.core.scheduler.TaskNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

/**
 * Builds the global task DAG using spatial hash pre-bucketing (arch doc §4.1–4.3).
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Assign each task to one or more 32-chunk buckets via {@link SpatialBucketIndex}.</li>
 *   <li>Build a per-bucket conflict graph in parallel (O(K²) per bucket, K ≪ N).</li>
 *   <li>Collect all edges, de-duplicate, then contract SCCs globally.</li>
 *   <li>Return a {@link TaskGraph} ready for layered execution.</li>
 * </ol>
 */
public final class BucketDagBuilder {

    private final SccContractor contractor;
    private final ForkJoinPool pool;
    private final int bucketSize;

    /** Uses the common ForkJoinPool, default SCC threshold, and default bucket size (32). */
    public BucketDagBuilder() {
        this(new SccContractor(), ForkJoinPool.commonPool(), BucketId.DEFAULT_BUCKET_SIZE);
    }

    public BucketDagBuilder(SccContractor contractor, ForkJoinPool pool) {
        this(contractor, pool, BucketId.DEFAULT_BUCKET_SIZE);
    }

    public BucketDagBuilder(SccContractor contractor, ForkJoinPool pool, int bucketSize) {
        this.contractor = contractor;
        this.pool = pool;
        this.bucketSize = bucketSize;
    }

    /** Convenience constructor for callers that only want to tune the bucket size. */
    public BucketDagBuilder(int bucketSize) {
        this(new SccContractor(), ForkJoinPool.commonPool(), bucketSize);
    }

    public int bucketSize() {
        return bucketSize;
    }

    /**
     * Builds the global TaskGraph from an arbitrary collection of tasks.
     * Tasks without spatial positions are placed in the GLOBAL bucket and
     * compared against every other task.
     */
    public TaskGraph build(Collection<TaskNode> tasks) {
        if (tasks.isEmpty()) {
            return new TaskGraph(Map.of(), Set.of());
        }

        long t0 = System.nanoTime();
        SpatialBucketIndex index = new SpatialBucketIndex(tasks, bucketSize);
        long tIndex = System.nanoTime();

        // Per-bucket conflict detection.  Use serial for small graphs to avoid
        // ForkJoinPool dispatch overhead — empty/idle worlds otherwise pay
        // ~2-3ms in parallel-stream startup for ~10 micro-buckets.
        Map<BucketId, List<DependencyEdge>> bucketEdges = new ConcurrentHashMap<>();
        Set<BucketId> bucketIds = index.bucketIds();
        if (tasks.size() < 256 || bucketIds.size() < 4) {
            for (BucketId bid : bucketIds) {
                List<TaskNode> bucketTasks = new ArrayList<>(index.tasksInBucket(bid));
                List<DependencyEdge> edges = detectConflicts(bucketTasks);
                if (!edges.isEmpty()) {
                    bucketEdges.put(bid, edges);
                }
            }
        } else {
            pool.submit(() ->
                bucketIds.parallelStream().forEach(bid -> {
                    List<TaskNode> bucketTasks = new ArrayList<>(index.tasksInBucket(bid));
                    List<DependencyEdge> edges = detectConflicts(bucketTasks);
                    if (!edges.isEmpty()) {
                        bucketEdges.put(bid, edges);
                    }
                })
            ).join();
        }
        long tBucket = System.nanoTime();

        // Global tasks conflict against everything that touches globals.
        // Tasks with empty global RW-sets cannot conflict with global tasks via
        // the global key-space, and their positional sets were already paired
        // up in the per-bucket pass — so we skip them here.
        Set<TaskNode> globalTasks = index.tasksInBucket(SpatialBucketIndex.GLOBAL_BUCKET);
        List<DependencyEdge> globalEdges = new ArrayList<>();
        if (!globalTasks.isEmpty()) {
            for (TaskNode global : globalTasks) {
                for (TaskNode other : tasks) {
                    if (global.taskId().equals(other.taskId())) continue;
                    if (!other.declaredRWSet().touchesGlobals() && !globalTasks.contains(other)) {
                        // Pure positional task — its positional sets were checked
                        // against the global task's positional sets in the bucket
                        // pass (if global has positional sets) or never (if not).
                        // No global-key intersection possible because other has none.
                        continue;
                    }
                    globalEdges.addAll(RWConflictDetector.edgesFor(global, other));
                }
            }
        }
        long tGlobal = System.nanoTime();

        // Merge all edges (de-duplicate via Set)
        Set<DependencyEdge> allEdges = new LinkedHashSet<>();
        bucketEdges.values().forEach(allEdges::addAll);
        allEdges.addAll(globalEdges);

        // SCC contraction → acyclic graph
        SccContractor.ContractionResult contracted = contractor.contract(tasks, allEdges);
        long tScc = System.nanoTime();

        Map<String, TaskNode> finalById = new LinkedHashMap<>();
        for (TaskNode t : contracted.tasks()) {
            finalById.put(t.taskId(), t);
        }
        long tEnd = System.nanoTime();

        BucketBuildStats.record(tIndex - t0, tBucket - tIndex, tGlobal - tBucket, tScc - tGlobal, tEnd - tScc);
        return new TaskGraph(Map.copyOf(finalById), Set.copyOf(contracted.edges()));
    }

    private static List<DependencyEdge> detectConflicts(List<TaskNode> tasks) {
        List<DependencyEdge> edges = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            for (int j = i + 1; j < tasks.size(); j++) {
                edges.addAll(RWConflictDetector.edgesFor(tasks.get(i), tasks.get(j)));
            }
        }
        return edges;
    }
}
