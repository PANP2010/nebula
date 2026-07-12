package org.nebula.integration;

import org.junit.jupiter.api.Test;
import org.nebula.core.math.Vec3;
import org.nebula.core.scheduler.CompositeTaskRunner;
import org.nebula.core.scheduler.DagBuilder;
import org.nebula.core.scheduler.TaskGraph;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.BlockEntityAction;
import org.nebula.entity.BlockEntitySnapshot;
import org.nebula.entity.BlockEntityState;
import org.nebula.entity.BlockEntityTaskFactory;
import org.nebula.entity.BlockEntityTaskRunner;
import org.nebula.entity.EntityPhysicsState;
import org.nebula.entity.EntitySnapshot;
import org.nebula.entity.EntityTaskAction;
import org.nebula.entity.EntityTaskFactory;
import org.nebula.entity.EntityTaskRunner;
import org.nebula.entity.actions.BlockEntityActions;
import org.nebula.entity.actions.EntityMoveAction;
import org.nebula.redstone.RedstoneComponentType;
import org.nebula.redstone.RedstoneTaskAction;
import org.nebula.redstone.RedstoneTaskFactory;
import org.nebula.redstone.RedstoneTaskRunner;
import org.nebula.redstone.RedstoneWorldState;
import org.nebula.redstone.actions.RedstoneActions;
import org.nebula.replay.StateHashComputer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Three-way combined tick: redstone, entity physics, AND block entities
 * scheduled in ONE DAG via a {@link CompositeTaskRunner} with three sub-runners.
 *
 * <p>This is the full cross-subsystem demonstration — all three live subsystems
 * share one dependency graph, routed by task type, and the combined tick
 * replays deterministically over the union of all three worlds.
 */
class ThreeWayCombinedTickTest {

    private static final int DIM = 0;

    private static final class World {
        final RedstoneWorldState redstone = new RedstoneWorldState();
        final EntityPhysicsState entities = new EntityPhysicsState();
        final BlockEntityState blockEntities = new BlockEntityState();
        final RedstoneTaskRunner redstoneRunner;
        final EntityTaskRunner entityRunner;
        final BlockEntityTaskRunner beRunner;
        final CompositeTaskRunner composite;

        World(Map<String, EntityTaskAction> entityActions, Map<String, BlockEntityAction> beActions) {
            Map<String, RedstoneTaskAction> rs = RedstoneActions.defaults();
            this.redstoneRunner = new RedstoneTaskRunner(redstone, rs);
            this.entityRunner = new EntityTaskRunner(entities, entityActions::get);
            this.beRunner = new BlockEntityTaskRunner(blockEntities, beActions::get);
            // Route by task-type prefix. ENTITY_ and BLOCK_ENTITY_ are distinct
            // prefixes (neither prefixes the other); redstone is the fallthrough.
            this.composite = new CompositeTaskRunner()
                .routeByTypePrefix("BLOCK_ENTITY_", beRunner)
                .routeByTypePrefix("ENTITY_", entityRunner)
                .route(t -> !t.taskType().startsWith("ENTITY_")
                    && !t.taskType().startsWith("BLOCK_ENTITY_"), redstoneRunner);
        }
    }

    private static byte[] runTick() throws Exception {
        Map<String, EntityTaskAction> entityActions = new LinkedHashMap<>();
        Map<String, BlockEntityAction> beActions = new LinkedHashMap<>();
        World w = new World(entityActions, beActions);
        List<TaskNode> tasks = new ArrayList<>();

        // Redstone: a wire next to a power source.
        WorldPos source = new WorldPos(DIM, 0, 64, 0);
        WorldPos wire = new WorldPos(DIM, 1, 64, 0);
        w.redstone.putPowerLevel(source, 15);
        w.redstone.putPowerLevel(wire, 0);
        tasks.add(RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, wire));

        // Entities: two falling.
        for (int i = 0; i < 2; i++) {
            long id = i + 1;
            w.entities.put(new EntityField(id, "position"), new Vec3(i * 10, 100, 0));
            w.entities.put(new EntityField(id, "velocity"), Vec3.ZERO);
            EntitySnapshot snap = EntitySnapshot.of(id, 0, 0, 0, DIM);
            TaskNode move = EntityTaskFactory.moveInert(snap);
            entityActions.put(move.taskId(), new EntityMoveAction(id, DIM));
            tasks.add(move);
        }

        // Block entities: a furnace with input + fuel.
        WorldPos furnacePos = new WorldPos(DIM, 50, 64, 50);
        w.blockEntities.put(new BlockEntityField(furnacePos, "inventory.slots[0]"), 5);
        w.blockEntities.put(new BlockEntityField(furnacePos, "inventory.slots[1]"), 2);
        BlockEntitySnapshot beSnap = BlockEntitySnapshot.furnace(furnacePos);
        TaskNode furnaceTask = BlockEntityTaskFactory.furnaceInert(beSnap);
        beActions.put(furnaceTask.taskId(), BlockEntityActions.furnace(furnacePos));
        tasks.add(furnaceTask);

