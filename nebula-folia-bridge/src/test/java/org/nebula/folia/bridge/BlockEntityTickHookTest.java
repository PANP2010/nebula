package org.nebula.folia.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.BlockEntitySnapshot;
import org.nebula.entity.BlockEntityTaskFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit proof for the block-entity-tick accumulator (B8 C3 first slice). Mirrors
 * {@link EntityTickHookTest} for the shared begin/record/drain contract, plus the
 * block-entity-specific dedup-by-position-keeping-latest behaviour.
 */
class BlockEntityTickHookTest {

    private static BlockEntitySnapshot hopperAt(int x, int y, int z) {
        return BlockEntitySnapshot.hopper(new WorldPos(0, x, y, z), 0, -1, 0);
    }

    @BeforeEach
    void setUp() {
        BlockEntityTickHook.setActive(false);
        BlockEntityTickHook.setResolver(null);
        BlockEntityTickHook.setExecutor(null);
    }

    @AfterEach
    void tearDown() {
        BlockEntityTickHook.setActive(false);
        BlockEntityTickHook.setResolver(null);
        BlockEntityTickHook.setExecutor(null);
    }

    @Test
    void inactiveHookProducesNoTasks() {
        BlockEntityTickHook.setActive(false);
        BlockEntityTickHook.beginTick("region-1");
        BlockEntityTickHook.recordDirty("region-1", "minecraft:overworld", hopperAt(0, 64, 0));
        List<TaskNode> tasks = BlockEntityTickHook.endTick("region-1", "minecraft:overworld");
        assertTrue(tasks.isEmpty());
    }

    @Test
    void activeHookWithNoResolverProducesNoTasks() {
        BlockEntityTickHook.setActive(true);
        BlockEntityTickHook.setResolver(null);
        BlockEntityTickHook.beginTick("region-1");
        BlockEntityTickHook.recordDirty("region-1", "minecraft:overworld", hopperAt(5, 64, 5));
        List<TaskNode> tasks = BlockEntityTickHook.endTick("region-1", "minecraft:overworld");
        assertTrue(tasks.isEmpty());
    }

    @Test
    void resolverNotSeedingReturnsNoTasks() {
        BlockEntityTickHook.setActive(true);
        List<BlockEntitySnapshot> queried = new ArrayList<>();
        BlockEntityTickHook.setResolver((worldName, snap) -> {
            queried.add(snap);
            return null; // inert container
        });

        BlockEntityTickHook.beginTick("r1");
        BlockEntityTickHook.recordDirty("r1", "minecraft:overworld", hopperAt(10, 64, 10));
        List<TaskNode> tasks = BlockEntityTickHook.endTick("r1", "minecraft:overworld");

        assertEquals(1, queried.size());
        assertEquals(new WorldPos(0, 10, 64, 10), queried.get(0).pos());
        assertTrue(tasks.isEmpty());
    }

    @Test
    void resolverCalledOncePerDistinctPosition() {
        BlockEntityTickHook.setActive(true);
        List<WorldPos> resolved = new ArrayList<>();
        BlockEntityTickHook.setResolver((worldName, snap) -> {
            resolved.add(snap.pos());
            return null;
        });

        BlockEntityTickHook.beginTick("r1");
        BlockEntityTickHook.recordDirty("r1", "minecraft:overworld", hopperAt(1, 64, 1));
        BlockEntityTickHook.recordDirty("r1", "minecraft:overworld", hopperAt(2, 64, 2));
        BlockEntityTickHook.recordDirty("r1", "minecraft:overworld", hopperAt(3, 64, 3));
        BlockEntityTickHook.endTick("r1", "minecraft:overworld");

        assertEquals(3, resolved.size());
    }

    @Test
    void executorCalledWithResolvedTasks() throws Exception {
        BlockEntityTickHook.setActive(true);
        BlockEntityTickHook.setResolver((worldName, snap) -> BlockEntityTaskFactory.inert(snap));

        List<List<TaskNode>> executorCalls = new ArrayList<>();
        BlockEntityTickHook.setExecutor((regionId, worldName, tasks) -> executorCalls.add(tasks));

        BlockEntityTickHook.beginTick("r1");
        BlockEntityTickHook.recordDirty("r1", "minecraft:overworld", hopperAt(5, 64, 5));
        List<TaskNode> drained = BlockEntityTickHook.endTick("r1", "minecraft:overworld");

        assertEquals(1, executorCalls.size());
        assertEquals(1, executorCalls.get(0).size());
        // Canonical stamped id from BlockEntitySnapshot.taskId(): <taskType>@<dim>:<x>,<y>,<z>
        assertEquals("BLOCK_ENTITY_HOPPER@0:5,64,5", drained.get(0).taskId());
        assertEquals("BLOCK_ENTITY_HOPPER", drained.get(0).taskType());
    }

