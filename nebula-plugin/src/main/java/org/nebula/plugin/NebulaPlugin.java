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

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;

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

    // World scanner
    private WorldRedstoneScanner worldScanner;

    // Capture harness (optional)
    private FoliaCaptureHarness captureHarness;
    private ReplayRecorder recorder;

    private NebulaFoliaBootstrap bootstrap;

    @Override
    public void onEnable() {
        boolean isFolia = FoliaRuntimeDetector.isFoliaRuntime();
        LOG.info("Nebula plugin enabling — Folia runtime: " + isFolia);

        // Force retransform of Folia's redstone classes that may have been
        // loaded before the agent's transformer was fully initialized.
        // This ensures our ASM hooks are injected into already-loaded classes.
        if (isFolia) {
            ClassLoader sysLoader = ClassLoader.getSystemClassLoader();
            try {
                // Agent classes are loaded by the system class loader
                Class<?> agentClass = Class.forName("org.nebula.agent.NebulaAgent", false, sysLoader);
                java.lang.instrument.Instrumentation inst = (java.lang.instrument.Instrumentation)
                    agentClass.getMethod("getInstrumentation").invoke(null);
                if (inst != null) {
                    // NMS classes are loaded by the context class loader (Folia's loader)
                    ClassLoader ctxLoader = Thread.currentThread().getContextClassLoader();
                    Class<?>[] classes = {
                        Class.forName("io.papermc.paper.redstone.RedstoneWireTurbo", false, ctxLoader),
                        Class.forName("net.minecraft.world.level.redstone.CollectingNeighborUpdater", false, ctxLoader),
                        Class.forName("net.minecraft.world.level.redstone.InstantNeighborUpdater", false, ctxLoader),
                        Class.forName("net.minecraft.world.level.redstone.NeighborUpdater", false, ctxLoader)
                    };
                    inst.retransformClasses(classes);
                    LOG.info("Retransformed " + classes.length + " Folia redstone classes");
                } else {
                    LOG.warning("NebulaAgent.getInstrumentation() returned null");
                }
            } catch (Exception e) {
                LOG.warning("Failed to retransform Folia redstone classes: " + e.getClass().getName() + ": " + e.getMessage());
            }

            // ── Verification: confirm ASM hooks are actually active ──────────────
            // The sentinel field NeighborUpdateHooks.hooksActive is set to true
            // by the injected bytecode at the top of every instrumented method.
            // If retransform silently failed (e.g. class format mismatch, missing
            // method, or the transformer was not registered), hooksActive will
            // remain false and Nebula must fall back to the shadow executor to
            // avoid silent data corruption.
            boolean hooksVerified = false;
            try {
                Class<?> hooksClass = Class.forName(
                    "org.nebula.agent.NeighborUpdateHooks", false, sysLoader);
                java.lang.reflect.Field hooksActiveField = hooksClass.getField("hooksActive");
                hooksVerified = hooksActiveField.getBoolean(null);
            } catch (Exception e) {
                LOG.severe("Failed to read NeighborUpdateHooks.hooksActive sentinel: "
                    + e.getClass().getName() + ": " + e.getMessage());
            }

            if (!hooksVerified) {
                LOG.severe("╔══════════════════════════════════════════════════════════════╗");
                LOG.severe("║  NEIGHBOR UPDATE HOOKS ARE NOT ACTIVE!                      ║");
                LOG.severe("║  ASM retransform of Folia redstone classes appears to have   ║");
                LOG.severe("║  failed silently.  Nebula will FALL BACK to shadow executor ║");
                LOG.severe("║  to prevent silent data corruption.                          ║");
                LOG.severe("║  Check agent setup: -javaagent:nebula-agent.jar              ║");
                LOG.severe("╚══════════════════════════════════════════════════════════════╝");
                // Fall back: disable Folia-specific wiring and use shadow executor.
                // This prevents the region-aware executor from running without hooks,
                // which would cause Nebula to miss neighbor updates and produce
                // incorrect redstone state.
                isFolia = false;
            } else {
                LOG.info("NeighborUpdateHooks sentinel verified: hooks are active");
            }
        }

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

        // Create world scanner for redstone discovery
        worldScanner = new WorldRedstoneScanner(this);

        // B2 fix: scan already-loaded chunks synchronously during onEnable,
        // BEFORE any redstone tick can happen.  The delayed async scan below
        // is kept as a safety net for chunks that finish loading later.
        for (World w : server.getWorlds()) {
            for (org.bukkit.Chunk chunk : w.getLoadedChunks()) {
                worldScanner.scanChunk(w, chunk);
            }
        }
        LOG.info(() -> "Initial sync scan complete: " + componentMap.size() + " redstone components registered");

        // Register chunk load listener for incremental scanning
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
                World world = event.getWorld();
                int total = worldScanner.scanChunk(world, event.getChunk());
                if (total > 0) {
                    LOG.fine(() -> "Scanned chunk " + event.getChunk().getX() + "," + event.getChunk().getZ()
                        + " in " + world.getName() + ": " + total + " redstone components");
                }
            }
        }, this);

        // Register block place/break listeners for incremental registration
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onBlockPlace(org.bukkit.event.block.BlockPlaceEvent event) {
                worldScanner.scanBlock(event.getBlock());
            }

            @org.bukkit.event.EventHandler
            public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent event) {
                worldScanner.unregisterBlock(event.getBlock());
            }
        }, this);

        // Schedule delayed scan of already-loaded chunks using GlobalRegionScheduler
        // We delay 100 ticks to let the world finish loading, then dispatch
        // per-chunk scan tasks to each chunk's owning region via RegionScheduler.
        server.getGlobalRegionScheduler().runDelayed(this, task -> {
            io.papermc.paper.threadedregions.scheduler.RegionScheduler regionScheduler = server.getRegionScheduler();
            for (World w : server.getWorlds()) {
                for (org.bukkit.Chunk chunk : w.getLoadedChunks()) {
                    regionScheduler.execute(this, w, chunk.getX(), chunk.getZ(), () -> {
                        int total = worldScanner.scanChunk(w, chunk);
                        if (total > 0) {
                            LOG.info("Initial scan of chunk " + chunk.getX() + "," + chunk.getZ()
                                + " in " + w.getName() + ": " + total + " redstone components");
                        }
                    });
                }
            }
        }, 100L);

        // Register commands
        NebulaCommand nebulaCmd = new NebulaCommand(this);
        getCommand("nebula").setExecutor(nebulaCmd);
        getCommand("nebula").setTabCompleter(nebulaCmd);

        // ── B1 fix: Drive RedstoneTickHook lifecycle via ServerTickStartEvent/ServerTickEndEvent ──
        // RedstoneTickHook.beginTick()/endTick() must bracket a full Folia tick so
        // that region threads have a window to call recordUpdate() between them.
        //
        // GlobalRegionScheduler lifecycle driver:
        // Run two separate tasks — beginTick in one, endTick in the next tick.
        // This brackets the region tick properly, so recordUpdate() calls made during
        // region threads are captured by endTick() in the subsequent tick.
        //
        // The regionId is always "nebula-global" because the interceptor runs on
        // region threads while beginTick/endTick run on the global tick thread.
        // Collecting all updates into one bucket avoids thread-handoff complexity.
        // Region-aware partitioning happens downstream at FoliaRegionTickExecutor,
        // which dispatches each task to its owning region thread via
        // RegionScheduler.execute().
        if (isFolia) {
            java.util.concurrent.atomic.AtomicBoolean tickPhase = new java.util.concurrent.atomic.AtomicBoolean(false);
            getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> {
                if (!RedstoneTickHook.isActive()) return;

                boolean isBeginPhase = tickPhase.get();
                if (isBeginPhase) {
                    // Begin phase: clear dirty positions for new tick
                    RedstoneTickHook.beginTick("nebula-global");
                } else {
                    // End phase: execute DAG for accumulated dirty positions
                    String regionId = "nebula-global";
                    for (World w : server.getWorlds()) {
                        String worldName = w.getName();
                        RedstoneTickHook.endTick(regionId, worldName);
                    }
                }

                tickPhase.set(!isBeginPhase);
            }, 1, 1);
            LOG.info("RedstoneTickHook lifecycle driver registered (global tick, alternating begin/end)");
        }
        // Temporary diagnostic: register Bukkit event listener
        getServer().getPluginManager().registerEvents(new RedstoneEventListener(), this);
        LOG.info("[Nebula] Registered RedstoneEventListener for diagnostics");
    }

    /**
     * Folia path: wire full pipeline with NMS bridges.
     */
    private NebulaFoliaBootstrap wireRegionAwareExecutor(Server server,
                                                         RWGuardConfig guardConfig) {
        FoliaRegionTickExecutor executor = new FoliaRegionTickExecutor(
            server, POSITION_OF,
            (world, worldName, tasks) -> {
                executeOwnedDag(world, tasks);
            }, this);

        NebulaFoliaBootstrap bootstrap = NebulaFoliaBootstrap.configure(guardConfig)
            .withInterceptMode()
            .withTickExecutor(executor)
            .withTaskResolver((worldName, pos) -> {
                // Resolve a WorldPos to a TaskNode if it's a known redstone component
                RedstoneComponentType type = componentMap.get(pos);
                if (type == null) return null;
                return org.nebula.redstone.RedstoneTaskFactory.inert(type, pos);
            })
            .activate();

        LOG.info("Nebula wired with region-aware FoliaRegionTickExecutor (OBSERVE mode)");
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

        // B2 guard: if componentMap is empty, we have no redstone components registered
        // and the DAG will produce no work.  Log a warning so the operator knows.
        if (componentMap.isEmpty()) {
            LOG.warning("executeOwnedDag called with " + ownedTasks.size()
                + " tasks but componentMap is empty — no redstone components registered. "
                + "Check WorldRedstoneScanner scan results.");
        }

        // B3 fix: take a snapshot of componentMap keys at the start of the tick so
        // that concurrent modifications from chunk load / block place events do not
        // cause a ConcurrentModificationException or inconsistent view during the
        // containsKey check below.  Set.copyOf gives an immutable snapshot.
        java.util.Set<WorldPos> componentSnapshot = java.util.Set.copyOf(componentMap.keySet());

        long t0 = System.nanoTime();

        // Phase 1: Sync FROM NMS for all affected positions
        for (TaskNode task : ownedTasks) {
            WorldPos pos = POSITION_OF.apply(task);
            if (!componentSnapshot.isEmpty() && !componentSnapshot.contains(pos)) {
                LOG.fine(() -> "executeOwnedDag: position " + pos
                    + " not found in componentMap — this specific position is not registered "
                    + "as a redstone component (componentMap has " + componentMap.size()
                    + " entries for other positions)");
            }
            blockBridge.syncFromNms(world, pos);
            blockEntityBridge.syncFromNms(world, pos);
        }

        // Phase 2: Execute DAG via MicroStepScheduler
        int totalTasks = ownedTasks.size();
        int microSteps = 0;
        java.util.Set<WorldPos> modifiedPositions = new java.util.LinkedHashSet<>();
        try {
            if (componentSnapshot.isEmpty()) {
                LOG.info("Skipping DAG execution: componentMap is empty (no redstone components)");
            } else {
                MicroStepScheduler.TickResult result = microStepScheduler.executeTick(ownedTasks);
                totalTasks = result.totalTasks();
                microSteps = result.microSteps();
                modifiedPositions.addAll(result.modifiedPositions());
                if (result.hasCommitFailures()) {
                    LOG.warning(() -> "DAG tick had " + result.commitFailures().size()
                        + " CAS commit failures");
                }
            }
        } catch (org.nebula.core.scheduler.DagExecutionException e) {
            LOG.warning(() -> "DAG execution failed: " + e.getMessage());
        }

        // Phase 3: Sync TO NMS (write back changes)
        // Use modifiedPositions from DAG execution (which includes positions
        // touched by microstep cascading), falling back to ownedTasks if the
        // DAG didn't report any modifications (e.g. empty componentMap).
        java.util.Set<WorldPos> syncPositions = !modifiedPositions.isEmpty()
            ? modifiedPositions
            : ownedTasks.stream().map(POSITION_OF).collect(java.util.stream.Collectors.toSet());
        for (WorldPos pos : syncPositions) {
            blockBridge.syncToNms(world, pos, redstoneState.getPowerLevel(pos));
            blockEntityBridge.syncToNms(world, pos);
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        int finalTasks = totalTasks;
        int finalMicroSteps = microSteps;
        LOG.info(() -> "DAG tick: " + finalTasks + " tasks, "
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