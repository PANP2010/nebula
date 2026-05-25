package org.nebula.replay;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads and replays a replay file for determinism verification (arch doc §12.1).
 *
 * <p>Usage:
 * <pre>{@code
 * List<ReplayFrame> frames = ReplayPlayer.load(path);
 * ReplayPlayer player = new ReplayPlayer(frames);
 * player.replay(scheduler);  // blocks until all ticks are replayed
 * }</pre>
 */
public final class ReplayPlayer {

    private static final Logger LOG = Logger.getLogger(ReplayPlayer.class.getName());
    private static final byte[] MAGIC = {'N', 'R', 'P', 'L'};

    private final List<ReplayFrame> frames;

    public ReplayPlayer(List<ReplayFrame> frames) {
        this.frames = List.copyOf(frames);
    }

    /**
     * Deserialises a replay file written by {@link ReplayRecorder#save(Path)}.
     *
     * @param path source file path
     * @return list of frames in tick order
     * @throws IOException on read failure or format mismatch
     */
    public static List<ReplayFrame> load(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(data));

        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException("Not a Nebula replay file (bad magic)");
        }
        short version = in.readShort();
        if (version != 1) {
            throw new IOException("Unsupported replay format version: " + version);
        }

        int frameCount = in.readInt();
        List<ReplayFrame> frames = new ArrayList<>(frameCount);

        for (int f = 0; f < frameCount; f++) {
            long tickNumber = in.readLong();
            int playerCount = in.readShort() & 0xFFFF;
            Map<String, List<byte[]>> inputs = new HashMap<>();

            for (int p = 0; p < playerCount; p++) {
                int idLen = in.readShort() & 0xFFFF;
                byte[] idBytes = new byte[idLen];
                in.readFully(idBytes);
                String playerId = new String(idBytes, StandardCharsets.UTF_8);

                int packetCount = in.readShort() & 0xFFFF;
                List<byte[]> packets = new ArrayList<>(packetCount);
                for (int pk = 0; pk < packetCount; pk++) {
                    int len = in.readInt();
                    byte[] packet = new byte[len];
                    in.readFully(packet);
                    packets.add(packet);
                }
                inputs.put(playerId, List.copyOf(packets));
            }

            int hashLen = in.readByte() & 0xFF;
            byte[] hash = new byte[hashLen];
            in.readFully(hash);

            TickInput tickInput = new TickInput(tickNumber, Map.copyOf(inputs));
            frames.add(new ReplayFrame(tickNumber, tickInput, hash));
        }

        LOG.info("Loaded replay with " + frames.size() + " frames from " + path);
        return List.copyOf(frames);
    }

    /**
     * Replays all frames through the given scheduler and returns a verification result.
     *
     * <p>For each frame, the player applies the recorded inputs, then computes the
     * state hash and compares it to the recorded hash.  The first mismatch is
     * reported in the returned {@link ReplayVerifier.VerificationResult}.
     *
     * @param scheduler game layer implementing the tick function
     * @return verification result (passed if all hashes match)
     */
    public ReplayVerifier.VerificationResult replay(ReplayScheduler scheduler) {
        List<ReplayFrame> candidateFrames = new ArrayList<>(frames.size());

        for (ReplayFrame frame : frames) {
            scheduler.applyInputs(frame.tickNumber(), frame.input());
            byte[] candidateHash = scheduler.computeStateHash();
            candidateFrames.add(new ReplayFrame(frame.tickNumber(), frame.input(), candidateHash));
        }

        return ReplayVerifier.verify(frames, candidateFrames);
    }

    /** Returns the frames loaded by this player. */
    public List<ReplayFrame> frames() {
        return frames;
    }

    /** Returns the number of frames. */
    public int frameCount() {
        return frames.size();
    }
}
