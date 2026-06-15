package org.nebula.integration;

import org.junit.jupiter.api.Test;
import org.nebula.core.scheduler.CompositeTaskRunner;
import org.nebula.core.scheduler.DagBuilder;
import org.nebula.core.scheduler.TaskGraph;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.EntityPhysicsState;
import org.nebula.entity.EntitySnapshot;
import org.nebula.entity.EntityTaskAction;
import org.nebula.entity.EntityTaskFactory;
import org.nebula.entity.EntityTaskRunner;
import org.nebula.entity.Vec3;
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
 * Cross-subsystem combined tick: redstone wire propagation and entity physics
 * scheduled together in ONE DAG and executed via a {@link CompositeTaskRunner}.
 *
 * <p>This validates the central architectural claim — that causally-independent
 * tasks share one dependency graph regardless of subsystem, and disjoint-state
 * tasks (a wire here, an entity there) run in the same layer. It also proves the
 * combined tick is deterministic: two runs produce identical redstone + entity
 * state hashes.
 */
class CombinedTickTest {

    private static final int DIM = 0;

    /** Holds both subsystem worlds plus the composite runner wired over them. */
    private static final class Combined {
        final RedstoneWorldState redstone = new RedstoneWorldState();
        final EntityPhysicsState entities = new EntityPhysicsState();
        final RedstoneTaskRunner redstoneRunner;
        final EntityTaskRunner entityRunner;
        final CompositeTaskRunner composite;

        Combined(Map<String, EntityTaskAction> entityActions) {
            Map<String, RedstoneTaskAction> rsActions = RedstoneActions.defaults();
            this.redstoneRunner = new RedstoneTaskRunner(redstone, rsActions);
            this.entityRunner = new EntityTaskRunner(entities, entityActions::get);
            // Route ENTITY_* tasks to the entity runner; everything else
            // (redstone component types) to the redstone runner.
            this.composite = new CompositeTaskRunner()
                .routeByTypePrefix("ENTITY_", entityRunner)
                .route(t -> !t.taskType().startsWith("ENTITY_"), redstoneRunner);
        }
    }

    /**
     * Builds a scenario with a wire line (redstone) and falling entities, runs
     * one combined tick layer-by-layer, and returns a state hash of both worlds.
     */
    private static byte[] runCombinedTick() throws Exception {
        // ── Redstone: a 6-wire line driven by a constant source ──
        WorldPos source = new WorldPos(DIM, 0, 64, 0);
        List<WorldPos> wires = new ArrayList<>();
        List<TaskNode> tasks = new ArrayList<>();

        // ── Entities: 4 free-falling entities, each with a MOVE action ──
        Map<String, EntityTaskAction> entityActions = new LinkedHashMap<>();
        Combined c = new Combined(entityActions);

        c.redstone.putPowerLevel(source, 15);
        for (int x = 1; x <= 6; x++) {
            WorldPos wire = new WorldPos(DIM, x, 64, 0);
            c.redstone.putPowerLevel(wire, 0);
            wires.add(wire);
            tasks.add(RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, wire));
        }
        for (int i = 0; i < 4; i++) {
            long id = i + 1;
            c.entities.put(new EntityField(id, "position"), new Vec3(i * 10, 100, 0));
            c.entities.put(new EntityField(id, "velocity"), Vec3.ZERO);
            EntitySnapshot snap = EntitySnapshot.of(id, 0, 0, 0, DIM);
            TaskNode move = EntityTaskFactory.moveInert(snap);
            entityActions.put(move.taskId(), new EntityMoveAction(id, DIM));
            tasks.add(move);
        }

        // One combined DAG over BOTH subsystems' tasks.
        c.entityRunner.beginTick(0L);
        TaskGraph graph = DagBuilder.build(tasks);
        for (List<String> layer : graph.topologicalLayers()) {
            for (String taskId : layer) {
                TaskNode t = graph.tasks().get(taskId);
                if (t != null) c.composite.run(t);
            }
            c.composite.commitLayer();
            c.composite.resetLayer();
        }

        return hashBoth(c);
    }

    @Test
    void redstoneAndEntitiesAdvanceInOneCombinedTick() throws Exception {
        // Build directly so we can inspect both worlds after the tick.
        Map<String, EntityTaskAction> entityActions = new LinkedHashMap<>();
        Combined c = new Combined(entityActions);

        WorldPos source = new WorldPos(DIM, 0, 64, 0);
        c.redstone.putPowerLevel(source, 15);
        WorldPos wire1 = new WorldPos(DIM, 1, 64, 0);
        c.redstone.putPowerLevel(wire1, 0);

        long entityId = 1L;
        c.entities.put(new EntityField(entityId, "position"), new Vec3(50, 100, 0));
        c.entities.put(new EntityField(entityId, "velocity"), Vec3.ZERO);

        List<TaskNode> tasks = new ArrayList<>();
        tasks.add(RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, wire1));
        EntitySnapshot snap = EntitySnapshot.of(entityId, 0, 0, 0, DIM);
        TaskNode move = EntityTaskFactory.moveInert(snap);
        entityActions.put(move.taskId(), new EntityMoveAction(entityId, DIM));
        tasks.add(move);

        c.entityRunner.beginTick(0L);
        TaskGraph graph = DagBuilder.build(tasks);
        for (List<String> layer : graph.topologicalLayers()) {
            for (String id : layer) {
                c.composite.run(graph.tasks().get(id));
            }
            c.composite.commitLayer();
            c.composite.resetLayer();
        }

        // Redstone: wire next to the 15-power source picks up 14 (decay by 1).
        assertEquals(14, c.redstone.getPowerLevel(wire1), "wire advanced via redstone runner");
        // Entity: fell under gravity via the entity runner.
        assertTrue(c.entities.getVec(new EntityField(entityId, "position")).y() < 100,
            "entity advanced via entity runner");
    }

    @Test
    void combinedTickIsDeterministic() throws Exception {
        byte[] first = runCombinedTick();
        byte[] second = runCombinedTick();
        assertTrue(java.util.Arrays.equals(first, second),
            "combined redstone+entity tick must replay deterministically");
    }

    @Test
    void unroutedTaskTypeIsRejected() {
        CompositeTaskRunner runner = new CompositeTaskRunner()
            .routeByTypePrefix("ENTITY_", new EntityTaskRunner(new EntityPhysicsState(), id -> null));
        TaskNode redstoneTask = RedstoneTaskFactory.inert(
            RedstoneComponentType.REDSTONE_WIRE, new WorldPos(DIM, 0, 64, 0));
        // No route for REDSTONE_WIRE → must fail loudly, not silently drop.
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class, () -> runner.run(redstoneTask));
    }

    /** Hashes both subsystem worlds into one deterministic digest. */
    private static byte[] hashBoth(Combined c) {
        Map<String, byte[]> blocks = new TreeMap<>();
        for (WorldPos p : c.redstone.positions()) {
            blocks.put("r:" + p.dimensionId() + ":" + p.x() + "," + p.y() + "," + p.z(),
                ByteBuffer.allocate(4).putInt(c.redstone.getPowerLevel(p)).array());
        }
        Map<String, byte[]> entityState = new TreeMap<>();
        for (EntityField f : c.entities.fields()) {
            Vec3 v = c.entities.getVec(f);
            entityState.put(f.entityId() + ":" + f.fieldPath().value(),
                ByteBuffer.allocate(24).putDouble(v.x()).putDouble(v.y()).putDouble(v.z()).array());
        }
        return StateHashComputer.compute(blocks, Map.of(), entityState, Map.of());
    }
}
