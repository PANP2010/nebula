package org.nebula.core.player;

import org.nebula.core.math.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Per-task write buffer for player state. Mirrors
 * {@link org.nebula.entity.EntityStateSnapshot}: reads capture field version,
 * writes accumulate locally until {@link #commit} CAS's each one.
 */
public final class PlayerStateSnapshot {

    private final Map<PlayerField, Long> readVersions = new LinkedHashMap<>();
    private final Map<PlayerField, Object> pending = new LinkedHashMap<>();
    private boolean committed = false;

    public Vec3 readVec(PlayerPhysicsState state, PlayerField field) {
        checkNotCommitted();
        readVersions.putIfAbsent(field, state.readStamp(field).version());
        return state.getVec(field);
    }

    public double readScalar(PlayerPhysicsState state, PlayerField field) {
        checkNotCommitted();
        readVersions.putIfAbsent(field, state.readStamp(field).version());
        return state.getScalar(field);
    }

    public boolean readBool(PlayerPhysicsState state, PlayerField field) {
        checkNotCommitted();
        readVersions.putIfAbsent(field, state.readStamp(field).version());
        Object v = state.peek(field) == null ? Boolean.FALSE : state.peek(field).value();
        return (Boolean) v;
    }

    public String readString(PlayerPhysicsState state, PlayerField field) {
        checkNotCommitted();
        readVersions.putIfAbsent(field, state.readStamp(field).version());
        Object v = state.peek(field) == null ? null : state.peek(field).value();
        return v == null ? null : (String) v;
    }

    public void write(PlayerField field, Object value) {
        checkNotCommitted();
        pending.put(field, value);
    }

    public CommitResult commit(PlayerPhysicsState state) {
        checkNotCommitted();
        committed = true;
        Map<PlayerField, Long> failed = new LinkedHashMap<>();
        for (var entry : pending.entrySet()) {
            PlayerField field = entry.getKey();
            long expected = readVersions.containsKey(field)
                ? readVersions.get(field)
                : state.getVersion(field);
            if (!state.casCommit(field, expected, entry.getValue())) {
                failed.put(field, expected);
            }
        }
        return failed.isEmpty() ? CommitResult.SUCCESS : new CommitResult(false, failed);
    }

    public Set<PlayerField> changedFields() { return Set.copyOf(pending.keySet()); }
    public boolean isEmpty() { return pending.isEmpty(); }
    public boolean isCommitted() { return committed; }

    private void checkNotCommitted() {
        if (committed) throw new IllegalStateException("Snapshot already committed");
    }

    public record CommitResult(boolean success, Map<PlayerField, Long> failedFields) {
        public static final CommitResult SUCCESS = new CommitResult(true, Map.of());
        public CommitResult {
            failedFields = failedFields == null ? Map.of() : Map.copyOf(failedFields);
        }
    }
}