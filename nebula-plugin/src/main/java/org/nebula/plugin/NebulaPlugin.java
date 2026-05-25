package org.nebula.plugin;

import org.bukkit.plugin.java.JavaPlugin;
import org.nebula.folia.bridge.NebulaFoliaBootstrap;
import org.nebula.folia.bridge.NeighborUpdateInterceptor;
import org.nebula.folia.bridge.RedstoneTickHook;
import org.nebula.guard.RWGuardConfig;
import org.nebula.guard.RWGuardMode;
import org.nebula.redstone.MicroStepScheduler;
import org.nebula.redstone.RedstoneComponentType;
import org.nebula.redstone.RedstoneTaskFactory;
import org.nebula.redstone.RedstoneTaskGenerator;
import org.nebula.core.state.WorldPos;

import org.nebula.replay.ReplayFrame;
import org.nebula.replay.ReplayRecorder;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
/**
 * Nebula Folia plugin — bootstraps the DAG redstone scheduler into Folia.
 *
 * <p>On enable:
 * <ol>
 *   <li>Configures the RW-guard (WARN mode, 1% sampling).</li>
 *   <li>Registers a TaskResolver that maps world positions to
 *       {@link org.nebula.core.scheduler.TaskNode}s using the
 *       {@link RedstoneTaskFactory} RW-set templates.</li>
 *   <li>Registers a Bukkit scheduler tick task that calls
 *       {@link RedstoneTickHook#beginTick} / {@link RedstoneTickHook#endTick}.</li>
 *   <li>Activates all hooks in OBSERVE mode (Phase 0 — listen only).</li>
 * </ol>
 *
 * <p>The /nebula command provides runtime diagnostics.
 */
public final class NebulaPlugin extends JavaPlugin {

    private NebulaFoliaBootstrap bootstrap;
    private ChunkRedstoneScanner scanner;
    private TickStateHasher stateHasher;
    private ReplaySession replaySession;
    private Dg1Verifier dg1Verifier;
    private ShadowExecutionMonitor shadowMonitor;
    private MsptMonitor msptMonitor;
    private MicroStepScheduler shadowScheduler;
    private TickSprinter tickSprinter;
    private InterceptMonitor interceptMonitor;
    private BenchmarkSession benchSession;
    private List<ReplayFrame> referenceReplay = null;

    // Component map populated by ChunkRedstoneScanner on chunk load.
    private final Map<WorldPos, RedstoneComponentType> componentMap =
        new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        Logger log = getLogger();
        log.info("Nebula v" + getDescription().getVersion() + " starting...");

        // 1. RW guard — WARN mode, 1% production sampling
        RWGuardConfig guardConfig = new RWGuardConfig(
            true, 0.01, RWGuardMode.WARN,
            getDataFolder().toPath().resolve("rw-violations.jsonl"),
            200, false
        );

        // 2. TaskResolver: maps world positions to TaskNodes
        RedstoneTickHook.TaskResolver resolver = (worldName, pos) -> {
            RedstoneComponentType type = componentMap.get(pos);
            if (type == null) return null;
            return RedstoneTaskFactory.inert(type, pos);
        };

        // 3. Shadow MicroStepScheduler — inert tasks, no game-state changes
        shadowMonitor    = new ShadowExecutionMonitor(log);
        msptMonitor      = new MsptMonitor(log);
        interceptMonitor = new InterceptMonitor(log);
        RedstoneTaskGenerator generator = new RedstoneTaskGenerator(componentMap);
        shadowScheduler = new MicroStepScheduler(generator, task -> { /* inert */ });

        // TickExecutor: shadow DAG (OBSERVE) or suppressing DAG (INTERCEPT)
        RedstoneTickHook.TickExecutor executor = (regionId, worldName, tasks) -> {
            if (tasks.isEmpty()) return;
            long t0 = System.nanoTime();

            // Track interceptions when in INTERCEPT mode
            if (NeighborUpdateInterceptor.getMode() == NeighborUpdateInterceptor.Mode.INTERCEPT) {
                interceptMonitor.recordSuppression(tasks.size());
            }

            try {
                MicroStepScheduler.TickResult result = shadowScheduler.executeTick(tasks);
                long ns = System.nanoTime() - t0;
                shadowMonitor.record(result, ns);
                msptMonitor.record(ns);
            } catch (org.nebula.core.scheduler.MicroStepLimitException e) {
                log.fine("Shadow DAG microstep limit in region " + regionId
                    + ": " + tasks.size() + " tasks (" + e.getMessage() + ")");
                shadowMonitor.record(
                    new MicroStepScheduler.TickResult(tasks.size(), 1, 256, List.of(), List.of()),
                    System.nanoTime() - t0);
            } catch (Exception e) {
                shadowMonitor.recordError(regionId, e);
                interceptMonitor.recordError();
            }
        };

