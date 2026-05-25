package org.nebula.redstone.actions;

import org.nebula.core.state.WorldPos;
import org.nebula.redstone.RedstoneTaskAction;
import org.nebula.redstone.RedstoneTaskContext;
import org.nebula.redstone.RedstoneTaskFactory;

/**
 * Comparator tick logic (arch doc §5.2, annotation: COMPARATOR_TICK).
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>Compare:</b> output = front signal if front >= max(side1, side2), else 0</li>
 *   <li><b>Subtract:</b> output = max(0, front - max(side1, side2))</li>
 * </ul>
 *
 * <p>The comparator can also read from a container block entity behind it
 * (e.g. chest fill level). That integration requires the Folia block entity
 * layer; for now we model it as a power level read from the input position.
 */
public final class ComparatorAction implements RedstoneTaskAction {

    private static final String KEY_MODE = "mode";
    private static final String MODE_SUBTRACT = "subtract";

    @Override
    public void execute(RedstoneTaskContext ctx) {
        WorldPos pos = ctx.position();
        WorldPos inputPos = RedstoneTaskFactory.neighbour(pos, 0, 0, -1);   // front input
        WorldPos sideLeft = RedstoneTaskFactory.neighbour(pos, -1, 0, 0);   // left side
        WorldPos sideRight = RedstoneTaskFactory.neighbour(pos, 1, 0, 0);   // right side
        WorldPos outputPos = RedstoneTaskFactory.neighbour(pos, 0, 0, 1);   // output

        int frontSignal = clamp(ctx.readPowerLevel(inputPos));
        int leftSignal = clamp(ctx.readPowerLevel(sideLeft));
        int rightSignal = clamp(ctx.readPowerLevel(sideRight));
        int currentOutput = ctx.readPowerLevel(pos);
        ctx.readPowerLevel(outputPos); // capture version for CAS write

        int maxSide = Math.max(leftSignal, rightSignal);

        // Determine mode
        Object modeObj = ctx.readInternalState(pos, KEY_MODE);
        boolean subtractMode = MODE_SUBTRACT.equals(modeObj);

        int newOutput;
        if (subtractMode) {
            newOutput = Math.max(0, frontSignal - maxSide);
        } else {
            // Compare mode: pass front signal only if it's >= side
            newOutput = frontSignal >= maxSide ? frontSignal : 0;
        }

        if (newOutput != currentOutput) {
            ctx.writePowerLevel(pos, newOutput);
            ctx.writePowerLevel(outputPos, newOutput);
        }
    }

    private static int clamp(int level) {
        return level < 0 ? 0 : Math.min(level, 15);
    }
}
