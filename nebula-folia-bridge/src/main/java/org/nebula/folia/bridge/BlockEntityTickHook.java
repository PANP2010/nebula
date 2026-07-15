package org.nebula.folia.bridge;

import org.nebula.annotations.MicroStepBehavior;
import org.nebula.annotations.NebulaRW;
import org.nebula.annotations.SccBehavior;
import org.nebula.core.scheduler.DagExecutionException;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.DimensionIds;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.BlockEntitySnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Bridges Folia's region tick lifecycle into the Nebula <em>block-entity</em> DAG
 * execution pipeline — the hopper/dropper/dispenser/furnace/brewing-stand analogue
 * of {@link RedstoneTickHook} and {@link EntityTickHook}.
 *
 * <p>Lifecycle per tick:
 * <pre>
 *   [Global tick thread] beginTick("nebula-global")
 *     ↓ region threads run and call recordDirty("nebula-global", ...) for each ticking block entity
 *   [Global tick thread, next tick] endTick("nebula-global", worldName)
 *     ↓ resolves dirty block entities → BLOCK_ENTITY_* tasks, runs them through the DAG
 * </pre>
 */
public final class BlockEntityTickHook {

    private static final Logger LOG = Logger.getLogger(BlockEntityTickHook.class.getName());

    public interface TickExecutor {
        void executeTasks(String regionId, String worldName, List<TaskNode> dirtyTasks)
            throws DagExecutionException;
    }

    public interface TaskResolver {
        TaskNode resolve(String worldName, BlockEntitySnapshot blockEntity);
    }

    private static volatile TickExecutor executor = null;
    private static volatile TaskResolver resolver = null;
    private static volatile boolean active = false;

    private static final ConcurrentHashMap<String, Queue<BlockEntitySnapshot>> dirtyBlockEntities =
        new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicBoolean> endTickInProgress =
        new ConcurrentHashMap<>();

    private BlockEntityTickHook() {}

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
     * Called when a block entity ticks (becomes dirty) this tick in the given region.
     *
     * <p>Reads the block entity's inventory/slot state to capture its current
     * tick inputs, and writes it to the dirty-block-entity accumulator.
     * Reads the block at the snapshot position.
     */
    @NebulaRW(
        readBlocks      = {"{snapshot}.pos"},
        readBlockEntities = {"{snapshot}.type.inventory"},
        writeBlocks     = {},
        writeBlockEntities = {},
        triggeredEvents = {"BLOCK_ENTITY_TICK"},
        microStep       = MicroStepBehavior.NONE,
        scc             = SccBehavior.AUTO,
        maxRandomCalls  = 0,
        mayLoadChunks   = false,
        mayTriggerBlockUpdates = true,
        maySpawnEntities = false,
        verifiedAt      = "folia-bridge-1.0",
        verifiedBy      = {}
    )
    public static void recordDirty(String regionId, String worldName, BlockEntitySnapshot snapshot) {
        if (!active) return;
        if (snapshot == null) return;
        String k = key(regionId, worldName);
        Queue<BlockEntitySnapshot> dirty = dirtyBlockEntities.get(k);
        if (dirty == null) {
            dirty = new ConcurrentLinkedQueue<>();
            Queue<BlockEntitySnapshot> existing = dirtyBlockEntities.putIfAbsent(k, dirty);
            if (existing != null) dirty = existing;
        }
        dirty.add(snapshot);
    }

    /**
     * Called at the end of each Folia region tick. Resolves dirty block entities into
     * TaskNodes and, if an executor is registered, passes them to the DAG.
     *
     * <p>Reads the TIME global state and the accumulated block-entity snapshots;
     * writes the resolved TASK nodes into the DAG.
     */
    @NebulaRW(
        readGlobals  = {"TIME"},
        readBlockEntities = {},
        writeBlockEntities = {},
        triggeredEvents = {"BLOCK_ENTITY_TICK"},
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
            LOG.fine(() -> "block-entity endTick already in progress for " + k + " — skipping concurrent call");
            return List.of();
        }
        try {
            return doEndTick(k, regionId, worldName);
        } finally {
            guard.set(false);
        }
    }

    private static List<TaskNode> doEndTick(String k, String regionId, String worldName) {
        Queue<BlockEntitySnapshot> dirty = dirtyBlockEntities.remove(k);
        if (dirty == null || dirty.isEmpty()) return List.of();

        TaskResolver res = resolver;
        if (res == null) return List.of();

        Map<WorldPos, BlockEntitySnapshot> latest = new LinkedHashMap<>();
        for (BlockEntitySnapshot snap : dirty) latest.put(snap.pos(), snap);

        List<TaskNode> dirtyTasks = new ArrayList<>();
        for (BlockEntitySnapshot snap : latest.values()) {
            TaskNode task = res.resolve(worldName, snap);
            if (task != null) dirtyTasks.add(task);
        }

        if (dirtyTasks.isEmpty()) return List.of();

        TickExecutor ex = executor;
        if (ex != null) {
            try {
                ex.executeTasks(regionId, worldName, dirtyTasks);
            } catch (DagExecutionException e) {
                LOG.warning("Block-entity DAG execution failed in region " + regionId + ": " + e.getMessage());
            }
        }

        if (dirtyTasks.size() > 100) {
            LOG.fine(() -> "Region " + regionId + ": " + dirtyTasks.size() + " dirty block-entity tasks in " + worldName);
        }

        return List.copyOf(dirtyTasks);
    }

    public static int dirtyCount(String regionId, String worldName) {
        Queue<BlockEntitySnapshot> dirty = dirtyBlockEntities.get(key(regionId, worldName));
        return dirty == null ? 0 : dirty.size();
    }
}
