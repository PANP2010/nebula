package org.nebula.folia.bridge;

import org.nebula.core.scheduler.DagExecutionException;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.DimensionIds;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Bridges Folia's region tick lifecycle into the Nebula DAG execution pipeline.
 *
 * <p>Lifecycle per tick:
 * <pre>
 *   [Global tick thread] beginTick("nebula-global")
 *     ↓ region threads run and call recordUpdate("nebula-global", ...)
 *   [Global tick thread, next tick] endTick("nebula-global", worldName)
 *     ↓ Nebula MicroStepScheduler executes accumulated dirty tasks via DAG
 *     ↓ ReplayRecorder records state hash (if enabled)
 * </pre>
 *
 * <p>All updates are accumulated into a single global bucket ("nebula-global")
 * because the interceptor runs on region threads while beginTick/endTick run
 * on the global tick thread.  Region-aware partitioning happens downstream at
 * the {@link org.nebula.folia.FoliaRegionTickExecutor} level, which dispatches
 * each task to its owning region thread via RegionScheduler.execute().
 *
 * <p>In OBSERVE mode, all Folia execution happens normally and the hook only
 * records which positions were updated, producing a ground-truth dataset for
 * comparison with the DAG execution path.
 *
 * <p>NOTE (verified 2026-07-08): INTERCEPT mode does NOT currently suppress
 * Folia's native propagation. The agent bytecode (see NeighborUpdateTransformer
 * and the three sibling redstone transformers) injects a void
 * {@code NeighborUpdateHooks.onNeighborUpdate(...)} call at method entry and
 * then lets the original method run to completion — it emits no early RETURN.
 * {@code NeighborUpdateInterceptor.shouldSuppress()} computes a suppression
 * decision, but that boolean is discarded by the agent path, so it has no
 * effect. Consequently Nebula is architecturally observe-only on the redstone
 * path in both modes: Folia's redstone remains authoritative and the DAG runs
 * in parallel as a non-authoritative shadow. This means {@code /nebula perf}
 * measures the DAG's ADDED overhead on top of Folia, not a replacement cost.
 * Making INTERCEPT actually suppress-and-replace is future work and must be
 * gated behind zero-diff validation before it can be trusted.
 */
public final class RedstoneTickHook {

    private static final Logger LOG = Logger.getLogger(RedstoneTickHook.class.getName());

    /** Callback interface for the game layer to implement tick execution. */
    public interface TickExecutor {
        /**
         * Execute a list of redstone tasks via the Nebula DAG for the given region.
         *
         * @param regionId   the Folia region identifier
         * @param worldName  dimension name
         * @param dirtyTasks tasks whose positions received BLOCK_UPDATE this tick
         * @throws DagExecutionException if any task fails
         */
        void executeTasks(String regionId, String worldName, List<TaskNode> dirtyTasks)
            throws DagExecutionException;
    }

    /** Callback to convert a (worldName, pos) into a TaskNode. */
    public interface TaskResolver {
        /**
         * @param worldName dimension name
         * @param pos       position that received a BLOCK_UPDATE
         * @return a TaskNode for this position, or {@code null} if not a
         *         known redstone component
         */
        TaskNode resolve(String worldName, WorldPos pos);
    }

    private static volatile TickExecutor executor = null;
    private static volatile TaskResolver resolver = null;
    private static volatile boolean active = false;

    // Per-region dirty position accumulator, keyed by "regionId::worldName"
    // so that each world gets its own accumulator.  This is necessary because
    // endTick is called per-world from the global tick driver, and without
    // the world suffix the first endTick call would drain the accumulator
    // for all worlds.
    private static final ConcurrentHashMap<String, Queue<WorldPos>> dirtyPositions =
        new ConcurrentHashMap<>();

    // Guards against concurrent endTick execution for the same key.
    // endTick atomically marks the key as in-progress and skips if already running.
    // This prevents re-entrant or overlapping execution when the global tick driver
    // or multiple region threads call endTick for the same region-world combination.
    private static final ConcurrentHashMap<String, AtomicBoolean> endTickInProgress =
        new ConcurrentHashMap<>();

    private RedstoneTickHook() {}