        // 4. Bootstrap
        bootstrap = NebulaFoliaBootstrap.configure(guardConfig)
            .withObserveMode()        // Phase 0: listen only
            .withTaskResolver(resolver)
            .withTickExecutor(executor)
            .activate();

        // 5. Register tick task via Folia's global region scheduler
        // (Legacy Bukkit scheduler is unsupported in Folia)
        getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> globalTick(), 1L, 1L);

        // 6. Register chunk scanner — auto-populates componentMap on chunk load/unload
        scanner = new ChunkRedstoneScanner(componentMap, log);
        getServer().getPluginManager().registerEvents(scanner, this);

        // 7. State hasher + replay session (Phase 0: REDSTONE_ONLY scope)
        stateHasher = new TickStateHasher(componentMap, TickStateHasher.HashScope.REDSTONE_ONLY, log);
        replaySession = new ReplaySession(stateHasher, log);
        dg1Verifier = new Dg1Verifier(stateHasher, log);
        tickSprinter = new TickSprinter(this, log);
        benchSession = new BenchmarkSession(msptMonitor, log);

        // 8. Register commands
        var cmd = getCommand("nebula");
        if (cmd != null) {
            cmd.setExecutor(new NebulaCommand(this, componentMap, scanner));
        }

        log.info("Nebula activated in OBSERVE mode. Scanner registered, waiting for chunk loads.");
        log.info("Use '/nebula status' for runtime diagnostics.");

        // Open the first dirty-position bucket so region threads can write into it immediately
        RedstoneTickHook.beginTick("nebula-global");

        // Auto-start 1000-tick reference recording after 15 seconds
        // (allow world setup and chunk loading to stabilize first)
        getServer().getGlobalRegionScheduler().runDelayed(this, task -> {
            if (replaySession != null && !replaySession.isRecording()) {
                getDataFolder().mkdirs();
                Path savePath = getDataFolder().toPath().resolve("replay-reference.replay");
                replaySession.startRecording(1000, savePath);
                log.info("Auto-started reference replay recording (1000 ticks) → " + savePath);
            }
        }, 300L); // 300 ticks = 15 seconds — let world stabilize
    }

    @Override
    public void onDisable() {
        if (bootstrap != null) {
            bootstrap.deactivate();
        }
        getLogger().info("Nebula deactivated.");
    }

    private int tickCounter = 0;

    private void globalTick() {
        // Benchmark: measure wall-clock time of each tick (both phases)
        if (benchSession != null && benchSession.isActive()) {
            benchSession.startTick();
        }

        // Fixed regionId — matches the bucket key used by NebulaFoliaBootstrap's recordUpdate listener
        String regionId = "nebula-global";
        // Drain the previous tick's accumulated updates FIRST, then open for the next tick.
        // Order matters: endTick drains & dispatches → beginTick opens a fresh bucket.
        // Region threads write concurrently into the open bucket during their tick.
        RedstoneTickHook.endTick(regionId, "minecraft:overworld");
        RedstoneTickHook.beginTick(regionId);

        tickCounter++;

        // Drive tick sprint (accelerated DG1/replay)
        if (tickSprinter != null && tickSprinter.isActive()) {
            org.bukkit.World overworld = getServer().getWorld("world");
            if (overworld != null) {
                tickSprinter.onServerTick(tickCounter, overworld);
            }
        }

        // Drive replay recording if active
        if (replaySession != null && replaySession.isRecording()) {
            org.bukkit.World overworld = getServer().getWorld("world");
            if (overworld != null) {
                boolean stillRecording = replaySession.onTick(tickCounter, overworld);
                // When reference recording finishes, store frames and auto-start DG1 candidate
                if (!stillRecording && referenceReplay == null && !replaySession.frames().isEmpty()) {
                    referenceReplay = replaySession.frames();
                    dg1Verifier.setReference(referenceReplay);
                    getLogger().info("Reference replay ready: " + referenceReplay.size() + " frames");

                    // Auto-start DG1 inline idempotency verification (10k ticks per arch doc §14.2)
                    getServer().getGlobalRegionScheduler().runDelayed(this, t2 -> {
                        dg1Verifier.startVerification(10_000);
                        getLogger().info("DG1 inline idempotency verification started (10000 ticks)");
                    }, 1L); // 1 tick gap only
                }
            }
        }

        // Drive DG1 candidate recording
        if (dg1Verifier != null && dg1Verifier.isActive()) {
            org.bukkit.World overworld = getServer().getWorld("world");
            if (overworld != null) {
                dg1Verifier.onTick(tickCounter, overworld);
            }
        }

        // Benchmark: report via MsptMonitor rolling window (no tick hook toggling)
        if (benchSession != null && benchSession.isActive()) {
            if (benchSession.isComplete() && tickCounter % 200 == 0) {
                getLogger().info("Bench: " + benchSession.report());
            }
        }

        // Log stats every 200 ticks (~10 seconds)
        if (tickCounter % 200 == 0 && scanner != null && scanner.componentCount() > 0) {
            getLogger().info("Nebula stats: " + componentMap.size()
                + " components, " + scanner.chunksScanned() + " chunks scanned"
                + (replaySession != null && replaySession.isRecording()
                    ? ", recording tick " + replaySession.ticksRecorded() : "")
                + (shadowMonitor != null && shadowMonitor.ticks() > 0
                    ? ", shadow avg " + String.format("%.1f", msptMonitor.avgMs()) + " ms" : ""));
        }
    }

    /** Returns the live component map (for admin commands and testing). */
    public Map<WorldPos, RedstoneComponentType> componentMap() {
        return componentMap;
    }

    /** Returns the active bootstrap (for admin commands). */
    public NebulaFoliaBootstrap bootstrap() {
        return bootstrap;
    }

    /** Returns the chunk scanner (for admin commands). */
    public ChunkRedstoneScanner scanner() {
        return scanner;
    }

    /** Returns the replay session (for admin commands). */
    public ReplaySession replaySession() {
        return replaySession;
    }

    /** Returns the reference replay frames, or null if not yet recorded. */
    public List<ReplayFrame> referenceReplay() {
        return referenceReplay;
    }

    /** Sets the reference replay (captured in OBSERVE mode). */
    public void setReferenceReplay(List<ReplayFrame> frames) {
        this.referenceReplay = frames;
    }

    /** Returns the shadow execution monitor. */
    public ShadowExecutionMonitor shadowMonitor() {
        return shadowMonitor;
    }

    /** Returns the MSPT monitor. */
    public MsptMonitor msptMonitor() {
        return msptMonitor;
    }

    /** Returns the DG1 verifier. */
    public Dg1Verifier dg1Verifier() {
        return dg1Verifier;
    }

    /** Switches the interceptor to INTERCEPT mode — DAG suppresses Folia propagation. */
    public void switchToInterceptMode() {
        NeighborUpdateInterceptor.setMode(NeighborUpdateInterceptor.Mode.INTERCEPT);
        if (interceptMonitor != null) interceptMonitor.start();
        getLogger().info("Nebula switched to INTERCEPT mode — DAG now drives redstone propagation.");
    }

    /** Switches the interceptor back to OBSERVE mode — Folia propagates normally. */
    public void switchToObserveMode() {
        NeighborUpdateInterceptor.setMode(NeighborUpdateInterceptor.Mode.OBSERVE);
        if (interceptMonitor != null && interceptMonitor.isActive()) interceptMonitor.stop();
        getLogger().info("Nebula switched to OBSERVE mode.");
    }

    /** Returns the INTERCEPT monitor. */
    public InterceptMonitor interceptMonitor() {
        return interceptMonitor;
    }

    /** Returns the benchmark session. */
    public BenchmarkSession benchSession() {
        return benchSession;
    }

    /** Returns the tick sprinter. */
    public TickSprinter tickSprinter() {
        return tickSprinter;
    }
}
