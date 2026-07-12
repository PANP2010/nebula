package org.nebula.entity;

import org.nebula.core.state.BlockEntityField;

import java.util.Set;

/**
 * Per-task write buffer for block-entity state, backed by versioned reads from
 * {@link BlockEntityState}. Mirrors {@code EntityStateSnapshot}: reads capture
 * the field version, writes buffer locally until {@link #commit} CAS's each one.
 *
 * <p>Supports two value types:
 * <ul>
 *   <li>{@code int} — inventory slots, timers, counters (standard block-entity fields)</li>
 *   <li>{@code String} — item IDs (dispenser item-ID-at-slot reads/writes)</li>
 * </ul>
 */
public final class BlockEntitySnapshotState {

    private final java.util.LinkedHashMap<BlockEntityField, Long> readVersions =
        new java.util.LinkedHashMap<>();
    private final java.util.LinkedHashMap<BlockEntityField, Integer> pendingInt =
        new java.util.LinkedHashMap<>();
    private final java.util.LinkedHashMap<BlockEntityField, String> pendingString =
        new java.util.LinkedHashMap<>();
    private boolean committed = false;

    // ── Int reads/writes ───────────────────────────────────────────────────

    /**
     * Reads an integer field. Returns a pending write if one exists,
     * otherwise the committed value from state.
     */
    public int read(BlockEntityState state, BlockEntityField field) {
        checkNotCommitted();
        readVersions.putIfAbsent(field, state.getVersion(field));
        Integer p = pendingInt.get(field);
        if (p != null) return p;
        return state.get(field);
    }

    /** Buffers an integer write. */
    public void write(BlockEntityField field, int value) {
        checkNotCommitted();
        pendingInt.put(field, value);
    }

    // ── String reads/writes ────────────────────────────────────────────────

    /**
     * Reads a string field (e.g. item ID at a slot). Returns a pending write if one
     * exists, otherwise the committed value from state.
     */
    public String readString(BlockEntityState state, BlockEntityField field) {
        checkNotCommitted();
        readVersions.putIfAbsent(field, state.getVersion(field));
        String p = pendingString.get(field);
        if (p != null) return p;
        return state.getString(field);
    }

    /** Buffers a string write. */
    public void writeString(BlockEntityField field, String value) {
        checkNotCommitted();
        pendingString.put(field, value);
    }

    // ── Commit ───────────────────────────────────────────────────────

    /**
     * Attempts to CAS-commit all buffered writes to {@code state}.
     */
    public CommitResult commit(BlockEntityState state) {
        checkNotCommitted();
        committed = true;
        java.util.LinkedHashMap<BlockEntityField, Long> failed =
            new java.util.LinkedHashMap<>();

        for (var entry : pendingInt.entrySet()) {
            BlockEntityField field = entry.getKey();
            long expected = readVersions.containsKey(field)
                ? readVersions.get(field) : state.getVersion(field);
            if (!state.casCommit(field, expected, entry.getValue())) {
                failed.put(field, expected);
            }
        }

        for (var entry : pendingString.entrySet()) {
            BlockEntityField field = entry.getKey();
            long expected = readVersions.containsKey(field)
                ? readVersions.get(field) : state.getVersion(field);
            if (!state.casCommitString(field, expected, entry.getValue())) {
                failed.put(field, expected);
            }
        }

        return failed.isEmpty() ? CommitResult.SUCCESS : new CommitResult(false, failed);
    }

    public Set<BlockEntityField> changedFields() {
        java.util.LinkedHashSet<BlockEntityField> all = new java.util.LinkedHashSet<>();
        all.addAll(pendingInt.keySet());
        all.addAll(pendingString.keySet());
        return Set.copyOf(all);
    }

    public boolean isEmpty() {
        return pendingInt.isEmpty() && pendingString.isEmpty();
    }

    private void checkNotCommitted() {
        if (committed) {
            throw new IllegalStateException("Snapshot already committed");
        }
    }

    public record CommitResult(boolean success,
                              java.util.Map<BlockEntityField, Long> failedFields) {
        public static final CommitResult SUCCESS = new CommitResult(true, java.util.Map.of());
    }
}
