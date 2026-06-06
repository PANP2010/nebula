package org.nebula.core.bucket;

import org.nebula.core.rw.RWSet;
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

        // Global tasks: tasks in GLOBAL_BUCKET (no positional reads/writes)
        // PLUS any task whose RW-set touches global keys, must be checked
        // against each other for ordering. We pre-partition into a small
        // "globalTouching" list once. globalTasks is iterated as a List for
        // O(1) indexed access (avoids HashSet.contains O(1) but with overhead).
        Set<TaskNode> globalTasks = index.tasksInBucket(SpatialBucketIndex.GLOBAL_BUCKET);
        List<DependencyEdge> globalEdges = new ArrayList<>();
        if (!globalTasks.isEmpty()) {
            List<TaskNode> globalTouching = new ArrayList<>(globalTasks.size() + 8);
            for (TaskNode t : tasks) {
                if (globalTasks.contains(t) || t.declaredRWSet().touchesGlobals()) {
                    globalTouching.add(t);
                }
            }
            for (TaskNode global : globalTasks) {
                for (TaskNode other : globalTouching) {
                    if (global == other || global.taskId().equals(other.taskId())) continue;
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
        // TaskGraph constructor will Map.copyOf / Set.copyOf — pass through
        // the mutable collection directly to avoid a double copy.
        return new TaskGraph(finalById, contracted.edges() instanceof Set<DependencyEdge> setEdges ? setEdges : new LinkedHashSet<>(contracted.edges()));
    }

    private static final java.util.concurrent.atomic.AtomicLong FAST_PATH_HITS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong FAST_PATH_MISSES = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong INDEXED_PATH_HITS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong INDEXED_CANDIDATE_PAIRS = new java.util.concurrent.atomic.AtomicLong();

    public static long fastPathHits() { return FAST_PATH_HITS.get(); }
    public static long fastPathMisses() { return FAST_PATH_MISSES.get(); }
    public static long indexedPathHits() { return INDEXED_PATH_HITS.get(); }
    public static long indexedCandidatePairs() { return INDEXED_CANDIDATE_PAIRS.get(); }
    public static void resetFastPathCounters() {
        FAST_PATH_HITS.set(0);
        FAST_PATH_MISSES.set(0);
        INDEXED_PATH_HITS.set(0);
        INDEXED_CANDIDATE_PAIRS.set(0);
    }

    private static List<DependencyEdge> detectConflicts(List<TaskNode> tasks) {
        // Hot path: in entity-heavy ticks every task in a positional bucket
        // is "self-only entity write" (writeEntity(self.*) + readBlock(pos)
        // for affinity). Such tasks can never conflict with each other:
        // - writes are to distinct entityIds
        // - readBlock × readBlock is not a write conflict
        // - no globals, BEs, or block writes
        boolean allSelfOnly = !tasks.isEmpty();
        for (TaskNode t : tasks) {
            if (!t.declaredRWSet().isSelfOnlyEntityWrite()) {
                allSelfOnly = false;
                break;
            }
        }
        if (allSelfOnly) {
            FAST_PATH_HITS.incrementAndGet();
            return List.of();
        }
        FAST_PATH_MISSES.incrementAndGet();
        INDEXED_PATH_HITS.incrementAndGet();

        Map<Object, List<TaskNode>> blockReads = new LinkedHashMap<>();
        Map<Object, List<TaskNode>> blockWrites = new LinkedHashMap<>();
        List<TaskNode> broadTasks = new ArrayList<>();
        for (TaskNode task : tasks) {
            RWSet rw = task.declaredRWSet();
            if (!rw.readBlockEntities().isEmpty()
                || !rw.writtenBlockEntities().isEmpty()
                || !rw.readEntityFields().isEmpty()
                || !rw.writtenEntityFields().isEmpty()
                || !rw.readGlobalKeys().isEmpty()
                || !rw.writtenGlobalKeys().isEmpty()) {
                broadTasks.add(task);
            }
            for (var pos : rw.readBlocks()) {
                blockReads.computeIfAbsent(pos, ignored -> new ArrayList<>()).add(task);
            }
            for (var pos : rw.writtenBlocks()) {
                blockWrites.computeIfAbsent(pos, ignored -> new ArrayList<>()).add(task);
            }
        }

        Map<String, TaskNode> byId = new LinkedHashMap<>();
        for (TaskNode task : tasks) {
            byId.put(task.taskId(), task);
        }

        Set<PairKey> candidates = new LinkedHashSet<>();
        for (Map.Entry<Object, List<TaskNode>> writes : blockWrites.entrySet()) {
            List<TaskNode> readers = blockReads.get(writes.getKey());
            if (readers != null) {
                addCandidatePairs(candidates, writes.getValue(), readers);
            }
            addCandidatePairs(candidates, writes.getValue(), writes.getValue());
        }
        addCandidatePairs(candidates, broadTasks, tasks);

        List<PairKey> orderedPairs = candidates.stream().sorted().toList();
        INDEXED_CANDIDATE_PAIRS.addAndGet(orderedPairs.size());
        List<DependencyEdge> edges = new ArrayList<>();
        for (PairKey pair : orderedPairs) {
            edges.addAll(RWConflictDetector.edgesFor(byId.get(pair.leftTaskId()), byId.get(pair.rightTaskId())));
        }
        return edges;
    }

    private static void addCandidatePairs(Set<PairKey> candidates, List<TaskNode> leftTasks, List<TaskNode> rightTasks) {
        for (TaskNode left : leftTasks) {
            for (TaskNode right : rightTasks) {
                if (left.taskId().equals(right.taskId())) continue;
                candidates.add(PairKey.of(left, right));
            }
        }
    }

    private record PairKey(String leftTaskId, String rightTaskId) implements Comparable<PairKey> {
        static PairKey of(TaskNode left, TaskNode right) {
            if (left.taskId().compareTo(right.taskId()) <= 0) {
                return new PairKey(left.taskId(), right.taskId());
            }
            return new PairKey(right.taskId(), left.taskId());
        }

        @Override
        public int compareTo(PairKey other) {
            int byLeft = leftTaskId.compareTo(other.leftTaskId);
            if (byLeft != 0) return byLeft;
            return rightTaskId.compareTo(other.rightTaskId);
        }
    }
}
