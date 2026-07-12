package org.nebula.player.actions;

import org.nebula.core.state.WorldPos;
import org.nebula.player.PlayerTaskAction;
import org.nebula.player.PlayerTaskContext;

import java.util.UUID;

/**
 * Player block place action. Reads the player's held item and the target position;
 * writes the block material at the target, decrements the held stack count.
 *
 * <p>RW-set covers the held inventory slot (stack count decrement) and the target
 * block position. This is the write-back complement of {@link PlayerBreakBlockAction}.
 *
 * <p>The actual block write (setting material) is delegated to
 * {@link org.nebula.folia.NmsBlockStateBridge} via the plugin layer — this action
 * only writes the player-side state.
 */
public final class PlayerPlaceBlockAction implements PlayerTaskAction {

    private final UUID playerId;
    private final WorldPos target;
    private final String material;
    private final int dimensionId;

    public PlayerPlaceBlockAction(UUID playerId, WorldPos target, String material) {
        this(playerId, target, material, 0);
    }

    public PlayerPlaceBlockAction(UUID playerId, WorldPos target, String material, int dimensionId) {
        this.playerId = playerId;
        this.target = target;
        this.material = material;
        this.dimensionId = dimensionId;
    }

    @Override
    public void execute(PlayerTaskContext ctx) {
        int heldSlot = (int) ctx.readScalar(playerId, "held_slot");
        double stackCount = ctx.readScalar(playerId, "slot_" + heldSlot + "_count");
        if (stackCount <= 0) {
            return;
        }
        ctx.writeScalar(playerId, "slot_" + heldSlot + "_count", stackCount - 1);
    }
}
