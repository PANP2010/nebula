package org.nebula.entity;

import org.nebula.core.state.WorldPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FluidState {

    private final ConcurrentHashMap<WorldPos, VersionedEntry> entries = new ConcurrentHashMap<>();

    public record VersionedEntry(Object value, long version) {}

    public VersionedEntry read(WorldPos pos) {
        return entries.getOrDefault(pos, new VersionedEntry(null, 0));
    }

    public Object get(WorldPos pos) {
        return read(pos).value();
    }

    public boolean casCommit(WorldPos pos, long expectedVersion, Object value) {
        VersionedEntry current = entries.get(pos);
        if (current == null) {
            if (expectedVersion != 0) {
                return false;
            }
            return value == null
                || entries.putIfAbsent(pos, new VersionedEntry(value, 1)) == null;
        }
        if (current.version() != expectedVersion) {
            return false;
        }
        if (value == null) {
            return entries.remove(pos, current);
        }
        return entries.replace(pos, current, new VersionedEntry(value, current.version() + 1));
    }

    public void put(WorldPos pos, Object value) {
        if (value == null) {
            entries.remove(pos);
            return;
        }
        entries.compute(pos, (ignored, current) ->
            new VersionedEntry(value, current == null ? 1 : current.version() + 1));
    }

    public Map<WorldPos, Object> values() {
        return entries.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
            Map.Entry::getKey, entry -> entry.getValue().value()));
    }
}
