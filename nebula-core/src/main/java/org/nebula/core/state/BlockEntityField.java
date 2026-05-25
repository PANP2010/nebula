package org.nebula.core.state;

public record BlockEntityField(WorldPos pos, FieldPath fieldPath) implements Comparable<BlockEntityField> {
    public BlockEntityField(WorldPos pos, String fieldPath) {
        this(pos, new FieldPath(fieldPath));
    }

    public boolean conflictsWith(BlockEntityField other) {
        return pos.equals(other.pos) && fieldPath.conflictsWith(other.fieldPath);
    }

    @Override
    public int compareTo(BlockEntityField other) {
        int byPos = pos.compareTo(other.pos);
        if (byPos != 0) {
            return byPos;
        }
        return fieldPath.compareTo(other.fieldPath);
    }

    public static BlockEntityField parse(String value) {
        String[] posAndPath = value.split("@", 2);
        WorldPos parsedPos = WorldPos.parse(posAndPath[0]);
        String path = posAndPath.length > 1 ? posAndPath[1] : "";
        return new BlockEntityField(parsedPos, new FieldPath(path));
    }
}
