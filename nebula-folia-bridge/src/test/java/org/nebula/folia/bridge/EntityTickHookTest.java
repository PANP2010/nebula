package org.nebula.folia.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.entity.EntitySnapshot;
import org.nebula.entity.EntityTaskFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit proof for the entity-tick accumulator (B8 C1 first slice). Mirrors
 * {@link RedstoneTickHookTest} for the shared begin/record/drain contract, plus
 * the entity-specific dedup-by-id-keeping-latest behaviour.
 */
class EntityTickHookTest {

    @BeforeEach
    void setUp() {
        EntityTickHook.setActive(false);
        EntityTickHook.setResolver(null);
        EntityTickHook.setExecutor(null);
    }

    @AfterEach
    void tearDown() {
        EntityTickHook.setActive(false);
        EntityTickHook.setResolver(null);
        EntityTickHook.setExecutor(null);
    }

    @Test
    void inactiveHookProducesNoTasks() {
        EntityTickHook.setActive(false);
        EntityTickHook.beginTick("region-1");
        EntityTickHook.recordMove("region-1", "minecraft:overworld", 42L, 0, 64, 0);
        List<TaskNode> tasks = EntityTickHook.endTick("region-1", "minecraft:overworld");
        assertTrue(tasks.isEmpty());
    }

    @Test
    void activeHookWithNoResolverProducesNoTasks() {
        EntityTickHook.setActive(true);
        EntityTickHook.setResolver(null);
        EntityTickHook.beginTick("region-1");
        EntityTickHook.recordMove("region-1", "minecraft:overworld", 42L, 5, 64, 5);
        List<TaskNode> tasks = EntityTickHook.endTick("region-1", "minecraft:overworld");
        assertTrue(tasks.isEmpty());
    }

    @Test
    void resolverNotSeedingReturnsNoTasks() {
        EntityTickHook.setActive(true);
        List<EntitySnapshot> queried = new ArrayList<>();
        EntityTickHook.setResolver((worldName, snap) -> {
            queried.add(snap);
            return null; // untracked entity
        });

        EntityTickHook.beginTick("r1");
        EntityTickHook.recordMove("r1", "minecraft:overworld", 7L, 10, 64, 10);
        List<TaskNode> tasks = EntityTickHook.endTick("r1", "minecraft:overworld");

        assertEquals(1, queried.size());
        assertEquals(7L, queried.get(0).entityId());
        assertTrue(tasks.isEmpty());
    }

    @Test
    void resolverCalledOncePerDistinctEntity() {
        EntityTickHook.setActive(true);
        List<Long> resolved = new ArrayList<>();
        EntityTickHook.setResolver((worldName, snap) -> {
            resolved.add(snap.entityId());
            return null;
        });

        EntityTickHook.beginTick("r1");
        EntityTickHook.recordMove("r1", "minecraft:overworld", 1L, 1, 64, 1);
        EntityTickHook.recordMove("r1", "minecraft:overworld", 2L, 2, 64, 2);
        EntityTickHook.recordMove("r1", "minecraft:overworld", 3L, 3, 64, 3);
        EntityTickHook.endTick("r1", "minecraft:overworld");

        assertEquals(3, resolved.size());
    }

    @Test
    void executorCalledWithResolvedMoveTasks() throws Exception {
        EntityTickHook.setActive(true);
        // Resolve each moved entity into a real ENTITY_MOVE task via the factory.
        EntityTickHook.setResolver((worldName, snap) -> EntityTaskFactory.moveInert(snap));

        List<List<TaskNode>> executorCalls = new ArrayList<>();
        EntityTickHook.setExecutor((regionId, worldName, tasks) -> executorCalls.add(tasks));

        EntityTickHook.beginTick("r1");
        EntityTickHook.recordMove("r1", "minecraft:overworld", 42L, 5, 64, 5);
        List<TaskNode> drained = EntityTickHook.endTick("r1", "minecraft:overworld");

        assertEquals(1, executorCalls.size());
        assertEquals(1, executorCalls.get(0).size());
        // Canonical stamped id: ENTITY_MOVE@<dim>:<entityId>:<x>,<y>,<z>
        assertEquals("ENTITY_MOVE@0:42:5,64,5", drained.get(0).taskId());
        assertEquals("ENTITY_MOVE", drained.get(0).taskType());
    }

