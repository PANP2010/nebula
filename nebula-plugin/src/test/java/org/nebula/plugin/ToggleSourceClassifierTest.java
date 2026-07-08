package org.nebula.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ToggleSourceClassifier}. Pure string-name classification,
 * no Folia/NMS types — the whole point is that lever/button identity survives the
 * scanner's collapse of those materials into {@code REDSTONE_TORCH}.
 */
class ToggleSourceClassifierTest {

    @Test
    void leverIsToggleSource() {
        assertTrue(ToggleSourceClassifier.isToggleSource("LEVER"));
    }

    @Test
    void stoneAndBlackstoneButtonsAreToggleSources() {
        assertTrue(ToggleSourceClassifier.isToggleSource("STONE_BUTTON"));
        assertTrue(ToggleSourceClassifier.isToggleSource("POLISHED_BLACKSTONE_BUTTON"));
    }

    @Test
    void allWoodenButtonsAreToggleSources() {
        for (String wood : new String[]{
                "OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK",
                "MANGROVE", "CHERRY", "PALE_OAK", "BAMBOO", "CRIMSON", "WARPED"}) {
            String name = wood + "_BUTTON";
            assertTrue(ToggleSourceClassifier.isToggleSource(name), name + " should be a toggle source");
        }
    }

    @Test
    void redstoneTorchIsNotAToggleSource() {
        // The crux: a torch and a lever both register as REDSTONE_TORCH in
        // componentMap, but only the lever is a manual toggle source.
        assertFalse(ToggleSourceClassifier.isToggleSource("REDSTONE_TORCH"));
        assertFalse(ToggleSourceClassifier.isToggleSource("REDSTONE_WALL_TORCH"));
    }

    @Test
    void wireAndOtherComponentsAreNotToggleSources() {
        assertFalse(ToggleSourceClassifier.isToggleSource("REDSTONE_WIRE"));
        assertFalse(ToggleSourceClassifier.isToggleSource("REPEATER"));
        assertFalse(ToggleSourceClassifier.isToggleSource("COMPARATOR"));
        assertFalse(ToggleSourceClassifier.isToggleSource("REDSTONE_BLOCK"));
    }

    @Test
    void eventDrivenInputsAreNotToggleSources() {
        // Observers, pressure plates, and tripwire hooks are redstone inputs, but
        // world-event-driven — not manual flips a deterministic replay drives.
        assertFalse(ToggleSourceClassifier.isToggleSource("OBSERVER"));
        assertFalse(ToggleSourceClassifier.isToggleSource("STONE_PRESSURE_PLATE"));
        assertFalse(ToggleSourceClassifier.isToggleSource("TRIPWIRE_HOOK"));
    }

    @Test
    void nullAndUnknownAreNotToggleSources() {
        assertFalse(ToggleSourceClassifier.isToggleSource(null));
        assertFalse(ToggleSourceClassifier.isToggleSource(""));
        assertFalse(ToggleSourceClassifier.isToggleSource("DIAMOND_BLOCK"));
    }
}
