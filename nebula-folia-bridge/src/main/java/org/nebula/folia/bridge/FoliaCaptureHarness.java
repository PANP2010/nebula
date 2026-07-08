package org.nebula.folia.bridge;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.nebula.replay.ReplayRecorder;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Hooks into Folia's tick loop to capture state hashes for zero-diff verification.
 *
 * <p>Usage:
 * <pre>{@code
 * FoliaCaptureHarness harness = new FoliaCaptureHarness(plugin, recorder, worldHasher);
 * harness.start(1000); // capture 1000 ticks
 * // ... ticks run ...
 * harness.stop();
 * List<ReplayFrame> frames = recorder.getFrames();
 * }</pre>
 *
 * <p>At each tick end, the harness:
 * <ol>
 *   <li>Calls the injected {@link StateHasher} to compute a hash of the current world state.</li>
 *   <li>Records a {@link ReplayFrame} with the tick number and state hash.</li>
 *   <li>Optionally records player inputs if a {@link InputCapture} is registered.</li>
 * </ol>
 *
 * <p>Designed for offline capture: run against a Folia server with the target world,
 * capture N ticks, then replay the same inputs through Nebula's DAG executor and
 * compare the state hashes via {@link org.nebula.replay.ReplayVerifier}.
 */
public final class FoliaCaptureHarness {

    private static final Logger LOG = Logger.getLogger(FoliaCaptureHarness.class.getName());

    private final Plugin plugin;
    private final ReplayRecorder recorder;
    private final StateHasher hasher;
    private final InputCapture inputCapture;
    private final TickDriver tickDriver;

    private volatile boolean running = false;
    private volatile long targetTicks = 0;
    private volatile long capturedTicks = 0;
    private long tickNumber = 0;

    /** Computes a deterministic hash of world state at a point in time. */
    @FunctionalInterface
    public interface StateHasher {
        /** Returns a hash of the world state for the given dimension, or null if unavailable. */
        byte[] hashState(World world, long tickNumber);
    }

    /** Captures player input packets during the tick. */
    @FunctionalInterface
    public interface InputCapture {
        /** Returns a map of player ID → list of packet bytes for the current tick. */
        Map<String, List<byte[]>> captureInputs();
    }

    /**
     * Drives deterministic input into the world at the start of each captured tick,
     * <em>before</em> the state hash is taken (the live-load-driver seam, arch doc
     * §12.1; DG1 Criterion 1 live-load slice).
     *
     * <p>This is the receiving seam the live-load driver plugs into: a capture run
     * that supplies a {@code TickDriver} (built from
     * {@code CanonicalToggleSources.newDriver(seed, period, applier)}) becomes a
     * <em>driven</em> capture — the same seed produces the identical toggle stream
     * tick-for-tick across two independent runs, so their {@code .nrp} files stay
     * byte-comparable and any divergence is attributable to the engine, not the
     * input. When no driver is supplied the harness captures a static world exactly
     * as before (the two-arg / three-arg constructors pass {@code null} here), so
     * this seam is inert by default and changes no existing capture behaviour.
     *
     * <h3>Why drive before hashing, on the same tick counter</h3>
     * The hash recorded for tick T must reflect the world <em>after</em> tick T's
     * input was applied, and both runs must key their drive decisions off the same
     * monotonic tick number the frame is recorded under — otherwise the two runs
     * would flip different sources on the "same" frame and diverge for a reason that
     * has nothing to do with the engine (the invisible-gap class of the B3
     * key-mismatch wound this whole driver exists to guard against). The harness
     * therefore calls {@link #driveTick} with its own {@code tickNumber} at the top
     * of {@code onTickEnd}, immediately before hashing.
     */
    @FunctionalInterface
    public interface TickDriver {
        /**
         * Applies this tick's deterministic input. Invoked once per captured tick on
         * the global tick thread, keyed off the harness tick counter (starts at 0).
         *
         * @param tickNumber the capture-local tick number this frame is recorded under
         */
        void driveTick(long tickNumber);
    }

    public FoliaCaptureHarness(Plugin plugin, ReplayRecorder recorder, StateHasher hasher) {
        this(plugin, recorder, hasher, null, null);
    }

    public FoliaCaptureHarness(Plugin plugin, ReplayRecorder recorder,
                                StateHasher hasher, InputCapture inputCapture) {
        this(plugin, recorder, hasher, inputCapture, null);
    }

    /**
     * Full constructor. Supply a non-null {@link TickDriver} to make this a
     * <em>driven</em> capture (see {@link TickDriver}); pass {@code null} for a
     * static capture.
     */
    public FoliaCaptureHarness(Plugin plugin, ReplayRecorder recorder,
                                StateHasher hasher, InputCapture inputCapture,
                                TickDriver tickDriver) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.inputCapture = inputCapture;
        this.tickDriver = tickDriver;
    }

    /**
     * Starts capturing ticks. The harness registers a global tick task that
     * captures state at the end of each tick.
     *
     * @param ticks number of ticks to capture; 0 = capture until stopped
     */
    public void start(long ticks) {
        if (running) {
            LOG.warning("FoliaCaptureHarness already running");
            return;
        }
        targetTicks = ticks;
        capturedTicks = 0;
        tickNumber = 0;
        running = true;
        recorder.start();

        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (!running) {
                task.cancel();
                return;
            }
            onTickEnd();
            capturedTicks++;
            if (targetTicks > 0 && capturedTicks >= targetTicks) {
                stop();
                task.cancel();
            }
        }, 1L, 1L);

        LOG.info("FoliaCaptureHarness started: capturing " + (ticks > 0 ? ticks + " ticks" : "until stopped"));
    }

    /** Stops capturing. */
    public void stop() {
        if (!running) return;
        running = false;
        recorder.stop();
        LOG.info("FoliaCaptureHarness stopped: captured " + capturedTicks + " ticks");
    }

    /**
     * Runs one captured tick: drives this tick's deterministic input (if a
     * {@link TickDriver} was supplied), records inputs, hashes the primary world,
     * and advances the tick counter. Package-private so the drive-then-hash ordering
     * can be unit-tested without a running Folia scheduler.
     */
    void onTickEnd() {
        // Drive this tick's deterministic input BEFORE hashing, keyed off the same
        // tick counter the frame is recorded under, so two runs of the same seed
        // stay byte-comparable (see TickDriver). Inert when no driver was supplied.
        if (tickDriver != null) {
            tickDriver.driveTick(tickNumber);
        }

        recorder.beginTick(tickNumber);

        // Record player inputs if available
        if (inputCapture != null) {
            Map<String, List<byte[]>> inputs = inputCapture.captureInputs();
            for (var entry : inputs.entrySet()) {
                String playerId = entry.getKey();
                for (byte[] packet : entry.getValue()) {
                    recorder.recordPlayerInput(playerId, packet);
                }
            }
        }

        // Compute and record state hash for the primary world
        World primaryWorld = plugin.getServer().getWorlds().isEmpty()
            ? null : plugin.getServer().getWorlds().get(0);
        if (primaryWorld != null) {
            byte[] hash = hasher.hashState(primaryWorld, tickNumber);
            if (hash != null) {
                recorder.endTick(hash);
            } else {
                recorder.endTick(new byte[0]); // empty hash as fallback
            }
        }

        tickNumber++;
    }

    public boolean isRunning() { return running; }
    public long capturedTicks() { return capturedTicks; }
}