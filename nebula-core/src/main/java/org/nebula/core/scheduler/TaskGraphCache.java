package org.nebula.core.scheduler;

import org.nebula.core.rw.RWSet;
import org.nebula.core.state.EventType;
import org.nebula.core.state.PoiQuery;
import org.nebula.core.state.RandomUsage;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
     * <p>Per-task contribution mixes the taskId with the concrete RWSet
     * contents that can affect conflict edges. This keeps cross-tick reuse
     * safe when task IDs are stable but positions or field targets move.
     */
    public static long fingerprint(Collection<TaskNode> tasks) {
        long h = 0xcbf29ce484222325L;
        for (TaskNode task : tasks) {
            long taskHash = 0xcbf29ce484222325L;
            taskHash = mixString(taskHash, task.taskId());
            taskHash = mixLong(taskHash, rwSetHash(task.declaredRWSet()));
            h ^= taskHash;
        }
        return h;
    }

    private static long rwSetHash(RWSet rw) {
        long h = 0xcbf29ce484222325L;
        h = mixWorldPositions(h, 1, rw.readBlocks());
        h = mixWorldPositions(h, 2, rw.writtenBlocks());
        h = mixComparableSet(h, 3, rw.readBlockEntities());
        h = mixComparableSet(h, 4, rw.writtenBlockEntities());
        h = mixComparableSet(h, 5, rw.readEntityFields());
        h = mixComparableSet(h, 6, rw.writtenEntityFields());
        h = mixPoiQueries(h, 7, rw.readPoiQueries());
        h = mixComparableSet(h, 8, rw.readGlobalKeys());
        h = mixComparableSet(h, 9, rw.writtenGlobalKeys());
        h = mixEvents(h, 10, rw.writtenEvents());
        if (rw.randomUsage().isPresent()) {
            RandomUsage usage = rw.randomUsage().orElseThrow();
            h = mixInt(h, 11);
            h = mixString(h, usage.instance().name());
            h = mixInt(h, usage.maxCallsEstimate());
        } else {
            h = mixInt(h, 12);
        }
        return h;
    }

    private static long mixWorldPositions(long h, int tag, Set<WorldPos> positions) {
        h = mixInt(h, tag);
        h = mixInt(h, positions.size());
        List<WorldPos> sorted = new ArrayList<>(positions);
        sorted.sort(Comparator.naturalOrder());
        for (WorldPos pos : sorted) {
            h = mixInt(h, pos.dimensionId());
            h = mixInt(h, pos.x());
            h = mixInt(h, pos.y());
            h = mixInt(h, pos.z());
        }
        return h;
    }

    private static <T extends Comparable<? super T>> long mixComparableSet(long h, int tag, Set<T> values) {
        h = mixInt(h, tag);
        h = mixInt(h, values.size());
        List<T> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        for (T value : sorted) {
            h = mixString(h, value.toString());
        }
        return h;
    }

    private static long mixPoiQueries(long h, int tag, Set<PoiQuery> queries) {
        h = mixInt(h, tag);
        h = mixInt(h, queries.size());
        List<PoiQuery> sorted = new ArrayList<>(queries);
        sorted.sort(Comparator
            .comparingInt(PoiQuery::dimensionId)
            .thenComparing(PoiQuery::center)
            .thenComparingInt(PoiQuery::radius)
            .thenComparing(PoiQuery::poiType));
        for (PoiQuery query : sorted) {
            h = mixInt(h, query.dimensionId());
            h = mixWorldPositions(h, 13, Set.of(query.center()));
            h = mixInt(h, query.radius());
            h = mixString(h, query.poiType());
        }
        return h;
    }

    private static long mixEvents(long h, int tag, Set<EventType> events) {
        h = mixInt(h, tag);
        h = mixInt(h, events.size());
        List<EventType> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparing(EventType::name));
        for (EventType event : sorted) {
            h = mixString(h, event.name());
        }
        return h;
    }

    private static long mixString(long h, String value) {
        h = mixInt(h, value.length());
        for (int i = 0, n = value.length(); i < n; i++) {
            h ^= value.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static long mixInt(long h, int value) {
        return mixLong(h, value);
    }

    private static long mixLong(long h, long value) {
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            h ^= (value >>> shift) & 0xffL;
            h *= 0x100000001b3L;
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
