package org.nebula.redstone.actions;

import org.nebula.redstone.RedstoneComponentType;
import org.nebula.redstone.RedstoneTaskAction;

import java.util.Map;

/**
 * Registry of built-in {@link RedstoneTaskAction} implementations for core
 * redstone components. Pass the result of {@link #defaults()} to
 * {@link org.nebula.redstone.RedstoneTaskRunner}.
 */
public final class RedstoneActions {

    private RedstoneActions() {}

    /**
     * Returns the default action map keyed by task type string.
     */
    public static Map<String, RedstoneTaskAction> defaults() {
        return Map.of(
            RedstoneComponentType.REDSTONE_WIRE.taskType(), new RedstoneWireAction(),
            RedstoneComponentType.REDSTONE_TORCH.taskType(), new RedstoneTorchAction(),
            RedstoneComponentType.REPEATER.taskType(), new RepeaterAction(),
            RedstoneComponentType.COMPARATOR.taskType(), new ComparatorAction()
        );
    }
}
