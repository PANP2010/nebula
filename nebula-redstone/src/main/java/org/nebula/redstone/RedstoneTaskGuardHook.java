package org.nebula.redstone;

import org.nebula.core.scheduler.TaskNode;

/**
 * Per-task observation hook fired by {@link RedstoneTaskRunner} at the exact
 * {@code run(TaskNode)} boundary, so a caller (e.g. the RW-guard bridge in
 * {@code nebula-plugin}) can bracket each task's execution.
 *
 * <h3>Why the boundary is here and not around the whole tick</h3>
 * The RW-guard compares a task's <em>actual</em> block accesses against <em>that
 * task's</em> declared {@code RWSet}. Those accesses accumulate in a thread-local
 * trace ({@code ThreadLocalAccessTrace}), which is per-thread, not per-task — so a
 * guard must reset the trace immediately before a task runs and snapshot it
 * immediately after, or a later task's accesses would be mis-attributed to an
 * earlier task's RW-set. {@link RedstoneTaskRunner#run(TaskNode)} is the only place
 * with both the {@link TaskNode} (hence its declared RW-set) and the exact
 * execution window in hand.
 *
 * <p>This interface lives in {@code nebula-redstone} (which has no dependency on
 * {@code nebula-guard-api}) purely as a callback seam; the guard-aware
 * implementation lives in {@code nebula-plugin}, the only module that sees both the
 * redstone access tracer and the guard's thread-local trace. When no hook is
 * installed the runner behaves exactly as before (zero overhead).
 *
 * <h3>Compound (SCC-contracted) tasks</h3>
 * A contracted wire line is dispatched as a single {@code COMPOUND_SCC} task whose
 * declared RW-set is the <em>merge</em> of its members' sets. The hook fires once
 * for the compound with that merged set, and the trace it brackets is the
 * <em>union</em> of the members' accesses — so union-vs-merge is a sound check for a
 * "zero violations" confirmation (a union access absent from the merged declaration
 * is still a real, flaggable violation). Per-member detection power is separately
 * unit-proven in {@code RedstoneRwGuardBridgeTest}.
 */
public interface RedstoneTaskGuardHook {

    /** Called immediately before {@code task}'s action runs. */
    void beforeTask(TaskNode task);

    /** Called immediately after {@code task}'s action completes (whether or not it threw). */
    void afterTask(TaskNode task);
}
