package org.nebula.folia.bridge;

import org.nebula.core.scheduler.DagExecutionException;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.DimensionIds;
import org.nebula.entity.EntitySnapshot;

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
 * Bridges Folia's region tick lifecycle into the Nebula <em>entity</em> DAG
 * execution pipeline — the entity-physics analogue of {@link RedstoneTickHook}
 * (arch doc §6.3, TODO B8 C1).
 *
 * <p>Lifecycle per tick, identical in shape to the redstone hook:
 * <pre>
 *   [Global tick thread] beginTick("nebula-global")
 *     ↓ region threads run and call recordMove("nebula-global", ...) for each moved entity
 *   [Global tick thread, next tick] endTick("nebula-global", worldName)
 *     ↓ resolves moved entities → ENTITY_MOVE tasks, runs them through the entity DAG
 * </pre>
 *
 * <p><b>Dirty unit.</b> Redstone accumulates {@link org.nebula.core.state.WorldPos}
 * (a block that received a {@code BLOCK_UPDATE}); entities accumulate an
 * {@link EntitySnapshot} (an entity that moved this tick, carrying its id and the
 * position it moved to). The resolver turns a snapshot into a {@code ENTITY_MOVE}
 * {@link TaskNode} via {@code EntityTaskFactory.move(...)}.
 *
 * <p><b>Why dedup is by entity id, not by value.</b> The redstone hook dedups dirty
 * positions with a {@code LinkedHashSet<WorldPos>}: two updates to the same block are
 * value-equal and collapse to one. An entity that moves twice in a tick produces two
 * snapshots with <em>different</em> coordinates, so they are NOT value-equal — a Set
 * would keep both and mint two {@code ENTITY_MOVE@dim:<id>:x,y,z} task IDs for the
 * same entity in one DAG (a spurious self-conflict). {@link #doEndTick} therefore
 * dedups by {@link EntitySnapshot#entityId()} keeping the <em>latest</em> recorded
 * position (first-sighting order, last value), so each entity seeds exactly one MOVE
 * task at the position it ended the tick on.
 *
 * <p><b>Scope (honest, verified 2026-07-09).</b> This is the accumulator + resolver
 * seam ONLY. It is a pure, unit-tested class in nebula-folia-bridge; nothing in the
 * plugin calls {@link #recordMove} or drives {@link #beginTick}/{@link #endTick} on a
 * live Folia tick yet, so it does NOT touch the live tick pipeline. The lifecycle
 * driver (an entity-move interceptor feeding {@code recordMove}, plus a
 * GlobalRegionScheduler drain bracketing {@code NmsEntityStateBridge} sync) is the
 * next slice of C1 and is where the ⚡ live "first entity DAG tick" milestone lands.
 * The begin/end/drain contract mirrors {@link RedstoneTickHook} exactly, including the
 * DG3 fix: {@link #beginTick} does NOT wipe the accumulator (endTick is the sole
 * drain-and-remove consumer), so a move recorded in the endTick→beginTick window
 * survives to the next endTick.
 */
public final class EntityTickHook {

    private static final Logger LOG = Logger.getLogger(EntityTickHook.class.getName());

    /** Callback interface for the game layer to implement entity-tick execution. */
    public interface TickExecutor {
        /**
         * Execute a list of entity tasks via the Nebula DAG for the given region.
         *
         * @param regionId   the Folia region identifier
         * @param worldName  dimension name
         * @param dirtyTasks tasks for entities that moved this tick
         * @throws DagExecutionException if any task fails
         */
        void executeTasks(String regionId, String worldName, List<TaskNode> dirtyTasks)
            throws DagExecutionException;
    }

    /** Callback to convert a moved entity snapshot into a TaskNode. */
    public interface TaskResolver {
        /**
         * @param worldName dimension name
         * @param entity    snapshot of an entity that moved this tick
         * @return a TaskNode for this entity, or {@code null} if it should not
         *         seed a task (e.g. an untracked / non-physics entity)
         */
        TaskNode resolve(String worldName, EntitySnapshot entity);
    }

    private static volatile TickExecutor executor = null;
    private static volatile TaskResolver resolver = null;
    private static volatile boolean active = false;

    // Per-region moved-entity accumulator, keyed by "regionId::dimId" so each world
    // gets its own bucket (endTick is called per-world; without the suffix the first
    // drain would empty every world's bucket). Mirrors RedstoneTickHook.
    private static final ConcurrentHashMap<String, Queue<EntitySnapshot>> dirtyEntities =
        new ConcurrentHashMap<>();

    // Guards against concurrent endTick execution for the same key (see RedstoneTickHook).
    private static final ConcurrentHashMap<String, AtomicBoolean> endTickInProgress =
        new ConcurrentHashMap<>();

    private EntityTickHook() {}

    /**
     * Builds the composite key "regionId::dimId". The world name is normalized to its
     * canonical dimension ID via {@link DimensionIds#fromName(String)} so the NMS
     * namespaced form ({@code "minecraft:overworld"}) used by the recorder and the
     * Bukkit folder name ({@code "world"}) used by the drain driver converge on the
     * same bucket — exactly as in {@link RedstoneTickHook}.
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
     *
     * <p><b>Does NOT clear the accumulator</b> — {@link #endTick} is the sole consumer
     * (drains and removes each bucket), so a move recorded in the endTick→beginTick
     * window survives to the next endTick. This mirrors the RedstoneTickHook DG3 fix;
     * wiping here would drop in-flight moves.
     */
    public static void beginTick(String regionId) {
        if (!active) return;
        String prefix = regionId + "::";
        endTickInProgress.entrySet().removeIf(entry -> entry.getKey().startsWith(prefix));
    }

    /**
     * Called when an entity's position changes this tick in the given region.
     *
     * @param regionId  the Folia region identifier
     * @param worldName dimension name (either the NMS namespaced or Bukkit folder form)
     * @param entityId  the entity's network id
     * @param x         block-truncated destination x
     * @param y         block-truncated destination y
     * @param z         block-truncated destination z
     */
    public static void recordMove(String regionId, String worldName,
                                  long entityId, int x, int y, int z) {
        if (!active) return;
        String k = key(regionId, worldName);
        Queue<EntitySnapshot> dirty = dirtyEntities.get(k);
        if (dirty == null) {
            dirty = new ConcurrentLinkedQueue<>();
            Queue<EntitySnapshot> existing = dirtyEntities.putIfAbsent(k, dirty);
            if (existing != null) {
                dirty = existing;
            }
        }
        int dimId = DimensionIds.fromName(worldName);
        dirty.add(EntitySnapshot.of(entityId, x, y, z, dimId));
    }

    /**
     * Called at the end of each Folia region tick. Resolves moved entities into
     * TaskNodes and, if an executor is registered, passes them to the entity DAG.
     *
     * @return the list of dirty tasks resolved this tick (for diagnostics)
     */
    public static List<TaskNode> endTick(String regionId, String worldName) {
        if (!active) return List.of();

        String k = key(regionId, worldName);

        AtomicBoolean guard = endTickInProgress.computeIfAbsent(k, _k -> new AtomicBoolean(false));
        if (!guard.compareAndSet(false, true)) {
            LOG.fine(() -> "entity endTick already in progress for " + k + " — skipping concurrent call");
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
        Queue<EntitySnapshot> dirty = dirtyEntities.remove(k);
        if (dirty == null || dirty.isEmpty()) return List.of();

        TaskResolver res = resolver;
        if (res == null) return List.of();

        // Dedup by entity id, keeping the LATEST recorded snapshot (first-sighting
        // order, last position) — see class javadoc on why value-dedup is wrong here.
        Map<Long, EntitySnapshot> latest = new LinkedHashMap<>();
        for (EntitySnapshot snap : dirty) {
            latest.put(snap.entityId(), snap);
        }

        List<TaskNode> dirtyTasks = new ArrayList<>();
        for (EntitySnapshot snap : latest.values()) {
            TaskNode task = res.resolve(worldName, snap);
            if (task != null) {
                dirtyTasks.add(task);
            }
        }

        if (dirtyTasks.isEmpty()) return List.of();

        TickExecutor ex = executor;
        if (ex != null) {
            try {
                ex.executeTasks(regionId, worldName, dirtyTasks);
            } catch (DagExecutionException e) {
                LOG.warning("Entity DAG execution failed in region " + regionId
                    + ": " + e.getMessage());
            }
        }

        if (dirtyTasks.size() > 100) {
            LOG.fine(() -> "Region " + regionId + ": " + dirtyTasks.size()
                + " dirty entity tasks in " + worldName);
        }

        return List.copyOf(dirtyTasks);
    }

    /** Returns current moved-entity count for the given region and world (for monitoring). */
    public static int dirtyCount(String regionId, String worldName) {
        Queue<EntitySnapshot> dirty = dirtyEntities.get(key(regionId, worldName));
        return dirty == null ? 0 : dirty.size();
    }
}
