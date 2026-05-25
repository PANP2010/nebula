package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.scheduler.TaskNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EntityTaskGeneratorTest {

    // Entity A at (10,64,10), Entity B at (12,64,10) — within radius 4
    private static final EntitySnapshot A = EntitySnapshot.of(1L, 10, 64, 10, 0);
    private static final EntitySnapshot B = EntitySnapshot.of(2L, 12, 64, 10, 0);
    // Entity C far away — outside radius
    private static final EntitySnapshot C = EntitySnapshot.of(3L, 100, 64, 100, 0);

    @Test
    void moveCompletedNearby_generatesCollisionTask() {
        EntityTaskGenerator gen = new EntityTaskGenerator(Map.of(
            A.entityId(), A,
            B.entityId(), B
        ));
        TaskNode moveTask = EntityTaskFactory.moveInert(A);

        List<TaskNode> downstream = gen.generateFrom(moveTask);

        assertEquals(1, downstream.size());
        assertEquals(EntityTaskType.COLLISION.taskType(), downstream.get(0).taskType());
    }

    @Test
    void moveCompleted_farEntity_noCollision() {
        EntityTaskGenerator gen = new EntityTaskGenerator(Map.of(
            A.entityId(), A,
            C.entityId(), C
        ));
        TaskNode moveTask = EntityTaskFactory.moveInert(A);

        List<TaskNode> downstream = gen.generateFrom(moveTask);

        assertTrue(downstream.isEmpty());
    }

    @Test
    void moveCompleted_multipleNearby_generatesAllCollisions() {
        EntitySnapshot D = EntitySnapshot.of(4L, 11, 64, 11, 0); // also within radius
        EntityTaskGenerator gen = new EntityTaskGenerator(Map.of(
            A.entityId(), A,
            B.entityId(), B,
            D.entityId(), D,
            C.entityId(), C  // far — excluded
        ));
        TaskNode moveTask = EntityTaskFactory.moveInert(A);

        List<TaskNode> downstream = gen.generateFrom(moveTask);

        assertEquals(2, downstream.size());
        assertTrue(downstream.stream().allMatch(t ->
            EntityTaskType.COLLISION.taskType().equals(t.taskType())));
    }

    @Test
    void collisionCompleted_generatesCollisionResponseTask() {
        EntityTaskGenerator gen = new EntityTaskGenerator(Map.of(
            A.entityId(), A,
            B.entityId(), B
        ));
        TaskNode collisionTask = EntityTaskFactory.collisionInert(A, B);

        List<TaskNode> downstream = gen.generateFrom(collisionTask);

        assertEquals(1, downstream.size());
        assertEquals(EntityTaskType.COLLISION_RESPONSE.taskType(), downstream.get(0).taskType());
    }

    @Test
    void collisionResponse_noFurtherPropagation() {
        EntityTaskGenerator gen = new EntityTaskGenerator(Map.of(
            A.entityId(), A,
            B.entityId(), B
        ));
        TaskNode respTask = EntityTaskFactory.collisionResponseInert(A, B);

        List<TaskNode> downstream = gen.generateFrom(respTask);

        assertTrue(downstream.isEmpty());
    }

    @Test
    void aiGoalTask_noDownstream() {
        EntityTaskGenerator gen = new EntityTaskGenerator(Map.of(A.entityId(), A));
        List<TaskNode> downstream = gen.generateFrom(EntityTaskFactory.aiGoalInert(A));
        assertTrue(downstream.isEmpty());
    }

    @Test
    void damageTask_noDownstream() {
        EntityTaskGenerator gen = new EntityTaskGenerator(Map.of(
            A.entityId(), A,
            B.entityId(), B
        ));
        List<TaskNode> downstream = gen.generateFrom(EntityTaskFactory.damageInert(A, B));
        assertTrue(downstream.isEmpty());
    }

    @Test
    void differentDimension_noCollision() {
        EntitySnapshot inNether = EntitySnapshot.of(5L, 10, 64, 10, -1);
        EntityTaskGenerator gen = new EntityTaskGenerator(Map.of(
            A.entityId(), A,
            inNether.entityId(), inNether
        ));
        TaskNode moveTask = EntityTaskFactory.moveInert(A);

        List<TaskNode> downstream = gen.generateFrom(moveTask);

        assertTrue(downstream.isEmpty());
    }

    @Test
    void collisionTaskId_isCommutative_generatorFindsResponse() {
        EntityTaskGenerator gen = new EntityTaskGenerator(Map.of(
            A.entityId(), A,
            B.entityId(), B
        ));
        // swap order — ID should be same, generator should still produce response
        TaskNode collisionBA = EntityTaskFactory.collisionInert(B, A);
        List<TaskNode> downstream = gen.generateFrom(collisionBA);

        assertEquals(1, downstream.size());
        assertEquals(EntityTaskType.COLLISION_RESPONSE.taskType(), downstream.get(0).taskType());
    }
}
