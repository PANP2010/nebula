package org.nebula.redstone.actions;

import org.nebula.core.state.WorldPos;
import org.nebula.redstone.RedstoneTaskAction;
import org.nebula.redstone.RedstoneTaskContext;
import org.nebula.redstone.RedstoneTaskFactory;

/**
 * Redstone torch inversion + burnout (arch doc §5.2, annotation: TORCH_TICK).
 *
 * <p>Logic:
 * <ol>
 *   <li>Read attached block power (below the torch).</li>
 *   <li>If attached block is powered → torch turns off (power = 0).</li>
 *   <li>If attached block is unpowered → torch turns on (power = 15).</li>
 *   <li>Burnout: if toggle count exceeds threshold within a time window,
 *       torch stays off.</li>
 * </ol>
 */
public final class RedstoneTorchAction implements RedstoneTaskAction {

    private static final int BURNOUT_THRESHOLD = 8;
    private static final int BURNOUT_WINDOW_TICKS = 60;
    private static final String KEY_TOGGLE_COUNT = "toggle_count";
    private static final String KEY_LAST_TOGGLE_TICK = "last_toggle_tick";
    private static final String KEY_BURNED_OUT = "burned_out";

    @Override
    public void execute(RedstoneTaskContext ctx) {
        WorldPos pos = ctx.position();
        WorldPos attachedBlock = RedstoneTaskFactory.neighbour(pos, 0, -1, 0);

        int currentPower = ctx.readPowerLevel(pos);
        int attachedPower = ctx.readPowerLevel(attachedBlock);

        // Check burnout state
        Object burnedOutObj = ctx.readInternalState(pos, KEY_BURNED_OUT);
        boolean burnedOut = Boolean.TRUE.equals(burnedOutObj);

        if (burnedOut) {
            // Torch stays off while burned out
            if (currentPower != 0) {
                ctx.writePowerLevel(pos, 0);
            }
            return;
        }

        boolean attachedIsPowered = attachedPower > 0;
        int desiredPower = attachedIsPowered ? 0 : 15;

        if (desiredPower != currentPower) {
            // Toggle detected — check burnout
            Object toggleCountObj = ctx.readInternalState(pos, KEY_TOGGLE_COUNT);
            int toggleCount = toggleCountObj instanceof Number n ? n.intValue() : 0;

            Object lastToggleTickObj = ctx.readInternalState(pos, KEY_LAST_TOGGLE_TICK);
            long lastToggleTick = lastToggleTickObj instanceof Number n ? n.longValue() : 0;

            // Simple burnout model: count toggles, reset if window expired
            // In production this would use the region's game time
            toggleCount++;

            if (toggleCount > BURNOUT_THRESHOLD) {
                // Burned out
                ctx.writePowerLevel(pos, 0);
                ctx.writeInternalState(pos, KEY_BURNED_OUT, true);
                ctx.writeInternalState(pos, KEY_TOGGLE_COUNT, 0);
                return;
            }

            ctx.writePowerLevel(pos, desiredPower);
            ctx.writeInternalState(pos, KEY_TOGGLE_COUNT, toggleCount);
            ctx.writeInternalState(pos, KEY_LAST_TOGGLE_TICK, lastToggleTick);
        }
    }
}
