package org.nebula.maintenance.asm;

import org.junit.jupiter.api.Test;
import org.nebula.maintenance.asm.NebulaRWAnnotationPatcher.PatchCandidate;
import org.nebula.maintenance.asm.NebulaRWAnnotationPatcher.PatchCandidate.Note;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link NebulaRWAnnotationPatcher}
 * (Phase 1.5, NEBULA-PATCH-2026-001 §14.3.5 组件 A).
 *
 * <p>The patcher produces conservative placeholder drafts for L0/L1 deltas
 * and skips obvious accessors. The tests pin those behaviours so the human-
 * review workflow can rely on the patcher's output.
 */
class NebulaRWAnnotationPatcherTest {

    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final int PUBLIC = org.objectweb.asm.Opcodes.ACC_PUBLIC;

    private static MethodDelta newMethod(String name, String descriptor) {
        var d = new BytecodeMethodDescriptor(ENTITY, "Entity", name, descriptor, PUBLIC, null);
        return new MethodDelta(ENTITY, name, Optional.empty(), Optional.of(d),
            DiffLevel.NEW, "");
    }

    private static MethodDelta removedMethod(String name, String descriptor) {
        var d = new BytecodeMethodDescriptor(ENTITY, "Entity", name, descriptor, PUBLIC, null);
        return new MethodDelta(ENTITY, name, Optional.of(d), Optional.empty(),
            DiffLevel.REMOVED, "");
    }

    private static MethodDelta descriptorChanged(String name, String oldDesc, String newDesc) {
        var oldD = new BytecodeMethodDescriptor(ENTITY, "Entity", name, oldDesc, PUBLIC, null);
        var newD = new BytecodeMethodDescriptor(ENTITY, "Entity", name, newDesc, PUBLIC, null);
        return new MethodDelta(ENTITY, name, Optional.of(oldD), Optional.of(newD),
            DiffLevel.DESCRIPTOR_CHANGED, "was " + oldDesc);
    }

    @Test
    void draftForL0ContainsTodoPlaceholders() {
        PatchCandidate c = new NebulaRWAnnotationPatcher().draft(newMethod("customName", "(Ljava/lang/String;)V"));
        assertNotNull(c);
        assertEquals(DiffLevel.NEW, c.level());
        assertEquals(Note.HUMAN_REVIEW_REQUIRED, c.note());
        assertTrue(c.annotation().contains("@NebulaRW("));
        // Placeholders — must include TODO to force human review.
        assertTrue(c.annotation().contains("<TODO"),
            "patch must include TODO placeholders so a placeholder can't silently pass review");
        // Reasoning explains why the patch exists.
        assertTrue(c.reasoning().contains("L0"));
    }

    @Test
    void draftForL1IncludesDescriptorDiffInReasoning() {
        PatchCandidate c = new NebulaRWAnnotationPatcher().draft(
            descriptorChanged("calculatePower", "()I", "(J)I"));
        assertNotNull(c);
        assertEquals(DiffLevel.DESCRIPTOR_CHANGED, c.level());
        assertTrue(c.reasoning().contains("()I"),
            "L1 reasoning should call out the prior descriptor so reviewer knows what changed");
    }

    @Test
    void draftForRemovedIsNull() {
        PatchCandidate c = new NebulaRWAnnotationPatcher().draft(
            removedMethod("oldTick", "()V"));
        assertNull(c, "REMOVED deltas should not produce a draft — they need human review, not auto-draft");
    }

    @Test
    void draftSkipsAccessorsByDefault() {
        PatchCandidate c = new NebulaRWAnnotationPatcher().draft(newMethod("getFoo", "()I"));
        assertNull(c, "getter methods are not tick hot paths and should be skipped by default");
    }

    @Test
    void draftIncludesAccessorsWhenOptedIn() {
        PatchCandidate c = new NebulaRWAnnotationPatcher()
            .setSkipAccessors(false)
            .draft(newMethod("getFoo", "()I"));
        assertNotNull(c);
    }

    @Test
    void draftAllEmitsOnePerDraftableDelta() {
        var deltas = List.of(
            newMethod("customName", "(Ljava/lang/String;)V"),
            newMethod("getHealth", "()I"),                 // skipped (accessor)
            descriptorChanged("calculatePower", "()I", "(J)I"),
            removedMethod("oldTick", "()V"));              // skipped (not draftable)

        var patches = new NebulaRWAnnotationPatcher().draftAll(deltas);
        assertEquals(2, patches.size(), "two draftable deltas: customName (NEW) + calculatePower (L1)");
        assertTrue(patches.stream().anyMatch(p -> p.targetMethod().equals("customName")));
        assertTrue(patches.stream().anyMatch(p -> p.targetMethod().equals("calculatePower")));
    }

    @Test
    void accessorDetection() {
        assertTrue(NebulaRWAnnotationPatcher.isAccessor("getX"));
        assertTrue(NebulaRWAnnotationPatcher.isAccessor("isFoo"));
        assertTrue(NebulaRWAnnotationPatcher.isAccessor("hasBar"));
        assertTrue(NebulaRWAnnotationPatcher.isAccessor("canBaz"));
        assertTrue(NebulaRWAnnotationPatcher.isAccessor("shouldQuux"));
        assertTrue(NebulaRWAnnotationPatcher.isAccessor("setFoo"));
        assertTrue(NebulaRWAnnotationPatcher.isAccessor("lambda$tick$0"));
        assertTrue(!NebulaRWAnnotationPatcher.isAccessor("tick"));
        assertTrue(!NebulaRWAnnotationPatcher.isAccessor("customName"));
    }
}