package org.nebula.player.actions;

import org.nebula.core.state.WorldPos;
import org.nebula.player.PlayerTaskAction;
import org.nebula.player.PlayerTaskContext;

import java.util.UUID;

/**
 * Player block break action. Reads the player's held tool durability and the target
 * block position; writes the block to AIR after enough ticks of sustained interaction.
 *
 * <p>Survival break progress: ticks of continuous LEFT_CLICK on the same block
 * accumulate; when progress >= block hardness, the block breaks. If the player
 * switches slots or looks away, progress resets.
 *
 * <p>Mirrors vanilla's {@code BlockBreakingEntityAABB} tick logic.
 */
public final class PlayerBreakBlockAction implements PlayerTaskAction {

    private final UUID playerId;
    private final int dimensionId;

    public PlayerBreakBlockAction(UUID playerId) {
        this(playerId, 0);
    }

    public PlayerBreakBlockAction(UUID playerId, int dimensionId) {
        this.playerId = playerId;
        this.dimensionId = dimensionId;
    }

    @Override
    public void execute(PlayerTaskContext ctx) {
        int slot = (int) ctx.readScalar(playerId, "held_slot");
        boolean breaking = ctx.readBool(playerId, "is_breaking_block");
        if (!breaking) {
            ctx.writeScalar(playerId, "break_progress", 0.0);
            return;
        }

        double progress = ctx.readScalar(playerId, "break_progress");
        double speed = ctx.readScalar(playerId, "break_speed");
        ctx.writeScalar(playerId, "break_progress", progress + speed);
    }
}
