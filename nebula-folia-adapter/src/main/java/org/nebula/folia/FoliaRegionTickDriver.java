package org.nebula.folia;

import org.bukkit.World;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Drives a Nebula DAG tick within Folia's region-threading model.
 *
 * <p>Folia ticks each region on its own thread; a block may only be touched
 * from the thread that owns its region. Nebula's DAG produces tasks keyed by
 * {@link WorldPos} that may span regions. This driver is the seam between the
 * two: per region tick it
 * <ol>
 *   <li>filters the dirty task set down to tasks whose position the
 *       <em>current</em> region owns ({@link FoliaRegionBridge#ownsCurrentRegion}),
 *       so a region only executes the work it is allowed to touch; and</li>
 *   <li>hands that owned subset to the supplied Nebula tick executor.</li>
 * </ol>
 *
 * <p>Tasks belonging to other regions are skipped here — they are executed when
 * <em>their</em> region ticks, or dispatched onto the owning region thread via
 * {@link #scheduleForOwningRegion}. This keeps Nebula's per-position scheduling
 * consistent with Folia's region ownership instead of touching foreign regions
 * from the wrong thread.
 *
 * <p>The driver is constructed with a {@link FoliaRegionBridge} (the real-API
 * boundary) and is therefore unit-testable with a stubbed bridge.
 */
public final class FoliaRegionTickDriver {

    /** Executes the region-owned dirty tasks through the Nebula DAG. */
    @FunctionalInterface
    public interface OwnedTaskExecutor {
        void execute(World world, List<TaskNode> ownedTasks) throws Exception;
    }

    private final FoliaRegionBridge bridge;

    public FoliaRegionTickDriver(FoliaRegionBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    /**
     * Partitions {@code dirtyTasks} into the subset the current region owns and
     * runs them through {@code executor}. Tasks whose position is owned by
     * another region are returned (not executed here) so the caller can route
     * or defer them.
     *
     * @param world      the world being ticked
     * @param dirtyTasks all dirty tasks for this tick (any region)
     * @param positionOf maps a task to the {@link WorldPos} used for ownership
     * @param executor   runs the owned subset through Nebula's DAG
     * @return the tasks NOT owned by the current region (deferred to their owner)
     */
    public List<TaskNode> tickOwnedTasks(World world,
                                         List<TaskNode> dirtyTasks,
                                         java.util.function.Function<TaskNode, WorldPos> positionOf,
                                         OwnedTaskExecutor executor) throws Exception {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(dirtyTasks, "dirtyTasks");
        Objects.requireNonNull(positionOf, "positionOf");
        Objects.requireNonNull(executor, "executor");

        List<TaskNode> owned = new ArrayList<>();
        List<TaskNode> foreign = new ArrayList<>();
        for (TaskNode task : dirtyTasks) {
            WorldPos pos = positionOf.apply(task);
            if (pos != null && bridge.ownsCurrentRegion(world, pos)) {
                owned.add(task);
            } else {
                foreign.add(task);
            }
        }

        if (!owned.isEmpty()) {
            executor.execute(world, owned);
        }
        return foreign;
    }

    /**
     * Schedules {@code work} for a position onto the region thread that owns it.
     * Use this to hand a foreign-region task back to its owner after a tick,
     * rather than touching that region's state from the wrong thread.
     */
    public void scheduleForOwningRegion(World world, WorldPos pos, Runnable work) {
        bridge.runOnRegion(world, pos, work);
    }

    /**
     * Convenience: groups foreign tasks by owning region and schedules each on
     * its owner via {@code perTask}. The grouping is implicit — each task is
     * dispatched to the region thread that owns its position.
     */
    public void dispatchForeignTasks(World world,
                                     List<TaskNode> foreignTasks,
                                     java.util.function.Function<TaskNode, WorldPos> positionOf,
                                     Consumer<TaskNode> perTask) {
        for (TaskNode task : foreignTasks) {
            WorldPos pos = positionOf.apply(task);
            if (pos != null) {
                bridge.runOnRegion(world, pos, () -> perTask.accept(task));
            }
        }
    }
}
