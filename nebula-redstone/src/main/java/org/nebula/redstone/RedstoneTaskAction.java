package org.nebula.redstone;

/**
 * A redstone task action that receives a {@link RedstoneTaskContext} for
 * versioned reads and buffered writes.
 */
@FunctionalInterface
public interface RedstoneTaskAction {
    void execute(RedstoneTaskContext context) throws Exception;
}
