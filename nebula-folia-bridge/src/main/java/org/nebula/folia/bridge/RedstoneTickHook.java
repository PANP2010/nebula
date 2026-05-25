package org.nebula.folia.bridge;

import org.nebula.core.scheduler.DagExecutionException;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Bridges Folia's region tick lifecycle into the Nebula DAG execution pipeline.
 *
 * <p>Lifecycle per tick (per region thread):
 * <pre>
 *   beginTick(regionId)
 *     ↓ Folia executes block/entity ticks as normal
 *   onBlockUpdate(worldName, x, y, z)  ← intercepted from CollectingNeighborUpdater
 *     ↓ dirty positions accumulate
 *   endTick(regionId)
 *     ↓ Nebula MicroStepScheduler executes accumulated dirty tasks via DAG
 *     ↓ ReplayRecorder records state hash (if enabled)
 * </pre>
 *
 * <p>In Phase 0 OBSERVE mode, all Folia execution still happens normally.
 * The hook only records which positions were updated.  This produces a
 * ground-truth dataset for later comparison with the DAG execution path.
 *
 * <p>In Phase 0 INTERCEPT mode (after validation), the hook suppresses
 * Folia's native propagation and replaces it with the DAG executor.
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

    // Per-region dirty position accumulator
    private static final ConcurrentHashMap<String, List<WorldPos>> dirtyPositions =
        new ConcurrentHashMap<>();

    private RedstoneTickHook() {}

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
        dirtyPositions.put(regionId, new ArrayList<>());
    }

    /**
     * Called by {@link NeighborUpdateInterceptor} when a BLOCK_UPDATE is
     * dispatched to a position in the given region.
     */
    public static void recordUpdate(String regionId, String worldName, int x, int y, int z) {
        if (!active) return;
        List<WorldPos> dirty = dirtyPositions.get(regionId);
        if (dirty != null) {
            // dimensionId: 0=overworld, -1=nether, 1=end (simplified mapping)
            int dimId = dimensionId(worldName);
            dirty.add(new WorldPos(dimId, x, y, z));
        }
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

        List<WorldPos> dirty = dirtyPositions.remove(regionId);
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

    /** Simple dimension-name → integer mapping. */
    private static int dimensionId(String worldName) {
        if (worldName == null) return 0;
        return switch (worldName) {
            case "minecraft:the_nether" -> -1;
            case "minecraft:the_end"    ->  1;
            default                     ->  0;
        };
    }

    /** Returns current dirty position count for the given region (for monitoring). */
    public static int dirtyCount(String regionId) {
        List<WorldPos> dirty = dirtyPositions.get(regionId);
        return dirty == null ? 0 : dirty.size();
    }
}
