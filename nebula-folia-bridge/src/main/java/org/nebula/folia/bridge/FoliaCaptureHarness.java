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

    public FoliaCaptureHarness(Plugin plugin, ReplayRecorder recorder, StateHasher hasher) {
        this(plugin, recorder, hasher, null);
    }

    public FoliaCaptureHarness(Plugin plugin, ReplayRecorder recorder,
                                StateHasher hasher, InputCapture inputCapture) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.inputCapture = inputCapture;
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

    private void onTickEnd() {
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