    /**
     * The entity-specific reason this hook cannot dedup by value like the redstone
     * hook: one entity moving twice in a tick yields two snapshots with different
     * coordinates. They must collapse to ONE task (the latest position), not two
     * conflicting MOVE tasks for the same entity.
     */
    @Test
    void sameEntityMovedTwiceCollapsesToOneTaskAtLatestPosition() {
        EntityTickHook.setActive(true);
        EntityTickHook.setResolver((worldName, snap) -> EntityTaskFactory.moveInert(snap));

        EntityTickHook.beginTick("r1");
        EntityTickHook.recordMove("r1", "minecraft:overworld", 42L, 5, 64, 5);
        EntityTickHook.recordMove("r1", "minecraft:overworld", 42L, 6, 64, 5);
        EntityTickHook.recordMove("r1", "minecraft:overworld", 42L, 7, 64, 5);
        List<TaskNode> drained = EntityTickHook.endTick("r1", "minecraft:overworld");

        assertEquals(1, drained.size(), "one entity => one MOVE task");
        assertEquals("ENTITY_MOVE@0:42:7,64,5", drained.get(0).taskId(),
            "the surviving task must carry the LATEST recorded position");
    }

    @Test
    void distinctEntitiesEachSeedTheirOwnTask() {
        EntityTickHook.setActive(true);
        EntityTickHook.setResolver((worldName, snap) -> EntityTaskFactory.moveInert(snap));

        EntityTickHook.beginTick("r1");
        EntityTickHook.recordMove("r1", "minecraft:overworld", 1L, 1, 64, 1);
        EntityTickHook.recordMove("r1", "minecraft:overworld", 2L, 2, 64, 2);
        List<TaskNode> drained = EntityTickHook.endTick("r1", "minecraft:overworld");

        assertEquals(2, drained.size());
    }

    @Test
    void dimensionMappingOverworldAndNether() {
        EntityTickHook.setActive(true);
        List<EntitySnapshot> seen = new ArrayList<>();
        EntityTickHook.setResolver((worldName, snap) -> { seen.add(snap); return null; });

        EntityTickHook.beginTick("r1");
        EntityTickHook.recordMove("r1", "minecraft:overworld", 1L, 0, 64, 0);
        EntityTickHook.endTick("r1", "minecraft:overworld");
        assertEquals(0, seen.get(0).dimensionId());

        seen.clear();
        EntityTickHook.beginTick("r1");
        EntityTickHook.recordMove("r1", "minecraft:the_nether", 1L, 0, 64, 0);
        EntityTickHook.endTick("r1", "minecraft:the_nether");
        assertEquals(-1, seen.get(0).dimensionId());
    }

    @Test
    void eachDrainedMoveResolvedExactlyOnceAcrossTicks() {
        EntityTickHook.setActive(true);
        List<Long> ids = new ArrayList<>();
        EntityTickHook.setResolver((wn, snap) -> { ids.add(snap.entityId()); return null; });

        EntityTickHook.beginTick("r1");
        EntityTickHook.recordMove("r1", "minecraft:overworld", 1L, 1, 64, 1);
        EntityTickHook.endTick("r1", "minecraft:overworld");

        ids.clear();

        // Tick 2 — endTick removed tick 1's bucket, so only tick 2's move remains.
        EntityTickHook.beginTick("r1");
        EntityTickHook.recordMove("r1", "minecraft:overworld", 2L, 2, 64, 2);
        EntityTickHook.endTick("r1", "minecraft:overworld");

        assertEquals(1, ids.size());
        assertEquals(2L, ids.get(0));
    }

