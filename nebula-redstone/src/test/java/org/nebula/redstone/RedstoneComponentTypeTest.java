package org.nebula.redstone;

import org.junit.jupiter.api.Test;
import org.nebula.annotations.MicroStepBehavior;
import org.nebula.annotations.SccBehavior;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RedstoneComponentTypeTest {

    @Test
    void allTypesHaveUniqueTaskTypeStrings() {
        Set<String> taskTypes = Arrays.stream(RedstoneComponentType.values())
            .map(RedstoneComponentType::taskType)
            .collect(Collectors.toSet());
        assertEquals(RedstoneComponentType.values().length, taskTypes.size(),
            "All taskType strings must be unique");
    }

    @Test
    void totalCountIs26() {
        assertEquals(26, RedstoneComponentType.values().length,
            "Expected 26 redstone component types (9 original + 17 new)");
    }

    @Test
    void allTypesHaveNonNullFields() {
        for (RedstoneComponentType type : RedstoneComponentType.values()) {
            assertNotNull(type.taskType(), type + " has null taskType");
            assertNotNull(type.microStepBehavior(), type + " has null microStepBehavior");
            assertNotNull(type.sccBehavior(), type + " has null sccBehavior");
            assertFalse(type.taskType().isBlank(), type + " has blank taskType");
        }
    }

    @Test
    void deferredSerializedTypesAreConsistent() {
        // DEFERRED+SERIALIZED is the stricter combination — verify the strict ones exist
        long deferredSerialized = Arrays.stream(RedstoneComponentType.values())
            .filter(t -> t.microStepBehavior() == MicroStepBehavior.DEFERRED
                && t.sccBehavior() == SccBehavior.SERIALIZED)
            .count();
        // Pistons, hoppers, dispensers, droppers are DEFERRED+SERIALIZED
        assertTrue(deferredSerialized >= 4, "Expected at least 4 DEFERRED+SERIALIZED types");
    }

    @Test
    void originalNineTypesPresent() {
        Set<String> names = Arrays.stream(RedstoneComponentType.values())
            .map(Enum::name).collect(Collectors.toSet());
        for (String original : new String[]{
            "REDSTONE_WIRE", "REPEATER", "COMPARATOR", "REDSTONE_TORCH",
            "PISTON", "STICKY_PISTON", "OBSERVER", "NOTE_BLOCK", "POWERED_RAIL"
        }) {
            assertTrue(names.contains(original), "Missing original type: " + original);
        }
    }

    @Test
    void newTypesPresent() {
        Set<String> names = Arrays.stream(RedstoneComponentType.values())
            .map(Enum::name).collect(Collectors.toSet());
        for (String newType : new String[]{
            "TRIPWIRE_HOOK", "TRIPWIRE", "REDSTONE_LAMP", "DAYLIGHT_DETECTOR",
            "HOPPER", "DISPENSER", "DROPPER", "TNT", "ACTIVATOR_RAIL",
            "REDSTONE_BLOCK", "LEVER", "BUTTON", "PRESSURE_PLATE",
            "FENCE_GATE", "TRAPDOOR", "IRON_DOOR", "PISTON_HEAD"
        }) {
            assertTrue(names.contains(newType), "Missing new type: " + newType);
        }
    }

    @Test
    void propagatingTypesCanBeContractible() {
        // All PROPAGATES types should allow contraction (they can be in redstone SCCs)
        for (RedstoneComponentType type : RedstoneComponentType.values()) {
            if (type.microStepBehavior() == MicroStepBehavior.PROPAGATES) {
                assertNotEquals(SccBehavior.SERIALIZED, type.sccBehavior(),
                    type + " propagates but is SERIALIZED — would break microstep loops");
            }
        }
    }
}
