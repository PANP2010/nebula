package org.nebula.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the vertical-only write-back arming predicate. These pin the
 * horizontal-speed gate that separates the verified vertical path (grounded rest /
 * straight fall — Y safe to mirror) from stochastic AI horizontal motion (must be
 * left to Folia), without any live server.
 */
class VerticalWriteBackGateTest {

    @Test
    void groundedRestIsOnVerticalPath() {
        // Resting mob: zero velocity on every axis → on the vertical path.
        assertTrue(VerticalWriteBackGate.onVerticalPath(Vec3.ZERO));
    }

    @Test
    void straightFallIsOnVerticalPath() {
        // Pure free-fall: downward Y velocity, no horizontal component → on the path.
        assertTrue(VerticalWriteBackGate.onVerticalPath(new Vec3(0.0, -0.5, 0.0)));
    }

    @Test
    void largeDownwardVelocityStillOnVerticalPath() {
        // Y magnitude is irrelevant to the gate — only horizontal speed matters.
        assertTrue(VerticalWriteBackGate.onVerticalPath(new Vec3(0.0, -3.92, 0.0)));
    }

    @Test
    void wanderingMobIsNotOnVerticalPath() {
        // A wandering cow moves at ~0.1/tick horizontally — well above the floor.
        assertFalse(VerticalWriteBackGate.onVerticalPath(new Vec3(0.1, -0.08, 0.0)));
    }

    @Test
    void horizontalMotionOnZAlsoFailsTheGate() {
        assertFalse(VerticalWriteBackGate.onVerticalPath(new Vec3(0.0, 0.0, 0.1)));
    }

    @Test
    void knockbackFailsTheGate() {
        // Knockback couples large X and Z velocity — clearly stochastic AI motion.
        assertFalse(VerticalWriteBackGate.onVerticalPath(new Vec3(0.4, 0.3, -0.4)));
    }

    @Test
    void numericJitterBelowFloorIsOnVerticalPath() {
        // Sub-floor horizontal jitter (below HORIZONTAL_SPEED_FLOOR) counts as vertical.
        double tiny = VerticalWriteBackGate.HORIZONTAL_SPEED_FLOOR / 2.0;
        assertTrue(VerticalWriteBackGate.onVerticalPath(new Vec3(tiny, -0.08, 0.0)));
    }

    @Test
    void exactlyAtFloorIsOnVerticalPath() {
        // Boundary: horizontal speed == floor is inclusive (<=).
        double s = VerticalWriteBackGate.HORIZONTAL_SPEED_FLOOR;
        assertTrue(VerticalWriteBackGate.onVerticalPath(new Vec3(s, 0.0, 0.0)));
    }

    @Test
    void justAboveFloorFailsTheGate() {
        double s = VerticalWriteBackGate.HORIZONTAL_SPEED_FLOOR * 1.01;
        assertFalse(VerticalWriteBackGate.onVerticalPath(new Vec3(s, 0.0, 0.0)));
    }
}
