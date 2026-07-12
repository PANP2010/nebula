package org.nebula.entity;

import org.nebula.core.state.WorldPos;

/**
 * Fluid microstep propagation engine (arch doc §8.3).
 *
 * <p>Each tick, fluid may advance one block along its slope. This engine models
 * fluid propagation as a DAG where each flow step is a microstep node — fluid
 * advances one direction per tick, accumulating distance traveled until it hits
 * a boundary or exceeds its level budget.
 *
 * <p>Rules:
 * <ol>
 *   <li>Downward always flows if level below < current level</li>
 *   <li>Horizontal flows if level below >= current level and target level < current level</li>
 *   <li>Source blocks never flow (they are infinite sources)</li>
 *   <li>Lava flows 3 blocks then stops; water flows 7 blocks</li>
 *   <li>Fluid cannot flow into solid blocks</li>
 * </ol>
 *
 * <p>This model is observe-only — it tracks what vanilla WOULD do but does not
 * write back to NMS. Microstep fan-out means a single FLOW task can seed
 * multiple downstream FLOW tasks in the same tick.
 */
public final class FluidMicrostepEngine {

    public static final int WATER_MAX_DISTANCE = 7;
    public static final int LAVA_MAX_DISTANCE = 3;

    private FluidMicrostepEngine() {}

    /**
     * Computes the next microstep for a flowing fluid source. Returns the WorldPos
     * that the fluid would flow into next, or null if it cannot flow further.
     */
    public static WorldPos nextFlowTarget(FluidSnapshot current, FluidState state) {
        int dim = current.pos().dimensionId();
        int x = current.pos().x(), y = current.pos().y(), z = current.pos().z();
        int depth = current.depth();
        FluidTaskType fluidType = current.type();
        int maxDist = (fluidType == FluidTaskType.WATER_FLOW) ? WATER_MAX_DISTANCE : LAVA_MAX_DISTANCE;

        if (depth >= maxDist) return null;
        if (current.isSource()) return null; // Sources don't flow

        // Downward: always try first
        WorldPos down = new WorldPos(dim, x, y - 1, z);
        if (canFlowInto(state, down, depth, fluidType)) {
            return down;
        }

        // Horizontal: pick the lowest same-level neighbour (vanilla slope rule)
        WorldPos[] neighbours = {
            new WorldPos(dim, x, y, z + 1),
            new WorldPos(dim, x, y, z - 1),
            new WorldPos(dim, x + 1, y, z),
            new WorldPos(dim, x - 1, y, z)
        };

        WorldPos best = null;
        int bestLevel = Integer.MAX_VALUE;
        for (WorldPos n : neighbours) {
            if (!canFlowInto(state, n, depth, fluidType)) continue;
            int nLevel = levelAt(state, n);
            if (nLevel < bestLevel) {
                bestLevel = nLevel;
                best = n;
            }
        }
        return best;
    }

    /**
     * Returns true if flowing into the target cell would produce a level change.
     */
    public static boolean canFlowInto(FluidState state, WorldPos target, int sourceDepth, FluidTaskType fluidType) {
        Object value = state.get(target);
        if (isSolid(state, target)) return false;

        FluidSnapshot targetSnapshot = asFluidSnapshot(value);
        String targetFluid = fluidTypeOf(targetSnapshot);
        int targetDepth = depthOf(targetSnapshot);

        if (!isSameFluid(targetFluid, fluidType)) {
            // Target is air or different fluid — flow if not below minimum depth (air has depth 0)
            return targetDepth <= 0;
        }
        // Target is same fluid — flow if target is lower
        return targetDepth < sourceDepth;
    }

    /**
     * Computes the resulting level when fluid flows into a target cell.
     */
    public static int computeFlowResult(FluidState state, WorldPos target, int sourceDepth, FluidTaskType fluidType) {
        if (isSolid(state, target)) return 0;

        FluidSnapshot targetSnapshot = asFluidSnapshot(state.get(target));
        if (targetSnapshot != null && isSameFluid(fluidTypeOf(targetSnapshot), fluidType)) {
            // Same fluid — the resulting depth is the MIN of source and target
            return Math.min(sourceDepth, depthOf(targetSnapshot));
        }
        // Flowing into air — produces level-1 of source
        return Math.max(1, sourceDepth - 1);
    }

