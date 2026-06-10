package org.nebula.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetVersionTest {

    @Test
    void anchorsMinecraft1214() {
        assertEquals("1.21.4", TargetVersion.MINECRAFT_VERSION);
        assertEquals("26.1.x", TargetVersion.FOLIA_API_LINE);
    }

    @Test
    void isCurrentMatchesTargetVersion() {
        assertTrue(TargetVersion.isCurrent("1.21.4"));
        assertFalse(TargetVersion.isCurrent("1.21.5"));
        assertFalse(TargetVersion.isCurrent(""));
        assertFalse(TargetVersion.isCurrent("1.20.4"));
    }
}
