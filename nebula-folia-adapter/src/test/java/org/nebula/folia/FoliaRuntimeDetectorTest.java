package org.nebula.folia;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Folia runtime detection.
 * Validates that Nebula can detect when running on Folia vs vanilla Paper.
 */
class FoliaRuntimeDetectorTest {

    @Test
    void detectsFoliaWhenRegionizedServerPresent() {
        // In test environment with mock class
        assertTrue(FoliaRuntimeDetector.isFoliaRuntime(getClass().getClassLoader()));
    }

    @Test
    void returnsFalseForIsolatedClassLoader() {
        // An isolated classloader without Folia should return false
        ClassLoader isolatedLoader = new ClassLoader() {};
        assertFalse(FoliaRuntimeDetector.isFoliaRuntime(isolatedLoader));
    }

    @Test
    void usesContextClassLoaderByDefault() {
        // Default call uses Thread context class loader
        assertTrue(FoliaRuntimeDetector.isFoliaRuntime());
    }

    @Test
    void exposesExpectedClassName() {
        assertEquals(
            "io.papermc.paper.threadedregions.RegionizedServer",
            FoliaRuntimeDetector.REGIONIZED_SERVER_CLASS
        );
    }
}
