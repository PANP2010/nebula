package org.nebula.replay;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReplayVerifierTest {

    private static byte[] hash(int seed) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(ByteBuffer.allocate(4).putInt(seed).array());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private static ReplayFrame frame(long tick, byte[] hash) {
        return new ReplayFrame(tick, new TickInput(tick, Map.of()), hash);
    }

    @Test
    void identicalReplaysPassVerification() {
        byte[] h = hash(42);
        List<ReplayFrame> ref = List.of(frame(0, h), frame(1, h));
        List<ReplayFrame> cand = List.of(frame(0, h), frame(1, h));

        ReplayVerifier.VerificationResult result = ReplayVerifier.verify(ref, cand);
        assertTrue(result.passed());
        assertEquals(0, result.mismatchCount());
    }

    @Test
    void mismatchedHashDetected() {
        byte[] h1 = hash(1);
        byte[] h2 = hash(2);
        List<ReplayFrame> ref = List.of(frame(0, h1), frame(1, h1));
        List<ReplayFrame> cand = List.of(frame(0, h1), frame(1, h2));

        ReplayVerifier.VerificationResult result = ReplayVerifier.verify(ref, cand);
        assertFalse(result.passed());
        assertEquals(1, result.mismatchCount());
        assertEquals(1, result.mismatches().get(0).tickNumber());
    }

    @Test
    void differentReplayLengthsDetected() {
        byte[] h = hash(1);
        List<ReplayFrame> ref = List.of(frame(0, h), frame(1, h), frame(2, h));
        List<ReplayFrame> cand = List.of(frame(0, h), frame(1, h));

        ReplayVerifier.VerificationResult result = ReplayVerifier.verify(ref, cand);
        assertFalse(result.passed());
        assertTrue(result.mismatches().stream().anyMatch(m -> "replay-length".equals(m.field())));
    }

    @Test
    void binarySearchFindsFirstMismatch() {
        List<byte[]> refHashes = new ArrayList<>();
        List<byte[]> candHashes = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            refHashes.add(hash(i));
            candHashes.add(i < 50 ? hash(i) : hash(i + 1000)); // diverge at tick 50
        }

        int firstMismatch = ReplayVerifier.binarySearchFirstMismatch(refHashes, candHashes);
        assertEquals(50, firstMismatch);
    }

    @Test
    void binarySearchReturnsNegativeForIdenticalSequences() {
        List<byte[]> hashes = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            hashes.add(hash(i));
        }

        int result = ReplayVerifier.binarySearchFirstMismatch(hashes, List.copyOf(hashes));
        assertEquals(-1, result);
    }

    @Test
    void binarySearchHandlesFirstTickMismatch() {
        List<byte[]> ref = List.of(hash(0), hash(1));
        List<byte[]> cand = List.of(hash(999), hash(1));

        assertEquals(0, ReplayVerifier.binarySearchFirstMismatch(ref, cand));
    }

    @Test
    void stateHashComputerProducesDeterministicOutput() {
        byte[] h1 = StateHashComputer.computeSeedHash(12345L);
        byte[] h2 = StateHashComputer.computeSeedHash(12345L);
        assertArrayEquals(h1, h2);
    }

    @Test
    void stateHashComputerDifferentSeedsDifferentHashes() {
        byte[] h1 = StateHashComputer.computeSeedHash(111L);
        byte[] h2 = StateHashComputer.computeSeedHash(222L);
        assertFalse(HexFormat.of().formatHex(h1).equals(HexFormat.of().formatHex(h2)));
    }

    @Test
    void stateHashComputerIncludesAllStateDimensions() {
        var chunks = Map.of("0:0,0,0", new byte[]{1, 2, 3});
        var entities = Map.of("e1", new byte[]{4, 5});
        var globals = Map.of("game_time", new byte[]{6, 7, 8});

        byte[] h1 = StateHashComputer.compute(chunks, Map.of(), entities, globals);
        byte[] h2 = StateHashComputer.compute(chunks, Map.of(), Map.of(), globals);

        assertFalse(HexFormat.of().formatHex(h1).equals(HexFormat.of().formatHex(h2)),
            "Adding entity data should change the hash");
    }
}
