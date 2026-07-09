package org.nebula.folia.bridge;

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
 * of {@link RedstoneTickHook} and {@link EntityTickHook} (arch doc §3.3, TODO B8 C3).
 *
 * <p>Lifecycle per tick, identical in shape to the other two hooks:
 * <pre>
 *   [Global tick thread] beginTick("nebula-global")
 *     ↓ region threads run and call recordDirty("nebula-global", ...) for each ticking block entity
 *   [Global tick thread, next tick] endTick("nebula-global", worldName)
 *     ↓ resolves dirty block entities → BLOCK_ENTITY_* tasks, runs them through the DAG
 * </pre>
 *
 * <p><b>Dirty unit.</b> Redstone accumulates {@link WorldPos} (a block that received a
 * {@code BLOCK_UPDATE}); the entity hook accumulates an {@code EntitySnapshot} (a mob
 * that moved). This hook accumulates a {@link BlockEntitySnapshot} — a block entity
 * that ticked this game tick (a hopper transferring, a furnace smelting, …), carrying
 * its fixed position and type. The resolver turns a snapshot into a
 * {@code BLOCK_ENTITY_*} {@link TaskNode} via {@code BlockEntityTaskFactory.inert(...)}.
 *
 * <p><b>Why dedup is by position, not by value.</b> A block entity is spatially fixed,
 * so unlike a moving mob its identity <em>is</em> its position. But its snapshot is not
 * necessarily value-stable within a tick — a hopper's {@code slotCount}/facing come from
 * live block state and a re-read could differ — so a value-dedup ({@code Set}) could
 * mint two {@code BLOCK_ENTITY_HOPPER@…} tasks for the same block, a spurious
 * self-conflict in one DAG. {@link #doEndTick} therefore dedups by {@link WorldPos}
 * keeping the <em>latest</em> recorded snapshot (first-sighting order, last value), so
 * each position seeds exactly one task. This mirrors the entity hook's dedup-by-id.
 *
 * <p><b>Scope (honest, not yet live-verified).</b> This is the accumulator + resolver
 * seam ONLY — a pure, unit-tested class in nebula-folia-bridge. Nothing in the plugin
 * calls {@link #recordDirty} or drives {@link #beginTick}/{@link #endTick} on a live
 * Folia tick yet, so it does NOT touch the live tick pipeline. Wiring a block-entity
 * dirty interceptor into {@code recordDirty} plus a GlobalRegionScheduler drain
 * bracketing NMS inventory sync is the next slice of C3, where the ⚡ live "first
 * block-entity DAG tick" milestone lands. The begin/end/drain contract mirrors
 * {@link RedstoneTickHook} and {@link EntityTickHook} exactly, including the DG3 fix:
 * {@link #beginTick} does NOT wipe the accumulator (endTick is the sole drain-and-remove
 * consumer), so a dirty recorded in the endTick→beginTick window survives to the next
 * endTick.
 */
public final class BlockEntityTickHook {

    private static final Logger LOG = Logger.getLogger(BlockEntityTickHook.class.getName());

    /** Callback interface for the game layer to implement block-entity-tick execution. */
    public interface TickExecutor {
        /**
         * Execute a list of block-entity tasks via the Nebula DAG for the given region.
         *
         * @param regionId   the Folia region identifier
         * @param worldName  dimension name
         * @param dirtyTasks tasks for block entities that ticked this tick
         * @throws DagExecutionException if any task fails
         */
        void executeTasks(String regionId, String worldName, List<TaskNode> dirtyTasks)
            throws DagExecutionException;
    }

    /** Callback to convert a dirty block-entity snapshot into a TaskNode. */
    public interface TaskResolver {
        /**
         * @param worldName dimension name
         * @param blockEntity snapshot of a block entity that ticked this tick
         * @return a TaskNode for this block entity, or {@code null} if it should not
         *         seed a task (e.g. an inert container with no autonomous tick)
         */
        TaskNode resolve(String worldName, BlockEntitySnapshot blockEntity);
    }

    private static volatile TickExecutor executor = null;
    private static volatile TaskResolver resolver = null;
    private static volatile boolean active = false;

    // Per-region dirty-block-entity accumulator, keyed by "regionId::dimId" so each
    // world gets its own bucket (endTick is called per-world; without the suffix the
    // first drain would empty every world's bucket). Mirrors RedstoneTickHook.
    private static final ConcurrentHashMap<String, Queue<BlockEntitySnapshot>> dirtyBlockEntities =
        new ConcurrentHashMap<>();

    // Guards against concurrent endTick execution for the same key (see RedstoneTickHook).
    private static final ConcurrentHashMap<String, AtomicBoolean> endTickInProgress =
        new ConcurrentHashMap<>();

    private BlockEntityTickHook() {}

    /**
     * Builds the composite key "regionId::dimId". The world name is normalized to its
     * canonical dimension ID via {@link DimensionIds#fromName(String)} so the NMS
     * namespaced form ({@code "minecraft:overworld"}) used by the recorder and the
     * Bukkit folder name ({@code "world"}) used by the drain driver converge on the
     * same bucket — exactly as in {@link RedstoneTickHook} and {@link EntityTickHook}.
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
     * (drains and removes each bucket), so a dirty recorded in the endTick→beginTick
     * window survives to the next endTick. This mirrors the RedstoneTickHook DG3 fix;
     * wiping here would drop in-flight dirties.
     */
    public static void beginTick(String regionId) {
        if (!active) return;
        String prefix = regionId + "::";
        endTickInProgress.entrySet().removeIf(entry -> entry.getKey().startsWith(prefix));
    }

    /**
     * Called when a block entity ticks (becomes dirty) this tick in the given region.
     *
     * @param regionId  the Folia region identifier
     * @param worldName dimension name (either the NMS namespaced or Bukkit folder form)
     * @param snapshot  the ticking block entity's snapshot (fixed position + type)
     */
    public static void recordDirty(String regionId, String worldName,
                                   BlockEntitySnapshot snapshot) {
        if (!active) return;
        if (snapshot == null) return;
        String k = key(regionId, worldName);
        Queue<BlockEntitySnapshot> dirty = dirtyBlockEntities.get(k);
        if (dirty == null) {
            dirty = new ConcurrentLinkedQueue<>();
            Queue<BlockEntitySnapshot> existing = dirtyBlockEntities.putIfAbsent(k, dirty);
            if (existing != null) {
                dirty = existing;
            }
        }
        dirty.add(snapshot);
    }

    /**
     * Called at the end of each Folia region tick. Resolves dirty block entities into
     * TaskNodes and, if an executor is registered, passes them to the DAG.
     *
     * @return the list of dirty tasks resolved this tick (for diagnostics)
     */
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

    /** Internal implementation of endTick, called under the AtomicBoolean guard. */
    private static List<TaskNode> doEndTick(String k, String regionId, String worldName) {
        Queue<BlockEntitySnapshot> dirty = dirtyBlockEntities.remove(k);
        if (dirty == null || dirty.isEmpty()) return List.of();

        TaskResolver res = resolver;
        if (res == null) return List.of();

        // Dedup by position, keeping the LATEST recorded snapshot (first-sighting
        // order, last value) — see class javadoc on why value-dedup is wrong here.
        Map<WorldPos, BlockEntitySnapshot> latest = new LinkedHashMap<>();
        for (BlockEntitySnapshot snap : dirty) {
            latest.put(snap.pos(), snap);
        }

        List<TaskNode> dirtyTasks = new ArrayList<>();
        for (BlockEntitySnapshot snap : latest.values()) {
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
                LOG.warning("Block-entity DAG execution failed in region " + regionId
                    + ": " + e.getMessage());
            }
        }

        if (dirtyTasks.size() > 100) {
            LOG.fine(() -> "Region " + regionId + ": " + dirtyTasks.size()
                + " dirty block-entity tasks in " + worldName);
        }

        return List.copyOf(dirtyTasks);
    }

    /** Returns current dirty-block-entity count for the given region and world (for monitoring). */
    public static int dirtyCount(String regionId, String worldName) {
        Queue<BlockEntitySnapshot> dirty = dirtyBlockEntities.get(key(regionId, worldName));
        return dirty == null ? 0 : dirty.size();
    }
}
