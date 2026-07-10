package org.nebula.folia;

import org.bukkit.Server;
import org.bukkit.World;
import org.nebula.core.scheduler.DagExecutionException;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.folia.bridge.RedstoneTickHook;

import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * The non-Folia (single-thread host, e.g. Paper) analogue of
 * {@link FoliaRegionTickExecutor}: the {@link RedstoneTickHook.TickExecutor}
 * that drives Nebula's per-tick dirty-task set through the real DAG cycle
 * <em>inline on the calling thread</em>.
 *
 * <h3>Why inline, and why this is the B9 single-thread oracle enabler</h3>
 * On Folia the {@link FoliaRegionTickExecutor} must hop every task to its
 * owning region thread via {@code RegionScheduler.execute()} because block
 * state is only safe to touch on that region thread. On a single-region host
 * like Paper there is exactly one owning thread — the main thread — and the
 * lifecycle driver already runs {@code endTick} there, so no region hop is
 * needed (and none is available in a meaningful sense). We therefore call the
 * injected {@link FoliaRegionTickExecutor.OwnedDagRunner} directly with the
 * whole dirty batch, running {@code syncFromNms → MicroStepScheduler →
 * syncToNms} synchronously.
 *
 * <p>Before this existed the non-Folia path installed a log-only executor
 * (it printed {@code dirtyTasks.size()} and returned), so a Paper server had
 * no shadow CAS state to read back — the B9 differential ("multi-thread Nebula
 * == single-thread MC") had nothing to compare against. This executor is what
 * makes a Paper run produce real per-tick Nebula power in the CAS store, which
 * a later {@code /nebula diff} (D3) reads on the same main thread against
 * Paper's authoritative block state.
 *
 * <p>Deliberately subsystem-agnostic and dependency-symmetric with
 * {@link FoliaRegionTickExecutor}: the {@link FoliaRegionTickExecutor.OwnedDagRunner
 * DAG runner} is injected, so the plugin reuses its existing
 * {@code executeOwnedDag} body unchanged.
 */
public final class InlineShadowTickExecutor implements RedstoneTickHook.TickExecutor {

    private static final Logger LOG = Logger.getLogger(InlineShadowTickExecutor.class.getName());

    private final Server server;
    private final FoliaRegionTickExecutor.OwnedDagRunner ownedRunner;

    public InlineShadowTickExecutor(Server server,
                                    FoliaRegionTickExecutor.OwnedDagRunner ownedRunner) {
        this.server = Objects.requireNonNull(server, "server");
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
        // Single-region host: the caller (endTick lifecycle driver) is already
        // on the main thread that owns every chunk, so run the whole batch inline.
        ownedRunner.run(world, worldName, dirtyTasks);
    }
}