    /**
     * Builds the composite key "regionId::dimId".
     *
     * <p>The world name is normalized to its canonical dimension ID via
     * {@link DimensionIds#fromName(String)} so that the two name forms that
     * reach this hook converge on the same bucket:
     * <ul>
     *   <li>the agent interceptor records updates using the NMS namespaced key
     *       ({@code "minecraft:overworld"}, from {@code Level.dimension().location()}), while</li>
     *   <li>the global-tick lifecycle driver drains with the Bukkit world folder
     *       name ({@code "world"}, from {@code World.getName()}).</li>
     * </ul>
     * Keying by the raw string left these in different buckets, so
     * agent-recorded dirty positions were never drained by {@code endTick}.
     * Normalizing to the dimension ID also matches the rest of the pipeline,
     * where {@link WorldPos} and the plugin's component map are dimension-keyed.
     */
    private static String key(String regionId, String worldName) {
        return regionId + "::" + DimensionIds.fromName(worldName);
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    public static void setExecutor(TickExecutor ex) {
        executor = ex;
    }

    public static void setResolver(TaskResolver res) {
        resolver = res;
    }

    public static void setActive(boolean a) {
        active = a;
    }

    public static boolean isActive() {
        return active;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Called at the start of each Folia region tick.
     * Clears the dirty-position accumulator for this region.
     */
    public static void beginTick(String regionId) {
        if (!active) return;
        // Clear any stale dirty-position entries for this region.
        // This ensures a clean slate at the start of each tick regardless
        // of whether endTick was called for every world.
        String prefix = regionId + "::";
        dirtyPositions.entrySet().removeIf(entry -> entry.getKey().startsWith(prefix));
        // Also clear any stale endTick-in-progress guards for this region.
        endTickInProgress.entrySet().removeIf(entry -> entry.getKey().startsWith(prefix));
    }

    /**
     * Called by {@link NeighborUpdateInterceptor} when a BLOCK_UPDATE is
     * dispatched to a position in the given region.
     */
    public static void recordUpdate(String regionId, String worldName, int x, int y, int z) {
        if (!active) return;
        String k = key(regionId, worldName);
        Queue<WorldPos> dirty = dirtyPositions.get(k);
        if (dirty == null) {
            dirty = new ConcurrentLinkedQueue<>();
            Queue<WorldPos> existing = dirtyPositions.putIfAbsent(k, dirty);
            if (existing != null) {
                dirty = existing;
            }
        }
        int dimId = DimensionIds.fromName(worldName);
        dirty.add(new WorldPos(dimId, x, y, z));
    }

    /**
     * Called at the end of each Folia region tick.
     *
     * <p>Resolves dirty positions into TaskNodes and, if an executor is
     * registered, passes them to the DAG execution path.
     *
     * @return the list of dirty tasks resolved this tick (for diagnostics)
     */
    public static List<TaskNode> endTick(String regionId, String worldName) {
        if (!active) return List.of();

        String k = key(regionId, worldName);

        // ── Guard: prevent concurrent endTick execution for the same key ──
        // Atomically mark this key as in-progress.  If another thread is already
        // executing endTick for this region-world combination, skip immediately.
        AtomicBoolean guard = endTickInProgress.computeIfAbsent(k, _k -> new AtomicBoolean(false));
        if (!guard.compareAndSet(false, true)) {
            LOG.fine(() -> "endTick already in progress for " + k + " — skipping concurrent call");
            return List.of();
        }
        try {
            return doEndTick(k, regionId, worldName);
        } finally {
            guard.set(false);
        }
    }

    /** Internal implementation of endTick, called under the AtomicBoolean guard. */
    private static List<TaskNode> doEndTick(String k, String regionId, String worldName) {
        Queue<WorldPos> dirty = dirtyPositions.remove(k);
        if (dirty == null || dirty.isEmpty()) return List.of();

        TaskResolver res = resolver;
        if (res == null) return List.of();

        // Resolve positions → tasks (deduplicate positions first)
        List<TaskNode> dirtyTasks = new ArrayList<>();
        java.util.Set<WorldPos> seen = new java.util.LinkedHashSet<>();
        for (WorldPos pos : dirty) seen.add(pos);
        for (WorldPos pos : seen) {
            TaskNode task = res.resolve(worldName, pos);
            if (task != null) {
                dirtyTasks.add(task);
            }
        }

        if (dirtyTasks.isEmpty()) return List.of();

        // Execute via DAG if executor registered
        TickExecutor ex = executor;
        if (ex != null) {
            try {
                ex.executeTasks(regionId, worldName, dirtyTasks);
            } catch (DagExecutionException e) {
                LOG.warning("DAG execution failed in region " + regionId
                    + ": " + e.getMessage());
            }
        }

        if (dirtyTasks.size() > 100) {
            LOG.fine(() -> "Region " + regionId + ": " + dirtyTasks.size()
                + " dirty redstone tasks in " + worldName);
        }

        return List.copyOf(dirtyTasks);
    }

    /** Returns current dirty position count for the given region and world (for monitoring). */
    public static int dirtyCount(String regionId, String worldName) {
        Queue<WorldPos> dirty = dirtyPositions.get(key(regionId, worldName));
        return dirty == null ? 0 : dirty.size();
    }
}