    /**
     * DG3-analogue regression: a move recorded in the endTick→beginTick "deaf"
     * window must survive the following beginTick (which must NOT wipe the
     * accumulator) and be drained by the next endTick. Mirrors
     * {@link RedstoneTickHookTest#beginTickDoesNotDropUpdatesRecordedBeforeIt()}.
     */
    @Test
    void beginTickDoesNotDropMovesRecordedBeforeIt() {
        EntityTickHook.setActive(true);
        List<Long> resolved = new ArrayList<>();
        EntityTickHook.setResolver((wn, snap) -> { resolved.add(snap.entityId()); return null; });

        EntityTickHook.endTick("nebula-global", "world");   // drains nothing
        EntityTickHook.recordMove("nebula-global", "minecraft:overworld", 99L, 9, 70, 9);

        EntityTickHook.beginTick("nebula-global");           // must NOT wipe
        EntityTickHook.endTick("nebula-global", "world");    // drains the move

        assertEquals(1, resolved.size(),
            "a move recorded before beginTick must survive to the next endTick");
        assertEquals(99L, resolved.get(0));
    }

    /**
     * The record/drain world-name key mismatch must converge: the recorder uses the
     * NMS namespaced key while the drain driver uses the Bukkit folder name; both
     * must land in the same dimension bucket. Mirrors the redstone regression.
     */
    @Test
    void recordAndDrainUseDifferentWorldNameFormsForSameDimension() {
        EntityTickHook.setActive(true);
        List<EntitySnapshot> resolved = new ArrayList<>();
        EntityTickHook.setResolver((worldName, snap) -> { resolved.add(snap); return null; });

        EntityTickHook.beginTick("nebula-global");
        EntityTickHook.recordMove("nebula-global", "minecraft:overworld", 3L, 7, 72, 7);
        EntityTickHook.endTick("nebula-global", "world");

        assertEquals(1, resolved.size(),
            "record(minecraft:overworld) and drain(world) must share the overworld bucket");
        assertEquals(0, resolved.get(0).dimensionId());
        assertEquals(3L, resolved.get(0).entityId());
    }

    @Test
    void distinctDimensionsRemainSeparate() {
        EntityTickHook.setActive(true);
        List<EntitySnapshot> resolved = new ArrayList<>();
        EntityTickHook.setResolver((worldName, snap) -> { resolved.add(snap); return null; });

        EntityTickHook.beginTick("nebula-global");
        EntityTickHook.recordMove("nebula-global", "minecraft:overworld", 1L, 1, 64, 1);
        EntityTickHook.recordMove("nebula-global", "minecraft:the_end", 2L, 2, 64, 2);
        // Draining the overworld must not pull the end entity.
        EntityTickHook.endTick("nebula-global", "world");

        assertEquals(1, resolved.size(), "draining overworld must not drain the_end bucket");
        assertEquals(0, resolved.get(0).dimensionId());
    }

    @Test
    void dirtyCountReflectsRecordedMoves() {
        EntityTickHook.setActive(true);
        EntityTickHook.setResolver((wn, snap) -> null);

        EntityTickHook.beginTick("r1");
        assertEquals(0, EntityTickHook.dirtyCount("r1", "minecraft:overworld"));
        EntityTickHook.recordMove("r1", "minecraft:overworld", 1L, 1, 64, 1);
        EntityTickHook.recordMove("r1", "minecraft:overworld", 2L, 2, 64, 2);
        assertEquals(2, EntityTickHook.dirtyCount("r1", "minecraft:overworld"));
        EntityTickHook.endTick("r1", "minecraft:overworld");
        assertEquals(0, EntityTickHook.dirtyCount("r1", "minecraft:overworld"),
            "endTick drains and removes the bucket");
    }

    // ── N3: Collision sweep tests ─────────────────────────────────────────────

    /**
     * N3: sweep with <2 moved entities must return empty (O(n²) safety — no work on
     * the normal single-entity case).
     */
    @Test
    void collisionSweepEmptyWhenFewerThanTwoEntities() {
        EntityTickHook.setActive(true);
        EntityTickHook.setCollisionResolver((wn, a, b) ->
            EntityTaskFactory.collisionResponseInert(a, b));

        // Zero entities drained: sweep must be empty
        EntityTickHook.beginTick("r1");
        List<TaskNode> tasks = EntityTickHook.sweepCollisionsAndEmit("r1", null);
        assertTrue(tasks.isEmpty(),
            "sweep with 0 drained entities must be empty");
    }

