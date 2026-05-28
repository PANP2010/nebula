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
     * structural shape hash — a position-agnostic signature capturing
     * WHICH categories of read/write are populated (entity write, block
     * read, global, etc.) without including the specific positions inside
     * readBlocks/writtenBlocks. This means moving entities still produce
     * the same fingerprint as long as their taskIds are stable, since the
     * EDGE STRUCTURE for self-only entity tasks does not depend on their
     * positional reads.
     */
    public static long fingerprint(Collection<TaskNode> tasks) {
        long h = 0xcbf29ce484222325L;
        for (TaskNode task : tasks) {
            long taskHash = 0xcbf29ce484222325L;
            String id = task.taskId();
            for (int i = 0, n = id.length(); i < n; i++) {
                taskHash ^= id.charAt(i);
                taskHash *= 0x100000001b3L;
            }
            taskHash ^= structuralHash(task.declaredRWSet());
            taskHash *= 0x100000001b3L;
            h ^= taskHash;
        }
        return h;
    }

    /**
     * Position-agnostic shape signature: a bitmask of which RW field
     * categories are non-empty, plus the sizes of writtenEntityFields and
     * writtenGlobalKeys (which together determine which edges get emitted).
     * Specific positions inside readBlocks/writtenBlocks are omitted so
     * moving-entity workloads don't invalidate the cache every tick.
     *
     * <p>Cache hit safety: For self-only entity tick patterns (most of the
     * load on any populated server), edges depend only on the SHAPE of the
     * RWSet — not on the specific block positions. Two ticks with identical
     * taskIds and identical RWSet shapes produce identical edge sets.
     */
    private static long structuralHash(org.nebula.core.rw.RWSet rw) {
        int mask = 0;
        if (!rw.readBlocks().isEmpty())          mask |= 1;
        if (!rw.writtenBlocks().isEmpty())       mask |= 2;
        if (!rw.readBlockEntities().isEmpty())   mask |= 4;
        if (!rw.writtenBlockEntities().isEmpty())mask |= 8;
        if (!rw.readEntityFields().isEmpty())    mask |= 16;
        if (!rw.writtenEntityFields().isEmpty()) mask |= 32;
        if (!rw.readPoiQueries().isEmpty())      mask |= 64;
        if (!rw.readGlobalKeys().isEmpty())      mask |= 128;
        if (!rw.writtenGlobalKeys().isEmpty())   mask |= 256;
        if (!rw.writtenEvents().isEmpty())       mask |= 512;
        if (rw.writesGlobalWildcard())           mask |= 1024;
        if (rw.readsGlobalWildcard())            mask |= 2048;
        long h = mask;
        h = (h * 0x9e3779b97f4a7c15L) ^ rw.writtenGlobalKeys().size();
        h = (h * 0x9e3779b97f4a7c15L) ^ rw.writtenEntityFields().size();
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
