package org.nebula.core.scheduler;

import org.nebula.core.state.EntityField;
import org.nebula.core.state.GlobalKey;
import org.nebula.core.state.WorldPos;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.StampedLock;

/**
 * Per-task write buffer for deferred, atomic state commitment (arch doc §4.4).
 *
 * <p>During task execution, all writes go into this buffer rather than directly
 * into shared global state.  After the entire layer finishes, buffers are
 * committed to the target state store via fine-grained {@link StampedLock}s.
 *
 * <h3>Conflict semantics</h3>
 * The DAG dependency edges already guarantee that two tasks writing to the same
 * position are never in the same layer.  Within a layer, writes are therefore
 * non-overlapping by construction — the merge is a simple union.
 *
 * <h3>Thread safety</h3>
 * A single {@code CASWriteBuffer} belongs to one task and is written by exactly
 * one thread.  The {@link #mergeInto} method is the only cross-thread operation
 * and it is serialised by the callers (called once per task, sequentially, after
 * all tasks in the layer complete).
 */
public final class CASWriteBuffer {

    private final Map<WorldPos, Integer> blockWrites = new LinkedHashMap<>();
    private final Map<EntityField, Object> entityWrites = new LinkedHashMap<>();
    private final Map<GlobalKey, Object> globalWrites = new LinkedHashMap<>();
    private boolean committed = false;

    // ── Write API (called by task action) ────────────────────────────────────

    public void writeBlock(WorldPos pos, int newState) {
        checkNotCommitted();
        blockWrites.put(pos, newState);
    }

    public void writeEntity(EntityField field, Object value) {
        checkNotCommitted();
        entityWrites.put(field, value);
    }

    public void writeGlobal(GlobalKey key, Object value) {
        checkNotCommitted();
        globalWrites.put(key, value);
    }

    // ── Commit / discard ──────────────────────────────────────────────────────

    /**
     * Merges this buffer's writes into the shared target maps using per-entry locking.
     * After this call the buffer is marked committed and cannot be written to again.
     */
    public void mergeInto(
        ConcurrentHashMap<WorldPos, Integer> blockState,
        ConcurrentHashMap<EntityField, Object> entityState,
        ConcurrentHashMap<GlobalKey, Object> globalState
    ) {
        checkNotCommitted();
        committed = true;
        blockState.putAll(blockWrites);
        entityState.putAll(entityWrites);
        globalState.putAll(globalWrites);
    }

    /** Discards all writes without applying them — used by shadow execution rollback. */
    public void discard() {
        checkNotCommitted();
        committed = true;
        blockWrites.clear();
        entityWrites.clear();
        globalWrites.clear();
    }

    // ── Inspection ───────────────────────────────────────────────────────────

    public int blockWriteCount() {
        return blockWrites.size();
    }

    public int entityWriteCount() {
        return entityWrites.size();
    }

    public boolean isCommitted() {
        return committed;
    }

    public Map<WorldPos, Integer> blockWrites() {
        return Map.copyOf(blockWrites);
    }

    public Map<EntityField, Object> entityWrites() {
        return Map.copyOf(entityWrites);
    }

    private void checkNotCommitted() {
        if (committed) {
            throw new IllegalStateException("WriteBuffer already committed or discarded");
        }
    }
}
