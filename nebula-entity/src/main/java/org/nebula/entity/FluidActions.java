package org.nebula.entity;

/** Pure fluid actions used to exercise the declared block footprint before live NMS wiring. */
public final class FluidActions {

    private FluidActions() {}

    /**
     * Reads self plus every declared flow neighbour and writes self plus one selected destination.
     * Downward flow wins; otherwise the first empty horizontal position wins.
     */
    public static FluidAction flow(FluidSnapshot fluid) {
        return ctx -> {
            Object self = ctx.readBlock(fluid.pos());
            Object down = ctx.readBlock(fluid.down());
            Object north = ctx.readBlock(fluid.north());
            Object south = ctx.readBlock(fluid.south());
            Object east = ctx.readBlock(fluid.east());
            Object west = ctx.readBlock(fluid.west());

            ctx.writeBlock(fluid.pos(), self);
            if (down == null) {
                ctx.writeBlock(fluid.down(), self);
            } else if (north == null) {
                ctx.writeBlock(fluid.north(), self);
            } else if (south == null) {
                ctx.writeBlock(fluid.south(), self);
            } else if (east == null) {
                ctx.writeBlock(fluid.east(), self);
            } else if (west == null) {
                ctx.writeBlock(fluid.west(), self);
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
}
