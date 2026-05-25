package org.nebula.core.state;

public record EntityField(long entityId, FieldPath fieldPath) implements Comparable<EntityField> {
    public EntityField(long entityId, String fieldPath) {
        this(entityId, new FieldPath(fieldPath));
    }

    public boolean conflictsWith(EntityField other) {
        return entityId == other.entityId && fieldPath.conflictsWith(other.fieldPath);
    }

    @Override
    public int compareTo(EntityField other) {
        int byEntity = Long.compare(entityId, other.entityId);
        if (byEntity != 0) {
            return byEntity;
        }
        return fieldPath.compareTo(other.fieldPath);
    }

    public static EntityField parse(String value) {
        String[] parts = value.split(":", 2);
        long id = Long.parseLong(parts[0]);
        String path = parts.length > 1 ? parts[1] : "";
        return new EntityField(id, new FieldPath(path));
    }
}
