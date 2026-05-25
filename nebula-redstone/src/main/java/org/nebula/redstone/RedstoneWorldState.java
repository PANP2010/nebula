package org.nebula.redstone;

import org.nebula.core.state.WorldPos;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared mutable world state for redstone signal levels and component internals.
 *
 * Each position carries a monotonically increasing version stamp. Readers capture
 * the version at read time; writers CAS against that version at commit time.
 * If the version advanced between read and commit, the write is rejected (stale read).
 *
 * Thread-safe: multiple DAG tasks may read concurrently; writes are serialised
 * per-position via CAS on the versioned entry.
 */
public final class RedstoneWorldState {

    private final ConcurrentHashMap<WorldPos, VersionedEntry> entries = new ConcurrentHashMap<>();
    private final AtomicLong globalVersion = new AtomicLong(0);

    public record VersionedEntry(int powerLevel, Map<String, Object> internalState, long version) {
        public VersionedEntry {
            internalState = internalState == null ? Map.of() : Map.copyOf(internalState);
        }
    }

    public record ReadStamp(WorldPos pos, long version) {}

    /**
     * Reads the current power level at {@code pos}, returning -1 if unset.
     * Also returns the version for stale-read detection.
     */
    public ReadStamp readPowerLevel(WorldPos pos) {
        VersionedEntry entry = entries.get(pos);
        if (entry == null) {
            return new ReadStamp(pos, 0);
        }
        return new ReadStamp(pos, entry.version());
    }

    public int getPowerLevel(WorldPos pos) {
        VersionedEntry entry = entries.get(pos);
        return entry == null ? -1 : entry.powerLevel();
    }

    public Object getInternalState(WorldPos pos, String key) {
        VersionedEntry entry = entries.get(pos);
        if (entry == null) return null;
        return entry.internalState().get(key);
    }

    public long getVersion(WorldPos pos) {
        VersionedEntry entry = entries.get(pos);
        return entry == null ? 0 : entry.version();
    }

    /**
     * Attempts a CAS commit: writes {@code newPowerLevel} and {@code newInternalState}
     * at {@code pos} only if the current version equals {@code expectedVersion}.
     *
     * @return true if the commit succeeded, false if the version has advanced (stale read)
     */
    public boolean casCommit(WorldPos pos, long expectedVersion, int newPowerLevel,
                             Map<String, Object> newInternalState) {
        long newVersion = globalVersion.incrementAndGet();
        VersionedEntry desired = new VersionedEntry(newPowerLevel, newInternalState, newVersion);

        if (expectedVersion == 0) {
            // Position was absent at read time — insert only if still absent
            VersionedEntry existing = entries.putIfAbsent(pos, desired);
            if (existing == null) {
                return true;
            }
            // Someone else inserted — check if their version matches our expectation
            return existing.version() == expectedVersion
                && entries.replace(pos, existing, desired);
        }

        VersionedEntry current = entries.get(pos);
        if (current == null || current.version() != expectedVersion) {
            return false;
        }
        return entries.replace(pos, current, desired);
    }

    /**
     * Unconditional write — used for initialization and testing.
     */
    public void put(WorldPos pos, int powerLevel, Map<String, Object> internalState) {
        long v = globalVersion.incrementAndGet();
        entries.put(pos, new VersionedEntry(powerLevel, internalState, v));
    }

    public void putPowerLevel(WorldPos pos, int powerLevel) {
        VersionedEntry existing = entries.get(pos);
        long v = globalVersion.incrementAndGet();
        Map<String, Object> state = existing == null ? Map.of() : existing.internalState();
        entries.put(pos, new VersionedEntry(powerLevel, state, v));
    }

    public int size() {
        return entries.size();
    }

    public Set<WorldPos> positions() {
        return Set.copyOf(entries.keySet());
    }

    public void clear() {
        entries.clear();
        globalVersion.set(0);
    }
}