    /**
     * The block-entity-specific dedup reason: a block entity's snapshot is not
     * value-stable within a tick (facing/slotCount come from live block state), so
     * two records for the same position must collapse to ONE task (the latest
     * snapshot), not two conflicting tasks for the same block.
     */
    @Test
    void samePositionRecordedTwiceCollapsesToOneTaskAtLatestSnapshot() {
        BlockEntityTickHook.setActive(true);
        List<BlockEntitySnapshot> resolved = new ArrayList<>();
        BlockEntityTickHook.setResolver((worldName, snap) -> {
            resolved.add(snap);
            return BlockEntityTaskFactory.inert(snap);
        });

        WorldPos pos = new WorldPos(0, 5, 64, 5);
        BlockEntityTickHook.beginTick("r1");
        BlockEntityTickHook.recordDirty("r1", "minecraft:overworld",
            BlockEntitySnapshot.hopper(pos, 0, -1, 0));   // facing down
        BlockEntityTickHook.recordDirty("r1", "minecraft:overworld",
            BlockEntitySnapshot.hopper(pos, 1, 0, 0));    // facing +X (latest)
        List<TaskNode> drained = BlockEntityTickHook.endTick("r1", "minecraft:overworld");

        assertEquals(1, drained.size(), "one position => one task");
        assertEquals(new WorldPos(0, 6, 64, 5), resolved.get(resolved.size() - 1).outputPos(),
            "the surviving snapshot must carry the LATEST recorded facing (+X)");
    }

    @Test
    void distinctPositionsEachSeedTheirOwnTask() {
        BlockEntityTickHook.setActive(true);
        BlockEntityTickHook.setResolver((worldName, snap) -> BlockEntityTaskFactory.inert(snap));

        BlockEntityTickHook.beginTick("r1");
        BlockEntityTickHook.recordDirty("r1", "minecraft:overworld", hopperAt(1, 64, 1));
        BlockEntityTickHook.recordDirty("r1", "minecraft:overworld", hopperAt(2, 64, 2));
        List<TaskNode> drained = BlockEntityTickHook.endTick("r1", "minecraft:overworld");

        assertEquals(2, drained.size());
    }

    @Test
    void mixedBlockEntityTypesEachResolveToTheirOwnTaskType() {
        BlockEntityTickHook.setActive(true);
        BlockEntityTickHook.setResolver((worldName, snap) -> BlockEntityTaskFactory.inert(snap));

        BlockEntityTickHook.beginTick("r1");
        BlockEntityTickHook.recordDirty("r1", "minecraft:overworld",
            BlockEntitySnapshot.hopper(new WorldPos(0, 1, 64, 1), 0, -1, 0));
        BlockEntityTickHook.recordDirty("r1", "minecraft:overworld",
            BlockEntitySnapshot.furnace(new WorldPos(0, 2, 64, 2)));
        BlockEntityTickHook.recordDirty("r1", "minecraft:overworld",
            BlockEntitySnapshot.dispenser(new WorldPos(0, 3, 64, 3), 1, 0, 0));
        List<TaskNode> drained = BlockEntityTickHook.endTick("r1", "minecraft:overworld");

        assertEquals(3, drained.size());
        List<String> types = drained.stream().map(TaskNode::taskType).toList();
        assertTrue(types.contains("BLOCK_ENTITY_HOPPER"));
        assertTrue(types.contains("BLOCK_ENTITY_FURNACE"));
        assertTrue(types.contains("BLOCK_ENTITY_DISPENSER"));
    }

    @Test
    void eachDrainedDirtyResolvedExactlyOnceAcrossTicks() {
        BlockEntityTickHook.setActive(true);
        List<WorldPos> seen = new ArrayList<>();
        BlockEntityTickHook.setResolver((wn, snap) -> { seen.add(snap.pos()); return null; });

        BlockEntityTickHook.beginTick("r1");
        BlockEntityTickHook.recordDirty("r1", "minecraft:overworld", hopperAt(1, 64, 1));
        BlockEntityTickHook.endTick("r1", "minecraft:overworld");

        seen.clear();

        // Tick 2 — endTick removed tick 1's bucket, so only tick 2's dirty remains.
        BlockEntityTickHook.beginTick("r1");
        BlockEntityTickHook.recordDirty("r1", "minecraft:overworld", hopperAt(2, 64, 2));
        BlockEntityTickHook.endTick("r1", "minecraft:overworld");

        assertEquals(1, seen.size());
        assertEquals(new WorldPos(0, 2, 64, 2), seen.get(0));
    }

