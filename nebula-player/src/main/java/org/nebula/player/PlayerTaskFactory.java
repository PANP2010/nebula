package org.nebula.player;

import org.nebula.core.player.PlayerSnapshot;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskAction;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.RandomUsage;
import org.nebula.core.state.WorldPos;

import java.util.Objects;
import java.util.UUID;

/**
 * Creates {@link TaskNode}s for player lifecycle tasks.
 *
 * <h3>RW-set templates</h3>
 * <ul>
 *   <li><b>MOVE:</b> reads position+velocity, reads terrain, writes position+velocity</li>
 *   <li><b>BLOCK_INTERACT:</b> reads held item + target block, writes block state,
 *       writes held item count, fires BLOCK_UPDATE</li>
 *   <li><b>INVENTORY_UPDATE:</b> reads/writes player inventory slot</li>
 *   <li><b>COMBAT:</b> reads attack damage + target health, writes target health</li>
 *   <li><b>ENTITY_INTERACT:</b> reads player pos + nearby entity, writes entity state</li>
 * </ul>
 */
public final class PlayerTaskFactory {

    private PlayerTaskFactory() {}

    public static TaskNode move(PlayerSnapshot player, TaskAction action) {
        Objects.requireNonNull(player);
        return new TaskNode(
            player.taskId("PLAYER_MOVE"),
            PlayerTaskType.MOVE.taskType(),
            moveRw(player),
            action
        );
    }

    public static TaskNode moveInert(PlayerSnapshot player) {
        return move(player, () -> {});
    }

    public static TaskNode blockInteract(PlayerSnapshot player, WorldPos target, TaskAction action) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(target);
        String taskId = PlayerTaskType.BLOCK_INTERACT.taskType()
            + "@" + player.dimensionId() + ":" + player.playerId()
            + ":" + target.x() + "," + target.y() + "," + target.z();
        return new TaskNode(taskId, PlayerTaskType.BLOCK_INTERACT.taskType(),
            blockInteractRw(player, target), action);
    }

    public static TaskNode blockInteractInert(PlayerSnapshot player, WorldPos target) {
        return blockInteract(player, target, () -> {});
    }

    public static TaskNode inventoryUpdate(PlayerSnapshot player, int slot, TaskAction action) {
        Objects.requireNonNull(player);
        String taskId = PlayerTaskType.INVENTORY_UPDATE.taskType()
            + "@" + player.dimensionId() + ":" + player.playerId()
            + ":" + slot;
        return new TaskNode(taskId, PlayerTaskType.INVENTORY_UPDATE.taskType(),
            inventoryUpdateRw(player, slot), action);
    }

    public static TaskNode inventoryUpdateInert(PlayerSnapshot player, int slot) {
        return inventoryUpdate(player, slot, () -> {});
    }

    public static TaskNode combat(PlayerSnapshot attacker, long targetEntityId, TaskAction action) {
        Objects.requireNonNull(attacker);
        String taskId = PlayerTaskType.COMBAT.taskType()
            + "@" + attacker.dimensionId() + ":" + attacker.playerId()
            + "→" + targetEntityId;
        return new TaskNode(taskId, PlayerTaskType.COMBAT.taskType(),
            combatRw(attacker, targetEntityId), action);
    }

    public static TaskNode combatInert(PlayerSnapshot attacker, long targetEntityId) {
        return combat(attacker, targetEntityId, () -> {});
    }

    public static TaskNode entityInteract(PlayerSnapshot player, long targetEntityId, TaskAction action) {
        Objects.requireNonNull(player);
        String taskId = PlayerTaskType.ENTITY_INTERACT.taskType()
            + "@" + player.dimensionId() + ":" + player.playerId()
            + ":" + targetEntityId;
        return new TaskNode(taskId, PlayerTaskType.ENTITY_INTERACT.taskType(),
            entityInteractRw(player, targetEntityId), action);
    }

    public static TaskNode entityInteractInert(PlayerSnapshot player, long targetEntityId) {
        return entityInteract(player, targetEntityId, () -> {});
    }

    // ── RW-set templates ──────────────────────────────────────────────────────

    private static RWSet moveRw(PlayerSnapshot p) {
        int dim = p.dimensionId();
        WorldPos at = new WorldPos(dim, p.x(), p.y(), p.z());
        return RWSet.builder()
            .readEntity(entityField(p.playerId(), "position"))
            .readEntity(entityField(p.playerId(), "velocity"))
            .readBlock(at)
            .readBlock(new WorldPos(dim, p.x() + 1, p.y(), p.z()))
            .readBlock(new WorldPos(dim, p.x() - 1, p.y(), p.z()))
            .readBlock(new WorldPos(dim, p.x(), p.y() + 1, p.z()))
            .readBlock(new WorldPos(dim, p.x(), p.y(), p.z() + 1))
            .readBlock(new WorldPos(dim, p.x(), p.y(), p.z() - 1))
            .writeEntity(entityField(p.playerId(), "position"))
            .writeEntity(entityField(p.playerId(), "velocity"))
            .build();
    }

    private static RWSet blockInteractRw(PlayerSnapshot p, WorldPos target) {
        UUID uid = p.playerId();
        return RWSet.builder()
            .readEntity(entityField(uid, "position"))
            .readEntity(entityField(uid, "held_slot"))
            .readBlock(target)
            .writeBlock(target)
            .writeEntity(entityField(uid, "held_slot"))
            .writeEvent(org.nebula.core.state.EventType.BLOCK_UPDATE)
            .randomUsage(new RandomUsage(RandomInstance.PLAYER_RANDOM, 4))
            .build();
    }

    private static RWSet inventoryUpdateRw(PlayerSnapshot p, int slot) {
        UUID uid = p.playerId();
        return RWSet.builder()
            .readEntity(entityField(uid, "inventory_slot_" + slot))
            .writeEntity(entityField(uid, "inventory_slot_" + slot))
            .build();
    }

    private static RWSet combatRw(PlayerSnapshot attacker, long targetEntityId) {
        return RWSet.builder()
            .readEntity(entityField(attacker.playerId(), "attack_damage"))
            .readEntity(entityField(targetEntityId, "health"))
            .writeEntity(entityField(targetEntityId, "health"))
            .randomUsage(new RandomUsage(RandomInstance.PLAYER_RANDOM, 2))
            .build();
    }

    private static RWSet entityInteractRw(PlayerSnapshot p, long targetEntityId) {
        return RWSet.builder()
            .readEntity(entityField(p.playerId(), "position"))
            .readEntity(entityField(targetEntityId, "state"))
            .writeEntity(entityField(targetEntityId, "state"))
            .writeEvent(org.nebula.core.state.EventType.ENTITY_INTERACTED)
            .randomUsage(new RandomUsage(RandomInstance.PLAYER_RANDOM, 3))
            .build();
    }

    // Players use Long entity IDs in the shared RWSet system (leastSignificantBits of UUID)
    private static org.nebula.core.state.EntityField entityField(UUID playerId, String path) {
        return new org.nebula.core.state.EntityField(
            playerId.getLeastSignificantBits(), path);
    }

    // For targeting other entities (combat, entity interact)
    private static org.nebula.core.state.EntityField entityField(long entityId, String path) {
        return new org.nebula.core.state.EntityField(entityId, path);
    }
}
