package org.nebula.folia.bridge;

import org.nebula.annotations.MicroStepBehavior;
import org.nebula.annotations.NebulaRW;
import org.nebula.annotations.SccBehavior;
import org.nebula.core.state.DimensionIds;
import org.nebula.core.state.WorldPos;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Bridges Folia's player tick lifecycle into the Nebula player DAG executor.
 *
 * <p>Mirrors {@link EntityTickHook} for player entities. On each player move event
 * or block interaction, the plugin records a snapshot; at endTick the snapshots are
 * resolved into PLAYER_MOVE / PLAYER_BLOCK_INTERACT task nodes and dispatched to
 * the DAG executor.
 *
 * <p>Lifecycle:
 * <pre>
 *   [PlayerMoveEvent]    → recordPlayerSnapshot(...)
 *   [PlayerInteractEvent] → recordPlayerSnapshot(...)
 *   [endTick]            → resolve snapshots → executeTasks → DAG run
 * </pre>
 */
public final class PlayerTickHook {

    private static final Logger LOG = Logger.getLogger(PlayerTickHook.class.getName());

    public interface TickExecutor {
        void executeTasks(String regionId, String worldName, List<org.nebula.core.scheduler.TaskNode> dirtyTasks)
            throws org.nebula.core.scheduler.DagExecutionException;
    }

    public interface TaskResolver {
        org.nebula.core.scheduler.TaskNode resolve(String worldName, org.nebula.core.player.PlayerSnapshot snapshot, String taskType);
    }

    private static volatile TickExecutor executor = null;
    private static volatile TaskResolver resolver = null;
    private static volatile boolean active = false;

    private static final ConcurrentHashMap<String, Queue<org.nebula.core.player.PlayerSnapshot>> dirtyPlayers =
        new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicBoolean> endTickInProgress =
        new ConcurrentHashMap<>();

    private PlayerTickHook() {}

    private static String key(String regionId, String worldName) {
        return regionId + "::" + DimensionIds.fromName(worldName);
    }

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

    /**
     * Called at the start of each Folia region tick.
     *
     * <p>Reads the global TIME state (Folia tick counter) to stamp the tick boundary;
     * no entity or block fields are read.
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
        endTickInProgress.entrySet().removeIf(e -> e.getKey().startsWith(prefix));
    }

    /**
     * Called when a player's state changes (move, interact). Records a snapshot
     * for the DAG seed.
     *
     * <p>Reads the player's position entity field and writes it into the snapshot
     * accumulator (no NMS or block state is mutated).
     */
    @NebulaRW(
        readEntities = {"{snap}.playerId.position"},
        writeEntities = {},
        triggeredEvents = {"PLAYER_MOVED"},
        microStep    = MicroStepBehavior.NONE,
        scc          = SccBehavior.AUTO,
        maxRandomCalls = 0,
        mayLoadChunks = false,
        mayTriggerBlockUpdates = false,
        maySpawnEntities = false,
        verifiedAt   = "folia-bridge-1.0",
        verifiedBy   = {}
    )
    public static void recordPlayerSnapshot(String regionId, String worldName,
                                            org.nebula.core.player.PlayerSnapshot snap) {
        if (!active) return;
        String k = key(regionId, worldName);
        Queue<org.nebula.core.player.PlayerSnapshot> dirty = dirtyPlayers.get(k);
        if (dirty == null) {
            dirty = new ConcurrentLinkedQueue<>();
            Queue<org.nebula.core.player.PlayerSnapshot> existing = dirtyPlayers.putIfAbsent(k, dirty);
            if (existing != null) dirty = existing;
        }
        dirty.add(snap);
    }

    /**
     * Drains all dirty player snapshots and dispatches them to the DAG executor.
     *
     * <p>Reads the accumulated player snapshots and writes them as DAG seed tasks;
     * reads TIME global state for the tick boundary.
     */
    @NebulaRW(
        readGlobals     = {"TIME"},
        writeGlobals    = {},
        triggeredEvents = {"PLAYER_MOVED"},
        microStep       = MicroStepBehavior.NONE,
        scc             = SccBehavior.AUTO,
        maxRandomCalls  = 0,
        mayLoadChunks   = false,
        mayTriggerBlockUpdates = false,
        maySpawnEntities = false,
        verifiedAt      = "folia-bridge-1.0",
        verifiedBy      = {}
    )
    public static List<org.nebula.core.scheduler.TaskNode> endTick(String regionId, String worldName) {
        if (!active) return List.of();

        String k = key(regionId, worldName);
        AtomicBoolean guard = endTickInProgress.computeIfAbsent(k, _k -> new AtomicBoolean(false));
        if (!guard.compareAndSet(false, true)) {
            return List.of();
        }
        try {
            return doEndTick(k, regionId, worldName);
        } finally {
            guard.set(false);
        }
    }

    private static List<org.nebula.core.scheduler.TaskNode> doEndTick(String k, String regionId, String worldName) {
        Queue<org.nebula.core.player.PlayerSnapshot> dirty = dirtyPlayers.remove(k);
        if (dirty == null || dirty.isEmpty()) return List.of();

        TaskResolver res = resolver;
        if (res == null) return List.of();

        java.util.Map<String, org.nebula.core.player.PlayerSnapshot> latest = new java.util.LinkedHashMap<>();
        for (org.nebula.core.player.PlayerSnapshot snap : dirty) {
            latest.put(snap.playerId().toString(), snap);
        }

        List<org.nebula.core.scheduler.TaskNode> tasks = new java.util.ArrayList<>();
        for (org.nebula.core.player.PlayerSnapshot snap : latest.values()) {
            org.nebula.core.scheduler.TaskNode task = res.resolve(worldName, snap, "PLAYER_MOVE");
            if (task != null) {
                tasks.add(task);
            }
        }

        if (tasks.isEmpty()) return List.of();

        TickExecutor ex = executor;
        if (ex != null) {
            try {
                ex.executeTasks(regionId, worldName, tasks);
            } catch (org.nebula.core.scheduler.DagExecutionException e) {
                LOG.warning("Player DAG execution failed: " + e.getMessage());
            }
        }

        return List.copyOf(tasks);
    }

    public static int dirtyCount(String regionId, String worldName) {
        Queue<org.nebula.core.player.PlayerSnapshot> dirty = dirtyPlayers.get(key(regionId, worldName));
        return dirty == null ? 0 : dirty.size();
    }
}
