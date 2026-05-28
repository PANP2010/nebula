package org.nebula.core.scheduler;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cross-tick DAG cache.
 *
 * <p>Most ticks present essentially the same task set as the previous tick:
 * the same entities, BEs, and global tasks. Their RWSets are identical
 * (the only thing that changes per-tick is the {@code TaskAction} lambda,
 * which closes over the current {@code Entity}/{@code BlockEntity} instance).
 *
 * <p>This cache stores the previous tick's {@code (fingerprint, edges,
 * layers)} tuple. If the next tick's input has the same fingerprint, we
 * skip BucketDagBuilder + SccContractor entirely and reuse the cached
 * graph structure, swapping in fresh TaskNode references.
 *
 * <p>Fingerprint is a 64-bit FNV-1a hash over sorted {@code (taskId,
 * rwSetIdentityHash)}. Identity hash is fine because RWSet objects are
 * cached as static finals at construction sites (e.g. RegionTickDecomposer's
 * GLOBAL_RW, BucketDagBuilder's per-task entity RWSet) — same shape ⇒ same
 * RWSet instance ⇒ same identity.
 *
 * <p>Cache is per-builder-instance, not global, so multiple TickPipelines
 * (e.g. test fixtures) don't collide.
 *
 * <p>Counters available via {@link #hits()} / {@link #misses()} for
 * /nebula bench.
 */
public final class TaskGraphCache {

    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong MISSES = new AtomicLong();

    private long lastFingerprint = 0L;
    private Set<DependencyEdge> lastEdges = Set.of();
    private List<List<String>> lastLayers = List.of();
    private boolean primed = false;

    public TaskGraphCache() {}

    /**
     * Compute a 64-bit fingerprint over the input task collection.
     * Order-independent: caller does NOT need to sort.
     *
     * <p>Per-task contribution mixes the taskId chars with the RWSet's
     * {@code hashCode()} (which is record-derived, so it's content-based —
     * two RWSets with identical fields produce the same hash regardless of
     * whether they're the same object). This means even when entity task
     * builders allocate fresh RWSet instances every tick, the cache hits
     * as long as the underlying RW shape is unchanged.
     */
    public static long fingerprint(Collection<TaskNode> tasks) {
        // FNV-1a 64-bit. We XOR per-task contributions so insertion order
        // doesn't affect the result — input collections may iterate in
        // different orders across ticks.
        long h = 0xcbf29ce484222325L;
        for (TaskNode task : tasks) {
            long taskHash = 0xcbf29ce484222325L;
            String id = task.taskId();
            for (int i = 0, n = id.length(); i < n; i++) {
                taskHash ^= id.charAt(i);
                taskHash *= 0x100000001b3L;
            }
            // Content hash of the RWSet — RWSet is a record so hashCode is
            // derived from its fields. Same shape ⇒ same hash even across
            // freshly-allocated instances per tick.
            taskHash ^= task.declaredRWSet().hashCode();
            taskHash *= 0x100000001b3L;
            h ^= taskHash;
        }
        return h;
    }

    /**
     * Try to reuse a previously cached graph for this task collection.
     * Returns {@code null} on cache miss; caller must then build fresh and
     * call {@link #put(long, Set, List)}.
     *
     * <p>On hit, returns a fresh {@link TaskGraph} that wires the cached
     * edges/layers to the current TaskNode references (with fresh actions).
     */
    public TaskGraph tryHit(Collection<TaskNode> tasks, long fp) {
        if (!primed || fp != lastFingerprint) {
            MISSES.incrementAndGet();
            return null;
        }
        HITS.incrementAndGet();
        // Build fresh tasks map (current TaskNode references with fresh
        // action lambdas) but reuse the cached edges set.
        Map<String, TaskNode> byId = new LinkedHashMap<>(tasks.size());
        for (TaskNode t : tasks) {
            byId.put(t.taskId(), t);
        }
        return new TaskGraph(byId, lastEdges);
    }

    /** Store a freshly computed graph for next-tick reuse. */
    public void put(long fp, Set<DependencyEdge> edges, List<List<String>> layers) {
        this.lastFingerprint = fp;
        this.lastEdges = edges;
        this.lastLayers = layers;
        this.primed = true;
    }

    public List<List<String>> cachedLayers() {
        return lastLayers;
    }

    /** Drops the cache (used in tests / when configuration changes). */
    public void clear() {
        lastFingerprint = 0L;
        lastEdges = Set.of();
        lastLayers = List.of();
        primed = false;
    }

    public static long hits()   { return HITS.get(); }
    public static long misses() { return MISSES.get(); }

    public static double hitRate() {
        long h = HITS.get();
        long m = MISSES.get();
        long total = h + m;
        return total == 0 ? 0.0 : h / (double) total;
    }

    public static void resetCounters() {
        HITS.set(0);
        MISSES.set(0);
    }

    public static String summary() {
        return String.format(
            "TaskGraph cache: hits=%d, misses=%d, hit-rate=%.1f%%",
            hits(), misses(), hitRate() * 100.0
        );
    }
}
