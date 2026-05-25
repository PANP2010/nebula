package org.nebula.core.state;

public record WorldPos(int dimensionId, int x, int y, int z) implements Comparable<WorldPos> {
    @Override
    public int compareTo(WorldPos other) {
        int byDimension = Integer.compare(dimensionId, other.dimensionId);
        if (byDimension != 0) {
            return byDimension;
        }
        int byX = Integer.compare(x, other.x);
        if (byX != 0) {
            return byX;
        }
        int byY = Integer.compare(y, other.y);
        if (byY != 0) {
            return byY;
        }
        return Integer.compare(z, other.z);
    }

    public static WorldPos parse(String value) {
        String[] parts = value.split(",");
        int dim = Integer.parseInt(parts[0]);
        int x = Integer.parseInt(parts[1]);
        int y = Integer.parseInt(parts[2]);
        int z = Integer.parseInt(parts[3]);
        return new WorldPos(dim, x, y, z);
    }
}
