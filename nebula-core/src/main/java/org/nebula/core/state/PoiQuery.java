package org.nebula.core.state;

public record PoiQuery(int dimensionId, WorldPos center, int radius, String poiType) {
    public PoiQuery {
        if (radius < 0) {
            throw new IllegalArgumentException("radius must be non-negative");
        }
    }
}
