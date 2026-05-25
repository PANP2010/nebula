package org.nebula.redstone.actions;

import org.nebula.core.state.WorldPos;
import org.nebula.redstone.RedstoneTaskAction;
import org.nebula.redstone.RedstoneTaskContext;
import org.nebula.redstone.RedstoneTaskFactory;

/**
 * Repeater tick logic (arch doc §5.2, annotation: REPEATER_TICK).
 *
 * <p>Logic:
 * <ol>
 *   <li>Read input signal (block behind, relative to facing).</li>
 *   <li>Read internal delay counter and locked state.</li>
 *   <li>If locked (side repeater powering into this one), do nothing.</li>
 *   <li>If input changed from what was captured, decrement delay counter.</li>
 *   <li>When counter reaches 0, commit output = input signal (either 15 or 0).</li>
 * </ol>
 *
 * <p>The repeater's DEFERRED microstep behavior means its output never triggers
 * same-tick propagation. The scheduler defers downstream tasks to the next tick.
 */
public final class RepeaterAction implements RedstoneTaskAction {

    private static final String KEY_DELAY_COUNTER = "delay_counter";
    private static final String KEY_DELAY_SETTING = "delay_setting";
    private static final String KEY_POWERED = "powered";
    private static final String KEY_LOCKED = "locked";

    @Override
    public void execute(RedstoneTaskContext ctx) {
        WorldPos pos = ctx.position();
        WorldPos inputPos = RedstoneTaskFactory.neighbour(pos, 0, 0, -1);  // input side
        WorldPos outputPos = RedstoneTaskFactory.neighbour(pos, 0, 0, 1); // output side

        // Read input signal and capture versions for all write targets
        int inputSignal = ctx.readPowerLevel(inputPos);
        if (inputSignal < 0) inputSignal = 0;
        ctx.readPowerLevel(outputPos); // capture version for CAS write

        // Read internal state
        Object lockedObj = ctx.readInternalState(pos, KEY_LOCKED);
        boolean locked = Boolean.TRUE.equals(lockedObj);

        if (locked) {
            return; // Side repeater holds this one — no state change
        }

        Object poweredObj = ctx.readInternalState(pos, KEY_POWERED);
        boolean currentlyPowered = Boolean.TRUE.equals(poweredObj);
        boolean shouldBePowered = inputSignal > 0;

        if (shouldBePowered == currentlyPowered) {
            return; // No change needed
        }

        // Read delay counter
        Object counterObj = ctx.readInternalState(pos, KEY_DELAY_COUNTER);
        int counter = counterObj instanceof Number n ? n.intValue() : -1;

        if (counter < 0) {
            // First tick of change detection — initialize counter from delay setting
            Object settingObj = ctx.readInternalState(pos, KEY_DELAY_SETTING);
            int delaySetting = settingObj instanceof Number n ? n.intValue() : 1;
            ctx.writeInternalState(pos, KEY_DELAY_COUNTER, delaySetting - 1);
            return;
        }

        if (counter > 0) {
            // Still counting down
            ctx.writeInternalState(pos, KEY_DELAY_COUNTER, counter - 1);
            return;
        }

        // Counter reached 0 — commit the output change
        int outputPower = shouldBePowered ? 15 : 0;
        ctx.writePowerLevel(pos, outputPower);
        ctx.writePowerLevel(outputPos, outputPower);
        ctx.writeInternalState(pos, KEY_POWERED, shouldBePowered);
        ctx.writeInternalState(pos, KEY_DELAY_COUNTER, -1); // reset for next change
    }
}
