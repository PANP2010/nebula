package org.nebula.core.state;

import java.util.Objects;

public record FieldPath(String value) implements Comparable<FieldPath> {
    public FieldPath {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("field path must not be blank");
        }
    }

    public boolean conflictsWith(FieldPath other) {
        if (value.equals(other.value)) {
            return true;
        }
        return hasSegmentPrefix(value, other.value) || hasSegmentPrefix(other.value, value);
    }

    private static boolean hasSegmentPrefix(String candidate, String prefix) {
        return candidate.startsWith(prefix + ".") || candidate.startsWith(prefix + "[");
    }

    @Override
    public int compareTo(FieldPath other) {
        return value.compareTo(other.value);
    }
}
