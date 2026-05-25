package org.nebula.folia.bridge;

public record FoliaRegionContext(String worldName, int regionX, int regionZ) {
    public FoliaRegionContext {
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("worldName must not be blank");
        }
    }
}
