package org.nebula.entity;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EntityField;
import org.nebula.entity.actions.EntityCollisionResponseAction;
import org.nebula.entity.actions.EntityMoveAction;
import org.nebula.replay.ReplayFrame;
import org.nebula.replay.ReplayRecorder;
import org.nebula.replay.ReplayVerifier;
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
 * End-to-end determinism test for the entity physics subsystem — the Phase 1
 * analogue of {@code RedstoneReplayDeterminismTest} (see DEVELOPMENT_PLAN.md
 * Milestone 7).
 *
 * <p>Drives a population of gravity-affected entities plus colliding pairs
 * through {@link EntityTickExecutor} with live actions, hashes the physics
 * state each tick via the real {@link StateHashComputer}/{@link ReplayRecorder},
 * runs the scenario twice, and asserts the per-tick hash sequences match
 * bit-for-bit via {@link ReplayVerifier}. A liveness assertion guards against
 * an inert (no-op) run.
 */
class EntityReplayDeterminismTest {

    private static final int DIM = 0;
    private static final int ENTITY_COUNT = 8;
    private static final int TICKS = 150;

    @Test
    void entityPhysicsReplaysDeterministically() throws Exception {
        List<ReplayFrame> first = simulate(TICKS);
        List<ReplayFrame> second = simulate(TICKS);

        ReplayVerifier.VerificationResult result = ReplayVerifier.verify(first, second);
        assertTrue(result.passed(), "Two identical entity-physics runs diverged: " + describe(result));
        assertEquals(TICKS, first.size());

        long distinct = first.stream().map(ReplayFrame::stateHashHex).distinct().count();
        assertTrue(distinct > 1, "Entity simulation was inert (no state change over the run)");
    }

    @Test
    @Tag("slow")
    void dg2ScaleEntityPhysicsReplaysDeterministically() throws Exception {
        List<ReplayFrame> first = simulate(5_000);
        List<ReplayFrame> second = simulate(5_000);
        ReplayVerifier.VerificationResult result = ReplayVerifier.verify(first, second);
        assertTrue(result.passed(), "DG2-scale entity run diverged: " + describe(result));
    }

    private List<ReplayFrame> simulate(int ticks) throws Exception {
        EntityPhysicsState state = new EntityPhysicsState();

        // Spawn entities in a line at varying heights with small initial
        // horizontal velocities, so some fall freely and adjacent ones collide.
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < ENTITY_COUNT; i++) {
            long id = i + 1;
            ids.add(id);
            state.put(new EntityField(id, "position"), new Vec3(i * 2, 100 + i, 0));
            // Alternating horizontal velocities to create deterministic crossings.
            double vx = (i % 2 == 0) ? 0.15 : -0.15;
            state.put(new EntityField(id, "velocity"), new Vec3(vx, 0, 0));
        }

        // Per-entity MOVE actions, plus a fixed set of collision-response pairs
        // (adjacent entities). Resolver maps task IDs to live actions.
        Map<String, EntityTaskAction> actions = new LinkedHashMap<>();
        List<TaskNode> moveTasks = new ArrayList<>();
        for (long id : ids) {
            EntitySnapshot snap = EntitySnapshot.of(id, 0, 0, 0, DIM);
            TaskNode move = EntityTaskFactory.moveInert(snap);
            actions.put(move.taskId(), new EntityMoveAction(id));
            moveTasks.add(move);
        }
        List<TaskNode> collisionPairs = new ArrayList<>();
        for (int i = 0; i + 1 < ids.size(); i++) {
            EntitySnapshot a = EntitySnapshot.of(ids.get(i), 0, 0, 0, DIM);
            EntitySnapshot b = EntitySnapshot.of(ids.get(i + 1), 0, 0, 0, DIM);
            TaskNode resp = EntityTaskFactory.collisionResponseInert(a, b);
            actions.put(resp.taskId(), new EntityCollisionResponseAction(ids.get(i), ids.get(i + 1)));
            collisionPairs.add(resp);
        }

        EntityTaskRunner runner = new EntityTaskRunner(state, actions::get);
        EntityTickExecutor executor = new EntityTickExecutor(runner);

        // Dirty set each tick: all moves, then all collision responses. The DAG
        // builder serialises by RW conflict; the executor commits per layer.
        List<TaskNode> dirty = new ArrayList<>();
        dirty.addAll(moveTasks);
        dirty.addAll(collisionPairs);

        ReplayRecorder recorder = new ReplayRecorder();
        recorder.start();
        for (long tick = 0; tick < ticks; tick++) {
            recorder.beginTick(tick);
            executor.executeTick(dirty);
            recorder.endTick(hashState(state));
        }
        recorder.stop();
        return recorder.getFrames();
    }

    /** Hashes every entity field (position + velocity vectors) deterministically. */
    private static byte[] hashState(EntityPhysicsState state) {
        Map<String, byte[]> entities = new TreeMap<>();
        for (EntityField f : state.fields()) {
            Vec3 v = state.getVec(f);
            ByteBuffer buf = ByteBuffer.allocate(24);
            buf.putDouble(v.x()).putDouble(v.y()).putDouble(v.z());
            entities.put(f.entityId() + ":" + f.fieldPath().value(), buf.array());
        }
        return StateHashComputer.compute(Map.of(), Map.of(), entities, Map.of());
    }

    private static String describe(ReplayVerifier.VerificationResult result) {
        StringBuilder sb = new StringBuilder();
        for (ReplayVerifier.Mismatch m : result.mismatches()) {
            sb.append("\n  tick=").append(m.tickNumber())
              .append(" field=").append(m.field())
              .append(" expected=").append(m.expected())
              .append(" actual=").append(m.actual());
        }
        return sb.toString();
    }
}
