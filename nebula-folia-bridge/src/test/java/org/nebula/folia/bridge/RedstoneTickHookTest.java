package org.nebula.folia.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RedstoneTickHookTest {

    @BeforeEach
    void setUp() {
        RedstoneTickHook.setActive(false);
        RedstoneTickHook.setResolver(null);
        RedstoneTickHook.setExecutor(null);
        NeighborUpdateInterceptor.setActive(false);
    }

    @AfterEach
    void tearDown() {
        RedstoneTickHook.setActive(false);
        NeighborUpdateInterceptor.setActive(false);
    }

    @Test
    void inactiveHookProducesNoTasks() {
        RedstoneTickHook.setActive(false);
        RedstoneTickHook.beginTick("region-1");
        RedstoneTickHook.recordUpdate("region-1", "minecraft:overworld", 0, 64, 0);
        List<org.nebula.core.scheduler.TaskNode> tasks =
            RedstoneTickHook.endTick("region-1", "minecraft:overworld");
        assertTrue(tasks.isEmpty());
    }

    @Test
    void activeHookWithNoResolverProducesNoTasks() {
        RedstoneTickHook.setActive(true);
        RedstoneTickHook.setResolver(null);
        RedstoneTickHook.beginTick("region-1");
        RedstoneTickHook.recordUpdate("region-1", "minecraft:overworld", 5, 64, 5);
        List<org.nebula.core.scheduler.TaskNode> tasks =
            RedstoneTickHook.endTick("region-1", "minecraft:overworld");
        assertTrue(tasks.isEmpty());
    }

    @Test
    void resolverNotCalledForUnknownPosition() {
        RedstoneTickHook.setActive(true);
        List<WorldPos> queried = new ArrayList<>();
        RedstoneTickHook.setResolver((worldName, pos) -> {
            queried.add(pos);
            return null; // unknown position
        });

        RedstoneTickHook.beginTick("r1");
        RedstoneTickHook.recordUpdate("r1", "minecraft:overworld", 10, 64, 10);
        List<org.nebula.core.scheduler.TaskNode> tasks =
            RedstoneTickHook.endTick("r1", "minecraft:overworld");

        assertEquals(1, queried.size());
        assertTrue(tasks.isEmpty());
    }

    @Test
    void resolverCalledForEachRecordedUpdate() {
        RedstoneTickHook.setActive(true);
        List<WorldPos> resolved = new ArrayList<>();
        RedstoneTickHook.setResolver((worldName, pos) -> {
            resolved.add(pos);
            return null;
        });

        RedstoneTickHook.beginTick("r1");
        RedstoneTickHook.recordUpdate("r1", "minecraft:overworld", 1, 64, 1);
        RedstoneTickHook.recordUpdate("r1", "minecraft:overworld", 2, 64, 2);
        RedstoneTickHook.recordUpdate("r1", "minecraft:overworld", 3, 64, 3);
        RedstoneTickHook.endTick("r1", "minecraft:overworld");

        assertEquals(3, resolved.size());
    }

    @Test
    void executorCalledWithResolvedTasks() throws Exception {
        RedstoneTickHook.setActive(true);
        org.nebula.core.rw.RWSet rw = org.nebula.core.rw.RWSet.builder()
            .writeBlock(new WorldPos(0, 5, 64, 5)).build();
        org.nebula.core.scheduler.TaskNode node =
            org.nebula.core.scheduler.TaskNode.inert("T_WIRE@0:5,64,5", "REDSTONE_WIRE", rw);

        RedstoneTickHook.setResolver((worldName, pos) ->
            pos.x() == 5 ? node : null);

        List<List<org.nebula.core.scheduler.TaskNode>> executorCalls = new ArrayList<>();
        RedstoneTickHook.setExecutor((regionId, worldName, tasks) -> executorCalls.add(tasks));

        RedstoneTickHook.beginTick("r1");
        RedstoneTickHook.recordUpdate("r1", "minecraft:overworld", 5, 64, 5);
        RedstoneTickHook.endTick("r1", "minecraft:overworld");

        assertEquals(1, executorCalls.size());
        assertEquals(1, executorCalls.get(0).size());
        assertEquals("T_WIRE@0:5,64,5", executorCalls.get(0).get(0).taskId());
    }

    @Test
    void dimensionMappingOverworld() {
        RedstoneTickHook.setActive(true);
        List<WorldPos> positions = new ArrayList<>();
        RedstoneTickHook.setResolver((worldName, pos) -> { positions.add(pos); return null; });

        RedstoneTickHook.beginTick("r1");
        RedstoneTickHook.recordUpdate("r1", "minecraft:overworld", 0, 64, 0);
        RedstoneTickHook.endTick("r1", "minecraft:overworld");

        assertEquals(0, positions.get(0).dimensionId());
    }

    @Test
    void dimensionMappingNether() {
        RedstoneTickHook.setActive(true);
        List<WorldPos> positions = new ArrayList<>();
        RedstoneTickHook.setResolver((worldName, pos) -> { positions.add(pos); return null; });

        RedstoneTickHook.beginTick("r1");
        RedstoneTickHook.recordUpdate("r1", "minecraft:the_nether", 0, 64, 0);
        RedstoneTickHook.endTick("r1", "minecraft:the_nether");

        assertEquals(-1, positions.get(0).dimensionId());
    }

    @Test
    void beginTickClearsPreviousDirtyPositions() {
        RedstoneTickHook.setActive(true);
        List<WorldPos> positions = new ArrayList<>();
        RedstoneTickHook.setResolver((wn, pos) -> { positions.add(pos); return null; });

        // Tick 1
        RedstoneTickHook.beginTick("r1");
        RedstoneTickHook.recordUpdate("r1", "minecraft:overworld", 1, 64, 1);
        RedstoneTickHook.endTick("r1", "minecraft:overworld");

        positions.clear();

        // Tick 2 — new beginTick should clear the old positions
        RedstoneTickHook.beginTick("r1");
        RedstoneTickHook.recordUpdate("r1", "minecraft:overworld", 2, 64, 2);
        RedstoneTickHook.endTick("r1", "minecraft:overworld");

        assertEquals(1, positions.size());
        assertEquals(2, positions.get(0).x());
    }
}
