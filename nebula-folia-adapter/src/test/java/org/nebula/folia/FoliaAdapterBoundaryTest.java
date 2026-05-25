package org.nebula.folia;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FoliaAdapterBoundaryTest {
    @Test
    void exposesTargetFoliaCoordinate() {
        assertEquals("dev.folia:folia-api:[26.1.2.build,)", FoliaAdapterBoundary.TARGET_FOLIA_API);
        assertEquals("26.1.2.build.8-stable", FoliaAdapterBoundary.TARGET_FOLIA_BUILD);
        assertEquals("25", FoliaAdapterBoundary.TARGET_JAVA_TOOLCHAIN);
    }
}
