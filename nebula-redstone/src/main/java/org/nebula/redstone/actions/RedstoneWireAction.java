package org.nebula.redstone.actions;

import org.nebula.core.state.WorldPos;
import org.nebula.redstone.RedstoneTaskAction;
import org.nebula.redstone.RedstoneTaskContext;
import org.nebula.redstone.RedstoneTaskFactory;

import java.util.function.Predicate;

/**
 * Redstone wire signal propagation (arch doc §5.2, annotation: WIRE_NEIGHBOR_CHANGED).
 *
 * <p>Signal decay is <em>not</em> uniform across neighbours. Matching vanilla
 * ({@code DefaultRedstoneWireEvaluator.calculateTargetStrength} +
 * {@code RedstoneWireEvaluator.getIncomingWireSignal}):
 * <ol>
 *   <li>A <b>source</b> neighbour (lever, redstone block, torch, powered block —
 *       anything that is NOT itself a wire) feeds the wire its power level
 *       <em>undecayed</em> ({@code blockSignal}).</li>
 *   <li>A <b>wire</b> neighbour feeds a signal that decays by 1 per block
 *       ({@code incomingWireSignal = max(0, maxWireNeighbour - 1)}).</li>
 *   <li>The wire's new power is the max of the two.</li>
 * </ol>
 *
 * <p>The earlier implementation decayed <em>every</em> neighbour by 1, including
 * power sources, which shifted an entire wire line down by one power level
 * relative to Folia ({@code nebula = folia − 1} at every wire — the DG3
 * settled-state off-by-one caught live by SETTLED-DIAG on 2026-07-09). The
 * source-vs-wire distinction is what closes that divergence.
 *
 * <p>Whether a neighbour is a wire is supplied by an injected
 * {@link Predicate}, since the {@link RedstoneTaskContext} tracks only power
 * levels, not component types (which live in the game layer's component map).
 * The no-arg constructor treats every neighbour as a wire, preserving the
 * pure wire→wire decay model for low-level tests that do not model sources.
 */
public final class RedstoneWireAction implements RedstoneTaskAction {

    private static final int[][] NEIGHBOURS = {
        {0, 0, -1},  // north
        {0, 0, 1},   // south
        {-1, 0, 0},  // west
        {1, 0, 0},   // east
        {0, -1, 0},  // below
        {0, 1, 0},   // above
    };

    private final Predicate<WorldPos> isWireNeighbour;

    /**
     * Treats every neighbour as a wire (pure wire→wire decay). Use only where
     * source vs. wire cannot be distinguished; live circuits must inject a real
     * classifier via {@link #RedstoneWireAction(Predicate)}.
     */
    public RedstoneWireAction() {
        this(pos -> true);
    }

    /**
     * @param isWireNeighbour returns true iff the given position is a redstone
     *                        wire; sources (false) feed power undecayed, wires
     *                        (true) decay by 1.
     */
    public RedstoneWireAction(Predicate<WorldPos> isWireNeighbour) {
        this.isWireNeighbour = isWireNeighbour == null ? (pos -> true) : isWireNeighbour;
    }

    @Override
    public void execute(RedstoneTaskContext ctx) {
        WorldPos pos = ctx.position();
        int currentPower = ctx.readPowerLevel(pos);

        int sourceMax = 0;   // undecayed contribution from non-wire (source) neighbours
        int wireMax = 0;     // pre-decay max among wire neighbours
        for (int[] d : NEIGHBOURS) {
            WorldPos neighbour = RedstoneTaskFactory.neighbour(pos, d[0], d[1], d[2]);
            int level = ctx.readPowerLevel(neighbour);
            if (level < 0) {
                level = 0;
            }
            if (isWireNeighbour.test(neighbour)) {
                wireMax = Math.max(wireMax, level);
            } else {
                sourceMax = Math.max(sourceMax, level);
            }
        }

        // Sources feed the wire undecayed; wire-to-wire transfer decays by 1.
        int newPower = Math.max(sourceMax, Math.max(0, wireMax - 1));

        if (newPower != currentPower) {
            ctx.writePowerLevel(pos, newPower);
        }
    }
}