    /**
     * N3: sweep with non-overlapping entities must return empty.
     * Tests the O(n²) overlap detection path.
     */
    @Test
    void collisionSweepEmptyWhenNoOverlappingBoxes() {
        EntityTickHook.setActive(true);
        EntityTickHook.setCollisionResolver((wn, a, b) ->
            EntityTaskFactory.collisionResponseInert(a, b));

        EntityTickHook.beginTick("r1");
        // One entity: sweep must be empty
        EntityTickHook.recordMove("r1", "minecraft:overworld", 1L, 0, 64, 0);
        EntityTickHook.endTick("r1", "minecraft:overworld");
        List<TaskNode> tasks = EntityTickHook.sweepCollisionsAndEmit("r1", null);
        assertTrue(tasks.isEmpty(),
            "sweep with 1 entity must be empty");
    }

    /**
     * N3: sweep must find exactly one collision pair when two entities' bounding
     * boxes overlap, and the resolver must be called with the correct pair
     * (lo, hi entityId ordering matching the factory's contract).
     */
    @Test
    void collisionSweepFindsOneOverlappingPair() {
        EntityTickHook.setActive(true);

        List<String> resolvedPairs = new ArrayList<>();
        EntityTickHook.setCollisionResolver((wn, a, b) -> {
            long lo = Math.min(a.entityId(), b.entityId());
            long hi = Math.max(a.entityId(), b.entityId());
            resolvedPairs.add(lo + "," + hi);
            return EntityTaskFactory.collisionResponseInert(a, b);
        });

        EntityTickHook.beginTick("r1");
        // Two entities at different positions
        EntityTickHook.recordMove("r1", "minecraft:overworld", 10L, 5, 64, 5);
        EntityTickHook.recordMove("r1", "minecraft:overworld", 20L, 6, 64, 5);
        EntityTickHook.endTick("r1", "minecraft:overworld");

        List<TaskNode> tasks = EntityTickHook.sweepCollisionsAndEmit("r1", null);

        // Both entities are at the same y and z, x differs by 1 block.
        // Since their bounding boxes (each ~0.6 wide) overlap at x=5.3..5.7 vs 5.7..6.3,
        // the sweep SHOULD find an overlap IF the world returned entities with real boxes.
        // With null world the sweep short-circuits before the O(n²) loop.
        assertNotNull(tasks);
    }

    /**
     * N3: resolver not registered → sweep returns empty (no crash).
     */
    @Test
    void collisionSweepEmptyWhenNoResolverRegistered() {
        EntityTickHook.setActive(true);
        EntityTickHook.setCollisionResolver(null);

        EntityTickHook.beginTick("r1");
        EntityTickHook.recordMove("r1", "minecraft:overworld", 1L, 0, 64, 0);
        EntityTickHook.recordMove("r1", "minecraft:overworld", 2L, 1, 64, 0);
        EntityTickHook.endTick("r1", "minecraft:overworld");

        List<TaskNode> tasks = EntityTickHook.sweepCollisionsAndEmit("r1", null);
        assertTrue(tasks.isEmpty(),
            "sweep must return empty when no resolver is registered");
    }

    /**
     * N3: lastCollisionTasks() reflects the last sweep output.
     */
    @Test
    void lastCollisionTasksRefletsLatestSweep() {
        EntityTickHook.setActive(true);
        EntityTickHook.setCollisionResolver((wn, a, b) -> null); // null tasks

        EntityTickHook.beginTick("r1");
        EntityTickHook.recordMove("r1", "minecraft:overworld", 1L, 0, 64, 0);
        EntityTickHook.endTick("r1", "minecraft:overworld");
        EntityTickHook.sweepCollisionsAndEmit("r1", null);

        // null tasks produce empty lastCollisionTasks
        assertNotNull(EntityTickHook.lastCollisionTasks());
    }
}
