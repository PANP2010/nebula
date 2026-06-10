package org.nebula.folia;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Folia runtime detection against the real Folia 26.1.2 API.
 * Validates that Nebula can detect when running on Folia vs vanilla Paper.
 */
class FoliaRuntimeDetectorTest {

    @Test
    void detectsFoliaWhenApiPresent() {
        // The real folia-api jar is on the test classpath, so the API marker
        // (RegionScheduler) resolves → detected as a Folia runtime.
        assertTrue(FoliaRuntimeDetector.isFoliaRuntime(getClass().getClassLoader()));
    }

    @Test
    void returnsFalseWhenApiAbsent() {
        // The platform class loader cannot see the application classpath (and
        // thus not the Folia API), so detection returns false — proving the
        // check is a real classpath probe, not a constant.
        assertFalse(FoliaRuntimeDetector.isFoliaRuntime(ClassLoader.getPlatformClassLoader()));
    }

    @Test
    void usesContextClassLoaderByDefault() {
        // Default call uses the thread context class loader (the app loader in
        // this test JVM, which has the API).
        assertTrue(FoliaRuntimeDetector.isFoliaRuntime());
    }

    @Test
    void isFoliaServerFalseWithApiOnlyClasspath() {
        // RegionizedServer is server-internal — NOT in the API jar — so the
        // stricter server check is false when only the API is present (as in
        // this test). It would be true only on a running Folia server.
        assertFalse(FoliaRuntimeDetector.isFoliaServer(getClass().getClassLoader()));
    }

    @Test
    void exposesExpectedClassNames() {
        assertEquals(
            "io.papermc.paper.threadedregions.scheduler.RegionScheduler",
            FoliaRuntimeDetector.REGION_SCHEDULER_CLASS
        );
        assertEquals(
            "io.papermc.paper.threadedregions.RegionizedServer",
            FoliaRuntimeDetector.REGIONIZED_SERVER_CLASS
        );
    }
}
