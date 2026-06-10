package org.nebula.entity;

import org.nebula.core.state.BlockEntityField;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Per-task write buffer for block-entity state, backed by versioned reads from
 * {@link BlockEntityState}. Mirrors {@code EntityStateSnapshot}: reads capture
 * the field version, writes buffer locally until {@link #commit} CAS's each one.
 */
public final class BlockEntitySnapshotState {

    private final Map<BlockEntityField, Long> readVersions = new LinkedHashMap<>();
    private final Map<BlockEntityField, Integer> pending = new LinkedHashMap<>();
    private boolean committed = false;

    public int read(BlockEntityState state, BlockEntityField field) {
        checkNotCommitted();
        readVersions.putIfAbsent(field, state.getVersion(field));
        // A pending write shadows the committed value for read-after-write.
        Integer p = pending.get(field);
        return p != null ? p : state.get(field);
    }

    public void write(BlockEntityField field, int value) {
        checkNotCommitted();
        pending.put(field, value);
    }

    public CommitResult commit(BlockEntityState state) {
        checkNotCommitted();
        committed = true;
        Map<BlockEntityField, Long> failed = new LinkedHashMap<>();
        for (var entry : pending.entrySet()) {
            BlockEntityField field = entry.getKey();
            long expected = readVersions.containsKey(field)
                ? readVersions.get(field)
                : state.getVersion(field);
            if (!state.casCommit(field, expected, entry.getValue())) {
                failed.put(field, expected);
            }
        }
        return failed.isEmpty() ? CommitResult.SUCCESS : new CommitResult(false, failed);
    }

    public Set<BlockEntityField> changedFields() {
        return Set.copyOf(pending.keySet());
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    private void checkNotCommitted() {
        if (committed) {
            throw new IllegalStateException("Snapshot already committed");
        }
    }

    public record CommitResult(boolean success, Map<BlockEntityField, Long> failedFields) {
        public static final CommitResult SUCCESS = new CommitResult(true, Map.of());
        public CommitResult {
            failedFields = failedFields == null ? Map.of() : Map.copyOf(failedFields);
        }
    }
}
