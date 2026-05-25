package org.nebula.replay;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Records a live server session as a replay file (arch doc §12.1).
 *
 * <p>The recorder hooks into the server tick loop:
 * <ol>
 *   <li>Call {@link #beginTick(long)} at the start of each tick.</li>
 *   <li>Call {@link #recordPlayerInput(String, byte[])} for each player packet.</li>
 *   <li>Call {@link #endTick(byte[])} with the post-tick state hash.</li>
 * </ol>
 *
 * <p>After recording, call {@link #save(Path)} to serialise to disk or
 * {@link #getFrames()} to access frames in memory.
 *
 * <h3>Binary format</h3>
 * <pre>
 * File: magic(4) + version(2) + frameCount(4) + frame*
 * Frame: tickNumber(8) + playerCount(2) + player* + hashLen(1) + hash(N)
 * Player: idLen(2) + id(UTF) + packetCount(2) + packet*
 * Packet: len(4) + data(N)
 * </pre>
 */
public final class ReplayRecorder {

    private static final Logger LOG = Logger.getLogger(ReplayRecorder.class.getName());
    private static final byte[] MAGIC = {'N', 'R', 'P', 'L'}; // NebulaRePLayback
    private static final short FORMAT_VERSION = 1;

    private final List<ReplayFrame> frames = new ArrayList<>();

    // Per-tick state
    private long currentTick = -1;
    private final Map<String, List<byte[]>> tickInputs = new HashMap<>();
    private boolean recording = false;

    /** Starts the recorder. Must be called before any tick methods. */
    public void start() {
        frames.clear();
        recording = true;
        LOG.info("ReplayRecorder started");
    }

    /** Stops the recorder. */
    public void stop() {
        recording = false;
        LOG.info("ReplayRecorder stopped after " + frames.size() + " frames");
    }

    /** Called at the start of each tick — captures the tick number. */
    public void beginTick(long tickNumber) {
        if (!recording) return;
        currentTick = tickNumber;
        tickInputs.clear();
    }

    /** Records a player input packet for the current tick. */
    public void recordPlayerInput(String playerId, byte[] packet) {
        if (!recording || currentTick < 0) return;
        tickInputs.computeIfAbsent(playerId, k -> new ArrayList<>()).add(packet.clone());
    }

    /**
     * Called at the end of each tick with the post-tick state hash.
     * Creates and stores a {@link ReplayFrame}.
     */
    public void endTick(byte[] stateHash) {
        if (!recording || currentTick < 0) return;
        TickInput input = new TickInput(currentTick, Map.copyOf(
            tickInputs.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                    Map.Entry::getKey,
                    e -> List.copyOf(e.getValue())))));
        frames.add(new ReplayFrame(currentTick, input, stateHash));
    }

    /** Returns all recorded frames (in tick order). */
    public List<ReplayFrame> getFrames() {
        return List.copyOf(frames);
    }

    /** Returns the number of frames recorded so far. */
    public int frameCount() {
        return frames.size();
    }

    /**
     * Serialises all frames to a binary replay file.
     *
     * @param path destination file path
     * @throws IOException on write failure
     */
    public void save(Path path) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        out.write(MAGIC);
        out.writeShort(FORMAT_VERSION);
        out.writeInt(frames.size());

        for (ReplayFrame frame : frames) {
            out.writeLong(frame.tickNumber());
            Map<String, List<byte[]>> inputs = frame.input().playerInputs();
            out.writeShort(inputs.size());
            for (Map.Entry<String, List<byte[]>> entry : inputs.entrySet()) {
                byte[] idBytes = entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.writeShort(idBytes.length);
                out.write(idBytes);
                out.writeShort(entry.getValue().size());
                for (byte[] packet : entry.getValue()) {
                    out.writeInt(packet.length);
                    out.write(packet);
                }
            }
            byte[] hash = frame.stateHash();
            out.writeByte(hash.length);
            out.write(hash);
        }

        out.flush();
        Files.write(path, baos.toByteArray());
        LOG.info("Replay saved to " + path + " (" + frames.size() + " frames, " + baos.size() + " bytes)");
    }
}
