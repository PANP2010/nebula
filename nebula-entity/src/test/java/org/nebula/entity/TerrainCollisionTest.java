package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.math.Vec3;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.actions.EntityMoveAction;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests terrain-aware MOVE: gravity integration plus collision against a
 * read-only {@link TerrainView}. Closes the gap where the MOVE RW-set declared
 * neighbour-block reads but the action ignored them.
 */
class TerrainCollisionTest {

    private static final double EPS = 1e-9;
    private static final int DIM = 0;

    /** Runs the move action once against the given terrain and returns final position. */
    private static void step(EntityPhysicsState state, long id, TerrainView terrain) throws Exception {
        EntityStateSnapshot snap = new EntityStateSnapshot();
        new EntityMoveAction(id, DIM).execute(new EntityTaskContext(state, snap, null, terrain));
        assertTrue(snap.commit(state).success());
    }

    @Test
    void entityFallsInOpenVoid() throws Exception {
        EntityPhysicsState state = new EntityPhysicsState();
        long id = 1L;
        state.put(new EntityField(id, "position"), new Vec3(0.5, 100, 0.5));
        state.put(new EntityField(id, "velocity"), Vec3.ZERO);

        // Two ticks: the first integrates gravity into the velocity (vanilla
        // move-then-integrate does not move on tick 1 from rest); the second
        // steps the position by that now-nonzero velocity.
        step(state, id, TerrainView.EMPTY);
        step(state, id, TerrainView.EMPTY);

        Vec3 pos = state.getVec(new EntityField(id, "position"));
        assertTrue(pos.y() < 100, "entity must fall in open void");
    }

    @Test
    void entityRestsOnFlatFloor() throws Exception {
        // Floor at y<=63 solid; entity starts just above it and falls.
        TerrainView floor = TerrainView.flatFloor(63);
        EntityPhysicsState state = new EntityPhysicsState();
        long id = 1L;
        state.put(new EntityField(id, "position"), new Vec3(0.5, 64.5, 0.5));
        state.put(new EntityField(id, "velocity"), Vec3.ZERO);

        // Step until it settles (or cap iterations).
        for (int i = 0; i < 50; i++) {
            step(state, id, floor);
        }

        Vec3 pos = state.getVec(new EntityField(id, "position"));
        Vec3 vel = state.getVec(new EntityField(id, "velocity"));
        // Block at y=63 occupies [63,64); resting feet sit on top at y=64.
        assertEquals(64.0, pos.y(), EPS, "entity should rest on top of the floor block");
        assertEquals(0.0, vel.y(), EPS, "resting entity has zero vertical velocity");
    }

    @Test
    void entityStaysAtRestOnceLanded() throws Exception {
        TerrainView floor = TerrainView.flatFloor(63);
        EntityPhysicsState state = new EntityPhysicsState();
        long id = 1L;
        state.put(new EntityField(id, "position"), new Vec3(0.5, 64.0, 0.5));
        state.put(new EntityField(id, "velocity"), Vec3.ZERO);

        for (int i = 0; i < 20; i++) {
            step(state, id, floor);
            assertEquals(64.0, state.getVec(new EntityField(id, "position")).y(), EPS,
                "a landed entity must not sink or jitter (tick " + i + ")");
        }
    }

    @Test
    void collisionUsesPerColumnSolidity() throws Exception {
        // A single solid block at one column; an entity over open air falls past
        // the floor level, while one over the block rests on it.
        WorldPos solid = new WorldPos(DIM, 0, 63, 0);
        TerrainView spotty = TerrainView.ofSolids(Set.of(solid));

        EntityPhysicsState state = new EntityPhysicsState();
        long onBlock = 1L;     // column (0,0): has the solid block
        long overAir = 2L;     // column (5,5): empty
        state.put(new EntityField(onBlock, "position"), new Vec3(0.5, 65, 0.5));
        state.put(new EntityField(onBlock, "velocity"), Vec3.ZERO);
        state.put(new EntityField(overAir, "position"), new Vec3(5.5, 65, 5.5));
        state.put(new EntityField(overAir, "velocity"), Vec3.ZERO);

        for (int i = 0; i < 60; i++) {
            step(state, onBlock, spotty);
            step(state, overAir, spotty);
        }

        assertEquals(64.0, state.getVec(new EntityField(onBlock, "position")).y(), EPS,
            "entity over the solid block rests on it");
        assertTrue(state.getVec(new EntityField(overAir, "position")).y() < 0,
            "entity over open air keeps falling past the floor level");
    }

    @Test
    void fallAndLandIsDeterministic() throws Exception {
        assertEquals(simulateLanding(), simulateLanding(),
            "terrain-collision physics must be reproducible");
    }

    @Test
    void fastFallDoesNotTunnelThroughThinFloor() throws Exception {
        // A single solid floor block at y=63 with open air above and below.
        // An entity given a large downward velocity (>1 block/tick) must land
        // ON the floor, not tunnel through it — the swept-collision guarantee.
        WorldPos floorBlock = new WorldPos(DIM, 0, 63, 0);
        TerrainView thinFloor = TerrainView.ofSolids(Set.of(floorBlock));

        EntityPhysicsState state = new EntityPhysicsState();
        long id = 1L;
        state.put(new EntityField(id, "position"), new Vec3(0.5, 70, 0.5));
        state.put(new EntityField(id, "velocity"), new Vec3(0, -8.0, 0)); // ~8 blocks/tick

        for (int i = 0; i < 10; i++) {
            step(state, id, thinFloor);
        }

        Vec3 pos = state.getVec(new EntityField(id, "position"));
        assertEquals(64.0, pos.y(), EPS,
            "fast-falling entity must land on the thin floor (y=64), not tunnel through");
        assertEquals(0.0, state.getVec(new EntityField(id, "velocity")).y(), EPS);
    }

    @Test
    void sweepLandsOnFirstSolidBlockNotLast() throws Exception {
        // Stack of solids at y=60 and y=63; entity falls fast from above. It must
        // rest on the HIGHER block (y=63 → top y=64), not punch down to y=60.
        TerrainView stacked = TerrainView.ofSolids(Set.of(
            new WorldPos(DIM, 0, 60, 0), new WorldPos(DIM, 0, 63, 0)));
        EntityPhysicsState state = new EntityPhysicsState();
        long id = 1L;
        state.put(new EntityField(id, "position"), new Vec3(0.5, 75, 0.5));
        state.put(new EntityField(id, "velocity"), new Vec3(0, -10.0, 0));

        for (int i = 0; i < 10; i++) {
            step(state, id, stacked);
        }

        assertEquals(64.0, state.getVec(new EntityField(id, "position")).y(), EPS,
            "must land on the first (highest) solid block encountered, not tunnel to the lower one");
    }

    private double simulateLanding() throws Exception {
        TerrainView floor = TerrainView.flatFloor(63);
        EntityPhysicsState state = new EntityPhysicsState();
        long id = 9L;
        state.put(new EntityField(id, "position"), new Vec3(0.5, 80, 0.5));
        state.put(new EntityField(id, "velocity"), Vec3.ZERO);
        for (int i = 0; i < 100; i++) {
            step(state, id, floor);
        }
        return state.getVec(new EntityField(id, "position")).y();
    }
}