        // One DAG over all three subsystems.
        w.entityRunner.beginTick(0L);
        TaskGraph graph = DagBuilder.build(tasks);
        for (List<String> layer : graph.topologicalLayers()) {
            for (String id : layer) {
                TaskNode t = graph.tasks().get(id);
                if (t != null) w.composite.run(t);
            }
            w.composite.commitLayer();
            w.composite.resetLayer();
        }
        return hashAll(w);
    }

    @Test
    void allThreeSubsystemsAdvanceInOneTick() throws Exception {
        Map<String, EntityTaskAction> entityActions = new LinkedHashMap<>();
        Map<String, BlockEntityAction> beActions = new LinkedHashMap<>();
        World w = new World(entityActions, beActions);

        WorldPos source = new WorldPos(DIM, 0, 64, 0);
        WorldPos wire = new WorldPos(DIM, 1, 64, 0);
        w.redstone.putPowerLevel(source, 15);
        w.redstone.putPowerLevel(wire, 0);

        long entityId = 1L;
        w.entities.put(new EntityField(entityId, "position"), new Vec3(0, 100, 0));
        // Seed a downward velocity: vanilla move-then-integrate order means a
        // single tick from ZERO velocity does not move the position (it steps by
        // the current velocity first). A nonzero velocity makes the one-tick
        // move observable, which is what this test asserts.
        w.entities.put(new EntityField(entityId, "velocity"), new Vec3(0, -0.1, 0));

        WorldPos furnacePos = new WorldPos(DIM, 50, 64, 50);
        w.blockEntities.put(new BlockEntityField(furnacePos, "inventory.slots[0]"), 5);
        w.blockEntities.put(new BlockEntityField(furnacePos, "inventory.slots[1]"), 2);

        List<TaskNode> tasks = new ArrayList<>();
        tasks.add(RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, wire));
        EntitySnapshot snap = EntitySnapshot.of(entityId, 0, 0, 0, DIM);
        TaskNode move = EntityTaskFactory.moveInert(snap);
        entityActions.put(move.taskId(), new EntityMoveAction(entityId, DIM));
        tasks.add(move);
        BlockEntitySnapshot beSnap = BlockEntitySnapshot.furnace(furnacePos);
        TaskNode furnaceTask = BlockEntityTaskFactory.furnaceInert(beSnap);
        beActions.put(furnaceTask.taskId(), BlockEntityActions.furnace(furnacePos));
        tasks.add(furnaceTask);

        w.entityRunner.beginTick(0L);
        TaskGraph graph = DagBuilder.build(tasks);
        for (List<String> layer : graph.topologicalLayers()) {
            for (String id : layer) w.composite.run(graph.tasks().get(id));
            w.composite.commitLayer();
            w.composite.resetLayer();
        }

        assertEquals(14, w.redstone.getPowerLevel(wire), "redstone advanced");
        assertTrue(w.entities.getVec(new EntityField(entityId, "position")).y() < 100,
            "entity advanced");
        // Furnace started burning (consumed a fuel item, began cook progress).
        assertEquals(1, w.blockEntities.get(new BlockEntityField(furnacePos, "inventory.slots[1]")),
            "furnace consumed one fuel item to start burning");
        assertEquals(1, w.blockEntities.get(new BlockEntityField(furnacePos, "cook_progress")),
            "furnace advanced one cook tick");
    }

    @Test
    void threeWayCombinedTickIsDeterministic() throws Exception {
        byte[] first = runTick();
        byte[] second = runTick();
        assertTrue(java.util.Arrays.equals(first, second),
            "three-way combined tick must replay deterministically");
    }

    private static byte[] hashAll(World w) {
        Map<String, byte[]> blocks = new TreeMap<>();
        for (WorldPos p : w.redstone.positions()) {
            blocks.put("r:" + p.x() + "," + p.y() + "," + p.z(),
                ByteBuffer.allocate(4).putInt(w.redstone.getPowerLevel(p)).array());
        }
        Map<String, byte[]> be = new TreeMap<>();
        for (BlockEntityField f : w.blockEntities.fields()) {
            be.put(f.pos() + ":" + f.fieldPath().value(),
                ByteBuffer.allocate(4).putInt(w.blockEntities.get(f)).array());
        }
        Map<String, byte[]> ent = new TreeMap<>();
        for (EntityField f : w.entities.fields()) {
            Vec3 v = w.entities.getVec(f);
            ent.put(f.entityId() + ":" + f.fieldPath().value(),
                ByteBuffer.allocate(24).putDouble(v.x()).putDouble(v.y()).putDouble(v.z()).array());
        }
        return StateHashComputer.compute(blocks, be, ent, Map.of());
    }
}
