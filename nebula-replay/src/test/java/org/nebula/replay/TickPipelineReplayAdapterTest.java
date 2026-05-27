package org.nebula.replay;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.scheduler.TaskRunner;
import org.nebula.core.scheduler.TickPipeline;
import org.nebula.core.state.WorldPos;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TickPipelineReplayAdapterTest {

    @Test
    void adapterExecutesTicksAndProducesHashes() {
        List<String> executedTasks = new ArrayList<>();

        TickPipeline pipeline = new TickPipeline(
            completed -> List.of(),
            TaskRunner.DIRECT
        );

        // Synthetic adapter: each tick input produces one write task
        TickPipelineReplayAdapter adapter = new TickPipelineReplayAdapter(pipeline) {
            private long stateAccumulator = 0;

            @Override
            protected Collection<TaskNode> inputToTasks(long tickNumber, TickInput input) {
                WorldPos pos = new WorldPos(0, (int) tickNumber, 64, 0);
                return List.of(new TaskNode(
                    "tick-" + tickNumber,
                    "SYNTHETIC",
                    RWSet.builder().writeBlock(pos).build(),
                    () -> executedTasks.add("tick-" + tickNumber)
                ));
            }

            @Override
            protected void onTickCompleted(long tickNumber, TickPipeline.TickResult result) {
                stateAccumulator += tickNumber;
            }

            @Override
            public byte[] computeStateHash() {
                try {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    md.update(ByteBuffer.allocate(8).putLong(stateAccumulator).array());
                    return md.digest();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };

        // Replay 5 ticks
        for (int i = 1; i <= 5; i++) {
            TickInput input = new TickInput(i, Map.of());
            adapter.applyInputs(i, input);
        }

        assertEquals(5, executedTasks.size());
        assertEquals("tick-1", executedTasks.get(0));
        assertEquals("tick-5", executedTasks.get(4));
        assertEquals(5, adapter.currentTick());

        // Hash should be non-null and 32 bytes (SHA-256)
        byte[] hash = adapter.computeStateHash();
        assertNotNull(hash);
        assertEquals(32, hash.length);
    }

    @Test
    void deferredTasksCarryForward() {
        List<String> executedTasks = new ArrayList<>();

        // Generator that creates a conflicting task (WAW with trigger) → deferred
        TickPipeline pipeline = new TickPipeline(
            completed -> {
                if (completed.taskId().equals("tick-1")) {
                    // Generate a task that writes same pos as tick-1 (WAW → deferred)
                    return List.of(new TaskNode("deferred-from-1", "DEFERRED",
                        RWSet.builder().writeBlock(new WorldPos(0, 1, 64, 0)).build(),
                        () -> executedTasks.add("deferred-from-1")));
                }
                return List.of();
            },
            TaskRunner.DIRECT
        );

        TickPipelineReplayAdapter adapter = new TickPipelineReplayAdapter(pipeline) {
            @Override
            protected Collection<TaskNode> inputToTasks(long tickNumber, TickInput input) {
                WorldPos pos = new WorldPos(0, (int) tickNumber, 64, 0);
                return List.of(new TaskNode(
                    "tick-" + tickNumber,
                    "SYNTHETIC",
                    RWSet.builder().writeBlock(pos).build(),
                    () -> executedTasks.add("tick-" + tickNumber)
                ));
            }

            @Override
            public byte[] computeStateHash() {
                return new byte[32];
            }
        };

        // Tick 1: executes tick-1, deferred-from-1 is deferred
        adapter.applyInputs(1, new TickInput(1, Map.of()));
        assertEquals(List.of("tick-1"), executedTasks);

        // Tick 2: executes tick-2 AND the deferred-from-1
        adapter.applyInputs(2, new TickInput(2, Map.of()));
        assertTrue(executedTasks.contains("tick-2"));
        assertTrue(executedTasks.contains("deferred-from-1"));
    }

    @Test
    void playerInputSeedingProducesOneTaskPerPlayerSorted() {
        // Mirrors NebulaServerReplayScheduler's contract: one GLOBAL_RW
        // task per player with packets, IDs sorted for determinism.
        List<String> executedIds = new ArrayList<>();

        TickPipeline pipeline = new TickPipeline(
            completed -> List.of(),
            TaskRunner.DIRECT
        );

        TickPipelineReplayAdapter adapter = new TickPipelineReplayAdapter(pipeline) {
            @Override
            protected Collection<TaskNode> inputToTasks(long tickNumber, TickInput input) {
                Map<String, List<byte[]>> inputs = input.playerInputs();
                if (inputs.isEmpty()) return List.of();

                List<String> ids = new ArrayList<>(inputs.keySet());
                java.util.Collections.sort(ids);

                List<TaskNode> tasks = new ArrayList<>();
                for (String id : ids) {
                    final String pid = id;
                    tasks.add(new TaskNode(
                        "seed-" + tickNumber + "-" + pid,
                        "REPLAY_PLAYER_INPUT",
                        RWSet.builder()
                            .readGlobal(org.nebula.core.state.GlobalKey.ALL)
                            .writeGlobal(org.nebula.core.state.GlobalKey.ALL)
                            .build(),
                        () -> executedIds.add(pid)
                    ));
                }
                return tasks;
            }

            @Override
            public byte[] computeStateHash() {
                return new byte[32];
            }
        };

        // Two players, given in non-sorted insertion order
        Map<String, List<byte[]>> packets = new java.util.LinkedHashMap<>();
        packets.put("zelda", List.of(new byte[]{1, 2}));
        packets.put("alice", List.of(new byte[]{3}));
        packets.put("mark", List.of(new byte[]{4}, new byte[]{5}));

        adapter.applyInputs(7, new TickInput(7, packets));

        // Three tasks created, executed in sorted player ID order.
        assertEquals(List.of("alice", "mark", "zelda"), executedIds);
    }

    @Test
    void emptyInputProducesNoSeedTasks() {
        List<String> executed = new ArrayList<>();
        TickPipeline pipeline = new TickPipeline(
            completed -> List.of(),
            TaskRunner.DIRECT
        );
        TickPipelineReplayAdapter adapter = new TickPipelineReplayAdapter(pipeline) {
            @Override
            protected Collection<TaskNode> inputToTasks(long tickNumber, TickInput input) {
                if (input.playerInputs().isEmpty()) return List.of();
                executed.add("should-not-fire");
                return List.of();
            }

            @Override
            public byte[] computeStateHash() {
                return new byte[32];
            }
        };

        adapter.applyInputs(1, new TickInput(1, Map.of()));
        assertTrue(executed.isEmpty());
    }
}
