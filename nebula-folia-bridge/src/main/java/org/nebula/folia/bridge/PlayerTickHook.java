package org.nebula.folia.bridge;

import org.nebula.core.player.PlayerSnapshot;
import org.nebula.core.scheduler.DagExecutionException;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.DimensionIds;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        void executeTasks(String regionId, String worldName, List<TaskNode> dirtyTasks)
            throws DagExecutionException;
    }

    public interface TaskResolver {
        TaskNode resolve(String worldName, PlayerSnapshot snapshot, String taskType);
    }

    private static volatile TickExecutor executor = null;
    private static volatile TaskResolver resolver = null;
    private static volatile boolean active = false;

    private static final ConcurrentHashMap<String, Queue<PlayerSnapshot>> dirtyPlayers =
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

    public static void beginTick(String regionId) {
        if (!active) return;
        String prefix = regionId + "::";
        endTickInProgress.entrySet().removeIf(e -> e.getKey().startsWith(prefix));
    }

    /**
     * Called when a player's state changes (move, interact). Records a snapshot
     * for the DAG seed.
     */
    public static void recordPlayerSnapshot(String regionId, String worldName, PlayerSnapshot snap) {
        if (!active) return;
        String k = key(regionId, worldName);
        Queue<PlayerSnapshot> dirty = dirtyPlayers.get(k);
        if (dirty == null) {
            dirty = new ConcurrentLinkedQueue<>();
            Queue<PlayerSnapshot> existing = dirtyPlayers.putIfAbsent(k, dirty);
            if (existing != null) dirty = existing;
        }
        dirty.add(snap);
    }

    /**
     * Drains all dirty player snapshots and dispatches them to the DAG executor.
     *
     * @return the list of tasks resolved this tick
     */
    public static List<TaskNode> endTick(String regionId, String worldName) {
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

    private static List<TaskNode> doEndTick(String k, String regionId, String worldName) {
        Queue<PlayerSnapshot> dirty = dirtyPlayers.remove(k);
        if (dirty == null || dirty.isEmpty()) return List.of();

        TaskResolver res = resolver;
        if (res == null) return List.of();

        // Dedup by UUID, keeping latest
        Map<String, PlayerSnapshot> latest = new java.util.LinkedHashMap<>();
        for (PlayerSnapshot snap : dirty) {
            latest.put(snap.playerId().toString(), snap);
        }

        List<TaskNode> tasks = new ArrayList<>();
        for (PlayerSnapshot snap : latest.values()) {
            TaskNode task = res.resolve(worldName, snap, "PLAYER_MOVE");
            if (task != null) {
                tasks.add(task);
            }
        }

        if (tasks.isEmpty()) return List.of();

        TickExecutor ex = executor;
        if (ex != null) {
            try {
                ex.executeTasks(regionId, worldName, tasks);
            } catch (DagExecutionException e) {
                LOG.warning("Player DAG execution failed: " + e.getMessage());
            }
        }

        return List.copyOf(tasks);
    }

    public static int dirtyCount(String regionId, String worldName) {
        Queue<PlayerSnapshot> dirty = dirtyPlayers.get(key(regionId, worldName));
        return dirty == null ? 0 : dirty.size();
    }
}
