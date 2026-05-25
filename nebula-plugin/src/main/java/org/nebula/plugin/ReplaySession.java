package org.nebula.plugin;

import org.bukkit.World;
import org.nebula.replay.ReplayFrame;
import org.nebula.replay.ReplayRecorder;
import org.nebula.replay.ReplayVerifier;
import org.nebula.replay.TickInput;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Manages a complete replay recording or verification session.
 *
 * <p>Phase 0 workflow:
 * <ol>
 *   <li>OBSERVE mode: call {@link #startRecording} — records every tick's state hash.</li>
 *   <li>After N ticks: call {@link #stopRecording} — saves the reference replay to disk.</li>
 *   <li>INTERCEPT mode: call {@link #startRecording} again while DAG executor is active.</li>
 *   <li>Call {@link #compareWith} to run {@link ReplayVerifier} against the reference.</li>
 * </ol>
 */
public final class ReplaySession {

    private final Logger log;
    private final TickStateHasher hasher;
    private final ReplayRecorder recorder = new ReplayRecorder();

    private volatile boolean recording = false;
    private long ticksRecorded = 0;
    private long maxTicks = Long.MAX_VALUE;
    private java.nio.file.Path autoSavePath = null;

    public ReplaySession(TickStateHasher hasher, Logger log) {
        this.hasher = hasher;
        this.log = log;
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    /** Starts recording. Call at the beginning of a test window. */
    public void startRecording(long maxTicks) {
        startRecording(maxTicks, null);
    }

    public void startRecording(long maxTicks, java.nio.file.Path savePath) {
        this.maxTicks = maxTicks;
        this.ticksRecorded = 0;
        this.autoSavePath = savePath;
        recorder.start();
        recording = true;
        log.info("ReplaySession: recording started (max " + maxTicks + " ticks)");
    }

    /**
     * Called each tick. Records the state hash for the given world.
     * Returns true if recording is still active, false if max ticks reached.
     */
    public boolean onTick(long tickNumber, World world) {
        if (!recording) return false;

        recorder.beginTick(tickNumber);
        // No player inputs in Phase 0 (no real players, just bot)
        byte[] stateHash = hasher.computeHash(world);
        recorder.endTick(stateHash);
        ticksRecorded++;

        if (ticksRecorded % 1000 == 0) {
            log.info("ReplaySession: " + ticksRecorded + " ticks recorded (hash="
                + hexShort(stateHash) + ")");
        }

        if (ticksRecorded >= maxTicks) {
            stopRecording(autoSavePath);
            return false;
        }
        return true;
    }

    /**
     * Stops recording and optionally saves to file.
     *
     * @param savePath path to write .replay file, or null to skip saving
     */
    public void stopRecording(Path savePath) {
        if (!recording) return;
        recording = false;
        recorder.stop();
        log.info("ReplaySession: recording stopped, " + ticksRecorded
            + " frames captured");

        if (savePath != null) {
            try {
                recorder.save(savePath);
                log.info("ReplaySession: saved to " + savePath);
            } catch (Exception e) {
                log.warning("ReplaySession: failed to save: " + e);
            }
        }
    }

    // ── Verification ──────────────────────────────────────────────────────────

    /**
     * Compares this session's recorded frames against a reference frame list.
     * Returns a human-readable summary.
     */
    public String compareWith(List<ReplayFrame> reference) {
        List<ReplayFrame> candidate = recorder.getFrames();
        ReplayVerifier.VerificationResult result = ReplayVerifier.verify(reference, candidate);

        if (result.passed()) {
            return "PASS: " + candidate.size() + " ticks identical";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("FAIL: ").append(result.mismatchCount()).append(" mismatches\n");
        for (ReplayVerifier.Mismatch m : result.mismatches()) {
            sb.append("  tick=").append(m.tickNumber())
              .append(" field=").append(m.field())
              .append(" expected=").append(m.expected().substring(0, Math.min(16, m.expected().length())))
              .append(" actual=").append(m.actual().substring(0, Math.min(16, m.actual().length())))
              .append("\n");
        }

        // Binary search first mismatch
        List<byte[]> refHashes = reference.stream().map(ReplayFrame::stateHash).toList();
        List<byte[]> candHashes = candidate.stream().map(ReplayFrame::stateHash).toList();
        int firstMismatch = ReplayVerifier.binarySearchFirstMismatch(refHashes, candHashes);
        if (firstMismatch >= 0) {
            sb.append("  First divergence: tick ").append(firstMismatch);
        }
        return sb.toString();
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public boolean isRecording() { return recording; }
    public long ticksRecorded() { return ticksRecorded; }
    public List<ReplayFrame> frames() { return recorder.getFrames(); }

    private static String hexShort(byte[] hash) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(4, hash.length); i++) {
            sb.append(String.format("%02x", hash[i]));
        }
        return sb + "...";
    }
}
