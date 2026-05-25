package org.nebula.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReplayRecorderTest {

    private static byte[] hash(int seed) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(new byte[]{(byte) seed, (byte) (seed >> 8), (byte) (seed >> 16), (byte) (seed >> 24)});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void emptyRecordingProducesNoFrames() {
        ReplayRecorder recorder = new ReplayRecorder();
        recorder.start();
        recorder.stop();
        assertEquals(0, recorder.frameCount());
    }

    @Test
    void singleTickRecorded() {
        ReplayRecorder recorder = new ReplayRecorder();
        recorder.start();
        recorder.beginTick(0L);
        recorder.recordPlayerInput("player1", new byte[]{1, 2, 3});
        recorder.endTick(hash(42));
        recorder.stop();

        assertEquals(1, recorder.frameCount());
        ReplayFrame frame = recorder.getFrames().get(0);
        assertEquals(0L, frame.tickNumber());
        assertTrue(frame.input().playerInputs().containsKey("player1"));
        assertArrayEquals(hash(42), frame.stateHash());
    }

    @Test
    void multipleTicksInOrder() {
        ReplayRecorder recorder = new ReplayRecorder();
        recorder.start();
        for (int i = 0; i < 5; i++) {
            recorder.beginTick(i);
            recorder.endTick(hash(i));
        }
        recorder.stop();

        assertEquals(5, recorder.frameCount());
        List<ReplayFrame> frames = recorder.getFrames();
        for (int i = 0; i < 5; i++) {
            assertEquals(i, frames.get(i).tickNumber());
        }
    }

    @Test
    void recordingIgnoredWhenStopped() {
        ReplayRecorder recorder = new ReplayRecorder();
        // Never started
        recorder.beginTick(0);
        recorder.endTick(hash(0));
        assertEquals(0, recorder.frameCount());
    }

    @Test
    void saveAndLoadRoundTrip(@TempDir Path tempDir) throws IOException {
        ReplayRecorder recorder = new ReplayRecorder();
        recorder.start();
        recorder.beginTick(1);
        recorder.recordPlayerInput("alice", new byte[]{10, 20});
        recorder.endTick(hash(100));
        recorder.beginTick(2);
        recorder.recordPlayerInput("bob", new byte[]{30, 40, 50});
        recorder.endTick(hash(200));
        recorder.stop();

        Path file = tempDir.resolve("test.replay");
        recorder.save(file);

        // Load and verify
        List<ReplayFrame> loaded = ReplayPlayer.load(file);
        assertEquals(2, loaded.size());

        assertEquals(1L, loaded.get(0).tickNumber());
        assertTrue(loaded.get(0).input().playerInputs().containsKey("alice"));
        assertArrayEquals(new byte[]{10, 20},
            loaded.get(0).input().playerInputs().get("alice").get(0));
        assertArrayEquals(hash(100), loaded.get(0).stateHash());

        assertEquals(2L, loaded.get(1).tickNumber());
        assertTrue(loaded.get(1).input().playerInputs().containsKey("bob"));
        assertArrayEquals(hash(200), loaded.get(1).stateHash());
    }

    @Test
    void multiplePlayersInOneTick(@TempDir Path tempDir) throws IOException {
        ReplayRecorder recorder = new ReplayRecorder();
        recorder.start();
        recorder.beginTick(0);
        recorder.recordPlayerInput("p1", new byte[]{1});
        recorder.recordPlayerInput("p2", new byte[]{2});
        recorder.recordPlayerInput("p3", new byte[]{3});
        recorder.endTick(hash(0));
        recorder.stop();

        Path file = tempDir.resolve("multi.replay");
        recorder.save(file);
        List<ReplayFrame> loaded = ReplayPlayer.load(file);

        Map<String, List<byte[]>> inputs = loaded.get(0).input().playerInputs();
        assertEquals(3, inputs.size());
        assertTrue(inputs.containsKey("p1"));
        assertTrue(inputs.containsKey("p2"));
        assertTrue(inputs.containsKey("p3"));
    }
}
