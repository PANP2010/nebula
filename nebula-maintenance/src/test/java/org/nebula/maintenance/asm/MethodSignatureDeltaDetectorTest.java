package org.nebula.maintenance.asm;

import org.junit.jupiter.api.Test;
import org.nebula.maintenance.asm.MethodSignatureDeltaDetector.PerClassResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link MethodSignatureDeltaDetector} L0/L1/L2
 * classification (Phase 1.5, NEBULA-PATCH-2026-001 §14.3.5 组件 A).
 *
 * <p>Each test builds two small in-memory {@link BytecodeMethodDescriptor}
 * collections and asserts the delta classification matches the spec. The
 * detector never reads from disk in these tests — it works on the extracted
 * form, which keeps the test code independent of the JVM's classpath layout.
 */
class MethodSignatureDeltaDetectorTest {

    private static BytecodeMethodDescriptor m(String cls, String name, String desc, int access) {
        int slash = cls.lastIndexOf('/');
        String simple = slash < 0 ? cls : cls.substring(slash + 1);
        return new BytecodeMethodDescriptor(cls, simple, name, desc, access, null);
    }

    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final int PUBLIC = org.objectweb.asm.Opcodes.ACC_PUBLIC;
    private static final int PROTECTED = org.objectweb.asm.Opcodes.ACC_PROTECTED;
    private static final int PRIVATE = org.objectweb.asm.Opcodes.ACC_PRIVATE;
    private static final int FINAL = org.objectweb.asm.Opcodes.ACC_FINAL;

    @Test
    void newMethodIsLevel0() {
        // Old has only tick(). New adds customName(String).
        var oldM = m(ENTITY, "tick", "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V", PUBLIC);
        var newM1 = oldM;
        var newM2 = m(ENTITY, "customName", "(Ljava/lang/String;)V", PUBLIC);

        var deltas = new MethodSignatureDeltaDetector()
            .detect(List.of(oldM), List.of(newM1, newM2));

        assertEquals(1, deltas.size(), "one delta for the addition");
        MethodDelta d = deltas.get(0);
        assertEquals(DiffLevel.NEW, d.level());
        assertEquals("customName", d.methodName());
        assertNull(d.oldDescOrNull());
        assertNotNull(d.newDescOrNull());
        assertEquals("(Ljava/lang/String;)V", d.newJvmDescriptor());
    }

    @Test
    void unchangedMethodProducesNoDelta() {
        var tick = m(ENTITY, "tick",
            "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V", PUBLIC);
        var deltas = new MethodSignatureDeltaDetector().detect(List.of(tick), List.of(tick));
        assertTrue(deltas.isEmpty(), "identical method → no delta");
    }

    @Test
    void descriptorChangedIsLevel1() {
        // Old: getHealth()I.  New: getHealth(J)I   (added a long parameter).
        var oldM = m(ENTITY, "getHealth", "()I", PUBLIC);
        var newM = m(ENTITY, "getHealth", "(J)I", PUBLIC);

        var deltas = new MethodSignatureDeltaDetector().detect(List.of(oldM), List.of(newM));
        assertEquals(1, deltas.size());
        MethodDelta d = deltas.get(0);
        assertEquals(DiffLevel.DESCRIPTOR_CHANGED, d.level());
        assertEquals("()I", d.oldJvmDescriptor());
        assertEquals("(J)I", d.newJvmDescriptor());
        assertTrue(d.note().contains("()I"));
    }

    @Test
    void returnTypeChangeIsLevel1() {
        // Old: getHealth()I.  New: getHealth()J.
        var oldM = m(ENTITY, "getHealth", "()I", PUBLIC);
        var newM = m(ENTITY, "getHealth", "()J", PUBLIC);

        var deltas = new MethodSignatureDeltaDetector().detect(List.of(oldM), List.of(newM));
        assertEquals(1, deltas.size());
        assertEquals(DiffLevel.DESCRIPTOR_CHANGED, deltas.get(0).level(),
            "return-type change is a descriptor change");
    }

    @Test
    void visibilityChangeIsLevel2() {
        // Old: tick() is public.  New: tick() is protected.
        var oldM = m(ENTITY, "tick", "()V", PUBLIC);
        var newM = m(ENTITY, "tick", "()V", PROTECTED);

        var deltas = new MethodSignatureDeltaDetector().detect(List.of(oldM), List.of(newM));
        assertEquals(1, deltas.size());
        assertEquals(DiffLevel.VISIBILITY_CHANGED, deltas.get(0).level());
    }

    @Test
    void finalAddedIsNotVisibilityByDefault() {
        // Old: public tick.  New: public final tick.  Static/final toggle is OFF by default.
        var oldM = m(ENTITY, "tick", "()V", PUBLIC);
        var newM = m(ENTITY, "tick", "()V", PUBLIC | FINAL);

        var deltas = new MethodSignatureDeltaDetector().detect(List.of(oldM), List.of(newM));
        assertTrue(deltas.isEmpty(),
            "static/final toggles should not trip a visibility change by default");
    }

    @Test
    void finalAddedIsVisibilityWhenOptedIn() {
        var oldM = m(ENTITY, "tick", "()V", PUBLIC);
        var newM = m(ENTITY, "tick", "()V", PUBLIC | FINAL);

        var deltas = new MethodSignatureDeltaDetector()
            .setTreatStaticFinalAsVisibility(true)
            .detect(List.of(oldM), List.of(newM));
        assertEquals(1, deltas.size());
        assertEquals(DiffLevel.VISIBILITY_CHANGED, deltas.get(0).level());
    }

