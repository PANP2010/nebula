package org.nebula.entity;

import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskAction;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.EventType;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.RandomUsage;
import org.nebula.core.state.WorldPos;

import java.util.Objects;

/**
 * Creates {@link TaskNode}s for entity lifecycle tasks (arch doc §6.2).
 *
 * <h3>RW-set templates</h3>
 * <ul>
 *   <li><b>MOVE:</b> reads position+velocity, reads 6 adjacent blocks for terrain,
 *       writes position, fires ENTITY_MOVED</li>
 *   <li><b>COLLISION:</b> reads both entities' positions only — pure read, fully parallel</li>
 *   <li><b>COLLISION_RESPONSE:</b> reads both velocities, writes both velocities</li>
 *   <li><b>AI_GOAL:</b> reads entity state, writes goal target (deferred, no events)</li>
 *   <li><b>ITEM_PICKUP:</b> reads inventory + item position, writes inventory + item removal</li>
 *   <li><b>DAMAGE:</b> reads attacker stats + defender health, writes defender health</li>
 * </ul>
 */
public final class EntityTaskFactory {

    private EntityTaskFactory() {}

    // ── Public factory methods ────────────────────────────────────────────────

    public static TaskNode move(EntitySnapshot entity, TaskAction action) {
        Objects.requireNonNull(entity);
        return new TaskNode(
            entity.taskId(EntityTaskType.MOVE),
            EntityTaskType.MOVE.taskType(),
            moveRw(entity),
            action
        );
    }

    public static TaskNode moveInert(EntitySnapshot entity) {
        return move(entity, () -> {});
    }

    public static TaskNode collision(EntitySnapshot a, EntitySnapshot b, TaskAction action) {
        Objects.requireNonNull(a);
        Objects.requireNonNull(b);
        long lo = Math.min(a.entityId(), b.entityId());
        long hi = Math.max(a.entityId(), b.entityId());
        String taskId = EntityTaskType.COLLISION.taskType() + "@" + a.dimensionId()
            + ":" + lo + "," + hi;
        return new TaskNode(taskId, EntityTaskType.COLLISION.taskType(), collisionRw(a, b), action);
    }

    public static TaskNode collisionInert(EntitySnapshot a, EntitySnapshot b) {
        return collision(a, b, () -> {});
    }

    public static TaskNode collisionResponse(EntitySnapshot a, EntitySnapshot b, TaskAction action) {
        Objects.requireNonNull(a);
        Objects.requireNonNull(b);
        long lo = Math.min(a.entityId(), b.entityId());
        long hi = Math.max(a.entityId(), b.entityId());
        String taskId = EntityTaskType.COLLISION_RESPONSE.taskType() + "@" + a.dimensionId()
            + ":" + lo + "," + hi;
        return new TaskNode(taskId, EntityTaskType.COLLISION_RESPONSE.taskType(),
            collisionResponseRw(a, b), action);
    }

    public static TaskNode collisionResponseInert(EntitySnapshot a, EntitySnapshot b) {
        return collisionResponse(a, b, () -> {});
    }

    public static TaskNode aiGoal(EntitySnapshot entity, TaskAction action) {
        Objects.requireNonNull(entity);
        return new TaskNode(
            entity.taskId(EntityTaskType.AI_GOAL),
            EntityTaskType.AI_GOAL.taskType(),
            aiGoalRw(entity),
            action
        );
    }

    public static TaskNode aiGoalInert(EntitySnapshot entity) {
        return aiGoal(entity, () -> {});
    }

    public static TaskNode itemPickup(EntitySnapshot picker, EntitySnapshot item, TaskAction action) {
        Objects.requireNonNull(picker);
        Objects.requireNonNull(item);
        String taskId = EntityTaskType.ITEM_PICKUP.taskType() + "@" + picker.dimensionId()
            + ":" + picker.entityId() + "," + item.entityId();
        return new TaskNode(taskId, EntityTaskType.ITEM_PICKUP.taskType(),
            itemPickupRw(picker, item), action);
    }

    public static TaskNode itemPickupInert(EntitySnapshot picker, EntitySnapshot item) {
        return itemPickup(picker, item, () -> {});
    }

