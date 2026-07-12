package org.nebula.player.actions;

import org.nebula.player.PlayerTaskAction;
import org.nebula.player.PlayerTaskContext;
import org.nebula.core.state.WorldPos;
import java.util.UUID;

/**
 * Tracks block break progress for the held item.
 *
 * Minecraft block breaking: player holds left-click on a block. Break time depends on:
 * - Block hardness (stone=1.5, dirt=0.5, obsidian=10.0, etc.)
 * - Item efficiency enchantment
 * - haste/instant mining effects
 * - Whether the player is in water (slower)
 *
 * This action advances break progress each tick. When progress >= block hardness,
 * the block breaks.
 */
public final class PlayerBlockBreakProgressAction implements PlayerTaskAction {

    private final UUID playerId;
    private final WorldPos targetBlock;
    private final double baseSpeed;

    public PlayerBlockBreakProgressAction(UUID playerId, WorldPos targetBlock, double baseSpeed) {
        this.playerId = playerId;
        this.targetBlock = targetBlock;
        this.baseSpeed = baseSpeed;
    }

    @Override
    public void execute(PlayerTaskContext ctx) {
        // Get break target
        WorldPos currentTarget = WorldPos.parse(ctx.readString(playerId, "break_target_pos"));
        if (currentTarget == null || !currentTarget.equals(targetBlock)) {
            // Target changed, reset progress
            ctx.writeScalar(playerId, "break_progress", 0.0);
            ctx.writeScalar(playerId, "break_target_hash", (double) targetBlock.hashCode());
            return;
        }

        // Get block hardness
        double hardness = blockHardness(ctx, targetBlock);
        if (hardness < 0) {
            // Unbreakable block (BEDROCK, barrier, etc.)
            ctx.writeScalar(playerId, "break_progress", 0.0);
            return;
        }

        // Speed modifiers
        double speed = baseSpeed;

        // Efficiency enchantment
        int efficiencyLevel = (int) ctx.readScalar(playerId, "efficiency_level");
        if (efficiencyLevel > 0) speed += efficiencyLevel * efficiencyLevel + 1.0;

        // Haste effect (from beacon, potions)
        double hasteMult = 1.0 + 0.2 * ctx.readScalar(playerId, "haste_level");
        speed *= hasteMult;

        // Mining fatigue
        double fatigueMult = 1.0 / (1.0 + 0.3 * ctx.readScalar(playerId, "mining_fatigue_level"));
        speed *= fatigueMult;

        // Water penalty
        if (ctx.readBool(playerId, "is_in_water") && !ctx.readBool(playerId, "has_aqua_affinity")) {
            speed *= 0.2;
        }

        // Advance progress
        double current = ctx.readScalar(playerId, "break_progress");
        double newProgress = current + speed / hardness;
        ctx.writeScalar(playerId, "break_progress", newProgress);

        // Check if block is broken
        if (newProgress >= 1.0) {
            ctx.writeScalar(playerId, "break_progress", 0.0);
            ctx.writeBool(playerId, "block_broken_this_tick", true);
            // Actual block removal is handled by PlayerBreakBlockAction
        }
    }

    private double blockHardness(PlayerTaskContext ctx, WorldPos pos) {
        // Read the world state to get block type
        // For now, return a default based on the block position hash
        // Real impl would query WorldStateManager
        int hash = (pos.x() * 31) ^ (pos.y() * 17) ^ (pos.z() * 7);
        int type = Math.abs(hash % 20);

        // Simplified hardness table:
        // 0: AIR=0 (unbreakable), 1: GRASS=0.6, 2: DIRT=0.5, 3: STONE=1.5
        // 4: COBBLESTONE=2.0, 5: WOOD=2.0, 6: LEAVES=0.2, 7: GLASS=0.3
        // 8: IRON_ORE=3.0, 9: DIAMOND_ORE=3.0, 10: GOLD_ORE=3.0
        // 11: COAL_ORE=3.0, 12: OBSIDIAN=10.0, 13: BEDROCK=-1 (unbreakable)
        double[] hardnessTable = {
            0.0, 0.6, 0.5, 1.5, 2.0, 2.0, 0.2, 0.3,
            3.0, 3.0, 3.0, 3.0, 10.0, -1.0, 1.5, 2.0,
            0.8, 0.4, 3.0, 5.0
        };
        return hardnessTable[type % hardnessTable.length];
    }
}
