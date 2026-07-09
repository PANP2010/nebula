package org.nebula.entity;

import org.nebula.core.state.BlockEntityField;

/**
 * Optional hook that observes every block-entity field read/write performed
 * through a {@link BlockEntityContext}. The block-entity analogue of
 * {@code RedstoneAccessTracer}.
 *
 * <p>Used by the RW-guard to verify a task's <em>actual</em> field accesses stay
 * within its declared {@code RWSet}. The guard-aware implementation lives in
 * {@code nebula-plugin} (the only module that sees both this seam and the guard's
 * thread-local trace); when no tracer is installed the context performs no extra
 * work (zero overhead), exactly as the redstone path.
 *
 * <p><b>Granularity.</b> A block-entity access is by {@link BlockEntityField} —
 * a position plus a field path such as {@code inventory.slots[0]},
 * {@code transfer_cooldown} or {@code cook_progress} — not merely a block
 * position. This matches the declared block-entity RW-set the checker compares
 * against ({@code RWSet.declaresBlockEntityRead/Write}), so an undeclared slot or
 * timer access is caught even when the block position itself is declared.
 */
public interface BlockEntityAccessTracer {
    void onFieldRead(BlockEntityField field);

    void onFieldWrite(BlockEntityField field);
}
