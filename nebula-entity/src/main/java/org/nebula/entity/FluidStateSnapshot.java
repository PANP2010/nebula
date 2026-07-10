package org.nebula.entity;

import org.nebula.core.state.WorldPos;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-task versioned read set and buffered write set for fluid actions. */
final class FluidStateSnapshot {

    private final Map<WorldPos, Long> readVersions = new LinkedHashMap<>();
    private final Map<WorldPos, Object> pending = new LinkedHashMap<>();

    Object read(FluidState state, WorldPos pos) {
        FluidState.VersionedEntry entry = state.read(pos);
        readVersions.putIfAbsent(pos, entry.version());
        return entry.value();
    }

    void write(WorldPos pos, Object value) {
        pending.put(pos, value);
    }

    boolean commit(FluidState state) {
        for (var entry : pending.entrySet()) {
            long expected = readVersions.getOrDefault(entry.getKey(), state.read(entry.getKey()).version());
            if (!state.casCommit(entry.getKey(), expected, entry.getValue())) {
                return false;
            }
        }
        return true;
    }
}
