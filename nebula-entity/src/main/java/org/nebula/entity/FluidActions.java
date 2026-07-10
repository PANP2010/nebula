package org.nebula.entity;

import org.nebula.core.state.WorldPos;

/**
 * Pure immediate fluid-spread actions.
 *
 * <p>This models the vanilla depth and direction rules that fit the six-block snapshot:
 * downward flow wins and becomes falling level {@code 8}; otherwise every empty horizontal
 * neighbour receives the next legacy level. It deliberately does not model collision shapes,
 * slope-distance search, source conversion, waterlogging, or lava/water reactions.
 */
public final class FluidActions {

    private FluidActions() {}

    /**
     * Reads self plus every declared flow neighbour, then applies immediate depth/direction spread.
     * Downward flow wins. If down is occupied, fluid spreads to every empty horizontal neighbour
     * while its next level remains within the legacy flowing range {@code 1..7}.
     */
    public static FluidAction flow(FluidSnapshot fluid) {
        return ctx -> {
            ctx.readBlock(fluid.pos());
            Object down = ctx.readBlock(fluid.down());
            Object north = ctx.readBlock(fluid.north());
            Object south = ctx.readBlock(fluid.south());
            Object east = ctx.readBlock(fluid.east());
            Object west = ctx.readBlock(fluid.west());

            if (down == null) {
                ctx.writeBlock(fluid.down(), fallingAt(fluid, fluid.down()));
                return;
            }

            int nextLevel = nextHorizontalLevel(fluid);
            if (nextLevel > 7) {
                return;
            }
            if (north == null) {
                ctx.writeBlock(fluid.north(), flowingAt(fluid, fluid.north(), nextLevel));
            }
            if (south == null) {
                ctx.writeBlock(fluid.south(), flowingAt(fluid, fluid.south(), nextLevel));
            }
            if (east == null) {
                ctx.writeBlock(fluid.east(), flowingAt(fluid, fluid.east(), nextLevel));
            }
            if (west == null) {
                ctx.writeBlock(fluid.west(), flowingAt(fluid, fluid.west(), nextLevel));
            }
        };
    }

    /** Reads and clears the fluid's own block. */
    public static FluidAction remove(FluidSnapshot fluid) {
        return ctx -> {
            ctx.readBlock(fluid.pos());
            ctx.writeBlock(fluid.pos(), null);
        };
    }

    private static int nextHorizontalLevel(FluidSnapshot fluid) {
        if (fluid.isSource() || fluid.depth() >= 8) {
            return 1;
        }
        return fluid.depth() + dropOff(fluid);
    }

    private static int dropOff(FluidSnapshot fluid) {
        if (fluid.type() == FluidTaskType.WATER_FLOW) {
            return 1;
        }
        return fluid.pos().dimensionId() == -1 ? 1 : 2;
    }

    private static FluidSnapshot fallingAt(FluidSnapshot fluid, WorldPos pos) {
        return snapshotAt(fluid, pos, 8);
    }

    private static FluidSnapshot flowingAt(FluidSnapshot fluid, WorldPos pos, int level) {
        return snapshotAt(fluid, pos, level);
    }

    private static FluidSnapshot snapshotAt(FluidSnapshot fluid, WorldPos pos, int level) {
        return fluid.type() == FluidTaskType.WATER_FLOW
            ? FluidSnapshot.water(pos, level, false)
            : FluidSnapshot.lava(pos, level, false);
    }
}
