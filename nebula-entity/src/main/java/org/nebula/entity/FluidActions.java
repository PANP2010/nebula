package org.nebula.entity;

import org.nebula.core.state.WorldPos;

/**
 * Pure immediate fluid-spread actions.
 *
 * <p>This models the vanilla depth, direction, and passability rules that fit the six-block
 * snapshot: downward flow wins and becomes falling level {@code 8}; otherwise fluid flows toward
 * the single passable horizontal neighbour with the lowest non-zero fluid level (vanilla's slope
 * selection). It deliberately does not model collision shapes, slope-distance search, source
 * conversion, waterlogging, or lava/water reactions.
 */
public final class FluidActions {

    private FluidActions() {}

    /**
     * Reads self plus every declared flow neighbour, then applies immediate depth/direction spread.
     * Downward flow wins. If down is occupied, fluid spreads toward the single passable horizontal
     * neighbour with the lowest non-zero fluid level (vanilla slope selection). Ties keep the
     * existing fixed-order tiebreak.
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

            // Vanilla slope selection: among neighbours holding the same fluid, pick the single
            // direction whose level is lowest. If no same-fluid neighbour exists, vanilla falls
            // back to a passable air (null) cell in fixed [N, S, E, W] order. Solid non-fluid
            // blocks are non-passable and never selected. The fixed order is the honest
            // deterministic tiebreak when multiple same-level same-fluid neighbours exist.
            WorldPos[] dirs = { fluid.north(), fluid.south(), fluid.east(), fluid.west() };
            Object[] values = { north, south, east, west };
            int bestDir = -1;
            int bestLevel = Integer.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                int nLevel = readFluidLevel(values[i], fluid.type());
                if (nLevel > 0 && nLevel < bestLevel) {
                    bestLevel = nLevel;
                    bestDir = i;
                }
            }
            if (bestDir < 0) {
                for (int i = 0; i < 4; i++) {
                    if (values[i] == null) {
                        bestDir = i;
                        break;
                    }
                }
                if (bestDir < 0) {
                    return;
                }
            }
            ctx.writeBlock(dirs[bestDir], flowingAt(fluid, dirs[bestDir], nextLevel));
        };
    }

    /** Reads and clears the fluid's own block. */
    public static FluidAction remove(FluidSnapshot fluid) {
        return ctx -> {
            ctx.readBlock(fluid.pos());
            ctx.writeBlock(fluid.pos(), null);
        };
    }

    /**
     * Returns the legacy Bukkit fluid level for a value previously read via
     * {@link FluidContext#readBlock(WorldPos)}.
     *
     * <p>Passable non-fluid cells (air) read as {@code null}; passable same-fluid cells expose
     * their {@code depth} via {@link FluidSnapshot#depth()}. Solid non-fluid blocks are not
     * passable and yield {@code 0} so they are skipped by the slope-selection scan.
     */
    private static int readFluidLevel(Object value, FluidTaskType expected) {
        if (value instanceof FluidSnapshot neighbour
            && neighbour.type() == expected) {
            return neighbour.depth();
        }
        return 0;
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