    /**
     * DG3-analogue regression: a dirty recorded in the endTick→beginTick "deaf"
     * window must survive the following beginTick (which must NOT wipe the
     * accumulator) and be drained by the next endTick. Mirrors
     * {@link EntityTickHookTest#beginTickDoesNotDropMovesRecordedBeforeIt()}.
     */
    @Test
    void beginTickDoesNotDropDirtiesRecordedBeforeIt() {
        BlockEntityTickHook.setActive(true);
        List<WorldPos> resolved = new ArrayList<>();
        BlockEntityTickHook.setResolver((wn, snap) -> { resolved.add(snap.pos()); return null; });

        BlockEntityTickHook.endTick("nebula-global", "world");   // drains nothing
        BlockEntityTickHook.recordDirty("nebula-global", "minecraft:overworld", hopperAt(9, 70, 9));

        BlockEntityTickHook.beginTick("nebula-global");           // must NOT wipe
        BlockEntityTickHook.endTick("nebula-global", "world");    // drains the dirty

        assertEquals(1, resolved.size(),
            "a dirty recorded before beginTick must survive to the next endTick");
        assertEquals(new WorldPos(0, 9, 70, 9), resolved.get(0));
    }

    /**
     * The record/drain world-name key mismatch must converge: the recorder uses the
     * NMS namespaced key while the drain driver uses the Bukkit folder name; both
     * must land in the same dimension bucket. Mirrors the redstone/entity regression.
     */
    @Test
    void recordAndDrainUseDifferentWorldNameFormsForSameDimension() {
        BlockEntityTickHook.setActive(true);
        List<BlockEntitySnapshot> resolved = new ArrayList<>();
        BlockEntityTickHook.setResolver((worldName, snap) -> { resolved.add(snap); return null; });

        BlockEntityTickHook.beginTick("nebula-global");
        BlockEntityTickHook.recordDirty("nebula-global", "minecraft:overworld", hopperAt(7, 72, 7));
        BlockEntityTickHook.endTick("nebula-global", "world");

        assertEquals(1, resolved.size(),
            "record(minecraft:overworld) and drain(world) must share the overworld bucket");
        assertEquals(0, resolved.get(0).pos().dimensionId());
    }

    @Test
    void distinctDimensionsRemainSeparate() {
        BlockEntityTickHook.setActive(true);
        List<BlockEntitySnapshot> resolved = new ArrayList<>();
        BlockEntityTickHook.setResolver((worldName, snap) -> { resolved.add(snap); return null; });

        BlockEntityTickHook.beginTick("nebula-global");
        BlockEntityTickHook.recordDirty("nebula-global", "minecraft:overworld",
            BlockEntitySnapshot.hopper(new WorldPos(0, 1, 64, 1), 0, -1, 0));
        BlockEntityTickHook.recordDirty("nebula-global", "minecraft:the_end",
            BlockEntitySnapshot.hopper(new WorldPos(1, 2, 64, 2), 0, -1, 0));
        // Draining the overworld must not pull the end block entity.
        BlockEntityTickHook.endTick("nebula-global", "world");

        assertEquals(1, resolved.size(), "draining overworld must not drain the_end bucket");
        assertEquals(0, resolved.get(0).pos().dimensionId());
    }

    @Test
    void dirtyCountReflectsRecordedDirties() {
        BlockEntityTickHook.setActive(true);
        BlockEntityTickHook.setResolver((wn, snap) -> null);

        BlockEntityTickHook.beginTick("r1");
        assertEquals(0, BlockEntityTickHook.dirtyCount("r1", "minecraft:overworld"));
        BlockEntityTickHook.recordDirty("r1", "minecraft:overworld", hopperAt(1, 64, 1));
        BlockEntityTickHook.recordDirty("r1", "minecraft:overworld", hopperAt(2, 64, 2));
        assertEquals(2, BlockEntityTickHook.dirtyCount("r1", "minecraft:overworld"));
        BlockEntityTickHook.endTick("r1", "minecraft:overworld");
        assertEquals(0, BlockEntityTickHook.dirtyCount("r1", "minecraft:overworld"),
            "endTick drains and removes the bucket");
    }
}
