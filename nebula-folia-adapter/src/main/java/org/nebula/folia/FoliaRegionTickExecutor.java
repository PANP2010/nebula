package org.nebula.folia;

import org.bukkit.Server;
import org.bukkit.World;
import org.nebula.core.scheduler.DagExecutionException;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;
import org.nebula.folia.bridge.RedstoneTickHook;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * The concrete {@link RedstoneTickHook.TickExecutor} that connects Nebula's
 * per-tick dirty-task set to Folia's region threading.
 *
 * <p>This executor is invoked from the <em>global tick thread</em> (via
 * {@code RedstoneTickHook.endTick()} driven by {@code GlobalRegionScheduler}).
 * The {@code regionId} parameter is always {@code "nebula-global"} because all
 * updates are accumulated into a single global bucket (see
 * {@link RedstoneTickHook} javadoc).  Because no region thread is active,
 * {@code isOwnedByCurrentRegion()} always returns false — making an
 * owned/foreign partition pointless. Instead, every dirty task is dispatched
 * to its owning region thread via
 * {@link RegionScheduler#execute RegionScheduler.execute()}, where the injected
 * {@link OwnedDagRunner} executes it in the correct region context.
 *
 * <p>This is where region-aware partitioning actually happens: each task is
 * dispatched to the region thread that owns its chunk coordinates, ensuring
 * thread-safe access to the world state for that region.
 *
 * <p>Kept subsystem-agnostic: the {@link Function position function} and the
 * {@link OwnedDagRunner DAG runner} are injected, so redstone, entity-physics,
 * and block-entity subsystems all reuse this same wiring with their own
 * task-id to position decoding and their own scheduler.
 */
public final class FoliaRegionTickExecutor implements RedstoneTickHook.TickExecutor {

    private static final Logger LOG = Logger.getLogger(FoliaRegionTickExecutor.class.getName());

    /**
     * Runs a batch of tasks through the real Nebula DAG executor. Invoked on
     * the owning region thread for each task (or batch of tasks for the same
     * region).
     */
    @FunctionalInterface
    public interface OwnedDagRunner {
        void run(World world, String worldName, List<TaskNode> tasks)
            throws DagExecutionException;
    }

    private final Server server;
    private final Function<TaskNode, WorldPos> positionOf;
    private final OwnedDagRunner ownedRunner;
    private final org.bukkit.plugin.Plugin plugin;

    public FoliaRegionTickExecutor(Server server,
                                   Function<TaskNode, WorldPos> positionOf,
                                   OwnedDagRunner ownedRunner,
                                   org.bukkit.plugin.Plugin plugin) {
        this.server = Objects.requireNonNull(server, "server");
        this.positionOf = Objects.requireNonNull(positionOf, "positionOf");
        this.ownedRunner = Objects.requireNonNull(ownedRunner, "ownedRunner");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void executeTasks(String regionId, String worldName, List<TaskNode> dirtyTasks)
            throws DagExecutionException {
        if (dirtyTasks == null || dirtyTasks.isEmpty()) {
            return;
        }
        World world = server.getWorld(worldName);
        if (world == null) {
            LOG.warning(() -> "No world for '" + worldName + "' (region " + regionId
                + ") — dropping " + dirtyTasks.size() + " dirty tasks");
            return;
        }

        // Dispatch every task to its owning region thread via RegionScheduler.
        // This is always necessary because executeTasks runs on the global tick
        // thread, not on any region thread.
        var scheduler = server.getRegionScheduler();
        for (TaskNode task : dirtyTasks) {
            WorldPos pos = positionOf.apply(task);
            if (pos == null) {
                LOG.fine(() -> "Skipping task " + task.taskId()
                    + " — positionOf returned null");
                continue;
            }
            scheduler.execute(plugin, world, pos.x() >> 4, pos.z() >> 4, () -> {
                try {
                    ownedRunner.run(world, worldName, List.of(task));
                } catch (DagExecutionException e) {
                    LOG.warning(() -> "DAG execution failed for "
                        + task.taskId() + " in " + worldName + ": " + e.getMessage());
                }
            });
        }
    }
}
