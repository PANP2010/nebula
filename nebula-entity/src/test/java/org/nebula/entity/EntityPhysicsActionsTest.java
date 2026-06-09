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
    void moveAppliesGravityAndIntegratesPosition() throws Exception {
        EntityPhysicsState state = new EntityPhysicsState();
        long id = 1L;
        state.put(new EntityField(id, "position"), new Vec3(0, 100, 0));
        state.put(new EntityField(id, "velocity"), Vec3.ZERO);

        commitOnce(state, new EntityMoveAction(id));

        // After one tick from rest: v = (0 + -0.08) * 0.98 = -0.0784; pos.y += v.
        Vec3 vel = state.getVec(new EntityField(id, "velocity"));
        Vec3 pos = state.getVec(new EntityField(id, "position"));
        assertEquals(-0.0784, vel.y(), EPS, "vertical velocity after gravity+drag");
        assertEquals(100 + -0.0784, pos.y(), EPS, "position integrated by new velocity");
        assertEquals(0.0, vel.x(), EPS);
        assertEquals(0.0, vel.z(), EPS);
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
