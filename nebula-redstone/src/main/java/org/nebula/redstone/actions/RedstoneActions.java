package org.nebula.redstone.actions;

import org.nebula.core.state.WorldPos;
import org.nebula.redstone.RedstoneComponentType;
import org.nebula.redstone.RedstoneTaskAction;

import java.util.Map;
import java.util.function.Predicate;

/**
 * Registry of built-in {@link RedstoneTaskAction} implementations for core
 * redstone components. Pass the result of {@link #defaults()} to
 * {@link org.nebula.redstone.RedstoneTaskRunner}.
 */
public final class RedstoneActions {

    private RedstoneActions() {}

    /**
     * Returns the default action map keyed by task type string, treating every
     * wire neighbour as a wire (pure wire→wire decay). Suitable for low-level
     * tests that do not model source blocks; live circuits should use
     * {@link #defaults(Predicate)} so that a wire adjacent to a source takes the
     * source's power undecayed (vanilla parity — see {@link RedstoneWireAction}).
     */
    public static Map<String, RedstoneTaskAction> defaults() {
        return defaults(pos -> true);
    }

    /**
     * Returns the default action map with a source-vs-wire classifier for the
     * wire action.
     *
     * @param isWireNeighbour returns true iff the given position is a redstone
     *                        wire; sources (false) feed power undecayed, wires
     *                        (true) decay by 1
     */
    public static Map<String, RedstoneTaskAction> defaults(Predicate<WorldPos> isWireNeighbour) {
        return Map.of(
            RedstoneComponentType.REDSTONE_WIRE.taskType(), new RedstoneWireAction(isWireNeighbour),
            RedstoneComponentType.REDSTONE_TORCH.taskType(), new RedstoneTorchAction(),
            RedstoneComponentType.REPEATER.taskType(), new RepeaterAction(),
            RedstoneComponentType.COMPARATOR.taskType(), new ComparatorAction()
        );
    }
}
