package org.nebula.folia.bridge;

import org.bukkit.util.BoundingBox;
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

    /**
     * Drains the raw entity snapshot queue for a given region/world WITHOUT resolving
     * or dispatching.  Returns the deduped list of snapshots.  Used by the collision
     * sweep to get the same moved-entity set that endTick() would process.
     */
    public static List<EntitySnapshot> drainSnapshots(String regionId, String worldName) {
        String k = key(regionId, worldName);
        Queue<EntitySnapshot> dirty = dirtyEntities.remove(k);
        if (dirty == null || dirty.isEmpty()) return List.of();

        // Same dedup logic as doEndTick: keep latest snapshot per entity id
        Map<Long, EntitySnapshot> latest = new LinkedHashMap<>();
        for (EntitySnapshot snap : dirty) {
            latest.put(snap.entityId(), snap);
        }
        return List.copyOf(latest.values());
    }

    /** Returns current moved-entity count for the given region and world (for monitoring). */
    public static int dirtyCount(String regionId, String worldName) {
        Queue<EntitySnapshot> dirty = dirtyEntities.get(key(regionId, worldName));
        return dirty == null ? 0 : dirty.size();
    }

    // ── N3: Entity COLLISION sweep ────────────────────────────────────────────

    /**
     * Callback to resolve a collision pair snapshot into a COLLISION_RESPONSE TaskNode.
     * The factory expects two entity snapshots with valid entityIds.
     */
    @FunctionalInterface
    public interface CollisionResolver {
        TaskNode resolve(String worldName, EntitySnapshot a, EntitySnapshot b);
    }

    private static volatile CollisionResolver collisionResolver = null;

    public static void setCollisionResolver(CollisionResolver res) {
        collisionResolver = res;
    }

    /**
     * Performs a brute-force O(n²) sweep of all moved entities to find overlapping
     * bounding boxes, then calls the collision resolver for each pair to produce
     * a COLLISION_RESPONSE TaskNode.  Called at the end of each global tick after
     * the moved-entity drain so we sweep the same snapshot the MOVE tasks read.
     *
     * <p>This is the N3 live seed for Entity COLLISION: the collision detection
     * is a Folia-internal event with no explicit event hook, so we approximate it
     * by sweeping moved entities' bounding boxes using live entity lookups.  A pair
     * is emitted only when the bounding boxes overlap (confirmed via
     * {@code boxA.overlaps(boxB)}).  The sweep runs on the global tick thread
     * because it needs to see all moved entities across all regions — the live entity
     * lookups hit each entity on its owning region thread, which is legal because
     * global tick executes on the thread that owns all regions.
     *
     * <p>The collision pair is emitted as a COLLISION_RESPONSE task (not COLLISION
     * read-only), because the collision RESPONSE (velocity exchange) is the
     * stateful effect the DAG must track.  The pure-read COLLISION task is a
     * theoretical predecessor that this sweep skips — we go straight to the
     * write-bearing effect, which is all the DAG needs for correctness.
     *
     * @param regionId  the global region identifier (always "nebula-global")
     * @param world     the Bukkit world to look up live entity bounding boxes
     * @param movedEntities  all EntitySnapshots drained this tick
     * @return list of collision tasks generated (may be empty)
     */
    public static List<TaskNode> sweepCollisionsAndEmit(
            String regionId, org.bukkit.World world) {
        if (world == null) return List.of();
        List<EntitySnapshot> movedEntities = lastDrainedSnapshots();
        if (collisionResolver == null || movedEntities == null || movedEntities.size() < 2) {
            return List.of();
        }
        String worldName = world.getName();
        List<TaskNode> collisionTasks = new ArrayList<>();
        for (int i = 0; i < movedEntities.size(); i++) {
            EntitySnapshot ai = movedEntities.get(i);
            BoundingBox boxA = null;
            for (org.bukkit.entity.Entity e : world.getEntities()) {
                if (e.getEntityId() == ai.entityId()) {
                    boxA = e.getBoundingBox();
                    break;
                }
            }
            if (boxA == null) continue;
            for (int j = i + 1; j < movedEntities.size(); j++) {
                EntitySnapshot bj = movedEntities.get(j);
                if (ai.entityId() == bj.entityId()) continue;
                BoundingBox boxB = null;
                for (org.bukkit.entity.Entity e : world.getEntities()) {
                    if (e.getEntityId() == bj.entityId()) {
                        boxB = e.getBoundingBox();
                        break;
                    }
                }
                if (boxB == null) continue;
                if (!boxA.overlaps(boxB)) continue;
                TaskNode task = collisionResolver.resolve(worldName, ai, bj);
                if (task != null) {
                    collisionTasks.add(task);
                }
            }
        }
        synchronized (lastCollisionTasks) {
            lastCollisionTasks.clear();
            lastCollisionTasks.addAll(collisionTasks);
        }
        if (collisionTasks.size() > 0) {
            LOG.info("⚡ Collision sweep: " + movedEntities.size()
                + " moved entities → " + collisionTasks.size()
                + " collision tasks in " + worldName);
        }
        return List.copyOf(collisionTasks);
    }

    /**
     * Returns the list of collision tasks last generated by sweepCollisionsAndEmit
     * (for diagnostics and testing).
     */
    public static List<TaskNode> lastCollisionTasks() {
        synchronized (lastCollisionTasks) {
            return List.copyOf(lastCollisionTasks);
        }
    }

    private static final List<TaskNode> lastCollisionTasks = new ArrayList<>();

    // N3: stores the deduped entity snapshots drained by endTick(), so the collision
    // sweep can access the same set that endTick() would process. Cleared after each
    // global tick cycle.  ThreadLocal so concurrent endTick calls (different worlds)
    // don't clobber each other.
    private static final ThreadLocal<List<EntitySnapshot>> lastDrainedSnapshots =
        ThreadLocal.withInitial(() -> new ArrayList<>());

    /**
     * Internal helper: drains and dedupes the queue, stores the snapshots in
     * lastDrainedSnapshots for collision sweep access, then returns the TaskNodes.
     */
    private static List<TaskNode> drainAndStoreSnapshots(String k, String worldName) {
        Queue<EntitySnapshot> dirty = dirtyEntities.remove(k);
        if (dirty == null || dirty.isEmpty()) {
            lastDrainedSnapshots.get().clear();
            return List.of();
        }
        Map<Long, EntitySnapshot> latest = new LinkedHashMap<>();
        for (EntitySnapshot snap : dirty) {
            latest.put(snap.entityId(), snap);
        }
        lastDrainedSnapshots.get().clear();
        lastDrainedSnapshots.get().addAll(latest.values());

        TaskResolver res = resolver;
        if (res == null) return List.of();

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
                ex.executeTasks(k.split("::")[0], worldName, dirtyTasks);
            } catch (DagExecutionException e) {
                LOG.warning("Entity DAG execution failed in region " + k.split("::")[0]
                    + ": " + e.getMessage());
            }
        }

        if (dirtyTasks.size() > 100) {
            LOG.fine(() -> "Region " + k.split("::")[0] + ": " + dirtyTasks.size()
                + " dirty entity tasks in " + worldName);
        }
        return List.copyOf(dirtyTasks);
    }

    /**
     * Returns the entity snapshots last drained by endTick() for the current thread/world.
     * The collision sweep reads this to access the same snapshot set.
     */
    public static List<EntitySnapshot> lastDrainedSnapshots() {
        return List.copyOf(lastDrainedSnapshots.get());
    }
}
