package org.nebula.plugin;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.BlockEntityState;
import org.nebula.entity.EntityPhysicsState;
import org.nebula.entity.EntityTickExecutor;
import org.nebula.entity.EntityTaskRunner;
import org.nebula.entity.actions.EntityMoveAction;
import org.nebula.entity.actions.EntityCollisionResponseAction;
import org.nebula.folia.FoliaRegionBridge;
import org.nebula.folia.FoliaRegionTickDriver;
import org.nebula.folia.FoliaRegionTickExecutor;
import org.nebula.folia.FoliaRuntimeDetector;
import org.nebula.folia.NmsBlockEntityStateBridge;
import org.nebula.folia.NmsBlockStateBridge;
import org.nebula.folia.NmsEntityStateBridge;
import org.nebula.folia.WorldStateHasher;
import org.nebula.folia.bridge.FoliaCaptureHarness;
import org.nebula.folia.bridge.NebulaFoliaBootstrap;
import org.nebula.folia.bridge.RedstoneTickHook;
import org.nebula.guard.RWGuardConfig;
import org.nebula.guard.RWGuardMode;
import org.nebula.redstone.MicroStepScheduler;
import org.nebula.redstone.RedstoneComponentType;
import org.nebula.redstone.RedstoneTaskAction;
import org.nebula.redstone.RedstoneTaskGenerator;
import org.nebula.redstone.RedstoneTaskRunner;
import org.nebula.redstone.RedstoneWorldState;
import org.nebula.redstone.actions.RedstoneActions;
import org.nebula.core.scheduler.CompositeTaskRunner;
import org.nebula.replay.ReplayRecorder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * The Folia plugin entry point for Nebula.
 *
 * <p>On enable, it:
 * <ol>
 *   <li>Detects Folia runtime via {@link FoliaRuntimeDetector#isFoliaRuntime()}</li>
 *   <li>Initializes CAS state stores (RedstoneWorldState, EntityPhysicsState, BlockEntityState)</li>
 *   <li>Creates NMS bridges (NmsBlockStateBridge, NmsEntityStateBridge, NmsBlockEntityStateBridge)</li>
 *   <li>Wires the region-aware tick executor (FoliaRegionTickExecutor)</li>
 *   <li>Optionally starts capture harness for zero-diff verification</li>
 * </ol>
 *
 * <p>When not running under Folia, falls back to OBSERVE mode with a shadow executor.
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

    // CAS state stores
    private RedstoneWorldState redstoneState;
    private EntityPhysicsState entityState;
    private BlockEntityState blockEntityState;

    // NMS bridges
    private NmsBlockStateBridge blockBridge;
    private NmsEntityStateBridge entityBridge;
    private NmsBlockEntityStateBridge blockEntityBridge;
    private WorldStateHasher stateHasher;

    // DAG execution
    private MicroStepScheduler microStepScheduler;
    private RedstoneTaskRunner redstoneRunner;
    private RedstoneTaskGenerator taskGenerator;
    private Map<WorldPos, RedstoneComponentType> componentMap;

    // Entity physics DAG
    private EntityTickExecutor entityTickExecutor;
    private EntityTaskRunner entityRunner;

    // Composite DAG runner (redstone + entity)
    private CompositeTaskRunner compositeRunner;

    // Capture harness (optional)
    private FoliaCaptureHarness captureHarness;
    private ReplayRecorder recorder;

    private NebulaFoliaBootstrap bootstrap;

    @Override
    public void onEnable() {
        boolean isFolia = FoliaRuntimeDetector.isFoliaRuntime();
        LOG.info("Nebula plugin enabling — Folia runtime: " + isFolia);

        // Initialize CAS state stores
        redstoneState = new RedstoneWorldState();
        entityState = new EntityPhysicsState();
        blockEntityState = new BlockEntityState();

        // Create NMS bridges
        blockBridge = new NmsBlockStateBridge(redstoneState);
        entityBridge = new NmsEntityStateBridge(entityState);
        blockEntityBridge = new NmsBlockEntityStateBridge(blockEntityState);

        // Create state hasher for zero-diff verification
        stateHasher = new WorldStateHasher(bytes -> WorldPos.parse(new String(bytes)));

        // Create DAG execution pipeline
        componentMap = new ConcurrentHashMap<>();
        Map<String, RedstoneTaskAction> actionRegistry = RedstoneActions.defaults();
        taskGenerator = new RedstoneTaskGenerator(componentMap, actionRegistry);
        redstoneRunner = new RedstoneTaskRunner(redstoneState, actionRegistry);
        microStepScheduler = new MicroStepScheduler(taskGenerator, redstoneRunner);

        // Create entity physics DAG pipeline
        entityRunner = new EntityTaskRunner(entityState,
            taskId -> resolveEntityAction(taskId));
        entityTickExecutor = new EntityTickExecutor(entityRunner);

        // Create composite runner for unified redstone + entity DAG
        compositeRunner = new CompositeTaskRunner()
            .routeByTypePrefix("REDSTONE_", redstoneRunner)
            .routeByTypePrefix("MOVE", entityRunner)
            .routeByTypePrefix("COLLISION", entityRunner);

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
     * Folia path: wire full pipeline with NMS bridges.
     */
    private NebulaFoliaBootstrap wireRegionAwareExecutor(Server server,
                                                         RWGuardConfig guardConfig) {
        FoliaRegionBridge bridge = new FoliaRegionBridge(server, this);
        FoliaRegionTickDriver driver = new FoliaRegionTickDriver(bridge);

        FoliaRegionTickExecutor executor = new FoliaRegionTickExecutor(
            server, driver, POSITION_OF,
            (world, worldName, ownedTasks) -> {
                executeOwnedDag(world, ownedTasks);
            });

        NebulaFoliaBootstrap bootstrap = NebulaFoliaBootstrap.configure(guardConfig)
            .withInterceptMode()
            .withTickExecutor(executor)
            .activate();

        LOG.info("Nebula wired with region-aware FoliaRegionTickExecutor (INTERCEPT mode)");
        LOG.info("NMS bridges initialized: block, entity, blockEntity");
        return bootstrap;
    }

    /**
     * Executes the owned DAG partition using NMS bridges for state sync
     * and MicroStepScheduler for real DAG execution.
     *
     * <p>For each tick:
     * <ol>
     *   <li>Sync FROM NMS: read current world state into CAS stores</li>
     *   <li>Execute DAG: run MicroStepScheduler with microstep expansion</li>
     *   <li>Sync TO NMS: write CAS state back to world</li>
     * </ol>
     */
    private void executeOwnedDag(World world, java.util.List<TaskNode> ownedTasks) {
        if (ownedTasks.isEmpty()) return;

        long t0 = System.nanoTime();

        // Phase 1: Sync FROM NMS for all affected positions
        for (TaskNode task : ownedTasks) {
            WorldPos pos = POSITION_OF.apply(task);
            blockBridge.syncFromNms(world, pos);
            blockEntityBridge.syncFromNms(world, pos);
        }

        // Phase 2: Execute DAG via MicroStepScheduler
        int totalTasks = ownedTasks.size();
        int microSteps = 0;
        try {
            MicroStepScheduler.TickResult result = microStepScheduler.executeTick(ownedTasks);
            totalTasks = result.totalTasks();
            microSteps = result.microSteps();
            if (result.hasCommitFailures()) {
                LOG.warning(() -> "DAG tick had " + result.commitFailures().size()
                    + " CAS commit failures");
            }
        } catch (org.nebula.core.scheduler.DagExecutionException e) {
            LOG.warning(() -> "DAG execution failed: " + e.getMessage());
        }

        // Phase 3: Sync TO NMS (write back changes)
        for (TaskNode task : ownedTasks) {
            WorldPos pos = POSITION_OF.apply(task);
            blockBridge.syncToNms(world, pos, redstoneState.getPowerLevel(pos));
            blockEntityBridge.syncToNms(world, pos);
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        int finalTasks = totalTasks;
        int finalMicroSteps = microSteps;
        LOG.fine(() -> "DAG tick: " + finalTasks + " tasks, "
            + finalMicroSteps + " microsteps in " + elapsedMs + "ms");
    }

    /**
     * Non-Folia fallback: OBSERVE mode with shadow executor.
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

    /**
     * Starts the capture harness for zero-diff verification.
     * Records state hashes for the specified number of ticks.
     */
    public void startCapture(long ticks) {
        if (captureHarness != null && captureHarness.isRunning()) {
            LOG.warning("Capture already running");
            return;
        }
        recorder = new ReplayRecorder();
        captureHarness = new FoliaCaptureHarness(this, recorder, stateHasher);
        captureHarness.start(ticks);
        LOG.info("Capture harness started: " + ticks + " ticks");
    }

    /** Stops the capture harness and returns recorded frames. */
    public java.util.List<org.nebula.replay.ReplayFrame> stopCapture() {
        if (captureHarness != null) {
            captureHarness.stop();
            return recorder.getFrames();
        }
        return java.util.List.of();
    }

    @Override
    public void onDisable() {
        if (captureHarness != null) {
            captureHarness.stop();
        }
        if (bootstrap != null) {
            bootstrap.deactivate();
        }
        LOG.info("Nebula plugin disabled");
    }

    // Accessors for diagnostics
    public NebulaFoliaBootstrap bootstrap() { return bootstrap; }
    public RedstoneWorldState redstoneState() { return redstoneState; }
    public EntityPhysicsState entityState() { return entityState; }
    public BlockEntityState blockEntityState() { return blockEntityState; }
    public NmsBlockStateBridge blockBridge() { return blockBridge; }
    public NmsEntityStateBridge entityBridge() { return entityBridge; }
    public NmsBlockEntityStateBridge blockEntityBridge() { return blockEntityBridge; }
    public WorldStateHasher stateHasher() { return stateHasher; }
    public MicroStepScheduler microStepScheduler() { return microStepScheduler; }
    public RedstoneTaskGenerator taskGenerator() { return taskGenerator; }

    /**
     * Registers a redstone component position. Required for DAG execution
     * to generate downstream tasks for signal propagation.
     */
    public void registerRedstoneComponent(WorldPos pos, RedstoneComponentType type) {
        componentMap.put(pos, type);
    }

    /**
     * Unregisters a redstone component position.
     */
    public void unregisterRedstoneComponent(WorldPos pos) {
        componentMap.remove(pos);
    }

    /**
     * Resolves an entity task action by task ID prefix.
     * Task IDs are of the form "MOVE@dim:entityId" or "COLLISION@dim:entityA:entityB".
     */
    private org.nebula.entity.EntityTaskAction resolveEntityAction(String taskId) {
        int at = taskId.indexOf('@');
        String suffix = at >= 0 ? taskId.substring(at + 1) : "";
        String prefix = at >= 0 ? taskId.substring(0, at) : taskId;

        if ("MOVE".equals(prefix)) {
            long entityId = parseEntityId(suffix);
            return new EntityMoveAction(entityId);
        }
        if ("COLLISION".equals(prefix)) {
            long[] ids = parseCollisionIds(suffix);
            return new EntityCollisionResponseAction(ids[0], ids[1]);
        }
        return null;
    }

    private static long parseEntityId(String suffix) {
        // suffix is "dim:entityId" — take last part
        String[] parts = suffix.split(":");
        if (parts.length >= 2) {
            try { return Long.parseLong(parts[parts.length - 1]); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    private static long[] parseCollisionIds(String suffix) {
        // suffix is "dim:entityA:entityB"
        String[] parts = suffix.split(":");
        long a = 0, b = 0;
        if (parts.length >= 3) {
            try { a = Long.parseLong(parts[parts.length - 2]); } catch (NumberFormatException e) {}
            try { b = Long.parseLong(parts[parts.length - 1]); } catch (NumberFormatException e) {}
        }
        return new long[]{a, b};
    }
}