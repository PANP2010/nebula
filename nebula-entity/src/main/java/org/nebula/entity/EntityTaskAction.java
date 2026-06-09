package org.nebula.entity;

/**
 * Executable logic for an entity physics task. Mirrors {@code RedstoneTaskAction}:
 * the action reads and writes entity state exclusively through the
 * {@link EntityTaskContext}, so all reads are versioned and all writes are
 * buffered until the layer commits.
 */
@FunctionalInterface
public interface EntityTaskAction {
    void execute(EntityTaskContext ctx) throws Exception;
}