    /**
     * Returns true if the given block is a fluid source.
     */
    public static boolean isSource(String fluidTypeName) {
        return fluidTypeName.contains("source") || fluidTypeName.contains("spring");
    }

    private static boolean isWater(FluidTaskType type) {
        return type == FluidTaskType.WATER_FLOW;
    }

    private static boolean isSameFluid(FluidTaskType a, FluidTaskType b) {
        return a == b;
    }

    private static boolean isSameFluid(String a, FluidTaskType b) {
        if (a == null || b == null) return false;
        FluidTaskType aType = FluidTaskType.fromTaskType(a);
        return aType != null && aType == b;
    }

    private static boolean isSameFluid(String a, String b) {
        if (a == null || b == null) return false;
        return a.equals(b) || (a.contains("water") && b.contains("water")) ||
               (a.contains("lava") && b.contains("lava"));
    }

    /** Returns the FluidSnapshot value at pos, or null if the cell is not a fluid. */
    private static FluidSnapshot asFluidSnapshot(WorldPos pos, FluidState state) {
        return asFluidSnapshot(state.get(pos));
    }

    private static FluidSnapshot asFluidSnapshot(Object value) {
        return value instanceof FluidSnapshot fs ? fs : null;
    }

    /** Depth of a fluid cell, or 0 for anything that isn't a fluid. */
    private static int depthAt(FluidState state, WorldPos pos) {
        return depthOf(asFluidSnapshot(state.get(pos)));
    }

    private static int depthOf(FluidSnapshot snapshot) {
        return snapshot == null ? 0 : snapshot.depth();
    }

    /** True when the cell holds a value that is NOT a FluidSnapshot (solid) or is absent. */
    private static boolean isSolid(FluidState state, WorldPos pos) {
        Object value = state.get(pos);
        return value != null && !(value instanceof FluidSnapshot);
    }

    /** Fluid task type name at pos, or null if not a fluid. */
    private static String fluidTypeAt(FluidState state, WorldPos pos) {
        return fluidTypeOf(asFluidSnapshot(state.get(pos)));
    }

    private static String fluidTypeOf(FluidSnapshot snapshot) {
        return snapshot == null ? null : snapshot.type().taskType();
    }

    /** Local level helper that bridges old call sites that asked for levelAt. */
    private static int levelAt(FluidState state, WorldPos pos) {
        return depthAt(state, pos);
    }

    /**
     * Source/sink conversion (arch doc §8.2): when a source block loses all
     * inflowing neighbours, it may convert to a flowing block. When a flowing
     * block gains an adjacent source, it may convert to a source.
     */
    public static boolean shouldBecomeSource(FluidSnapshot snapshot, FluidState state) {
        int dim = snapshot.pos().dimensionId();
        int x = snapshot.pos().x(), y = snapshot.pos().y(), z = snapshot.pos().z();
        int depth = snapshot.depth();

        // Count adjacent sources
        WorldPos[] neighbours = {
            new WorldPos(dim, x, y, z + 1),
            new WorldPos(dim, x, y, z - 1),
            new WorldPos(dim, x + 1, y, z),
            new WorldPos(dim, x - 1, y, z),
            new WorldPos(dim, x, y + 1, z),
            new WorldPos(dim, x, y - 1, z)
        };

        int sourceCount = 0;
        for (WorldPos n : neighbours) {
            FluidSnapshot neighbourSnapshot = asFluidSnapshot(n, state);
            if (neighbourSnapshot != null
                && isSource(fluidTypeOf(neighbourSnapshot))
                && depthOf(neighbourSnapshot) >= depth) {
                sourceCount++;
            }
        }
        return sourceCount >= 2;
    }

    /**
     * Fluid reaction with other fluids (arch doc §8.2): lava + water → cobblestone/stone.
     */
    public static String reactionResult(String fluid1, String fluid2) {
        boolean w1 = fluid1.contains("water");
        boolean w2 = fluid2.contains("water");
        boolean l1 = fluid1.contains("lava") || fluid1.contains("magma");
        boolean l2 = fluid2.contains("lava") || fluid2.contains("magma");
        if ((w1 && l2) || (w2 && l1)) {
            return "minecraft:cobblestone"; // fast-flowing lava + water
        }
        return null;
    }
}
