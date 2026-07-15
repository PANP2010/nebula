package org.nebula.entity;

import org.nebula.core.state.BlockEntityField;

import java.util.Set;

/**
 * Shared mutable state for block entities (arch doc §3.3), keyed by the same
 * {@link BlockEntityField} coordinates that block-entity RW-sets declare.
 *
 * <p>Mirrors {@code EntityPhysicsState} / {@code RedstoneWorldState}: each field
 * carries a monotonically increasing version stamp; readers capture it and
 * writers CAS against it at commit, so stale reads are detected and a layer's
 * writes become visible atomically.
 *
 * <p>Supports two value types:
 * <ul>
 *   <li>{@code int} — inventory slot counts, timers (cook_progress, fuel_time,
 *       transfer_cooldown, brew_time, fuel)</li>
 *   <li>{@code String} — item IDs stored in inventory slots, encoded as
 *       {@code "namespace:item"} or {@code "namespace:item:count"} for combined
 *       item+count (used by dispenser for item-ID-at-slot reads/writes)</li>
 * </ul>
 */
public final class BlockEntityState {

    private final java.util.concurrent.ConcurrentHashMap<BlockEntityField, VersionedEntry> entries =
        new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong globalVersion =
        new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * Variant entry: exactly one of intValue/stringValue is non-null.
     * The version is the committed version.
     */
    public record VersionedEntry(long version, Integer intValue, String stringValue) {}

    public long getVersion(BlockEntityField field) {
        VersionedEntry e = entries.get(field);
        return e == null ? 0 : e.version();
    }

    /** Returns the int value at {@code field}, or 0 if unset or not an int. */
    public int get(BlockEntityField field) {
        VersionedEntry e = entries.get(field);
        return e == null || e.intValue() == null ? 0 : e.intValue();
    }

    /** Returns the string value at {@code field}, or {@code ""} if unset or not a string. */
    public String getString(BlockEntityField field) {
        VersionedEntry e = entries.get(field);
        return e == null || e.stringValue() == null ? "" : e.stringValue();
    }

    /**
     * CAS commit for an integer value.
     *
     * @return true if the write succeeded (version matched), false if stale
     */
    public boolean casCommit(BlockEntityField field, long expectedVersion, int newValue) {
        if (expectedVersion == 0) {
            // Init-style write: insert only if absent
            VersionedEntry desired = new VersionedEntry(1, newValue, null);
            return entries.putIfAbsent(field, desired) == null;
        }
        // CAS: replace only if current version matches expected
        VersionedEntry current = entries.get(field);
        if (current == null || current.version() != expectedVersion) {
            return false;
        }
        VersionedEntry desired = new VersionedEntry(current.version() + 1, newValue, null);
        return entries.replace(field, current, desired);
    }

    /**
     * CAS commit for a string value.
     *
     * @return true if the write succeeded (version matched), false if stale
     */
    public boolean casCommitString(BlockEntityField field, long expectedVersion, String newValue) {
        if (expectedVersion == 0) {
            VersionedEntry desired = new VersionedEntry(1, null, newValue);
            return entries.putIfAbsent(field, desired) == null;
        }
        VersionedEntry current = entries.get(field);
        if (current == null || current.version() != expectedVersion) {
            return false;
        }
        VersionedEntry desired = new VersionedEntry(current.version() + 1, null, newValue);
        return entries.replace(field, current, desired);
    }

    /** Unconditional write for int — initialisation and testing. */
    public void putInt(BlockEntityField field, int value) {
        long v = globalVersion.incrementAndGet();
        entries.put(field, new VersionedEntry(v, value, null));
    }

    /** Convenience overload for {@link #putInt} accepting an int primitive. */
    public void put(BlockEntityField field, int value) {
        putInt(field, value);
    }

    /** Unconditional write for string — initialisation and testing. */
    public void putString(BlockEntityField field, String value) {
        long v = globalVersion.incrementAndGet();
        entries.put(field, new VersionedEntry(v, null, value));
    }

    public int size() {
        return entries.size();
    }

    public Set<BlockEntityField> fields() {
        return Set.copyOf(entries.keySet());
    }

    public void clear() {
        entries.clear();
        globalVersion.set(0);
    }
}