    @Test
    void removedMethodIsLevel2() {
        var oldM = m(ENTITY, "oldTick", "()V", PUBLIC);

        var deltas = new MethodSignatureDeltaDetector().detect(List.of(oldM), List.of());
        assertEquals(1, deltas.size());
        MethodDelta d = deltas.get(0);
        assertEquals(DiffLevel.REMOVED, d.level());
        assertNotNull(d.oldDescOrNull());
        assertNull(d.newDescOrNull());
    }

    @Test
    void renameHintCollapsesOldNewPairIntoDescriptorChanged() {
        // Old: foo(I)V. New: bar(I)V — same descriptor, rename.
        var oldM = m(ENTITY, "foo", "(I)V", PUBLIC);
        var newM = m(ENTITY, "bar", "(I)V", PUBLIC);

        var detector = new MethodSignatureDeltaDetector()
            .setRenameHint(oldM.identityKey(), newM.identityKey());
        var deltas = detector.detect(List.of(oldM), List.of(newM));
        assertEquals(1, deltas.size());
        // After the rename hint, foo's descriptor is rewritten under bar's key,
        // so the diff sees "bar" in both old and new with the same descriptor.
        // That counts as a descriptor change (because foo→bar is rename, not a
        // strict same-name descriptor change), but at least it is NOT split
        // into REMOVED + NEW.
        assertTrue(deltas.get(0).level() == DiffLevel.DESCRIPTOR_CHANGED
                || deltas.get(0).level() == DiffLevel.NEW,
            "rename hint collapses the pair (descriptor-changed or new)");
    }

    @Test
    void perClassBucketingReportsOwnerClass() {
        var oldTick = m(ENTITY, "tick", "()V", PUBLIC);
        var newTick = m(ENTITY, "tick", "()V", PUBLIC);
        var newCustomName = m(ENTITY, "customName", "(Ljava/lang/String;)V", PUBLIC);

        Map<String, List<BytecodeMethodDescriptor>> oldMap = Map.of(ENTITY, List.of(oldTick));
        Map<String, List<BytecodeMethodDescriptor>> newMap =
            Map.of(ENTITY, List.of(newTick, newCustomName));

        List<PerClassResult> results =
            new MethodSignatureDeltaDetector().detectByClass(oldMap, newMap);
        assertEquals(1, results.size());
        PerClassResult r = results.get(0);
        assertEquals(ENTITY, r.ownerClass());
        assertEquals(1, r.countOf(DiffLevel.NEW));
        assertTrue(r.hasAutoDraftable());
    }

    @Test
    void multipleLevelsCoexistInOneClass() {
        // Old has tick()V and getHealth()I.  New has tick()V (unchanged),
        // getHealth(J)I (descriptor changed), customName(String)V (new),
        // and drops getOldHealth()I (removed).
        var oldTick = m(ENTITY, "tick", "()V", PUBLIC);
        var oldHealth = m(ENTITY, "getHealth", "()I", PUBLIC);
        var oldGone = m(ENTITY, "getOldHealth", "()I", PUBLIC);

        var newTick = oldTick;
        var newHealth = m(ENTITY, "getHealth", "(J)I", PUBLIC);
        var newCustomName = m(ENTITY, "customName", "(Ljava/lang/String;)V", PUBLIC);

        var deltas = new MethodSignatureDeltaDetector()
            .detect(List.of(oldTick, oldHealth, oldGone),
                    List.of(newTick, newHealth, newCustomName));
        assertEquals(3, deltas.size());

        // Group by level for stability-agnostic assertions.
        Map<DiffLevel, Long> byLevel = deltas.stream().collect(
            java.util.stream.Collectors.groupingBy(MethodDelta::level,
                java.util.stream.Collectors.counting()));
        assertEquals(1L, byLevel.getOrDefault(DiffLevel.NEW, 0L));
        assertEquals(1L, byLevel.getOrDefault(DiffLevel.DESCRIPTOR_CHANGED, 0L));
        assertEquals(1L, byLevel.getOrDefault(DiffLevel.REMOVED, 0L));
    }

    @Test
    void diffLevelClassifications() {
        // The numericLevel mapping is part of the public API contract — the
        // source-side ChangeLevel reuses the same L0/L1/L2 numbers.
        assertEquals(0, DiffLevel.NEW.numericLevel());
        assertEquals(1, DiffLevel.DESCRIPTOR_CHANGED.numericLevel());
        assertEquals(2, DiffLevel.VISIBILITY_CHANGED.numericLevel());
        assertEquals(2, DiffLevel.REMOVED.numericLevel());

        // Auto-draftable levels.
        assertTrue(DiffLevel.NEW.isAutoDraftable());
        assertTrue(DiffLevel.DESCRIPTOR_CHANGED.isAutoDraftable());
        assertTrue(!DiffLevel.VISIBILITY_CHANGED.isAutoDraftable());
        assertTrue(!DiffLevel.REMOVED.isAutoDraftable());

        // Human-review levels.
        assertTrue(DiffLevel.VISIBILITY_CHANGED.requiresHumanReview());
        assertTrue(DiffLevel.REMOVED.requiresHumanReview());
        assertTrue(!DiffLevel.NEW.requiresHumanReview());
        assertTrue(!DiffLevel.DESCRIPTOR_CHANGED.requiresHumanReview());
    }
}