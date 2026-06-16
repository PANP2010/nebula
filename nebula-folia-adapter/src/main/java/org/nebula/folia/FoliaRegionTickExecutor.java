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
 * per-tick dirty-task set to Folia's region threading via
 * {@link FoliaRegionTickDriver}.
 *
 * <p>This is release-path task #2's remaining half: the {@link RedstoneTickHook}
 * lifecycle ({@code beginTick → recordUpdate → endTick}) hands a region-blind
 * list of dirty {@link TaskNode}s to {@link #executeTasks}. Folia, however,
 * forbids touching a block from any thread other than the one ticking the region
 * that owns it. This executor reconciles the two:
 *
 * <ol>
 *   <li>resolve the {@link World} for the dimension being ticked;</li>
 *   <li>partition dirty tasks into <em>owned</em> (this region thread may run
 *       them now) and <em>foreign</em> via
 *       {@link FoliaRegionTickDriver#tickOwnedTasks};</li>
 *   <li>run the owned tasks inline through the injected {@link OwnedDagRunner}
 *       (the real DAG execution, e.g. {@code MicroStepScheduler});</li>
 *   <li>dispatch each foreign task onto the region thread that owns it via
 *       {@link FoliaRegionTickDriver#dispatchForeignTasks}, where the same
 *       runner executes it in the correct region context.</li>
 * </ol>
 *
 * <p>Kept subsystem-agnostic: the {@link Function position function} and the
 * {@link OwnedDagRunner DAG runner} are injected, so redstone, entity-physics,
 * and block-entity subsystems all reuse this same wiring with their own
 * task-id → position decoding and their own scheduler.
 */
public final class FoliaRegionTickExecutor implements RedstoneTickHook.TickExecutor {

    private static final Logger LOG = Logger.getLogger(FoliaRegionTickExecutor.class.getName());

    /**
     * Runs a batch of tasks that are owned by the current region thread through
     * the real Nebula DAG executor. Invoked both for the inline owned partition
     * and, later, for each foreign task once it has been re-scheduled onto its
     * owning region thread.
     */
    @FunctionalInterface
    public interface OwnedDagRunner {
        void run(World world, String worldName, List<TaskNode> ownedTasks)
            throws DagExecutionException;
    }

    private final Server server;
    private final FoliaRegionTickDriver driver;
    private final Function<TaskNode, WorldPos> positionOf;
    private final OwnedDagRunner ownedRunner;

    public FoliaRegionTickExecutor(Server server,
                                   FoliaRegionTickDriver driver,
                                   Function<TaskNode, WorldPos> positionOf,
                                   OwnedDagRunner ownedRunner) {
        this.server = Objects.requireNonNull(server, "server");
        this.driver = Objects.requireNonNull(driver, "driver");
        this.positionOf = Objects.requireNonNull(positionOf, "positionOf");
        this.ownedRunner = Objects.requireNonNull(ownedRunner, "ownedRunner");
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

        // (1)+(2)+(3): run the owned partition inline on this region thread, get the foreign rest.
        List<TaskNode> foreign;
        try {
            foreign = driver.tickOwnedTasks(world, dirtyTasks, positionOf,
                (w, owned) -> ownedRunner.run(w, worldName, owned));
        } catch (DagExecutionException e) {
            throw e;
        } catch (Exception e) {
            // Surface non-DAG failures (e.g. position decoding) as a DAG failure
            // so the hook's existing diagnostics path reports them uniformly.
            throw new DagExecutionException(-1, List.of(), List.of(e));
        }

        if (foreign.isEmpty()) {
            return;
        }

        // (4): hand each foreign task to the region thread that owns it. The
        // RegionScheduler runnable cannot propagate checked exceptions, so a DAG
        // failure on a foreign region is logged rather than rethrown here.
        driver.dispatchForeignTasks(world, foreign, positionOf, task -> {
            try {
                ownedRunner.run(world, worldName, List.of(task));
            } catch (DagExecutionException e) {
                LOG.warning(() -> "Foreign-region DAG execution failed for "
                    + task.taskId() + " in " + worldName + ": " + e.getMessage());
            }
        });
    }
}
