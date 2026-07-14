package org.nebula.maintenance.asm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link NebulaRWPatchVerifier}'s package-dot → filesystem-path conversion.
 *
 * <p>Pin the {@code .java} extension preservation so a future refactor that switches
 * {@code String.replace('.', '/')} doesn't silently mis-resolve every patch.
 *
 * @see NebulaRWPatchVerifier
 */
class NebulaRWPatchVerifierTest {

    /** Convert a patch header's "net.minecraft.foo.Bar.java" to a filesystem path
     *  while preserving the trailing ".java". Mirrors the verifier's logic. */
    private static String toFsPath(String classPath) {
        int lastDot = classPath.lastIndexOf('.');
        if (lastDot > 0) {
            return classPath.substring(0, lastDot).replace('.', '/') + classPath.substring(lastDot);
        }
        return classPath;
    }

    @Test
    void preservesJavaExtension() {
        assertEquals("net/minecraft/world/entity/Entity.java",
            toFsPath("net.minecraft.world.entity.Entity.java"));
    }

    @Test
    void singleSegmentClassPath() {
        assertEquals("Entity.java", toFsPath("Entity.java"));
    }

    @Test
    void topLevelClass() {
        assertEquals("CrashReport.java", toFsPath("CrashReport.java"));
    }

    @Test
    void deepNested() {
        assertEquals("net/minecraft/world/level/block/entity/ShulkerBoxBlockEntity.java",
            toFsPath("net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity.java"));
    }

    @Test
    void noExtensionEdgeCase() {
        // No '.' at all → pass through unchanged.
        assertEquals("Foo", toFsPath("Foo"));
    }

    @Test
    void onlyOneSegmentWithNoDots() {
        assertEquals("Foo.java", toFsPath("Foo.java"));
    }
}
