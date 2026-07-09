package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.EntityField;
import org.nebula.entity.actions.EntityCollisionResponseAction;
import org.nebula.entity.actions.EntityMoveAction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the live entity physics actions, executed through the
 * snapshot/commit path against a real {@link EntityPhysicsState}.
 */
class EntityPhysicsActionsTest {

    private static final double EPS = 1e-9;

    private static Vec3 commitOnce(EntityPhysicsState state, EntityTaskAction action) throws Exception {
        EntityStateSnapshot snapshot = new EntityStateSnapshot();
        action.execute(new EntityTaskContext(state, snapshot));
        EntityStateSnapshot.CommitResult result = snapshot.commit(state);
        assertTrue(result.success(), "commit should succeed without contention");
        return null;
    }

    @Test
    void moveUsesVanillaMoveThenIntegrateOrder() throws Exception {
        EntityPhysicsState state = new EntityPhysicsState();
        long id = 1L;
        state.put(new EntityField(id, "position"), new Vec3(0, 100, 0));
        state.put(new EntityField(id, "velocity"), Vec3.ZERO);

        // Tick 1 from rest: vanilla moves by the CURRENT velocity (0) FIRST, so
        // the position does not change this tick; gravity+drag are integrated
        // AFTER the move into the velocity carried to the next tick.
        commitOnce(state, new EntityMoveAction(id));
        Vec3 vel1 = state.getVec(new EntityField(id, "velocity"));
        Vec3 pos1 = state.getVec(new EntityField(id, "position"));
        assertEquals(-0.0784, vel1.y(), EPS, "velocity integrated: (0 + -0.08) * 0.98");
        assertEquals(100.0, pos1.y(), EPS, "move-then-integrate: no position change on the first tick from rest");
        assertEquals(0.0, vel1.x(), EPS);
        assertEquals(0.0, vel1.z(), EPS);

        // Tick 2: now moves by the velocity computed last tick, then integrates
        // again. This is the ordering that eliminates the systematic Y over-fall
        // against Folia (an earlier revision folded gravity in BEFORE moving).
        commitOnce(state, new EntityMoveAction(id));
        Vec3 vel2 = state.getVec(new EntityField(id, "velocity"));
        Vec3 pos2 = state.getVec(new EntityField(id, "position"));
        assertEquals(100 + -0.0784, pos2.y(), EPS, "position stepped by the prior tick's velocity");
        assertEquals((-0.0784 + -0.08) * 0.98, vel2.y(), EPS, "velocity integrated again for the next tick");
    }

    @Test
    void moveIsDeterministicOverManyTicks() throws Exception {
        Vec3 firstFinal = simulateFall();
        Vec3 secondFinal = simulateFall();
        assertEquals(firstFinal.y(), secondFinal.y(), EPS, "free-fall must be reproducible");
    }

    private Vec3 simulateFall() throws Exception {
        EntityPhysicsState state = new EntityPhysicsState();
        long id = 7L;
        state.put(new EntityField(id, "position"), new Vec3(0, 256, 0));
        state.put(new EntityField(id, "velocity"), Vec3.ZERO);
        EntityMoveAction move = new EntityMoveAction(id);
        for (int i = 0; i < 100; i++) {
            commitOnce(state, move);
        }
        return state.getVec(new EntityField(id, "position"));
    }

    @Test
    void collisionResponseExchangesVelocitiesAndConservesMomentum() throws Exception {
        EntityPhysicsState state = new EntityPhysicsState();
        long a = 1L;
        long b = 2L;
        Vec3 va = new Vec3(1.0, 0, 0);
        Vec3 vb = new Vec3(-0.5, 0, 0);
        state.put(new EntityField(a, "velocity"), va);
        state.put(new EntityField(b, "velocity"), vb);

        double momentumBefore = va.x() + vb.x();

        commitOnce(state, new EntityCollisionResponseAction(a, b));

        Vec3 newVa = state.getVec(new EntityField(a, "velocity"));
        Vec3 newVb = state.getVec(new EntityField(b, "velocity"));
        // Equal-mass elastic exchange: velocities swap.
        assertEquals(vb.x(), newVa.x(), EPS);
        assertEquals(va.x(), newVb.x(), EPS);
        assertEquals(momentumBefore, newVa.x() + newVb.x(), EPS, "momentum conserved");
    }
}