    public static TaskNode damage(EntitySnapshot attacker, EntitySnapshot defender, TaskAction action) {
        Objects.requireNonNull(attacker);
        Objects.requireNonNull(defender);
        String taskId = EntityTaskType.DAMAGE.taskType() + "@" + defender.dimensionId()
            + ":" + attacker.entityId() + "→" + defender.entityId();
        return new TaskNode(taskId, EntityTaskType.DAMAGE.taskType(),
            damageRw(attacker, defender), action);
    }

    public static TaskNode damageInert(EntitySnapshot attacker, EntitySnapshot defender) {
        return damage(attacker, defender, () -> {});
    }

    // ── RW-set templates ──────────────────────────────────────────────────────

    private static RWSet moveRw(EntitySnapshot e) {
        int dim = e.dimensionId();
        // Read 6 surrounding blocks for terrain collision check
        WorldPos at = new WorldPos(dim, e.x(), e.y(), e.z());
        RWSet.Builder b = RWSet.builder()
            .readEntity(field(e.entityId(), "position"))
            .readEntity(field(e.entityId(), "velocity"))
            .readBlock(at)
            .readBlock(new WorldPos(dim, e.x() + 1, e.y(), e.z()))
            .readBlock(new WorldPos(dim, e.x() - 1, e.y(), e.z()))
            .readBlock(new WorldPos(dim, e.x(), e.y() + 1, e.z()))
            .readBlock(new WorldPos(dim, e.x(), e.y() - 1, e.z()))
            .readBlock(new WorldPos(dim, e.x(), e.y(), e.z() + 1))
            .readBlock(new WorldPos(dim, e.x(), e.y(), e.z() - 1))
            .writeEntity(field(e.entityId(), "position"))
            // The live MOVE action applies gravity to velocity then integrates
            // position, so velocity is both read and written (declared here to
            // keep the RW-set consistent with what the action touches).
            .writeEntity(field(e.entityId(), "velocity"))
            .writeEvent(EventType.ENTITY_MOVED);
        return b.build();
    }

    private static RWSet collisionRw(EntitySnapshot a, EntitySnapshot b) {
        // Pure read — no writes, enabling full parallelism across all collision pairs
        return RWSet.builder()
            .readEntity(field(a.entityId(), "position"))
            .readEntity(field(b.entityId(), "position"))
            .build();
    }

    private static RWSet collisionResponseRw(EntitySnapshot a, EntitySnapshot b) {
        return RWSet.builder()
            .readEntity(field(a.entityId(), "velocity"))
            .readEntity(field(b.entityId(), "velocity"))
            .writeEntity(field(a.entityId(), "velocity"))
            .writeEntity(field(b.entityId(), "velocity"))
            .build();
    }

    private static RWSet aiGoalRw(EntitySnapshot e) {
        return RWSet.builder()
            .readEntity(field(e.entityId(), "position"))
            .readEntity(field(e.entityId(), "ai_state"))
            .writeEntity(field(e.entityId(), "goal_target"))
            .randomUsage(new RandomUsage(RandomInstance.ENTITY_RANDOM, 8))
            .build();
    }

    private static RWSet itemPickupRw(EntitySnapshot picker, EntitySnapshot item) {
        return RWSet.builder()
            .readEntity(field(picker.entityId(), "position"))
            .readEntity(field(item.entityId(), "position"))
            .readEntity(field(picker.entityId(), "inventory"))
            .writeEntity(field(picker.entityId(), "inventory"))
            .writeEntity(field(item.entityId(), "removed"))   // item despawn
            .writeEvent(EventType.INVENTORY_CHANGED)
            .build();
    }

    private static RWSet damageRw(EntitySnapshot attacker, EntitySnapshot defender) {
        return RWSet.builder()
            .readEntity(field(attacker.entityId(), "attack_damage"))
            .readEntity(field(defender.entityId(), "health"))
            .readEntity(field(defender.entityId(), "armor"))
            .writeEntity(field(defender.entityId(), "health"))
            .randomUsage(new RandomUsage(RandomInstance.ENTITY_RANDOM, 3))
            .build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static EntityField field(long entityId, String path) {
        return new EntityField(entityId, path);
    }
}
