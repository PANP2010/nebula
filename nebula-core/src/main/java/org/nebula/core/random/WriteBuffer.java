package org.nebula.core.random;

import org.nebula.core.state.EntityField;
import org.nebula.core.state.GlobalKey;
import org.nebula.core.state.WorldPos;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shadow execution buffer for entity isolation (arch doc §11.3).
 *
 * <p>During shadow execution, entity task writes go into this buffer rather than
 * directly into shared state.  If actual random consumption {@code C ≤ B}, the
 * buffer is committed.  If {@code C > B}, the buffer is discarded and the task
 * re-executes with an expanded budget.
 *
 * <p>The buffer is intentionally separate from {@link org.nebula.core.scheduler.CASWriteBuffer}
 * — it carries the extra context needed for re-execution (the random seed and call sequence).
 */
public final class WriteBuffer {

    private final Map<WorldPos, Integer> blockWrites = new LinkedHashMap<>();
    private final Map<EntityField, Object> entityWrites = new LinkedHashMap<>();
    private final Map<GlobalKey, Object> globalWrites = new LinkedHashMap<>();
    private boolean committed = false;

    // ── Write API ─────────────────────────────────────────────────────────────

    public void writeBlock(WorldPos pos, int state) {
        checkOpen();
        blockWrites.put(pos, state);
    }

    public void writeEntity(EntityField field, Object value) {
        checkOpen();
        entityWrites.put(field, value);
    }

    public void writeGlobal(GlobalKey key, Object value) {
        checkOpen();
        globalWrites.put(key, value);
    }

    // ── Commit / discard ──────────────────────────────────────────────────────

    /**
     * Commits all buffered writes to the provided state maps.
     * The buffer is sealed after this call.
     */
    public void commit(
        Map<WorldPos, Integer> targetBlocks,
        Map<EntityField, Object> targetEntities,
        Map<GlobalKey, Object> targetGlobals
    ) {
        checkOpen();
        committed = true;
        targetBlocks.putAll(blockWrites);
        targetEntities.putAll(entityWrites);
        targetGlobals.putAll(globalWrites);
    }

    /**
     * Discards all buffered writes without applying them.
     * Used when re-execution is needed due to budget overflow.
     */
    public void discard() {
        checkOpen();
        committed = true;
        blockWrites.clear();
        entityWrites.clear();
        globalWrites.clear();
    }

    // ── Inspection ───────────────────────────────────────────────────────────

    public boolean isCommitted() {
        return committed;
    }

    public boolean isEmpty() {
        return blockWrites.isEmpty() && entityWrites.isEmpty() && globalWrites.isEmpty();
    }

    public Map<WorldPos, Integer> blockWrites() {
        return Map.copyOf(blockWrites);
    }

    public Map<EntityField, Object> entityWrites() {
        return Map.copyOf(entityWrites);
    }

    private void checkOpen() {
        if (committed) {
            throw new IllegalStateException("WriteBuffer already committed or discarded");
        }
    }
}
