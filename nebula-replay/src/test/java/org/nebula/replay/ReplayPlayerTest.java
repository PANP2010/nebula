package org.nebula.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReplayPlayerTest {

    private static byte[] hash(int seed) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(new byte[]{(byte) seed, (byte) (seed >> 8), (byte) (seed >> 16), (byte) (seed >> 24)});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ReplayFrame frame(long tick, byte[] h) {
        return new ReplayFrame(tick, new TickInput(tick, Map.of()), h);
    }

    /** A scheduler that re-plays the given reference hashes deterministically. */
    private static ReplayScheduler deterministicScheduler(List<byte[]> hashes) {
        return new ReplayScheduler() {
            private long tick = 0;
            @Override public void applyInputs(long tickNumber, TickInput input) { tick = tickNumber; }
            @Override public byte[] computeStateHash() { return hashes.get((int) tick).clone(); }
            @Override public long currentTick() { return tick; }
        };
    }

    @Test
    void identicalReplayPasses() {
        List<byte[]> hashes = List.of(hash(0), hash(1), hash(2));
        List<ReplayFrame> frames = new ArrayList<>();
        for (int i = 0; i < hashes.size(); i++) frames.add(frame(i, hashes.get(i)));

        ReplayPlayer player = new ReplayPlayer(frames);
        ReplayVerifier.VerificationResult result = player.replay(deterministicScheduler(hashes));
        assertTrue(result.passed());
    }

    @Test
    void divergingReplayFails() {
        List<byte[]> referenceHashes = List.of(hash(0), hash(1), hash(2));
        // Candidate diverges at tick 1
        List<byte[]> candidateHashes = List.of(hash(0), hash(999), hash(2));

        List<ReplayFrame> frames = new ArrayList<>();
        for (int i = 0; i < referenceHashes.size(); i++) frames.add(frame(i, referenceHashes.get(i)));

        ReplayPlayer player = new ReplayPlayer(frames);
        ReplayVerifier.VerificationResult result = player.replay(deterministicScheduler(candidateHashes));

        assertFalse(result.passed());
        assertEquals(1, result.mismatchCount());
        assertEquals(1L, result.mismatches().get(0).tickNumber());
    }

    @Test
    void loadAndReplaySavedFile(@TempDir Path tempDir) throws IOException {
        // Record
        ReplayRecorder recorder = new ReplayRecorder();
        recorder.start();
        for (int i = 0; i < 10; i++) {
            recorder.beginTick(i);
            recorder.endTick(hash(i));
        }
        recorder.stop();
        Path file = tempDir.resolve("r.replay");
        recorder.save(file);

        // Load and replay with matching scheduler
        List<ReplayFrame> loaded = ReplayPlayer.load(file);
        List<byte[]> hashes = new ArrayList<>();
        for (int i = 0; i < 10; i++) hashes.add(hash(i));

        ReplayPlayer player = new ReplayPlayer(loaded);
        ReplayVerifier.VerificationResult result = player.replay(deterministicScheduler(hashes));
        assertTrue(result.passed());
        assertEquals(10, result.referenceTicks());
    }

    @Test
    void emptyReplayAlwaysPasses() {
        ReplayPlayer player = new ReplayPlayer(List.of());
        ReplayVerifier.VerificationResult result = player.replay(deterministicScheduler(List.of()));
        assertTrue(result.passed());
    }

    @Test
    void frameCountMatches() {
        List<ReplayFrame> frames = List.of(frame(0, hash(0)), frame(1, hash(1)));
        assertEquals(2, new ReplayPlayer(frames).frameCount());
    }
}
