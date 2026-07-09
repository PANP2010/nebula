package org.nebula.plugin;

import org.junit.jupiter.api.Test;
import org.nebula.core.scheduler.CompositeTaskRunner;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EntityField;
import org.nebula.entity.EntityPhysicsState;
import org.nebula.entity.EntitySnapshot;
import org.nebula.entity.EntityTaskAction;
import org.nebula.entity.EntityTaskFactory;
import org.nebula.entity.EntityTaskRunner;
import org.nebula.entity.Vec3;
import org.nebula.redstone.RedstoneComponentType;
import org.nebula.redstone.RedstoneTaskFactory;
import org.nebula.core.state.WorldPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the plugin's entity task wiring to the IDs that
 * {@link EntityTaskFactory} / {@link EntitySnapshot} actually stamp.
 *
 * <p>Regression guard for a real drift bug: the plugin used to route on
 * {@code "MOVE"}/{@code "COLLISION"} prefixes and parse a fictional
 * {@code MOVE@dim:entityId} grammar, while the factory stamps
 * {@code ENTITY_MOVE@<dim>:<id>:<x>,<y>,<z>} and
 * {@code ENTITY_COLLISION_RESPONSE@<dim>:<lo>,<hi>}. Every entity task therefore
 * fell through {@link CompositeTaskRunner}'s no-route hard error, and even had it
 * routed, {@link NebulaPlugin#resolveEntityAction} would have parsed the wrong
 * token and produced entity id 0. These tests close the loop
 * factory-stamps-ID → plugin-resolves-action → action-mutates-right-entity, so
 * the C-series entity DAG bring-up starts from a correct seam.
 */
class EntityTaskResolutionTest {

    private static final double EPS = 1e-9;
    private static final int DIM = 0;

    // ── resolveEntityAction: grammar of the real stamped IDs ──────────────────

    @Test
    void resolvesMoveFromFactoryStampedId() {
        EntitySnapshot e = EntitySnapshot.of(42L, 10, 64, -3, DIM);
        String id = e.taskId(org.nebula.entity.EntityTaskType.MOVE);
        // Sanity: this is the grammar the resolver must understand.
        assertEquals("ENTITY_MOVE@0:42:10,64,-3", id);

        EntityTaskAction action = NebulaPlugin.resolveEntityAction(id);
        assertNotNull(action, "ENTITY_MOVE must resolve to a move action");
        // Prove it targets entity 42 (not 0, not the coord token) by executing it.
        assertEquals(42L, movedEntityId(action));
    }

    @Test
    void resolvesCollisionResponseFromFactoryStampedId() {
        EntitySnapshot a = EntitySnapshot.of(7L, 0, 64, 0, DIM);
        EntitySnapshot b = EntitySnapshot.of(9L, 1, 64, 0, DIM);
        String id = EntityTaskFactory.collisionResponse(a, b, () -> {}).taskId();
        assertEquals("ENTITY_COLLISION_RESPONSE@0:7,9", id);

        EntityTaskAction action = NebulaPlugin.resolveEntityAction(id);
        assertNotNull(action, "ENTITY_COLLISION_RESPONSE must resolve to an action");
        // Execute against a seeded state and confirm ids 7 and 9 had velocities swapped.
        EntityPhysicsState state = new EntityPhysicsState();
        Vec3 va = new Vec3(1.0, 0, 0);
        Vec3 vb = new Vec3(-0.5, 0, 0);
        state.put(new EntityField(7L, "velocity"), va);
        state.put(new EntityField(9L, "velocity"), vb);
        runAndCommit(state, action);
        assertEquals(vb.x(), state.getVec(new EntityField(7L, "velocity")).x(), EPS);
        assertEquals(va.x(), state.getVec(new EntityField(9L, "velocity")).x(), EPS);
    }

    @Test
    void pureCollisionResolvesToNoAction() {
        EntitySnapshot a = EntitySnapshot.of(7L, 0, 64, 0, DIM);
        EntitySnapshot b = EntitySnapshot.of(9L, 1, 64, 0, DIM);
        String id = EntityTaskFactory.collision(a, b, () -> {}).taskId();
        assertEquals("ENTITY_COLLISION@0:7,9", id);
        // COLLISION is a pure read — it writes nothing, so it must not resolve to
        // a mutating action (that would be the collision *response*).
        assertNull(NebulaPlugin.resolveEntityAction(id),
            "pure ENTITY_COLLISION must resolve to no action");
    }

    @Test
    void unmodelledTypeResolvesToNull() {
        assertNull(NebulaPlugin.resolveEntityAction("ENTITY_AI_GOAL@0:5:1,2,3"));
        assertNull(NebulaPlugin.resolveEntityAction("no-at-sign"));
    }

    // ── CompositeTaskRunner routing: prefixes must match stamped types ────────

    @Test
    void compositeRoutesEntityMoveThroughEntityRunner() throws Exception {
        EntityPhysicsState state = new EntityPhysicsState();
        state.put(new EntityField(42L, "position"), new Vec3(0, 100, 0));
        state.put(new EntityField(42L, "velocity"), Vec3.ZERO);

        EntityTaskRunner entityRunner = new EntityTaskRunner(state, NebulaPlugin::resolveEntityAction);
        CompositeTaskRunner composite = new CompositeTaskRunner()
            .routeByTypePrefix("REDSTONE_", new ThrowingRunner())
            .routeByTypePrefix("ENTITY_", entityRunner);

        EntitySnapshot e = EntitySnapshot.of(42L, 0, 100, 0, DIM);
        TaskNode move = EntityTaskFactory.moveInert(e);
        composite.run(move);            // routes to entityRunner (no IllegalStateException)
        composite.commitLayer();

        // Gravity applied to entity 42 ⇒ its y-velocity is now negative.
        assertTrue(state.getVec(new EntityField(42L, "velocity")).y() < 0,
            "entity 42 should have fallen after one MOVE tick");
    }

    @Test
    void compositeStillRoutesRedstoneToRedstoneSide() throws Exception {
        // The redstone route ("REDSTONE_") must remain intact after the entity fix.
        RecordingRunner redstone = new RecordingRunner();
        CompositeTaskRunner composite = new CompositeTaskRunner()
            .routeByTypePrefix("REDSTONE_", redstone)
            .routeByTypePrefix("ENTITY_", new ThrowingRunner());
        TaskNode wire = RedstoneTaskFactory.inert(
            RedstoneComponentType.REDSTONE_WIRE, new WorldPos(DIM, 1, 64, 0));
        composite.run(wire);
        assertEquals(1, redstone.count, "REDSTONE_WIRE must route to the redstone runner");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Executes a move action against a fresh state and returns the entity whose position changed. */
    private static long movedEntityId(EntityTaskAction action) {
        EntityPhysicsState state = new EntityPhysicsState();
        // Seed a spread of candidate ids so a wrong-token parse (0 or a coord) would miss id 42.
        for (long id : new long[]{0L, 10L, 42L}) {
            state.put(new EntityField(id, "position"), new Vec3(0, 100, 0));
            state.put(new EntityField(id, "velocity"), Vec3.ZERO);
        }
        runAndCommit(state, action);
        long moved = -1L;
        for (long id : new long[]{0L, 10L, 42L}) {
            if (state.getVec(new EntityField(id, "velocity")).y() < 0) {
                assertEquals(-1L, moved, "exactly one entity should move");
                moved = id;
            }
        }
        return moved;
    }

    private static void runAndCommit(EntityPhysicsState state, EntityTaskAction action) {
        EntityTaskRunner runner = new EntityTaskRunner(state, id -> action);
        try {
            runner.run(new TaskNode("probe@x", "ENTITY_PROBE",
                org.nebula.core.rw.RWSet.empty(), () -> {}));
            runner.commitLayer();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static final class RecordingRunner implements org.nebula.core.scheduler.LayerCommitting {
        int count;
        @Override public void run(TaskNode task) { count++; }
        @Override public java.util.List<String> commitLayer() { return java.util.List.of(); }
        @Override public void resetLayer() {}
    }

    private static final class ThrowingRunner implements org.nebula.core.scheduler.LayerCommitting {
        @Override public void run(TaskNode task) {
            throw new AssertionError("wrong runner received task " + task.taskId());
        }
        @Override public java.util.List<String> commitLayer() { return java.util.List.of(); }
        @Override public void resetLayer() {}
    }
}
