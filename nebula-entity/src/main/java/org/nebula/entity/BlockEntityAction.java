package org.nebula.entity;

/**
 * Executable logic for a block-entity tick task (hopper, furnace, …). Reads and
 * writes block-entity state exclusively through the {@link BlockEntityContext},
 * so all reads are versioned and writes buffer until the layer commits.
 */
@FunctionalInterface
public interface BlockEntityAction {
    void execute(BlockEntityContext ctx) throws Exception;
}
