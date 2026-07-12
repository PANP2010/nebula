package org.nebula.core.player;

import org.nebula.core.math.Vec3;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared mutable state for players, keyed by {@link PlayerField}.
 *
 * <p>Mirrors {@link org.nebula.entity.EntityPhysicsState}: each field carries a
 * monotonically increasing version stamp. Readers capture the version; writers CAS
 * against it at commit time.
 */
public final class PlayerPhysicsState {

    private final ConcurrentHashMap<PlayerField, VersionedEntry> entries = new ConcurrentHashMap<>();
    private final AtomicLong globalVersion = new AtomicLong(0);

    public record VersionedEntry(Object value, long version) {}

    public record ReadStamp(PlayerField field, long version) {}

    public ReadStamp readStamp(PlayerField field) {
        VersionedEntry entry = entries.get(field);
        return new ReadStamp(field, entry == null ? 0 : entry.version());
    }

    public long getVersion(PlayerField field) {
        VersionedEntry entry = entries.get(field);
        return entry == null ? 0 : entry.version();
    }

    public Vec3 getVec(PlayerField field) {
        VersionedEntry entry = entries.get(field);
        return entry == null ? Vec3.ZERO : (Vec3) entry.value();
    }

    public double getScalar(PlayerField field) {
        VersionedEntry entry = entries.get(field);
        return entry == null ? 0.0 : (Double) entry.value();
    }

    public boolean casCommit(PlayerField field, long expectedVersion, Object newValue) {
        long newVersion = globalVersion.incrementAndGet();
        VersionedEntry desired = new VersionedEntry(newValue, newVersion);
        if (expectedVersion == 0) {
            VersionedEntry existing = entries.putIfAbsent(field, desired);
            if (existing == null) return true;
            return existing.version() == expectedVersion && entries.replace(field, existing, desired);
        }
        VersionedEntry current = entries.get(field);
        if (current == null || current.version() != expectedVersion) return false;
        return entries.replace(field, current, desired);
    }

    public void put(PlayerField field, Object value) {
        long v = globalVersion.incrementAndGet();
        entries.put(field, new VersionedEntry(value, v));
    }

    public int size() { return entries.size(); }

    public Set<PlayerField> fields() { return Set.copyOf(entries.keySet()); }

    public VersionedEntry peek(PlayerField field) { return entries.get(field); }

    public void clear() { entries.clear(); globalVersion.set(0); }
}