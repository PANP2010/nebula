package org.nebula.entity;

import org.nebula.core.state.EntityField;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared mutable physics state for entities (arch doc §6.2), keyed by the same
 * {@link EntityField} coordinates that entity RW-sets declare.
 *
 * <p>Mirrors {@code RedstoneWorldState}: each field carries a monotonically
 * increasing version stamp. Readers capture the version at read time; writers
 * CAS against that version at commit time. If the version advanced between read
 * and commit, the write is rejected (stale read) and the caller can retry.
 *
 * <p>Thread-safe: multiple DAG tasks read concurrently; writes are serialised
 * per-field via CAS on the versioned entry. The values stored are {@link Vec3}
 * for vector fields (position, velocity) and {@link Double} for scalar fields
 * (health, etc.); callers use the typed accessors.
 */
public final class EntityPhysicsState {

    private final ConcurrentHashMap<EntityField, VersionedEntry> entries = new ConcurrentHashMap<>();
    private final AtomicLong globalVersion = new AtomicLong(0);

    public record VersionedEntry(Object value, long version) {}

    public record ReadStamp(EntityField field, long version) {}

    /** Reads the version stamp for {@code field} (0 if absent), for stale-read detection. */
    public ReadStamp readStamp(EntityField field) {
        VersionedEntry entry = entries.get(field);
        return new ReadStamp(field, entry == null ? 0 : entry.version());
    }

    public long getVersion(EntityField field) {
        VersionedEntry entry = entries.get(field);
        return entry == null ? 0 : entry.version();
    }

    /** Returns the vector value at {@code field}, or {@link Vec3#ZERO} if unset. */
    public Vec3 getVec(EntityField field) {
        VersionedEntry entry = entries.get(field);
        return entry == null ? Vec3.ZERO : (Vec3) entry.value();
    }

    /** Returns the scalar value at {@code field}, or {@code 0.0} if unset. */
    public double getScalar(EntityField field) {
        VersionedEntry entry = entries.get(field);
        return entry == null ? 0.0 : (Double) entry.value();
    }

    /**
     * Attempts a CAS commit: writes {@code newValue} at {@code field} only if
     * the current version equals {@code expectedVersion}.
     *
     * @return true if committed, false if the version advanced (stale read)
     */
    public boolean casCommit(EntityField field, long expectedVersion, Object newValue) {
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
    public void put(EntityField field, Object value) {
        long v = globalVersion.incrementAndGet();
        entries.put(field, new VersionedEntry(value, v));
    }

    public int size() {
        return entries.size();
    }

    public Set<EntityField> fields() {
        return Set.copyOf(entries.keySet());
    }

    /**
     * Returns the raw versioned entry for {@code field} without copying —
     * used by the per-tick stale-snapshot capture in
     * {@link EntityTaskRunner#commitLayer()}. Returns {@code null} when the
     * field is unset (callers skip it).
     */
    public VersionedEntry peek(EntityField field) {
        return entries.get(field);
    }

    public void clear() {
        entries.clear();
        globalVersion.set(0);
    }
}
