package org.nebula.redstone.actions;

import org.nebula.core.state.WorldPos;
import org.nebula.redstone.RedstoneTaskAction;
import org.nebula.redstone.RedstoneTaskContext;
import org.nebula.redstone.RedstoneTaskFactory;

/**
 * Redstone wire signal propagation (arch doc §5.2, annotation: WIRE_NEIGHBOR_CHANGED).
 *
 * <p>Logic:
 * <ol>
 *   <li>Read power levels from 6 adjacent blocks.</li>
 *   <li>Compute max incoming signal. Subtract 1 for signal decay.</li>
 *   <li>If new power != current power, write new power to self.</li>
 * </ol>
 *
 * <p>In vanilla, wire also carries "strong" vs "weak" power and considers
 * connection directions. This implementation models the core propagation rule;
 * directional connections will be added when we integrate with Folia block state.
 */
public final class RedstoneWireAction implements RedstoneTaskAction {

    @Override
    public void execute(RedstoneTaskContext ctx) {
        WorldPos pos = ctx.position();
        int currentPower = ctx.readPowerLevel(pos);

        int maxNeighbour = 0;
        maxNeighbour = Math.max(maxNeighbour, readNeighbour(ctx, pos, 0, 0, -1));  // north
        maxNeighbour = Math.max(maxNeighbour, readNeighbour(ctx, pos, 0, 0, 1));   // south
        maxNeighbour = Math.max(maxNeighbour, readNeighbour(ctx, pos, -1, 0, 0));  // west
        maxNeighbour = Math.max(maxNeighbour, readNeighbour(ctx, pos, 1, 0, 0));   // east
        maxNeighbour = Math.max(maxNeighbour, readNeighbour(ctx, pos, 0, -1, 0));  // below
        maxNeighbour = Math.max(maxNeighbour, readNeighbour(ctx, pos, 0, 1, 0));   // above

        // Signal decays by 1 per block of wire
        int newPower = Math.max(0, maxNeighbour - 1);

        if (newPower != currentPower) {
            ctx.writePowerLevel(pos, newPower);
        }
    }

    private int readNeighbour(RedstoneTaskContext ctx, WorldPos pos, int dx, int dy, int dz) {
        WorldPos neighbour = RedstoneTaskFactory.neighbour(pos, dx, dy, dz);
        int level = ctx.readPowerLevel(neighbour);
        return level < 0 ? 0 : level;
    }
}
