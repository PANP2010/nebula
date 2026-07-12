package org.nebula.entity;

import org.nebula.core.state.WorldPos;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared mutable state for explosion sub-DAG (arch doc §9.1), keyed by
 * {@link WorldPos} coordinates that {@link ExplosionTaskFactory}'s RW-sets
 * declare.
 *
 * <p>Mirrors {@code EntityPhysicsState}: each block position carries a
 * monotonically increasing version stamp. Readers capture the version at read
 * time; writers CAS against that version at commit time. If the version
 * advanced between read and commit, the write is rejected (stale read) and the
 * caller retries.
 *
 * <p>Thread-safe: multiple DAG tasks (ray-trace, block-destroy) read
 * concurrently; writes are serialised per-position via CAS on the versioned
 * entry. The values stored are either:
 * <ul>
 *   <li>{@code Boolean} — whether the block is destroyed ({@code true}) or
 *       intact ({@code false}, the default)</li>
 *   <li>{@code Double} — the per-ray impact force at the position</li>
 * </ul>
 *
 * <p>The two fields per position are:
 * <ul>
 *   <li>{@code "destroyed"} — boolean, written by {@code blockDestroy} tasks</li>
 *   <li>{@code "force"} — double, written by {@code rayTrace} tasks</li>
 * </ul>
 */
public final class ExplosionState {

    /**
     * Compound key pairing a block position with a named sub-field.
     * Equality and hashCode consider both the position and the field name so
     * that multiple sub-fields at the same position are distinct entries.
     */
    public record BlockField(WorldPos pos, String field) {
        public BlockField {
            Objects.requireNonNull(pos, "pos");
            Objects.requireNonNull(field, "field");
        }
    }

    private final ConcurrentHashMap<BlockField, VersionedEntry> entries = new ConcurrentHashMap<>();
    private final AtomicLong globalVersion = new AtomicLong(0);

    public record VersionedEntry(Object value, long version) {}

    public record ReadStamp(BlockField field, long version) {}

    /**
     * Reads the version stamp for {@code field} (0 if absent), for stale-read
     * detection.
     */
    public ReadStamp readStamp(BlockField field) {
        VersionedEntry entry = entries.get(field);
        return new ReadStamp(field, entry == null ? 0 : entry.version());
    }

    /**
     * Returns the boolean value at the given position/field, or {@code false}
     * if unset.
     */
    public boolean getDestroyed(WorldPos pos) {
        VersionedEntry e = entries.get(new BlockField(pos, "destroyed"));
        return e != null && Boolean.TRUE.equals(e.value());
    }

    /**
     * Returns the double "force" value at the given position, or {@code 0.0}
     * if unset.
     */
    public double getForce(WorldPos pos) {
        VersionedEntry e = entries.get(new BlockField(pos, "force"));
        return e == null ? 0.0 : (Double) e.value();
    }

    /**
     * Attempts a CAS commit: writes {@code newValue} at {@code field} only if
     * the current version equals {@code expectedVersion}.
     *
     * @return true if committed, false if the version advanced (stale read)
     */
    public boolean casCommit(BlockField field, long expectedVersion, Object newValue) {
        long newVersion = globalVersion.incrementAndGet();
        VersionedEntry desired = new VersionedEntry(newValue, newVersion);

        if (expectedVersion == 0) {
            VersionedEntry existing = entries.putIfAbsent(field, desired);
            if (existing == null) {
                return true;
            }
            return existing.version() == expectedVersion
                && entries.replace(field, existing, desired);
        }

        VersionedEntry current = entries.get(field);
        if (current == null || current.version() != expectedVersion) {
            return false;
        }
        return entries.replace(field, current, desired);
    }

    /** Unconditional write — used for initialisation and testing. */
    public void put(WorldPos pos, String field, Object value) {
        long v = globalVersion.incrementAndGet();
        entries.put(new BlockField(pos, field), new VersionedEntry(value, v));
    }

    /**
     * Convenience: mark a block as destroyed without tracking a version stamp.
     * Used by the explosion snapshot's initial block list population.
     */
    public void markDestroyed(WorldPos pos) {
        put(pos, "destroyed", Boolean.TRUE);
    }

    /**
     * Convenience: record the per-ray force at a position.
     */
    public void setForce(WorldPos pos, double force) {
        put(pos, "force", force);
    }

    public int size() {
        return entries.size();
    }

    public Set<BlockField> fields() {
        return Set.copyOf(entries.keySet());
    }

    public void clear() {
        entries.clear();
        globalVersion.set(0);
    }
}
