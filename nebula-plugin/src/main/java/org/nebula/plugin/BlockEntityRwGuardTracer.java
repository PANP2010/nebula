package org.nebula.plugin;

import org.nebula.core.state.BlockEntityField;
import org.nebula.entity.BlockEntityAccessTracer;
import org.nebula.guard.ThreadLocalAccessTrace;

/**
 * Bridges the live block-entity access tracer to the RW-guard's thread-local trace
 * (B8 C3). The block-entity analogue of {@link RedstoneRwGuardTracer}.
 *
 * <h3>Why this class exists</h3>
 * The RW-guard ({@code nebula-guard-api}) checks a task's actual accesses against its
 * declared {@code RWSet} by reading from {@link ThreadLocalAccessTrace}. But the live
 * block-entity path never populated that trace: block-entity actions record their
 * reads/writes through the {@link BlockEntityAccessTracer} seam on
 * {@code BlockEntityContext}, a seam that until now had no production implementation.
 * So dropping the guard onto the live {@code BlockEntityTaskRunner} as-is would trace
 * <em>nothing</em> and report a false "zero violations" — the exact doc-vs-reality
 * drift this project fights.
 *
 * <p>This tracer is the bridge that makes a live block-entity guard run honest: install
 * it on the runner's {@code BlockEntityContext} and every real slot / timer read or
 * write the hopper/furnace action performs lands in {@link ThreadLocalAccessTrace},
 * where {@code RWSetConsistencyChecker} can compare it against the declared
 * block-entity RW-set.
 *
 * <p><b>Granularity.</b> Access is by {@link BlockEntityField} (position + field path
 * such as {@code inventory.slots[0]}, {@code transfer_cooldown}, {@code cook_progress}),
 * matching the block-entity RW-set the checker compares against — so an undeclared slot
 * or timer access is caught even when the block position itself is declared.
 */
public final class BlockEntityRwGuardTracer extends RwGuardTracer implements BlockEntityAccessTracer {

    /** Shared stateless instance — all state lives in {@link ThreadLocalAccessTrace}. */
    public static final BlockEntityRwGuardTracer INSTANCE = new BlockEntityRwGuardTracer();

    @Override
    public void onFieldRead(BlockEntityField field) {
        ThreadLocalAccessTrace.traceBlockEntityRead(field);
    }

    @Override
    public void onFieldWrite(BlockEntityField field) {
        ThreadLocalAccessTrace.traceBlockEntityWrite(field);
    }
}
