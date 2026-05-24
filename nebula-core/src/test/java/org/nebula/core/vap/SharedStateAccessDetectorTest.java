package org.nebula.core.vap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SharedStateAccessDetectorTest {

    private SharedStateAccessDetector detector;

    @BeforeEach
    void setUp() {
        detector = new SharedStateAccessDetector();
    }

    @Test
    void noViolationForSamePlugin() {
        detector.recordAccess("pluginA", "MyClass#field1", true);
        detector.recordAccess("pluginA", "MyClass#field1", true);
        assertEquals(0, detector.violationCount());
    }

    @Test
    void detectsWriteWriteViolation() {
        detector.recordAccess("pluginA", "MyClass#health", true);
        detector.recordAccess("pluginB", "MyClass#health", true);
        assertEquals(1, detector.violationCount());

        var v = detector.violations().get(0);
        assertEquals("MyClass#health", v.fieldKey());
        assertEquals("pluginA", v.firstPlugin());
        assertEquals("pluginB", v.secondPlugin());
        assertTrue(v.firstIsWrite());
        assertTrue(v.secondIsWrite());
    }

    @Test
    void detectsReadWriteViolation() {
        detector.recordAccess("pluginA", "Field#x", false);
        detector.recordAccess("pluginB", "Field#x", true);
        assertEquals(1, detector.violationCount());
    }

    @Test
    void detectsWriteReadViolation() {
        detector.recordAccess("pluginA", "Field#x", true);
        detector.recordAccess("pluginB", "Field#x", false);
        assertEquals(1, detector.violationCount());
    }

    @Test
    void noViolationForReadRead() {
        detector.recordAccess("pluginA", "Field#x", false);
        detector.recordAccess("pluginB", "Field#x", false);
        assertEquals(0, detector.violationCount());
    }

    @Test
    void releaseAccessClearsTracking() {
        detector.recordAccess("pluginA", "Field#x", true);
        detector.releaseAccess("Field#x");
        detector.recordAccess("pluginB", "Field#x", true);
        assertEquals(0, detector.violationCount());
    }

    @Test
    void tickResetClearsAllActive() {
        detector.recordAccess("pluginA", "f1", true);
        detector.recordAccess("pluginA", "f2", true);
        detector.tickReset();
        detector.recordAccess("pluginB", "f1", true);
        detector.recordAccess("pluginB", "f2", true);
        assertEquals(0, detector.violationCount());
    }

    @Test
    void disabledDetectorRecordsNothing() {
        detector.setEnabled(false);
        detector.recordAccess("pluginA", "Field#x", true);
        detector.recordAccess("pluginB", "Field#x", true);
        assertEquals(0, detector.violationCount());
    }

    @Test
    void clearViolationsResetsCount() {
        detector.recordAccess("pluginA", "f", true);
        detector.recordAccess("pluginB", "f", true);
        assertEquals(1, detector.violationCount());
        detector.clearViolations();
        assertEquals(0, detector.violationCount());
    }

    @Test
    void multipleFieldsTrackedIndependently() {
        detector.recordAccess("pluginA", "field1", true);
        detector.recordAccess("pluginB", "field2", true);
        assertEquals(0, detector.violationCount());
    }

    @Test
    void violationToStringIsReadable() {
        detector.recordAccess("Auth", "Player#health", true);
        detector.recordAccess("Combat", "Player#health", false);

        String str = detector.violations().get(0).toString();
        assertTrue(str.contains("Player#health"));
        assertTrue(str.contains("Auth"));
        assertTrue(str.contains("Combat"));
        assertTrue(str.contains("WRITE"));
        assertTrue(str.contains("READ"));
    }
}
