package org.nebula.entity;

import org.nebula.core.math.Vec3;
import org.nebula.core.state.EntityField;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-task write buffer for entity physics, backed by versioned reads from
 * {@link EntityPhysicsState}. Mirrors {@code RedstoneStateSnapshot}.
 *
 * <p>Reads capture the field's version; writes accumulate locally until
 * {@link #commit} CAS's each one against its captured version. A version that
 * advanced between read and commit (stale read) makes the commit report the
 * failed fields so the caller can re-execute the task.
 */
public final class EntityStateSnapshot {

    private final Map<EntityField, Long> readVersions = new LinkedHashMap<>();
    private final Map<EntityField, Object> pending = new LinkedHashMap<>();
    private boolean committed = false;

    public Vec3 readVec(EntityPhysicsState state, EntityField field) {
        checkNotCommitted();
        readVersions.putIfAbsent(field, state.readStamp(field).version());
        return state.getVec(field);
    }

    public double readScalar(EntityPhysicsState state, EntityField field) {
        checkNotCommitted();
        readVersions.putIfAbsent(field, state.readStamp(field).version());
        return state.getScalar(field);
    }

    /** Buffers a write. Not applied until {@link #commit}. */
    public void write(EntityField field, Object value) {
        checkNotCommitted();
        pending.put(field, value);
    }

    /**
     * Attempts to commit all buffered writes to {@code state} via CAS.
     *
     * @return a {@link CommitResult}; success means every field committed
     */
    public CommitResult commit(EntityPhysicsState state) {
        checkNotCommitted();
        committed = true;

        Map<EntityField, Long> failed = new LinkedHashMap<>();
        for (var entry : pending.entrySet()) {
            EntityField field = entry.getKey();
            long expectedVersion = readVersions.containsKey(field)
                ? readVersions.get(field)
                : state.getVersion(field);
            if (!state.casCommit(field, expectedVersion, entry.getValue())) {
                failed.put(field, expectedVersion);
            }
        }
        return failed.isEmpty() ? CommitResult.SUCCESS : new CommitResult(false, failed);
    }

    public Set<EntityField> changedFields() {
        return Set.copyOf(new LinkedHashSet<>(pending.keySet()));
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    public boolean isCommitted() {
        return committed;
    }

    private void checkNotCommitted() {
        if (committed) {
            throw new IllegalStateException("Snapshot already committed");
        }
    }

    public record CommitResult(boolean success, Map<EntityField, Long> failedFields) {
        public static final CommitResult SUCCESS = new CommitResult(true, Map.of());

        public CommitResult {
            failedFields = failedFields == null ? Map.of() : Map.copyOf(failedFields);
        }
    }
}
