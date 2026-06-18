package org.nebula.plugin;

import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;
import org.nebula.folia.FoliaRegionBridge;
import org.nebula.folia.FoliaRegionTickDriver;
import org.nebula.folia.FoliaRegionTickExecutor;
import org.nebula.folia.FoliaRuntimeDetector;
import org.nebula.folia.bridge.NebulaFoliaBootstrap;
import org.nebula.folia.bridge.NeighborUpdateInterceptor;
import org.nebula.folia.bridge.RedstoneTickHook;
import org.nebula.guard.RWGuardConfig;
import org.nebula.guard.RWGuardMode;

import java.util.function.Function;
import java.util.logging.Logger;

/**
 * The Folia plugin entry point for Nebula.
 *
 * <p>On enable, it detects whether it is running inside a Folia server
 * ({@link FoliaRuntimeDetector#isFoliaRuntime()}), then wires the
 * region-aware tick executor ({@link FoliaRegionTickExecutor}) into the
 * {@link RedstoneTickHook} lifecycle so that Nebula's DAG dispatches
 * tasks only on the region threads that own the affected blocks.
 *
 * <p>When <em>not</em> running under Folia (e.g. unit tests, vanilla Paper),
 * the hook is left in OBSERVE mode and a region-blind shadow executor is used
 * as a fallback.
 */
public final class NebulaPlugin extends JavaPlugin {

    private static final Logger LOG = Logger.getLogger(NebulaPlugin.class.getName());

    /** Decodes a taskId of the form "TYPE@dim:x,y,z" into a WorldPos. */
    private static final Function<TaskNode, WorldPos> POSITION_OF =
        t -> {
            String id = t.taskId();
            int at = id.indexOf('@');
            return WorldPos.parse(at >= 0 ? id.substring(at + 1) : id);
        };

    private NebulaFoliaBootstrap bootstrap;

    @Override
    public void onEnable() {
        boolean isFolia = FoliaRuntimeDetector.isFoliaRuntime();
        LOG.info("Nebula plugin enabling — Folia runtime: " + isFolia);

        Server server = getServer();
        RWGuardConfig guardConfig = new RWGuardConfig(
            true, 0.01, RWGuardMode.WARN,
            getDataFolder().toPath().resolve("rw-violations.jsonl"),
            200, false
        );

        if (isFolia) {
            bootstrap = wireRegionAwareExecutor(server, guardConfig);
        } else {
            bootstrap = wireShadowExecutor(guardConfig);
        }
    }

    /**
     * Folia path: build the full {@link FoliaRegionBridge} →
     * {@link FoliaRegionTickDriver} → {@link FoliaRegionTickExecutor} chain
     * and install it as the hook's {@link RedstoneTickHook.TickExecutor}.
     */
    private NebulaFoliaBootstrap wireRegionAwareExecutor(Server server,
                                                         RWGuardConfig guardConfig) {
        FoliaRegionBridge bridge = new FoliaRegionBridge(server, this);
        FoliaRegionTickDriver driver = new FoliaRegionTickDriver(bridge);

        FoliaRegionTickExecutor executor = new FoliaRegionTickExecutor(
            server, driver, POSITION_OF,
            (world, worldName, ownedTasks) -> {
                // TODO (#3): replace with real MicroStepScheduler once CAS stores
                // are bound to NMS state. For now the shadow runner executes inert tasks.
                LOG.fine(() -> "Region-owned DAG tick: " + ownedTasks.size()
                    + " tasks in " + worldName);
            });

        NebulaFoliaBootstrap bootstrap = NebulaFoliaBootstrap.configure(guardConfig)
            .withInterceptMode()
            .withTickExecutor(executor)
            .activate();

        LOG.info("Nebula wired with region-aware FoliaRegionTickExecutor (INTERCEPT mode)");
        return bootstrap;
    }

    /**
     * Non-Folia fallback: OBSERVE mode with a region-blind shadow executor that
     * simply logs dirty tasks. No scheduling happens; this is a diagnostic path.
     */
    private NebulaFoliaBootstrap wireShadowExecutor(RWGuardConfig guardConfig) {
        RedstoneTickHook.TickExecutor shadowExecutor =
            (regionId, worldName, dirtyTasks) -> {
                LOG.fine(() -> "Shadow tick: region=" + regionId
                    + " world=" + worldName + " tasks=" + dirtyTasks.size());
            };

        NebulaFoliaBootstrap bootstrap = NebulaFoliaBootstrap.configure(guardConfig)
            .withObserveMode()
            .withTickExecutor(shadowExecutor)
            .activate();

        LOG.info("Nebula wired with shadow executor (OBSERVE mode, non-Folia)");
        return bootstrap;
    }

    @Override
    public void onDisable() {
        if (bootstrap != null) {
            bootstrap.deactivate();
        }
        LOG.info("Nebula plugin disabled");
    }

    /** Returns the active bootstrap (for diagnostics). */
    public NebulaFoliaBootstrap bootstrap() {
        return bootstrap;
    }
}