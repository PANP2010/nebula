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
import org.nebula.folia.FoliaRegionTickExecutor;
import org.nebula.folia.FoliaRuntimeDetector;
import org.nebula.folia.FoliaToggleApplier;
import org.nebula.folia.NmsBlockEntityStateBridge;
import org.nebula.folia.NmsBlockStateBridge;
import org.nebula.folia.NmsEntityStateBridge;
import org.nebula.folia.RedstoneCasStateHasher;
import org.nebula.folia.bridge.EntityTickHook;
import org.nebula.folia.bridge.FoliaCaptureHarness;
import org.nebula.folia.bridge.NebulaFoliaBootstrap;
import org.nebula.folia.bridge.RedstoneTickHook;
import org.nebula.entity.EntitySnapshot;
import org.nebula.entity.EntityTaskFactory;
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
import org.nebula.replay.SettledDivergenceGrader;
import org.nebula.replay.SettledSnapshotFormatter;

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

    /**
     * Decodes an entity task's <em>destination</em> {@link WorldPos} — the block the
     * entity moved into this tick — from a factory-stamped task ID, or {@code null}
     * if the ID carries no position.
     *
     * <p>This is the entity analogue of {@link #POSITION_OF}, and it is deliberately
     * NOT {@code WorldPos.parse}: the redstone grammar is a 2-token {@code dim:x,y,z}
     * suffix, whereas an entity {@code ENTITY_MOVE} ID is 3-token
     * {@code dim:<entityId>:x,y,z} (the entity id sits between the dimension and the
     * coordinates). Feeding an ENTITY_MOVE suffix to {@code WorldPos.parse} throws a
     * {@link NumberFormatException} on the {@code "0:42:10"}-style leading token.
     *
     * <p>Only {@code ENTITY_MOVE} carries coordinates; the pair-based types
     * ({@code ENTITY_COLLISION[_RESPONSE]}, {@code ENTITY_ITEM_PICKUP},
     * {@code ENTITY_DAMAGE}) stamp {@code dim:<idA>,<idB>} with no block, so this
     * returns {@code null} for them — matching {@link FoliaRegionTickExecutor}'s
     * contract that a {@code null} position skips region dispatch. The destination
     * block is the correct dispatch key for write-back: it is the region that will
     * own the entity after the move, so {@code NmsEntityStateBridge.syncPhysicsToNms}
     * runs on the owning region thread.
     */
    static final Function<TaskNode, WorldPos> ENTITY_POSITION_OF =
        t -> {
            String id = t.taskId();
            int at = id.indexOf('@');
            if (at < 0) return null;
            if (!"ENTITY_MOVE".equals(id.substring(0, at))) return null;
            return parseMovePosition(id.substring(at + 1));
        };

    // CAS state stores
    private RedstoneWorldState redstoneState;
    private EntityPhysicsState entityState;
    private BlockEntityState blockEntityState;

    // NMS bridges
    private NmsBlockStateBridge blockBridge;
    private NmsEntityStateBridge entityBridge;
    private NmsBlockEntityStateBridge blockEntityBridge;
    private RedstoneCasStateHasher stateHasher;

    // DAG execution
    private MicroStepScheduler microStepScheduler;
    private RedstoneTaskRunner redstoneRunner;
    private RedstoneTaskGenerator taskGenerator;
    private Map<WorldPos, RedstoneComponentType> componentMap;

    // Live-load driver input: manual toggle-source (lever/button) positions,
    // tracked separately from componentMap because the scanner collapses those
    // materials into REDSTONE_TORCH and so loses their identity. Populated at
    // scan/register time; consumed only when a live-load driver is assembled —
    // NOT read on the per-tick pipeline.
    private final ToggleSourceRegistry toggleSources = new ToggleSourceRegistry();

    // B4: honest per-tick cost measurement
    private final org.nebula.core.metrics.TickTimeRecorder tickTimeRecorder =
        new org.nebula.core.metrics.TickTimeRecorder();

    // DG1 Criterion 2: per-tick microstep-count distribution (max must stay ≤256)
    private final org.nebula.core.metrics.MicroStepRecorder microStepRecorder =
        new org.nebula.core.metrics.MicroStepRecorder();

    // DG1 Criterion 2 caveat investigation: opt-in per-invocation cascade diagnostic.
    // When enabled (via /nebula diag on), executeOwnedDag logs one INFO line per
    // invocation showing the seed-task count and, for each seed, its CAS power
    // BEFORE syncFromNms vs. the NMS value read INTO the CAS store by syncFromNms,
    // plus the resulting microsteps/modified count. This gathers live EVIDENCE for
    // why the live microstep max stays at 1: if the synced NMS value already equals
    // the value the DAG would compute (i.e. Folia has already settled the signal
    // before the observe-only shadow runs), the task produces no change and nothing
    // cascades. Off by default — the log is per-tick and would flood + skew MSPT.
    private volatile boolean cascadeDiag = false;

    // Entity physics DAG
    private EntityTickExecutor entityTickExecutor;
    private EntityTaskRunner entityRunner;

    // B8 C1 ⚡ live: latches false→true the first time a real moving entity drives
    // an entity DAG tick, so the bring-up milestone lands as ONE explicit INFO line
    // in server-run.log rather than being lost in per-tick FINE noise. Mirrors the
    // redstone bring-up's "first DAG tick" evidence.
    private final java.util.concurrent.atomic.AtomicBoolean firstEntityDagTickLogged =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    // B8 C3 block-entity physics DAG (hopper/dropper/dispenser/furnace/brewing).
    // The block-entity analogue of entityTickExecutor/entityRunner.
    private org.nebula.entity.BlockEntityTaskRunner blockEntityRunner;
    private org.nebula.entity.BlockEntityTickExecutor blockEntityTickExecutor;

    // B8 C3 ⚡ live: latches false→true the first time a real ticking block entity
    // (a hopper transferring an item, seeded by InventoryMoveItemEvent) drives a
    // block-entity DAG tick, so the bring-up milestone lands as ONE explicit INFO
    // line in server-run.log. Mirrors firstEntityDagTickLogged.
    private final java.util.concurrent.atomic.AtomicBoolean firstBlockEntityDagTickLogged =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    // B8 C3: taskId → the snapshot that seeded it. A block-entity taskId
    // (TYPE@dim:x,y,z) drops the hopper's facing/slot count, which
    // BlockEntityActionResolver needs, so the runner (dispatched to region threads)
    // recovers the snapshot from here. Populated by the hook's TaskResolver; keyed by
    // the stable taskId, so it grows only to the count of distinct ticking
    // block-entity positions (bounded, small). Concurrent because the resolver runs on
    // the global drain thread while the runner reads on region threads.
    private final java.util.concurrent.ConcurrentHashMap<String, org.nebula.entity.BlockEntitySnapshot>
        blockEntitySnapshots = new java.util.concurrent.ConcurrentHashMap<>();

    // B8 C1 divergence signal (observe-only): quantifies how far EntityMoveAction's
    // approximate shadow physics drift from Folia's authoritative movement, frame for
    // frame. Gated behind -Dnebula.entity.divergence=true (default OFF) so an ordinary
    // server pays nothing. Driven from executeOwnedEntityDag, which runs concurrently
    // across region threads, so every touch of this tracker is synchronised on it (its
    // per-entity HashMap is not thread-safe). This is a MEASUREMENT, never a pass/fail
    // proof — see EntityDivergenceTracker's class doc for the honesty framing.
    private final org.nebula.entity.EntityDivergenceTracker entityDivergenceTracker =
        new org.nebula.entity.EntityDivergenceTracker();
    private final java.util.concurrent.atomic.AtomicBoolean firstDivergenceSampleLogged =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    // How often (in tracker OBSERVATIONS) to log the accruing divergence heartbeat.
    // Keyed on observations, not samples, so the tracker's liveness is visible even
    // when no contiguous frame-for-frame pair forms. Kept coarse to avoid log flood.
    private static final long DIVERGENCE_SUMMARY_EVERY = 20L;

    // Composite DAG runner (redstone + entity)
    private CompositeTaskRunner compositeRunner;

    // B8 B2b: live RW-guard bridge, installed on the redstone runner only when
    // -Dnebula.rw.guard=true. Null when the guard is off (the default) — the
    // hot path is then exactly as before. When present, executeOwnedDag logs a
    // one-line RW-GUARD summary per invocation.
    private RedstoneRwGuardHook rwGuardHook;

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

        // Create state hasher for zero-diff verification.  Reads from the
        // thread-safe RedstoneWorldState CAS store rather than live NMS blocks,
        // so it is safe to invoke from the global tick thread (where the capture
        // harness runs) and reflects the state Nebula's DAG actually computed.
        stateHasher = new RedstoneCasStateHasher(redstoneState);

        // Create DAG execution pipeline
        componentMap = new ConcurrentHashMap<>();
        // The wire action needs to tell a source neighbour (undecayed) from a wire
        // neighbour (decays by 1) — vanilla parity, closes the DG3 settled-state
        // off-by-one. Classify against the live componentMap: a neighbour is a
        // wire iff it is registered as REDSTONE_WIRE.
        Map<String, RedstoneTaskAction> actionRegistry = RedstoneActions.defaults(
            neighbour -> componentMap.get(neighbour) == RedstoneComponentType.REDSTONE_WIRE);
        taskGenerator = new RedstoneTaskGenerator(componentMap, actionRegistry);

        // B8 B2b: opt-in live RW-guard on the redstone DAG. When -Dnebula.rw.guard=true,
        // install the per-access tracer (RedstoneAccessTracer → ThreadLocalAccessTrace)
        // AND the per-task hook that checks each task's real accesses against its declared
        // RW-set. Off by default: the runner then gets no tracer/hook and runs unchanged.
        boolean rwGuardEnabled = Boolean.getBoolean("nebula.rw.guard");
        RWGuardConfig redstoneGuardConfig = new RWGuardConfig(
            rwGuardEnabled,
            rwGuardSamplingRate(),
            RWGuardMode.WARN,
            getDataFolder().toPath().resolve("rw-violations.jsonl"),
            200, false
        );
        if (rwGuardEnabled) {
            rwGuardHook = new RedstoneRwGuardHook(redstoneGuardConfig);
            redstoneRunner = new RedstoneTaskRunner(redstoneState, actionRegistry,
                RedstoneRwGuardTracer.INSTANCE, rwGuardHook);
            LOG.info("RW-GUARD ENABLED (WARN mode, sampling=" + redstoneGuardConfig.samplingRate()
                + ") — redstone block accesses will be checked against declared RW-sets; "
                + "violations → " + redstoneGuardConfig.violationLog());
        } else {
            redstoneRunner = new RedstoneTaskRunner(redstoneState, actionRegistry);
        }
        microStepScheduler = new MicroStepScheduler(taskGenerator, redstoneRunner);

        // Create entity physics DAG pipeline. Wire a LIVE terrain oracle so
        // EntityMoveAction.restedOn() can fire: without it the runner defaults to
        // TerrainView.EMPTY (open void) and every grounded mob is modelled as
        // free-falling — the live divergence probe measured a constant Y over-fall
        // of exactly -0.0784/tick (one tick of ungrounded gravity+drag) for a mob
        // resting on the ground (B8 C1, diagnosed 2026-07-10). NmsTerrainView reads
        // block solidity on the owning region thread, matching the MOVE RW-set.
        entityRunner = new EntityTaskRunner(entityState,
            NebulaPlugin::resolveEntityAction)
            .withTerrain(new org.nebula.folia.NmsTerrainView(getServer()));
        entityTickExecutor = new EntityTickExecutor(entityRunner);

        // Create block-entity physics DAG pipeline (B8 C3). The runner resolves each
        // drained task to its REAL BlockEntityAction (hopper/furnace item math) via the
        // canonical taskId → snapshot → action composition, backed by the
        // blockEntitySnapshots registry the tick hook's resolver populates. Real actions
        // only mutate anything once the CAS store holds real inventory counts, which the
        // region-thread syncFromNms read (in executeOwnedBlockEntityDag) supplies — so
        // unlike the prior inert-on-the-global-thread stage, this stage's math is
        // observable. NMS write-back (syncToNms) stays gated OFF by default, exactly as
        // the entity path armed its region-threaded read before its write-back.
        blockEntityRunner = org.nebula.entity.BlockEntityTaskRunner.withSnapshotResolver(
            blockEntityState, blockEntitySnapshots::get);
        blockEntityTickExecutor = new org.nebula.entity.BlockEntityTickExecutor(blockEntityRunner);

        // Create composite runner for unified redstone + entity DAG.
        // Route on the canonical task-type prefixes that the factories actually
        // stamp: RedstoneComponentType → "REDSTONE_*", EntityTaskType → "ENTITY_*".
        // (The earlier "MOVE"/"COLLISION" prefixes never matched a stamped type,
        // so every entity task fell through to the no-route hard error.)
        compositeRunner = new CompositeTaskRunner()
            .routeByTypePrefix("REDSTONE_", redstoneRunner)
            .routeByTypePrefix("ENTITY_", entityRunner);

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

            wireEntityTickHook(server);
            wireBlockEntityTickHook(server);
        }
        // Temporary diagnostic: register Bukkit event listener
        getServer().getPluginManager().registerEvents(new RedstoneEventListener(), this);
        LOG.info("[Nebula] Registered RedstoneEventListener for diagnostics");
    }

    /**
     * B8 C1 ⚡ live: wires the {@link EntityTickHook} accumulator + resolver + executor
     * into the live Folia tick and starts feeding it real moved entities, now with
     * per-entity <em>region-thread dispatch</em> (the entity analogue of the redstone
     * {@link #wireRegionAwareExecutor} path).
     *
     * <h3>Region-thread dispatch + why write-back is gated OFF by default</h3>
     * The drained ENTITY_MOVE tasks are handed to a {@link FoliaRegionTickExecutor}
     * that dispatches each to the region thread owning its destination chunk. On that
     * thread {@link #executeOwnedEntityDag} runs the full cycle: look up the entity,
     * {@code syncPhysicsFromNms} (an NMS entity read that is ONLY legal on the owning
     * region thread — it NPEs elsewhere, exactly like a Folia block read), run the DAG,
     * then optionally {@code syncPhysicsToNms}.
     *
     * <p><b>The write-back stays OFF unless {@code -Dnebula.entity.writeback=true}.</b>
     * {@link EntityMoveAction} does not mirror Folia's position — it recomputes an
     * <em>approximate</em> trajectory (its own gravity/drag constants, vertical-only
     * collision) and writes that new position. Teleporting the live entity to it every
     * tick would fight vanilla movement, so it is emphatically NOT zero-diff. The
     * genuinely new, safe capability proven here is the region-threaded tick with a
     * legal region-thread NMS read; arming write-back into a true Folia mirror (or
     * validating its divergence) is the next slice.
     *
     * <h3>Thread-safety</h3>
     * The lifecycle driver and {@code endTick} drain run on the global tick thread, but
     * the actual entity read/DAG/write-back now runs on each entity's OWNING region
     * thread via the region executor — which is exactly what makes the NMS entity read
     * legal. The {@link EntityMoveEvent} listener that seeds moves runs on the entity's
     * own region thread, and {@link EntityTickHook#recordMove} is a concurrent-safe enqueue.
     */
    private void wireEntityTickHook(Server server) {
        // Resolver: a moved-entity snapshot → an ENTITY_MOVE TaskNode. The runner
        // re-resolves the action from the stamped taskId via resolveEntityAction, so
        // an inert node carries the correct id/coords and the executor runs real physics.
        EntityTickHook.setResolver((worldName, snapshot) -> EntityTaskFactory.moveInert(snapshot));

        // Executor: dispatch each moved-entity task to its OWNING region thread via
        // the subsystem-agnostic FoliaRegionTickExecutor (ENTITY_POSITION_OF decodes
        // the ENTITY_MOVE destination block; pair-types decode to null and are
        // skipped). On that region thread executeOwnedEntityDag runs the real
        // read → DAG → (gated) write-back cycle. This is the entity analogue of the
        // redstone wireRegionAwareExecutor path.
        //
        // Why region dispatch matters: NmsEntityStateBridge.syncPhysicsFromNms reads
        // Entity#getLocation/#getVelocity, which — like a Folia block read — NPEs off
        // the entity's owning region thread. The prior OBSERVE executor ran on the
        // global tick thread and so could ONLY touch the CAS store, never a real
        // entity. Dispatching to the owning region is what makes the NMS read legal.
        FoliaRegionTickExecutor entityRegionExecutor = new FoliaRegionTickExecutor(
            server, ENTITY_POSITION_OF,
            this::executeOwnedEntityDag, this);
        EntityTickHook.setExecutor((regionId, worldName, dirtyTasks) -> {
            try {
                entityRegionExecutor.executeTasks(regionId, worldName, dirtyTasks);
            } catch (org.nebula.core.scheduler.DagExecutionException e) {
                LOG.warning(() -> "Entity region dispatch failed in " + worldName
                    + ": " + e.getMessage());
            }
        });

        // Lifecycle driver: begin AND drain every game tick (not the redstone driver's
        // begin/end alternation). Why the entity path must NOT alternate:
        // EntityMoveAction forecasts exactly ONE game tick of gravity/drag ahead, but an
        // alternating driver runs the DAG only every OTHER game tick — so between two
        // consecutive entity-DAG passes for the same entity, TWO real ticks of Folia
        // physics elapse and getCurrentTick() jumps by 2. The divergence tracker's
        // contiguity guard (gap==1) then correctly rejects every pair (samples=0), and
        // pairing them anyway would diff a 1-tick forecast against a 2-tick reality — a
        // spurious drift, the dishonest fix the C1 pointer warned against. Draining every
        // tick makes one DAG pass == one game tick, so getCurrentTick() advances by 1 and
        // the guard yields HONEST frame-for-frame samples. This is safe because the DG3
        // fix already made beginTick a no-op on the accumulator (endTick is the sole
        // drain-and-remove consumer): calling begin then end in the same tick no longer
        // wipes in-flight moves — beginTick here only reaps stale endTickInProgress guards.
        server.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            if (!EntityTickHook.isActive()) return;
            EntityTickHook.beginTick("nebula-global");
            for (World w : server.getWorlds()) {
                EntityTickHook.endTick("nebula-global", w.getName());
            }
        }, 1, 1);

        // Seed source: every non-player LivingEntity move fires EntityMoveEvent on its
        // owning region thread. Record the block-truncated destination into the hook.
        server.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(ignoreCancelled = true)
            public void onEntityMove(io.papermc.paper.event.entity.EntityMoveEvent event) {
                if (!EntityTickHook.isActive()) return;
                org.bukkit.entity.Entity e = event.getEntity();
                org.bukkit.Location to = event.getTo();
                EntityTickHook.recordMove("nebula-global", e.getWorld().getName(),
                    e.getEntityId(), to.getBlockX(), to.getBlockY(), to.getBlockZ());
            }
        }, this);

        EntityTickHook.setActive(true);
        LOG.info("EntityTickHook lifecycle driver + EntityMoveEvent seed registered "
            + "(begin+drain every game tick so a DAG pass == one game tick, keeping the "
            + "divergence tracker's frame-for-frame guard honest; per-entity DAG dispatched to "
            + "owning region threads; NMS write-back "
            + (entityWriteBackEnabled() ? "ARMED full (-Dnebula.entity.writeback=true)"
               : entityVerticalWriteBackEnabled()
                   ? "ARMED vertical-only (-Dnebula.entity.writeback.vertical=true)"
                   : "OFF — observe-only") + ")");
    }

    /**
     * B8 C3 ⚡ live: wires the {@link org.nebula.folia.bridge.BlockEntityTickHook}
     * accumulator + resolver + executor into the live Folia tick and starts feeding
     * it real ticking block entities — the hopper/dropper analogue of
     * {@link #wireEntityTickHook}.
     *
     * <h3>Region-threaded tile read + real action math (entity-C1 analogue)</h3>
     * This slice runs each ticking block entity's REAL hopper/furnace action against a
     * CAS store primed from live tile state on the OWNING region thread, with NMS
     * write-back gated OFF — the block-entity analogue of the entity path's first
     * region-threaded tick (its region-thread NMS read armed before write-back):
     * <ul>
     *   <li><b>Resolver:</b> a ticking block entity's snapshot →
     *       {@code BlockEntityTaskFactory.inert(snapshot)}, a BLOCK_ENTITY_* TaskNode
     *       carrying its real RW-set (self/above/output slot fields). The snapshot is
     *       also registered under the stamped taskId ({@link #blockEntitySnapshots}) so
     *       the runner can recover the facing/slot topology the taskId drops and resolve
     *       the real {@link org.nebula.entity.actions.BlockEntityActions}.</li>
     *   <li><b>Executor:</b> a {@link FoliaRegionTickExecutor} dispatches each task to
     *       its owning region thread, where {@link #executeOwnedBlockEntityDag} does
     *       {@code syncFromNms(self)} → {@link org.nebula.entity.BlockEntityTickExecutor}
     *       → (gated OFF) {@code syncToNms(self)}. The region-thread tile read is exactly
     *       what the prior global-thread stage could not do, and it is what makes the
     *       real item math observable rather than a silent all-zero no-op.</li>
     *   <li><b>Seed source:</b> {@link org.bukkit.event.inventory.InventoryMoveItemEvent}
     *       fires on the region thread whenever a hopper (or dropper/hopper-minecart)
     *       moves an item — the block-entity analogue of {@code EntityMoveEvent}. Each
     *       block-holder endpoint is classified by {@link org.nebula.entity.BlockEntityClassifier};
     *       an autonomously-ticking type (hopper/dropper/dispenser/furnace/brewing stand)
     *       seeds one correctly-typed snapshot via {@link BlockEntityTickHook#recordDirty},
     *       while a non-ticking transfer target (chest/barrel) is skipped.</li>
     *   <li><b>Driver:</b> begin + drain every game tick (same cadence as the entity
     *       hook), so a DAG pass maps to one game tick. The DG3 fix makes beginTick a
     *       no-op on the accumulator, so begin-then-end in one tick never wipes
     *       in-flight dirties.</li>
     * </ul>
     *
     * <p>Only {@code self} is synced (not the hopper's neighbours, which may live on
     * another region thread); neighbour sync for a full multi-container transfer and
     * arming write-back are the next slices — see {@link #executeOwnedBlockEntityDag}
     * and {@link #blockEntityWriteBackEnabled}.
     */
    private void wireBlockEntityTickHook(Server server) {
        // Resolver: a ticking block-entity snapshot → a BLOCK_ENTITY_* TaskNode with
        // its real RW-set. inert(...) stamps the RW-set by type; we ALSO register the
        // snapshot under the stamped taskId so the region-thread runner can recover the
        // facing/slot topology the taskId drops and resolve the real action. The node's
        // own action stays a no-op — the runner re-resolves from the snapshot registry,
        // mirroring how the entity path re-resolves ENTITY_MOVE from the stamped ID.
        org.nebula.folia.bridge.BlockEntityTickHook.setResolver((worldName, snapshot) -> {
            TaskNode task = org.nebula.entity.BlockEntityTaskFactory.inert(snapshot);
            blockEntitySnapshots.put(task.taskId(), snapshot);
            return task;
        });

        // Executor: dispatch each block-entity task to its OWNING region thread via the
        // subsystem-agnostic FoliaRegionTickExecutor (POSITION_OF decodes the 2-token
        // BLOCK_ENTITY_* taskId — the existing redstone decoder handles it, per the
        // block-entity-dag-live-verified memory; no new decoder needed). On that region
        // thread executeOwnedBlockEntityDag runs the real syncFromNms → DAG → (gated OFF)
        // syncToNms cycle. This is the block-entity analogue of the entity path's
        // region-dispatch: NmsBlockEntityStateBridge.syncFromNms reads Bukkit tile state,
        // which — like every Folia block read — NPEs off the owning region thread, so the
        // prior global-thread executor could only ever touch an all-zero CAS store (the
        // real item math was a silent no-op). Dispatching to the owning region is what
        // makes the read legal and the math observable.
        FoliaRegionTickExecutor blockEntityRegionExecutor = new FoliaRegionTickExecutor(
            server, POSITION_OF, this::executeOwnedBlockEntityDag, this);
        org.nebula.folia.bridge.BlockEntityTickHook.setExecutor((regionId, worldName, dirtyTasks) -> {
            try {
                blockEntityRegionExecutor.executeTasks(regionId, worldName, dirtyTasks);
            } catch (org.nebula.core.scheduler.DagExecutionException e) {
                LOG.warning(() -> "Block-entity region dispatch failed in " + worldName
                    + ": " + e.getMessage());
            }
        });

        // Lifecycle driver: begin AND drain every game tick, mirroring the entity hook.
        server.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            if (!org.nebula.folia.bridge.BlockEntityTickHook.isActive()) return;
            org.nebula.folia.bridge.BlockEntityTickHook.beginTick("nebula-global");
            for (World w : server.getWorlds()) {
                org.nebula.folia.bridge.BlockEntityTickHook.endTick("nebula-global", w.getName());
            }
        }, 1, 1);

        // Seed source: a hopper (or dropper/hopper-minecart) moving an item fires
        // InventoryMoveItemEvent on its owning region thread. EITHER endpoint may be a
        // ticking block entity at a fixed WorldPos (a hopper pushing into a chest below
        // is a block source; a hopper-minecart pushing into a chest is a block
        // destination), so record BOTH block-holder endpoints. Non-block holders
        // (minecart inventories) have no fixed position and are skipped. The hook
        // dedups by position, so recording both never double-seeds one block.
        server.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(ignoreCancelled = true)
            public void onInventoryMoveItem(org.bukkit.event.inventory.InventoryMoveItemEvent event) {
                if (!org.nebula.folia.bridge.BlockEntityTickHook.isActive()) return;
                recordBlockHolder(event.getSource().getHolder());
                recordBlockHolder(event.getDestination().getHolder());
            }

            private void recordBlockHolder(org.bukkit.inventory.InventoryHolder holder) {
                if (!(holder instanceof org.bukkit.block.BlockState blockState)) return;
                org.bukkit.block.Block block = blockState.getBlock();
                // Classify the block: only an autonomously-ticking block entity
                // (hopper/dropper/dispenser/furnace/brewing stand) seeds a task. A chest
                // or barrel destination is a transfer TARGET, not a ticking task, so it
                // classifies null and is skipped — the hopper's own task already declares
                // the write into the target's slots. (ce6abf2 wrongly stamped every
                // endpoint as HOPPER; this is the fix.)
                org.nebula.entity.BlockEntityTaskType type =
                    org.nebula.entity.BlockEntityClassifier.classify(block.getType().name());
                if (type == null) return;
                int dim = org.nebula.core.state.DimensionIds.fromName(block.getWorld().getName());
                WorldPos pos = new WorldPos(dim, block.getX(), block.getY(), block.getZ());
                // Real facing from the block's directional data (hopper/dropper/dispenser
                // eject toward getFacing()); furnace/brewing stand ignore facing in
                // forType. This gives the correct per-type RW-set and output position,
                // vs. the old hard-coded HOPPER@…,0,0,0 that put every output at self.
                int fx = 0, fy = 0, fz = 0;
                if (block.getBlockData() instanceof org.bukkit.block.data.Directional dir) {
                    org.bukkit.block.BlockFace face = dir.getFacing();
                    fx = face.getModX();
                    fy = face.getModY();
                    fz = face.getModZ();
                }
                org.nebula.folia.bridge.BlockEntityTickHook.recordDirty("nebula-global",
                    block.getWorld().getName(),
                    org.nebula.entity.BlockEntitySnapshot.forType(type, pos, fx, fy, fz));
            }
        }, this);

        org.nebula.folia.bridge.BlockEntityTickHook.setActive(true);
        LOG.info("BlockEntityTickHook lifecycle driver + InventoryMoveItemEvent seed "
            + "registered (begin+drain every game tick; each ticking block entity now "
            + "dispatched to its OWNING region thread where syncFromNms reads live tile "
            + "state and the real hopper/furnace action runs on the CAS DAG; NMS "
            + "write-back " + (blockEntityWriteBackEnabled()
                ? "ARMED (-Dnebula.blockentity.writeback=true)"
                : "OFF — observe-only") + ")");
    }

    /**
     * The block-entity analogue of {@link #executeOwnedEntityDag}, invoked by
     * {@link FoliaRegionTickExecutor} on the region thread that owns each ticking
     * block entity's chunk. Running here — not on the global tick thread — is what
     * makes the {@link NmsBlockEntityStateBridge} tile read legal (off-region block
     * reads NPE on Folia).
     *
     * <p>Per task:
     * <ol>
     *   <li>Decode the block-entity position from the {@code BLOCK_ENTITY_*@dim:x,y,z}
     *       task ID via {@link #POSITION_OF}.</li>
     *   <li>{@code syncFromNms(self)}: read the live hopper/furnace's transfer cooldown,
     *       timers and per-slot item counts into the CAS store — the region-thread tile
     *       read this slice unlocks. Without it the real action math runs on an all-zero
     *       store and mutates nothing (the silent no-op the prior global-thread stage
     *       could not escape).</li>
     *   <li>Run the task through {@link #blockEntityTickExecutor}; the runner resolves
     *       the real {@link org.nebula.entity.actions.BlockEntityActions} from the
     *       snapshot registry and mutates CAS.</li>
     *   <li>Only if {@link #blockEntityWriteBackEnabled()}: {@code syncToNms(self)} the
     *       computed CAS state back onto the live tile. Off by default (lossy int-count
     *       model — see that flag's javadoc).</li>
     * </ol>
     *
     * <p><b>Scope of the region-thread read.</b> Only {@code self} is synced, not the
     * hopper's above/output neighbours. The neighbours may be owned by a different
     * region thread, so reading them here would risk the very cross-region NMS access
     * this dispatch exists to avoid; and this bring-up's honest claim is "the real
     * action runs region-threaded against live self state", not "a full multi-container
     * transfer mirrors Folia". Neighbour sync (and the deduplicated multi-position read
     * a real transfer needs) is the next slice.
     */
    private void executeOwnedBlockEntityDag(World world, String worldName,
                                            java.util.List<TaskNode> ownedTasks)
            throws org.nebula.core.scheduler.DagExecutionException {
        if (ownedTasks.isEmpty()) return;

        final boolean writeBack = blockEntityWriteBackEnabled();
        int layers = 0;
        int applied = 0;
        for (TaskNode task : ownedTasks) {
            WorldPos self = POSITION_OF.apply(task);
            if (self == null) {
                LOG.fine(() -> "executeOwnedBlockEntityDag: no position for " + task.taskId()
                    + " — skipping");
                continue;
            }

            // Region-thread NMS read: the capability this slice unlocks. Feeds the live
            // hopper cooldown / furnace timers / slot counts into CAS so the resolved
            // action has real numbers to work on.
            blockEntityBridge.syncFromNms(world, self);

            try {
                layers = blockEntityTickExecutor.executeTick(java.util.List.of(task));
            } catch (Exception e) {
                LOG.warning(() -> "block-entity DAG tick failed for " + task.taskId()
                    + " in " + worldName + ": " + e.getMessage());
                continue;
            }

            if (writeBack) {
                blockEntityBridge.syncToNms(world, self);
                applied++;
            }
        }

        if (firstBlockEntityDagTickLogged.compareAndSet(false, true)) {
            LOG.info("⚡ FIRST region-threaded block-entity DAG tick: " + ownedTasks.size()
                + " ticking block-entity task(s), " + layers + " layer(s) in " + worldName
                + " on thread '" + Thread.currentThread().getName() + "' — the tile read + "
                + "real hopper/furnace action now run on the OWNING region thread "
                + "(region-thread NMS tile read is legal here). NMS write-back "
                + (writeBack ? "ARMED, applied to " + applied + " tile(s)"
                   : "OFF (observe-only; -Dnebula.blockentity.writeback=true to arm)"));
        }
    }

    /**
     * Whether the block-entity DAG's computed CAS state is written back to the live
     * tile entity via {@link NmsBlockEntityStateBridge#syncToNms}. Overridable via
     * {@code -Dnebula.blockentity.writeback=true}; defaults to {@code false}.
     *
     * <p><b>Why OFF by default (the honesty gate).</b> Folia stays authoritative on the
     * block-entity path exactly as on redstone and entities: the DAG is a
     * non-authoritative shadow. Arming write-back would push the CAS store's integer
     * item-count model back onto real {@code ItemStack}s, and that model is lossy —
     * {@link NmsBlockEntityStateBridge#setSlotAmount} cannot even create an item in an
     * empty slot (it tracks amounts, not material types). So write-back is NOT
     * zero-diff and must not be armed as a side effect of this bring-up. The verified
     * new capability is the region-threaded tick with a legal region-thread tile read
     * feeding real action math — the block-entity analogue of the entity C1 milestone.
     */
    static boolean blockEntityWriteBackEnabled() {
        return Boolean.getBoolean("nebula.blockentity.writeback");
    }

    /**
     * Whether the entity DAG's computed position/velocity is written back to the live
     * entity via {@link NmsEntityStateBridge#syncPhysicsToNms}. Overridable via
     * {@code -Dnebula.entity.writeback=true}; defaults to {@code false}.
     *
     * <p><b>Off by default on purpose.</b> {@link EntityMoveAction} recomputes an
     * approximate trajectory (its own gravity/drag, vertical-only collision) rather
     * than mirroring Folia's authoritative position, so arming write-back teleports
     * live mobs onto Nebula's shadow path every tick — decidedly NOT zero-diff. The
     * flag exists so the write-back seam can be exercised deliberately (e.g. for
     * divergence measurement) without perturbing an ordinary server.
     *
     * <p>Package-private so {@code EntityTaskResolutionTest} can pin the default OFF.
     */
    static boolean entityWriteBackEnabled() {
        return Boolean.getBoolean("nebula.entity.writeback");
    }

    /**
     * Whether <em>vertical-only</em> entity write-back is armed. Overridable via
     * {@code -Dnebula.entity.writeback.vertical=true}; defaults to {@code false}.
     *
     * <p>This is the honest write-back scope established by the B8 C1 divergence
     * finding (2026-07-10): {@link EntityMoveAction}'s Y matches Folia on the
     * verified vertical path (grounded rest / straight fall) while its horizontal
     * X/Z drift is STOCHASTIC AI pathing a deterministic mirror can never reproduce.
     * So when armed, {@link #executeOwnedEntityDag} writes back only the Y position
     * and Y velocity — and only for entities the {@link org.nebula.entity.VerticalWriteBackGate}
     * deems on the vertical path (negligible authoritative horizontal speed) — leaving
     * X/Z authoritative to Folia. A wandering or knocked mob is left entirely untouched.
     *
     * <p>Distinct from and safer than {@link #entityWriteBackEnabled()} (full write-back,
     * which teleports every moved entity onto Nebula's approximate trajectory and fights
     * vanilla AI). Full write-back stays off until — if ever — an AI-intent mirror exists.
     * If both flags are set, full write-back takes precedence (the superset).
     *
     * <p>Package-private so {@code EntityTaskResolutionTest} can pin the default OFF.
     */
    static boolean entityVerticalWriteBackEnabled() {
        return Boolean.getBoolean("nebula.entity.writeback.vertical");
    }

    /**
     * Whether the observe-only entity divergence tracker is armed. Overridable via
     * {@code -Dnebula.entity.divergence=true}; defaults to {@code false}.
     *
     * <p>When on, {@link #executeOwnedEntityDag} feeds each entity's authoritative
     * (pre-DAG, from {@code syncPhysicsFromNms}) and predicted (post-DAG, from
     * {@link EntityMoveAction}) position into {@link org.nebula.entity.EntityDivergenceTracker}
     * and periodically logs its {@code summary()}. This does NOT arm write-back and
     * does NOT teleport anything — it is a pure measurement of the gap between
     * Nebula's approximate shadow physics and vanilla, so a later cycle can judge how
     * close the model is. Independent of (and safe to combine with) the write-back gate.
     *
     * <p>Package-private so {@code EntityTaskResolutionTest} can pin the default OFF.
     */
    static boolean entityDivergenceEnabled() {
        return Boolean.getBoolean("nebula.entity.divergence");
    }

    /**
     * The entity analogue of {@link #executeOwnedDag}, invoked by
     * {@link FoliaRegionTickExecutor} on the region thread that owns each moved
     * entity's destination chunk. Running here — not on the global tick thread — is
     * what makes the {@link NmsEntityStateBridge} entity read legal (off-region entity
     * reads NPE on Folia, exactly like block reads).
     *
     * <p>Per task:
     * <ol>
     *   <li>Decode the entity id from the ENTITY_MOVE task and look up the live entity
     *       (skip silently if it has since despawned or moved worlds).</li>
     *   <li>{@code syncPhysicsFromNms}: read the entity's real position/velocity into
     *       the CAS store — the region-thread NMS read this whole slice unlocks.</li>
     *   <li>Run the task through {@link #entityTickExecutor}.</li>
     *   <li>Only if {@link #entityDivergenceEnabled()}: feed the authoritative
     *       (pre-DAG) and predicted (post-DAG) position into the observe-only
     *       {@link org.nebula.entity.EntityDivergenceTracker}. Pure measurement, no
     *       write-back. Off by default.</li>
     *   <li>Only if {@link #entityWriteBackEnabled()}: {@code syncPhysicsToNms} the
     *       computed state back onto the live entity. Off by default (see that helper).</li>
     * </ol>
     */
    private void executeOwnedEntityDag(World world, String worldName,
                                       java.util.List<TaskNode> ownedTasks)
            throws org.nebula.core.scheduler.DagExecutionException {
        if (ownedTasks.isEmpty()) return;

        final boolean writeBack = entityWriteBackEnabled();
        final boolean verticalWriteBack = !writeBack && entityVerticalWriteBackEnabled();
        final boolean diverge = entityDivergenceEnabled();
        // Tick label for the frame-for-frame divergence pairing. We are on the owning
        // region thread here, so getCurrentTick() is legal (it NPEs off a region
        // thread). Only needed when the tracker is armed.
        final long tick = diverge ? getServer().getCurrentTick() : 0L;
        int layers = 0;
        int applied = 0;
        for (TaskNode task : ownedTasks) {
            long entityId = parseMoveEntityId(task.taskId().substring(task.taskId().indexOf('@') + 1));
            java.util.Optional<org.bukkit.entity.Entity> found =
                NmsEntityStateBridge.findEntityById(world, (int) entityId);
            if (found.isEmpty()) {
                LOG.fine(() -> "executeOwnedEntityDag: entity " + entityId
                    + " not found in " + worldName + " — skipping (despawned or moved worlds)");
                continue;
            }
            org.bukkit.entity.Entity entity = found.get();

            // Region-thread NMS read: the capability this slice unlocks.
            entityBridge.syncPhysicsFromNms(entity);

            // Authoritative position = the value Folia just produced, captured BEFORE
            // the DAG overwrites CAS with its own prediction. Only meaningful when the
            // divergence tracker is armed, so skip the read otherwise.
            final org.nebula.core.state.EntityField posField =
                new org.nebula.core.state.EntityField(entityId, "position");
            org.nebula.entity.Vec3 authoritative =
                diverge ? entityBridge.casStore().getVec(posField) : null;

            // For the vertical-only gate we need Folia's authoritative HORIZONTAL
            // velocity (is it moving this mob sideways right now?). Captured pre-DAG for
            // the same reason: the DAG overwrites velocity with its own integration.
            final org.nebula.entity.Vec3 authoritativeVel = verticalWriteBack
                ? entityBridge.casStore().getVec(
                    new org.nebula.core.state.EntityField(entityId, "velocity"))
                : null;

            try {
                layers = entityTickExecutor.executeTick(java.util.List.of(task));
            } catch (Exception e) {
                LOG.warning(() -> "entity DAG tick failed for " + task.taskId()
                    + " in " + worldName + ": " + e.getMessage());
                continue;
            }

            if (diverge) {
                // Predicted next position = the value EntityMoveAction just wrote over
                // the authoritative one. Diff them across contiguous ticks. The tracker
                // is not thread-safe and executeOwnedEntityDag runs on many region
                // threads at once, so serialise every touch of it.
                org.nebula.entity.Vec3 predicted = entityBridge.casStore().getVec(posField);
                recordEntityDivergence(entityId, tick, authoritative, predicted, worldName);
            }

            if (writeBack) {
                entityBridge.syncPhysicsToNms(entity, world);
                applied++;
            } else if (verticalWriteBack
                    && org.nebula.entity.VerticalWriteBackGate.onVerticalPath(authoritativeVel)) {
                // Vertical path only (grounded rest / straight fall): mirror Y, leave
                // Folia's stochastic X/Z untouched. A wandering/knocked mob (nonzero
                // horizontal speed) fails the gate and is left entirely to Folia.
                entityBridge.syncVerticalPhysicsToNms(entity, world);
                applied++;
            }
        }

        if (firstEntityDagTickLogged.compareAndSet(false, true)) {
            LOG.info("⚡ FIRST region-threaded entity DAG tick: " + ownedTasks.size()
                + " moved-entity task(s), " + layers + " layer(s) in " + worldName
                + " on thread '" + Thread.currentThread().getName() + "' — the entity "
                + "read/DAG now run on the OWNING region thread (region-thread NMS read "
                + "is legal here). NMS write-back "
                + (writeBack ? "ARMED (full), applied to " + applied + " entity(ies)"
                   : verticalWriteBack
                       ? "ARMED (vertical-only), applied to " + applied + " entity(ies) on the vertical path"
                       : "OFF (observe-only; -Dnebula.entity.writeback[.vertical]=true to arm)"));
        }
    }

    /**
     * Feeds one entity's authoritative (Folia-produced) and predicted
     * ({@link EntityMoveAction}-computed) position for {@code tick} into the
     * observe-only {@link org.nebula.entity.EntityDivergenceTracker}, and logs the
     * first contiguous drift sample as an explicit bring-up line (mirroring the
     * ⚡ first-DAG-tick evidence). Synchronised because {@code executeOwnedEntityDag}
     * runs concurrently across region threads and the tracker's per-entity map is not
     * thread-safe. Nonzero drift is EXPECTED (write-back is off, the model is
     * approximate) — this quantifies the gap, it does not judge correctness.
     */
    private void recordEntityDivergence(long entityId, long tick,
                                        org.nebula.entity.Vec3 authoritative,
                                        org.nebula.entity.Vec3 predicted,
                                        String worldName) {
        final java.util.Optional<org.nebula.entity.EntityDivergenceTracker.Sample> sample;
        final long obs;
        final String periodicSummary;
        synchronized (entityDivergenceTracker) {
            sample = entityDivergenceTracker.record(entityId, tick, authoritative, predicted);
            obs = entityDivergenceTracker.observationCount();
            // Heartbeat every DIVERGENCE_SUMMARY_EVERY OBSERVATIONS (not samples): the
            // tracker's liveness must be legible even when zero contiguous samples pair
            // up, so a silent run reads as "obs=N samples=0 nonContiguousSkips=N gap=2"
            // rather than nothing at all — the anti-drift posture. Snapshotted under the
            // same lock; logged outside it.
            periodicSummary = (obs % DIVERGENCE_SUMMARY_EVERY == 0)
                ? entityDivergenceTracker.summary() : null;
        }
        if (periodicSummary != null) {
            LOG.info("entity-divergence heartbeat: " + periodicSummary);
        }
        if (sample.isPresent() && firstDivergenceSampleLogged.compareAndSet(false, true)) {
            org.nebula.entity.EntityDivergenceTracker.Sample s = sample.get();
            LOG.info(String.format(
                "⚡ FIRST entity divergence sample in %s on thread '%s': entity=%d tick=%d "
                    + "drift=%.6f (authoritative=%s predicted=%s) — observe-only measurement of "
                    + "EntityMoveAction vs Folia; nonzero drift is EXPECTED (write-back off, model "
                    + "approximate). %s",
                worldName, Thread.currentThread().getName(), s.entityId(), s.tick(), s.drift(),
                s.authoritative(), s.predicted(), summarizeEntityDivergence()));
        }
    }

    /** Thread-safe snapshot of the divergence tracker's one-line summary. */
    private String summarizeEntityDivergence() {
        synchronized (entityDivergenceTracker) {
            return entityDivergenceTracker.summary();
        }
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

        // DG1 Criterion 2 caveat probe: when enabled, record each seed position's
        // CAS power BEFORE syncFromNms vs. the value syncFromNms pulls in from NMS.
        // If they already match, Folia settled the signal before this shadow ran, so
        // the task produces no change and the tick cannot cascade (max microsteps 1).
        final boolean diag = cascadeDiag;
        java.util.List<String> diagSeeds = diag ? new java.util.ArrayList<>() : null;

        // Phase 1: Sync FROM NMS for all affected positions
        java.util.List<WorldPos> seedPositions = new java.util.ArrayList<>(ownedTasks.size());
        for (TaskNode task : ownedTasks) {
            WorldPos pos = POSITION_OF.apply(task);
            seedPositions.add(pos);
            if (!componentSnapshot.isEmpty() && !componentSnapshot.contains(pos)) {
                LOG.fine(() -> "executeOwnedDag: position " + pos
                    + " not found in componentMap — this specific position is not registered "
                    + "as a redstone component (componentMap has " + componentMap.size()
                    + " entries for other positions)");
            }
            int casBefore = diag ? redstoneState.getPowerLevel(pos) : 0;
            blockBridge.syncFromNms(world, pos);
            blockEntityBridge.syncFromNms(world, pos);
            if (diag) {
                int casAfter = redstoneState.getPowerLevel(pos);
                diagSeeds.add(pos + " cas=" + casBefore + "→nms=" + casAfter
                    + (casBefore == casAfter ? " (settled)" : " (dirty)"));
            }
        }

        // Phase 1b (DG3 multi-region seeding-race fix): a source (lever/button/
        // torch) feeds an adjacent wire its power UNDECAYED, but the cascade reads
        // that power from the CAS store — and a source only lands in CAS if its OWN
        // BLOCK_UPDATE was drained as a seed this tick, which races region-thread
        // timing on an OFF→ON toggle. When it isn't, the wire reads the source as
        // -1 and the whole line collapses to 0 (the run-varying nebula=-1-at-lever
        // divergence the --settled gate exposes). Seeding the source off its
        // adjacent WIRE seed is deterministic: a wire next to a toggled lever
        // reliably fires when the lever flips, so it is always a seed. Same-chunk
        // only — a cross-chunk neighbour may be owned by another region and reading
        // it here would NPE on Folia (SourceSeedPlanner enforces this).
        java.util.Set<WorldPos> sourceSeeds = SourceSeedPlanner.plan(
            seedPositions, componentMap::get);
        for (WorldPos src : sourceSeeds) {
            int casBefore = diag ? redstoneState.getPowerLevel(src) : 0;
            blockBridge.syncFromNms(world, src);
            if (diag) {
                int casAfter = redstoneState.getPowerLevel(src);
                diagSeeds.add(src + " cas=" + casBefore + "→nms=" + casAfter
                    + (casBefore == casAfter ? " (settled)" : " (dirty)") + " [source-seed]");
            }
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

        long elapsedNs = System.nanoTime() - t0;
        tickTimeRecorder.record(elapsedNs);
        microStepRecorder.record(microSteps);
        long elapsedMs = elapsedNs / 1_000_000;
        int finalTasks = totalTasks;
        int finalMicroSteps = microSteps;
        // Per-tick logging is FINE: at 20 TPS an INFO line here floods the log and
        // its own I/O skews the very MSPT we're measuring. Use /nebula perf for
        // aggregated percentiles instead.
        LOG.fine(() -> "DAG tick: " + finalTasks + " tasks, "
            + finalMicroSteps + " microsteps in " + elapsedMs + "ms");

        // DG1 Criterion 2 caveat probe (opt-in, INFO): one line per invocation with
        // the seed count and each seed's settled/dirty state, so the live cause of
        // "max microsteps = 1" can be read off server-run.log instead of inferred.
        if (diag) {
            int seedCount = ownedTasks.size();
            int modCount = modifiedPositions.size();
            LOG.info("CASCADE-DIAG: seedTasks=" + seedCount
                + " microsteps=" + finalMicroSteps
                + " modified=" + modCount
                + " seeds=" + diagSeeds);
        }

        // B8 B2b: when the RW-guard is on, surface its running verdict as an INFO
        // line the operator can read straight off server-run.log after a toggle —
        // this is the first time the Achilles'-heel protection reports against real
        // Folia accesses. Only logged when the hook is installed (guard enabled), so
        // the default hot path stays silent.
        if (rwGuardHook != null) {
            LOG.info("RW-GUARD: tracedTasks=" + rwGuardHook.tracedTasks()
                + " violations=" + rwGuardHook.violationCount()
                + (rwGuardHook.violationCount() == 0 ? " (clean)" : " (SEE rw-violations.jsonl)"));
        }
    }

    /**
     * Sampling rate for the live redstone RW-guard, overridable via
     * {@code -Dnebula.rw.guard.sample=<0..1>}. Defaults to 1.0 (check every task) so
     * the first live guard run (B2b) has full coverage on a hand-driven toggle; drop
     * it for sustained high-load runs where per-task tracing would skew MSPT. Values
     * outside [0,1] fall back to 1.0.
     */
    private static double rwGuardSamplingRate() {
        String raw = System.getProperty("nebula.rw.guard.sample");
        if (raw == null || raw.isBlank()) {
            return 1.0;
        }
        try {
            double v = Double.parseDouble(raw.trim());
            return (v >= 0.0 && v <= 1.0) ? v : 1.0;
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }

    /**
     * Emits one {@code SETTLED-DIAG} snapshot line per world, comparing Nebula's
     * shadow power against Folia's authoritative power for every tracked position at
     * quiescence. This is the load-bearing settled-state Folia-vs-Nebula divergence
     * signal (DG3 correctness) that the residual-dirty-rate grade cannot be.
     *
     * <h3>Why this is on-demand and NOT auto-hooked into {@code executeOwnedDag}</h3>
     * At steady state a fully-powered wire fires no {@code BLOCK_UPDATE}s, so it is
     * never re-seeded into {@code executeOwnedDag} and never appears in the
     * CASCADE-DIAG seed stream — a settled-only filter of that stream reports 0
     * divergence <em>by construction</em> (a fake PASS; see
     * {@link SettledDivergenceGrader}'s javadoc and memory
     * divergence-grade-needs-settled-sampling). The honest surface is this one: the
     * operator drives a toggle, lets the circuit settle, then invokes {@code /nebula
     * settled}, which snapshots EVERY tracked position — including the settled ON
     * wires that never re-seed.
     *
     * <h3>Region-thread safety</h3>
     * Folia block reads NPE off the owning region thread, so this dispatches the
     * snapshot of each tracked position via {@code RegionScheduler.execute} on the
     * region that owns it, then logs one line per world once all its regions report.
     * The per-position sample reads {@code nebula=redstoneState.getPowerLevel(pos)}
     * (the shadow value) and {@code folia=blockBridge.readNmsPower(world, pos)} — the
     * READ-ONLY NMS sample, NOT {@code syncFromNms}, which would clobber the shadow
     * value with Folia's and make them equal by construction (the divergence
     * tautology {@code readNmsPower}'s javadoc warns about).
     *
     * @return the number of tracked positions whose snapshot was dispatched
     */
    public int emitSettledSnapshot() {
        if (stateHasher == null || blockBridge == null) {
            LOG.warning("SETTLED-DIAG requested but bridges are not wired (non-Folia?)");
            return 0;
        }
        java.util.List<WorldPos> tracked = stateHasher.trackedPositions().stream()
            .sorted()
            .toList();
        if (tracked.isEmpty()) {
            LOG.info("SETTLED-DIAG: tracked=0 — no positions registered; run /nebula scan first");
            return 0;
        }

        Server server = getServer();
        io.papermc.paper.threadedregions.scheduler.RegionScheduler regionScheduler =
            server.getRegionScheduler();

        int dispatched = 0;
        for (World w : server.getWorlds()) {
            int dim = org.nebula.core.state.DimensionIds.fromName(w.getName());
            java.util.List<WorldPos> here = tracked.stream()
                .filter(p -> p.dimensionId() == dim)
                .toList();
            if (here.isEmpty()) {
                continue;
            }
            // One thread-safe accumulator per world; each owning region contributes
            // its samples, and the last region to finish emits the world's line. Using
            // a copy-on-write list keeps the accumulation safe across region threads
            // without a lock, and the AtomicInteger tracks completion.
            final World fw = w;
            java.util.List<SettledDivergenceGrader.PositionSample> samples =
                new java.util.concurrent.CopyOnWriteArrayList<>();
            java.util.concurrent.atomic.AtomicInteger remaining =
                new java.util.concurrent.atomic.AtomicInteger(here.size());
            for (WorldPos pos : here) {
                regionScheduler.execute(this, fw, pos.x() >> 4, pos.z() >> 4, () -> {
                    int nebula = redstoneState.getPowerLevel(pos);
                    int folia = blockBridge.readNmsPower(fw, pos);
                    samples.add(new SettledDivergenceGrader.PositionSample(pos, nebula, folia));
                    if (remaining.decrementAndGet() == 0) {
                        // The tick label is read HERE, inside a ticking region task —
                        // Server.getCurrentTick() throws "No currently ticking region"
                        // off a region thread (e.g. the global command thread), so it
                        // cannot be sampled in the dispatch loop above.
                        int tick = server.getCurrentTick();
                        // Sort by WorldPos so the emitted order is deterministic
                        // regardless of region-thread completion order.
                        java.util.List<SettledDivergenceGrader.PositionSample> ordered =
                            samples.stream()
                                .sorted(java.util.Comparator.comparing(
                                    SettledDivergenceGrader.PositionSample::pos))
                                .toList();
                        LOG.info(SettledSnapshotFormatter.format(tick, ordered));
                    }
                });
                dispatched++;
            }
        }
        LOG.info("SETTLED-DIAG dispatched for " + dispatched
            + " tracked position(s); snapshot line(s) follow asynchronously once each "
            + "owning region reports.");
        return dispatched;
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
     * Starts a <em>static</em> capture for zero-diff verification (no driven
     * input). Records state hashes for the specified number of ticks.
     */
    public void startCapture(long ticks) {
        startCapture(ticks, null, DEFAULT_DRIVE_PERIOD);
    }

    /** Default per-source flip period (ticks) when {@code --drive} omits one. */
    public static final int DEFAULT_DRIVE_PERIOD = 8;

    /**
     * Starts the capture harness. When {@code driveSeed} is non-null, this is a
     * <em>driven live-load</em> capture: each captured tick the harness applies a
     * deterministic square-wave toggle stream to the registered toggle sources
     * (levers/buttons) BEFORE hashing, keyed off the harness tick counter, via the
     * {@link FoliaCaptureHarness.TickDriver} seam. Two runs of the same seed emit a
     * byte-identical toggle stream tick-for-tick, so the two {@code .nrp} files stay
     * comparable and any divergence is attributable to the engine, not the input —
     * this is the game-layer wiring the pure driver stack was built for (DG1
     * Criterion 1 live-load slice).
     *
     * <p>When {@code driveSeed} is null the capture is static, exactly as before —
     * the harness receives a null {@link FoliaCaptureHarness.TickDriver} and this
     * behaves identically to the legacy path.
     *
     * @param ticks     number of ticks to capture
     * @param driveSeed determinism seed, or null for a static (undriven) capture
     * @param period    per-source flip period in ticks (≥ 1); ignored when driveSeed is null
     */
    public void startCapture(long ticks, Long driveSeed, int period) {
        if (captureHarness != null && captureHarness.isRunning()) {
            LOG.warning("Capture already running");
            return;
        }
        recorder = new ReplayRecorder();

        FoliaCaptureHarness.TickDriver driver = null;
        if (driveSeed != null) {
            // Build the live-load driver from the SAME toggle-source set the scanner
            // populated, in canonical order (CanonicalToggleSources sorts by WorldPos,
            // so the source ordering — and thus the seed-derived phases — depend only
            // on the SET of sources, never on enumeration order). Applying via
            // FoliaToggleApplier flips the real lever with physics so the observe-only
            // DAG shadow sees it exactly as an organic edit.
            if (toggleSources.size() == 0) {
                LOG.warning("Driven capture requested (seed=" + driveSeed
                    + ") but no toggle sources are registered — run /nebula scan after "
                    + "placing levers/buttons. Falling back to a STATIC capture.");
            } else {
                World world = getServer().getWorlds().isEmpty()
                    ? null : getServer().getWorlds().get(0);
                if (world == null) {
                    LOG.warning("Driven capture requested but no world is loaded — "
                        + "falling back to a STATIC capture.");
                } else {
                    FoliaToggleApplier applier = new FoliaToggleApplier(
                        new FoliaRegionBridge(getServer(), this), world);
                    var liveDriver = toggleSources.canonical()
                        .newDriver(driveSeed, period, applier);
                    driver = liveDriver::driveTick;
                    LOG.info("Driven capture armed: seed=" + driveSeed + " period=" + period
                        + " over " + toggleSources.size() + " toggle source(s) in world '"
                        + world.getName() + "'");
                }
            }
        }

        captureHarness = new FoliaCaptureHarness(this, recorder, stateHasher, null, driver);
        captureHarness.start(ticks);
        LOG.info("Capture harness started: " + ticks + " ticks"
            + (driver != null ? " (DRIVEN, seed=" + driveSeed + ")" : " (static)"));
    }

    /** Stops the capture harness and returns recorded frames. */
    public java.util.List<org.nebula.replay.ReplayFrame> stopCapture() {
        if (captureHarness != null) {
            captureHarness.stop();
            return recorder.getFrames();
        }
        return java.util.List.of();
    }

    /**
     * Saves the most recently recorded capture to a timestamped {@code .nrp}
     * file under the plugin data folder's {@code captures/} directory.  Two
     * such files from separate runs can be compared with
     * {@link org.nebula.replay.ReplayVerifier} for zero-diff verification.
     *
     * @return the saved path (as a string) relative reference, or {@code null}
     *         if there was nothing to save or the write failed
     */
    public String saveLastCapture() {
        if (recorder == null || recorder.frameCount() == 0) return null;
        try {
            java.nio.file.Path dir = getDataFolder().toPath().resolve("captures");
            java.nio.file.Files.createDirectories(dir);
            String name = "capture-" + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".nrp";
            java.nio.file.Path path = dir.resolve(name);
            recorder.save(path);
            return "captures/" + name;
        } catch (java.io.IOException e) {
            LOG.warning("Failed to save capture: " + e.getMessage());
            return null;
        }
    }

    @Override
    public void onDisable() {
        if (captureHarness != null) {
            captureHarness.stop();
        }
        if (bootstrap != null) {
            bootstrap.deactivate();
        }
        EntityTickHook.setActive(false);
        org.nebula.folia.bridge.BlockEntityTickHook.setActive(false);
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
    public RedstoneCasStateHasher stateHasher() { return stateHasher; }
    public MicroStepScheduler microStepScheduler() { return microStepScheduler; }
    public RedstoneTaskGenerator taskGenerator() { return taskGenerator; }
    public org.nebula.core.metrics.TickTimeRecorder tickTimeRecorder() { return tickTimeRecorder; }
    public org.nebula.core.metrics.MicroStepRecorder microStepRecorder() { return microStepRecorder; }

    /** DG1 Criterion 2 caveat probe: enable/disable the per-invocation cascade diagnostic log. */
    public void setCascadeDiag(boolean on) { this.cascadeDiag = on; }
    public boolean cascadeDiag() { return cascadeDiag; }

    /**
     * Registers a redstone component position. Required for DAG execution
     * to generate downstream tasks for signal propagation.
     */
    public void registerRedstoneComponent(WorldPos pos, RedstoneComponentType type) {
        componentMap.put(pos, type);
        // Track the position for zero-diff capture so the state hasher knows
        // which components to include.  Without this the hasher's tracked set
        // stays empty and capture hashes carry no redstone state.
        if (stateHasher != null) {
            stateHasher.trackPosition(pos);
        }
    }

    /**
     * Unregisters a redstone component position.
     */
    public void unregisterRedstoneComponent(WorldPos pos) {
        componentMap.remove(pos);
        if (stateHasher != null) {
            stateHasher.untrackPosition(pos);
        }
    }

    /** Number of registered redstone components (for diagnostics / {@code /nebula status}). */
    public int componentCount() {
        return componentMap.size();
    }

    /**
     * Records a manual toggle-source (lever/button) position for the live-load
     * driver. Called by the scanner when a scanned/placed block is classified as a
     * toggle source ({@link ToggleSourceClassifier}). This is separate from
     * {@link #registerRedstoneComponent} because the component map collapses
     * levers/buttons into {@code REDSTONE_TORCH}, erasing their identity.
     */
    public void registerToggleSource(WorldPos pos) {
        toggleSources.register(pos);
    }

    /** Removes a toggle-source position (e.g. the lever was broken). */
    public void unregisterToggleSource(WorldPos pos) {
        toggleSources.unregister(pos);
    }

    /** Number of tracked manual toggle sources (for {@code /nebula status}). */
    public int toggleSourceCount() {
        return toggleSources.size();
    }

    /**
     * The toggle-source registry, exposed for the live-load driver wiring (a future
     * cycle) to build a {@link org.nebula.replay.CanonicalToggleSources} plan from
     * {@link ToggleSourceRegistry#canonical()}.
     */
    public ToggleSourceRegistry toggleSources() {
        return toggleSources;
    }

    /**
     * Rescans every loaded chunk in every world for redstone components,
     * repopulating the component map.  Used by {@code /nebula scan} so an
     * operator can register redstone that was placed via commands (e.g. RCON
     * {@code setblock}), which do not fire {@link org.bukkit.event.block.BlockPlaceEvent}.
     *
     * <p>Block reads must happen on the region thread that owns each chunk, so
     * each chunk scan is dispatched via {@code RegionScheduler.execute}.  This
     * method therefore returns immediately; the component map is populated
     * asynchronously.  The returned value is the current (pre-dispatch) count
     * for reference — check {@link #componentCount()} again after a tick.
     *
     * @return the number of chunks whose scan was dispatched
     */
    public int rescanLoadedChunks() {
        if (worldScanner == null) return 0;
        Server server = getServer();
        io.papermc.paper.threadedregions.scheduler.RegionScheduler regionScheduler = server.getRegionScheduler();
        int dispatched = 0;
        for (World w : server.getWorlds()) {
            for (org.bukkit.Chunk chunk : w.getLoadedChunks()) {
                final World fw = w;
                final org.bukkit.Chunk fc = chunk;
                regionScheduler.execute(this, fw, fc.getX(), fc.getZ(), () -> {
                    int n = worldScanner.scanChunk(fw, fc);
                    if (n > 0) {
                        LOG.info("Rescan of chunk " + fc.getX() + "," + fc.getZ()
                            + " in " + fw.getName() + ": " + n + " redstone components (total "
                            + componentMap.size() + ")");
                    }
                });
                dispatched++;
            }
        }
        LOG.info("Manual rescan dispatched for " + dispatched + " loaded chunk(s)");
        return dispatched;
    }

    /**
     * Resolves an entity task action from its canonical task ID, or {@code null}
     * for a task that mutates no state (pure-read {@code ENTITY_COLLISION}, or an
     * unmodelled type). The IDs are the ones {@link org.nebula.entity.EntityTaskFactory}
     * / {@link org.nebula.entity.EntitySnapshot} actually stamp:
     * <ul>
     *   <li>{@code ENTITY_MOVE@<dim>:<entityId>:<x>,<y>,<z>} → {@link EntityMoveAction}</li>
     *   <li>{@code ENTITY_COLLISION_RESPONSE@<dim>:<lo>,<hi>} → {@link EntityCollisionResponseAction}</li>
     *   <li>{@code ENTITY_COLLISION@<dim>:<lo>,<hi>} → {@code null} (pure read, no writes)</li>
     * </ul>
     * The dimension is the first token of the {@code @}-suffix; entity IDs follow.
     */
    static org.nebula.entity.EntityTaskAction resolveEntityAction(String taskId) {
        int at = taskId.indexOf('@');
        if (at < 0) {
            return null;
        }
        String type = taskId.substring(0, at);
        String suffix = taskId.substring(at + 1);
        int dim = parseDimension(suffix);

        if ("ENTITY_MOVE".equals(type)) {
            long entityId = parseMoveEntityId(suffix);
            return new EntityMoveAction(entityId, dim);
        }
        if ("ENTITY_COLLISION_RESPONSE".equals(type)) {
            long[] ids = parsePairIds(suffix);
            return new EntityCollisionResponseAction(ids[0], ids[1]);
        }
        // ENTITY_COLLISION is a pure read (no writes) and any other type is
        // unmodelled here → no action, so the runner treats it as a no-op.
        return null;
    }

    /** Dimension is the first {@code :}-delimited token of a task-ID suffix. */
    private static int parseDimension(String suffix) {
        int colon = suffix.indexOf(':');
        String head = colon < 0 ? suffix : suffix.substring(0, colon);
        try {
            return Integer.parseInt(head);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Entity ID for a MOVE suffix {@code <dim>:<entityId>:<x>,<y>,<z>} — the
     * second {@code :}-delimited token.
     */
    private static long parseMoveEntityId(String suffix) {
        String[] parts = suffix.split(":");
        if (parts.length >= 2) {
            try { return Long.parseLong(parts[1]); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    /**
     * Destination {@link WorldPos} for a MOVE suffix
     * {@code <dim>:<entityId>:<x>,<y>,<z>} — dimension from the first token, block
     * coordinates from the comma-separated third token. Returns {@code null} if the
     * suffix is not the 3-token MOVE grammar (e.g. a malformed or pair-based ID), so
     * a caller can skip region dispatch rather than crash. Kept tolerant of stray
     * whitespace but strict about the token count, so a wrong-arity ID never
     * silently decodes to a bogus position.
     */
    private static WorldPos parseMovePosition(String suffix) {
        String[] parts = suffix.split(":");
        if (parts.length < 3) return null;
        try {
            int dim = Integer.parseInt(parts[0].trim());
            String[] coords = parts[2].split(",");
            if (coords.length < 3) return null;
            int x = Integer.parseInt(coords[0].trim());
            int y = Integer.parseInt(coords[1].trim());
            int z = Integer.parseInt(coords[2].trim());
            return new WorldPos(dim, x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The two entity IDs for a collision-pair suffix {@code <dim>:<lo>,<hi>} —
     * the comma-separated pair after the dimension.
     */
    private static long[] parsePairIds(String suffix) {
        long a = 0, b = 0;
        int colon = suffix.indexOf(':');
        if (colon >= 0) {
            String[] pair = suffix.substring(colon + 1).split(",");
            if (pair.length >= 2) {
                try { a = Long.parseLong(pair[0]); } catch (NumberFormatException e) {}
                try { b = Long.parseLong(pair[1]); } catch (NumberFormatException e) {}
            }
        }
        return new long[]{a, b};
    }
}