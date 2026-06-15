package org.nebula.entity;

import org.nebula.core.state.BlockEntityField;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared mutable state for block entities (arch doc §3.3), keyed by the same
 * {@link BlockEntityField} coordinates that block-entity RW-sets declare.
 *
 * <p>Mirrors {@code EntityPhysicsState} / {@code RedstoneWorldState}: each field
 * carries a monotonically increasing version stamp; readers capture it and
 * writers CAS against it at commit, so stale reads are detected and a layer's
 * writes become visible atomically.
 *
 * <p>Values are integers — inventory slots hold item counts, and timers
 * (cook progress, fuel time, transfer cooldown) are tick counters. This is a
 * faithful-enough model for deterministic transfer/smelt logic without holding
 * live {@code ItemStack} references.
 */
public final class BlockEntityState {

    private final ConcurrentHashMap<BlockEntityField, VersionedEntry> entries = new ConcurrentHashMap<>();
    private final AtomicLong globalVersion = new AtomicLong(0);

    public record VersionedEntry(int value, long version) {}

    public long getVersion(BlockEntityField field) {
        VersionedEntry e = entries.get(field);
        return e == null ? 0 : e.version();
    }

    /** Returns the value at {@code field}, or 0 if unset. */
    public int get(BlockEntityField field) {
        VersionedEntry e = entries.get(field);
        return e == null ? 0 : e.value();
    }

    /**
     * CAS commit: writes {@code newValue} only if the current version equals
     * {@code expectedVersion}. Returns true on success, false on stale read.
     */
    public boolean casCommit(BlockEntityField field, long expectedVersion, int newValue) {
        long newVersion = globalVersion.incrementAndGet();
        VersionedEntry desired = new VersionedEntry(newValue, newVersion);
        if (expectedVersion == 0) {
            VersionedEntry existing = entries.putIfAbsent(field, desired);
            if (existing == null) {
                return true;
            }
            return existing.version() == expectedVersion && entries.replace(field, existing, desired);
        }
        VersionedEntry current = entries.get(field);
        if (current == null || current.version() != expectedVersion) {
            return false;
        }
        return entries.replace(field, current, desired);
    }

    /** Unconditional write — initialisation and testing. */
    public void put(BlockEntityField field, int value) {
        long v = globalVersion.incrementAndGet();
        entries.put(field, new VersionedEntry(value, v));
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
