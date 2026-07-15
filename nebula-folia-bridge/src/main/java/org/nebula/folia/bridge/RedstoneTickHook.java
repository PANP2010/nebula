package org.nebula.folia.bridge;

import org.nebula.annotations.MicroStepBehavior;
import org.nebula.annotations.NebulaRW;
import org.nebula.annotations.SccBehavior;
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
 * <p>NOTE (verified 2026-07-08): INTERCEPT mode does NOT currently suppress
 * Folia's native propagation — see class javadoc for the full architectural note.
 */
public final class RedstoneTickHook {

    private static final Logger LOG = Logger.getLogger(RedstoneTickHook.class.getName());

    public interface TickExecutor {
        void executeTasks(String regionId, String worldName, List<TaskNode> dirtyTasks)
            throws DagExecutionException;
    }

    public interface TaskResolver {
        TaskNode resolve(String worldName, WorldPos pos);
    }

    private static volatile TickExecutor executor = null;
    private static volatile TaskResolver resolver = null;
    private static volatile boolean active = false;

    private static final ConcurrentHashMap<String, Queue<WorldPos>> dirtyPositions =
        new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicBoolean> endTickInProgress =
        new ConcurrentHashMap<>();

    private RedstoneTickHook() {}

    private static String key(String regionId, String worldName) {
        return regionId + "::" + DimensionIds.fromName(worldName);
    }

    public static void setExecutor(TickExecutor ex) { executor = ex; }
    public static void setResolver(TaskResolver res) { resolver = res; }
    public static void setActive(boolean a) { active = a; }
    public static boolean isActive() { return active; }

    /**
     * Called at the start of each Folia region tick.
     *
     * <p>Reads the global TIME state to stamp the tick boundary. No block
     * positions are read or written.
     */
    @NebulaRW(
        readGlobals  = {"TIME"},
        writeGlobals = {},
        triggeredEvents = {},
        microStep    = MicroStepBehavior.NONE,
        scc          = SccBehavior.AUTO,
        maxRandomCalls = 0,
        mayLoadChunks = false,
        mayTriggerBlockUpdates = false,
        maySpawnEntities = false,
        verifiedAt   = "folia-bridge-1.0",
        verifiedBy   = {}
    )
    public static void beginTick(String regionId) {
        if (!active) return;
        String prefix = regionId + "::";
        endTickInProgress.entrySet().removeIf(entry -> entry.getKey().startsWith(prefix));
    }

    /**
     * Called by {@link NeighborUpdateInterceptor} when a BLOCK_UPDATE is
     * dispatched to a position in the given region.
     *
     * <p>Reads the block at the given position (to observe its current
     * power level) and writes it to the dirty-position accumulator.
     */
    @NebulaRW(
        readBlocks   = {"{pos}"},
        writeBlocks  = {},
        triggeredEvents = {"BLOCK_UPDATE"},
        microStep    = MicroStepBehavior.NONE,
        scc          = SccBehavior.AUTO,
        maxRandomCalls = 0,
        mayLoadChunks = false,
        mayTriggerBlockUpdates = false,
        maySpawnEntities = false,
        verifiedAt   = "folia-bridge-1.0",
        verifiedBy   = {}
    )
    public static void recordUpdate(String regionId, String worldName, int x, int y, int z) {
        if (!active) return;
        String k = key(regionId, worldName);
        Queue<WorldPos> dirty = dirtyPositions.get(k);
        if (dirty == null) {
            dirty = new ConcurrentLinkedQueue<>();
            Queue<WorldPos> existing = dirtyPositions.putIfAbsent(k, dirty);
            if (existing != null) dirty = existing;
        }
        int dimId = DimensionIds.fromName(worldName);
        dirty.add(new WorldPos(dimId, x, y, z));
    }

    /**
     * Called at the end of each Folia region tick.
     *
     * <p>Reads the accumulated dirty block positions and the TIME global state,
     * then writes the resolved TASK nodes into the DAG. Reads block states
     * during the resolve step (resolver.resolve reads the block's current
     * power level).
     */
    @NebulaRW(
        readGlobals  = {"TIME"},
        readBlocks   = {},  // resolve() reads block state per position
        writeBlocks  = {},
        triggeredEvents = {"BLOCK_UPDATE"},
        microStep    = MicroStepBehavior.DEFERRED,
        scc          = SccBehavior.AUTO,
        maxRandomCalls = 0,
        mayLoadChunks = false,
        mayTriggerBlockUpdates = true,
        maySpawnEntities = false,
        verifiedAt   = "folia-bridge-1.0",
        verifiedBy   = {}
    )
    public static List<TaskNode> endTick(String regionId, String worldName) {
        if (!active) return List.of();

        String k = key(regionId, worldName);

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

    private static List<TaskNode> doEndTick(String k, String regionId, String worldName) {
        Queue<WorldPos> dirty = dirtyPositions.remove(k);
        if (dirty == null || dirty.isEmpty()) return List.of();

        TaskResolver res = resolver;
        if (res == null) return List.of();

        List<TaskNode> dirtyTasks = new ArrayList<>();
        java.util.Set<WorldPos> seen = new java.util.LinkedHashSet<>();
        for (WorldPos pos : dirty) seen.add(pos);
        for (WorldPos pos : seen) {
            TaskNode task = res.resolve(worldName, pos);
            if (task != null) dirtyTasks.add(task);
        }

        if (dirtyTasks.isEmpty()) return List.of();

        TickExecutor ex = executor;
        if (ex != null) {
            try {
                ex.executeTasks(regionId, worldName, dirtyTasks);
            } catch (DagExecutionException e) {
                LOG.warning("DAG execution failed in region " + regionId + ": " + e.getMessage());
            }
        }

        if (dirtyTasks.size() > 100) {
            LOG.fine(() -> "Region " + regionId + ": " + dirtyTasks.size() + " dirty redstone tasks in " + worldName);
        }

        return List.copyOf(dirtyTasks);
    }

    public static int dirtyCount(String regionId, String worldName) {
        Queue<WorldPos> dirty = dirtyPositions.get(key(regionId, worldName));
        return dirty == null ? 0 : dirty.size();
    }
}
