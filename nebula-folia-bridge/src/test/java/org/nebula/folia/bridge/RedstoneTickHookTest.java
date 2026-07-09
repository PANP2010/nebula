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
    void eachDrainedUpdateResolvedExactlyOnceAcrossTicks() {
        RedstoneTickHook.setActive(true);
        List<WorldPos> positions = new ArrayList<>();
        RedstoneTickHook.setResolver((wn, pos) -> { positions.add(pos); return null; });

        // Tick 1
        RedstoneTickHook.beginTick("r1");
        RedstoneTickHook.recordUpdate("r1", "minecraft:overworld", 1, 64, 1);
        RedstoneTickHook.endTick("r1", "minecraft:overworld");

        positions.clear();

        // Tick 2 — endTick removed tick 1's bucket, so only tick 2's update remains.
        RedstoneTickHook.beginTick("r1");
        RedstoneTickHook.recordUpdate("r1", "minecraft:overworld", 2, 64, 2);
        RedstoneTickHook.endTick("r1", "minecraft:overworld");

        assertEquals(1, positions.size());
        assertEquals(2, positions.get(0).x());
    }

    /**
     * Regression for the DG3 {@code nebula=-1} whole-circuit-absent race: an update
     * recorded on a region thread <em>between</em> an {@code endTick} drain and the
     * following {@code beginTick} must NOT be discarded by that {@code beginTick} — it
     * has to survive to the next {@code endTick} that drains it.
     *
     * <p>The live driver alternates {@code endTick, beginTick, endTick, beginTick, …}
     * (one game tick each). Before this fix {@code beginTick} wiped every undrained
     * dirty position, so a circuit whose OFF→ON {@code BLOCK_UPDATE} burst landed in
     * the {@code endTick}→{@code beginTick} window contributed zero seeds and never
     * entered CAS — nondeterministic, binary per-circuit. {@code endTick} is now the
     * sole consumer (it drains-and-removes each bucket), so no recorded update is lost.
     */
    @Test
    void beginTickDoesNotDropUpdatesRecordedBeforeIt() {
        RedstoneTickHook.setActive(true);
        List<WorldPos> resolved = new ArrayList<>();
        RedstoneTickHook.setResolver((wn, pos) -> { resolved.add(pos); return null; });

        // A region thread records an update in the "deaf" window (after an endTick
        // drained an empty bucket, before the next beginTick).
        RedstoneTickHook.endTick("nebula-global", "world");   // drains nothing
        RedstoneTickHook.recordUpdate("nebula-global", "minecraft:overworld", 9, 70, 9);

        // The following beginTick must NOT wipe that recorded update ...
        RedstoneTickHook.beginTick("nebula-global");
        // ... so the next endTick drains it.
        RedstoneTickHook.endTick("nebula-global", "world");

        assertEquals(1, resolved.size(),
            "an update recorded before beginTick must survive to the next endTick");
        assertEquals(9, resolved.get(0).x());
    }

    /**
     * Regression test for the production world-name key mismatch: the agent
     * interceptor records dirty positions using the NMS namespaced dimension key
     * ("minecraft:overworld"), while the global-tick lifecycle driver drains
     * using the Bukkit world folder name ("world").  These must land in the same
     * accumulator bucket (both map to dimension 0) or agent-recorded updates are
     * silently dropped and the DAG never executes.
     */
    @Test
    void recordAndDrainUseDifferentWorldNameFormsForSameDimension() {
        RedstoneTickHook.setActive(true);
        List<WorldPos> resolved = new ArrayList<>();
        RedstoneTickHook.setResolver((worldName, pos) -> { resolved.add(pos); return null; });

        RedstoneTickHook.beginTick("nebula-global");
        // Agent path records with the NMS namespaced key ...
        RedstoneTickHook.recordUpdate("nebula-global", "minecraft:overworld", 7, 72, 7);
        // ... but the lifecycle driver drains with the Bukkit folder name.
        RedstoneTickHook.endTick("nebula-global", "world");

        assertEquals(1, resolved.size(),
            "record(minecraft:overworld) and drain(world) must share the overworld bucket");
        assertEquals(0, resolved.get(0).dimensionId());
        assertEquals(7, resolved.get(0).x());
    }

    /** The same mismatch must also converge for the nether. */
    @Test
    void recordAndDrainConvergeForNether() {
        RedstoneTickHook.setActive(true);
        List<WorldPos> resolved = new ArrayList<>();
        RedstoneTickHook.setResolver((worldName, pos) -> { resolved.add(pos); return null; });

        RedstoneTickHook.beginTick("nebula-global");
        RedstoneTickHook.recordUpdate("nebula-global", "minecraft:the_nether", 1, 64, 1);
        RedstoneTickHook.endTick("nebula-global", "world_nether");

        assertEquals(1, resolved.size(),
            "record(minecraft:the_nether) and drain(world_nether) must share the nether bucket");
        assertEquals(-1, resolved.get(0).dimensionId());
    }

    /** Distinct dimensions must NOT be merged, even after normalization. */
    @Test
    void distinctDimensionsRemainSeparate() {
        RedstoneTickHook.setActive(true);
        List<WorldPos> resolved = new ArrayList<>();
        RedstoneTickHook.setResolver((worldName, pos) -> { resolved.add(pos); return null; });

        RedstoneTickHook.beginTick("nebula-global");
        RedstoneTickHook.recordUpdate("nebula-global", "minecraft:overworld", 1, 64, 1);
        RedstoneTickHook.recordUpdate("nebula-global", "minecraft:the_end", 2, 64, 2);
        // Draining the overworld must not pull the end position.
        RedstoneTickHook.endTick("nebula-global", "world");

        assertEquals(1, resolved.size(),
            "draining overworld must not drain the_end bucket");
        assertEquals(0, resolved.get(0).dimensionId());
    }
}
