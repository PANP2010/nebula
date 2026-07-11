package org.nebula.plugin;

import org.nebula.core.state.WorldPos;
import org.nebula.guard.ThreadLocalAccessTrace;
import org.nebula.redstone.RedstoneAccessTracer;

/**
 * Bridges the live redstone access tracer to the RW-guard's thread-local trace.
 *
 * <h3>Why this class exists (B8 task B2's load-bearing seam)</h3>
 * The RW-guard ({@code nebula-guard-api}) checks a task's actual accesses against
 * its declared {@code RWSet} by reading from {@link ThreadLocalAccessTrace}. But
 * the live redstone path never populates that trace: redstone actions record their
 * reads/writes through the {@link RedstoneAccessTracer} hook on
 * {@code RedstoneTaskContext}, a hook that until now had no production
 * implementation — only the guard's own test doubles fed
 * {@link ThreadLocalAccessTrace} directly.
 *
 * <p>Worse, {@code RWGuardTaskRunner} traces {@code task.action().execute()}, but
 * every live redstone {@code TaskNode} is built via
 * {@code RedstoneTaskFactory.inert(...)} with a no-op action — the real logic runs
 * through {@code RedstoneTaskRunner} + the action registry. So dropping the guard
 * runner into the live executor as-is would trace <em>nothing</em> and report a
 * false "zero violations" — the exact doc-vs-reality drift this project fights.
 *
 * <p>This tracer is the bridge that makes a live guard run honest: install it on
 * the {@code RedstoneTaskContext} and every real block read/write the redstone
 * action performs lands in {@link ThreadLocalAccessTrace}, where
 * {@code RWSetConsistencyChecker} can compare it against the declared redstone
 * RW-set. Block-entity/entity/global/random accesses are not yet routed through
 * {@code RedstoneTaskContext}, so they are out of scope here — this covers exactly
 * the block-level reads/writes the redstone actions actually perform today.
 */
public final class RedstoneRwGuardTracer extends RwGuardTracer implements RedstoneAccessTracer {

    /** Shared stateless instance — all state lives in {@link ThreadLocalAccessTrace}. */
    public static final RedstoneRwGuardTracer INSTANCE = new RedstoneRwGuardTracer();

    @Override
    public void onBlockRead(WorldPos pos) {
        ThreadLocalAccessTrace.traceBlockRead(pos);
    }

    @Override
    public void onBlockWrite(WorldPos pos) {
        ThreadLocalAccessTrace.traceBlockWrite(pos);
    }
}
