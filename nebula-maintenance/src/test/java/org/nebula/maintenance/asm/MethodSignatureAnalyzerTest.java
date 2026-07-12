package org.nebula.maintenance.asm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MethodSignatureAnalyzer} and the supporting
 * {@link AsmClassDiffer} / {@link AnnotationDriftDetector}
 * (Phase 1.5, NEBULA-PATCH-2026-001 §14.3.5 组件 A).
 *
 * <p>The tests build two synthetic {@code .class} byte arrays via
 * {@link SyntheticClass}, run the analyzer on them, and assert the diff
 * classifies each shape correctly. Because the analyzer is a pure
 * function over byte arrays, every test case stays independent and
 * deterministic.
 */
class MethodSignatureAnalyzerTest {

    private static final int PUBLIC = org.objectweb.asm.Opcodes.ACC_PUBLIC;
    private static final int PROTECTED = org.objectweb.asm.Opcodes.ACC_PROTECTED;

    private static SyntheticClass.MethodSpec pub(String name, String desc) {
        return new SyntheticClass.MethodSpec(name, desc, PUBLIC);
    }

    private static SyntheticClass.MethodSpec prot(String name, String desc) {
        return new SyntheticClass.MethodSpec(name, desc, PROTECTED);
    }

    @Test
    void sameMethodProducesNoDrift() {
        byte[] a = SyntheticClass.singleMethodClass(
            "com/example/Foo", "tick", "()V", PUBLIC);
        byte[] b = SyntheticClass.singleMethodClass(
            "com/example/Foo", "tick", "()V", PUBLIC);

        List<SignatureDrift> drifts = new MethodSignatureAnalyzer().detect(a, b);
        assertTrue(drifts.isEmpty(),
            "identical methods on both sides should produce an empty drift list");
    }

    @Test
    void addedMethodIsReportedAsAdded() {
        byte[] a = SyntheticClass.singleMethodClass(
            "com/example/Foo", "tick", "()V", PUBLIC);
        byte[] b = SyntheticClass.multiMethodClass(
            "com/example/Foo",
            List.of(pub("tick", "()V"), pub("customName", "(Ljava/lang/String;)V")));

        List<SignatureDrift> drifts = new MethodSignatureAnalyzer().detect(a, b);
        assertEquals(1, drifts.size());
        SignatureDrift drift = drifts.get(0);
        assertEquals(SignatureDrift.Kind.ADDED, drift.kind());
        assertEquals("customName", drift.methodName());
        assertEquals("(Ljava/lang/String;)V", drift.descriptor());
        assertEquals(0, drift.accessA());
        assertTrue((drift.accessB() & org.objectweb.asm.Opcodes.ACC_PUBLIC) != 0);
    }

    @Test
    void removedMethodIsReportedAsRemoved() {
        byte[] a = SyntheticClass.multiMethodClass(
            "com/example/Foo",
            List.of(pub("tick", "()V"), pub("legacyTick", "()V")));
        byte[] b = SyntheticClass.singleMethodClass(
            "com/example/Foo", "tick", "()V", PUBLIC);

        List<SignatureDrift> drifts = new MethodSignatureAnalyzer().detect(a, b);
        assertEquals(1, drifts.size());
        SignatureDrift drift = drifts.get(0);
        assertEquals(SignatureDrift.Kind.REMOVED, drift.kind());
        assertEquals("legacyTick", drift.methodName());
        assertEquals(0, drift.accessB());
        assertTrue((drift.accessA() & org.objectweb.asm.Opcodes.ACC_PUBLIC) != 0);
    }

    @Test
    void modifiedDescriptorIsReportedAsModified() {
        byte[] a = SyntheticClass.singleMethodClass(
            "com/example/Foo", "getHealth", "()I", PUBLIC);
        byte[] b = SyntheticClass.singleMethodClass(
            "com/example/Foo", "getHealth", "(J)I", PUBLIC);

        List<SignatureDrift> drifts = new MethodSignatureAnalyzer().detect(a, b);
        assertEquals(1, drifts.size());
        SignatureDrift drift = drifts.get(0);
        assertEquals(SignatureDrift.Kind.MODIFIED, drift.kind());
        assertEquals("getHealth", drift.methodName());
        assertEquals("(J)I", drift.descriptor(),
            "MODIFIED surfaces the post-update descriptor");
        assertTrue(drift.detail().contains("descriptor"),
            "MODIFIED drift should describe what changed; got: " + drift.detail());
        assertTrue(drift.detail().contains("()I"),
            "MODIFIED drift should mention the prior descriptor; got: " + drift.detail());
        assertTrue((drift.accessA() & org.objectweb.asm.Opcodes.ACC_PUBLIC) != 0);
        assertTrue((drift.accessB() & org.objectweb.asm.Opcodes.ACC_PUBLIC) != 0);
    }

    @Test
    void visibilityChangeIsReportedAsModified() {
        byte[] a = SyntheticClass.singleMethodClass(
            "com/example/Foo", "tick", "()V", PUBLIC);
        byte[] b = SyntheticClass.singleMethodClass(
            "com/example/Foo", "tick", "()V", PROTECTED);

        List<SignatureDrift> drifts = new MethodSignatureAnalyzer().detect(a, b);
        assertEquals(1, drifts.size());
        SignatureDrift drift = drifts.get(0);
        assertEquals(SignatureDrift.Kind.MODIFIED, drift.kind());
        assertTrue(drift.detail().contains("visibility"),
            "visibility change should be surfaced in the detail; got: " + drift.detail());
        assertTrue(drift.detail().contains("public"));
        assertTrue(drift.detail().contains("protected"));
    }

    @Test
    void overloadAdditionIsAddedNotModified() {
        // Old has foo()V only. New adds foo(I)V as an overload.
        // The bug fixed in P1.5.1: earlier code mis-reported this as MODIFIED.
        byte[] a = SyntheticClass.multiMethodClass(
            "com/example/Foo", List.of(pub("foo", "()V")));
        byte[] b = SyntheticClass.multiMethodClass(
            "com/example/Foo", List.of(pub("foo", "()V"), pub("foo", "(I)V")));

        List<SignatureDrift> drifts = new MethodSignatureAnalyzer().detect(a, b);
        assertEquals(1, drifts.size(),
            "overload addition must produce exactly one drift (ADDED), not MODIFIED");
        SignatureDrift drift = drifts.get(0);
        assertEquals(SignatureDrift.Kind.ADDED, drift.kind(),
            "overload addition must be ADDED, never MODIFIED; got: " + drift.kind());
        assertEquals("foo", drift.methodName());
        assertEquals("(I)V", drift.descriptor());
    }

    @Test
    void overloadRemovalIsRemovedNotModified() {
        // Old has foo()V AND foo(I)V. New drops the foo(I)V overload.
        byte[] a = SyntheticClass.multiMethodClass(
            "com/example/Foo", List.of(pub("foo", "()V"), pub("foo", "(I)V")));
        byte[] b = SyntheticClass.multiMethodClass(
            "com/example/Foo", List.of(pub("foo", "()V")));

        List<SignatureDrift> drifts = new MethodSignatureAnalyzer().detect(a, b);
        assertEquals(1, drifts.size(),
            "overload removal must produce exactly one drift (REMOVED), not MODIFIED");
        SignatureDrift drift = drifts.get(0);
        assertEquals(SignatureDrift.Kind.REMOVED, drift.kind(),
            "overload removal must be REMOVED, never MODIFIED; got: " + drift.kind());
        assertEquals("(I)V", drift.descriptor());
    }

    @Test
    void pureDescriptorChangeWithoutOverloadsIsModified() {
        // Single foo on each side, descriptor changed → unambiguous MODIFIED.
        byte[] a = SyntheticClass.singleMethodClass(
            "com/example/Foo", "foo", "()I", PUBLIC);
        byte[] b = SyntheticClass.singleMethodClass(
            "com/example/Foo", "foo", "(J)I", PUBLIC);

        List<SignatureDrift> drifts = new MethodSignatureAnalyzer().detect(a, b);
        assertEquals(1, drifts.size());
        assertEquals(SignatureDrift.Kind.MODIFIED, drifts.get(0).kind());
    }

    @Test
    void multipleKindsCoexist() {
        byte[] a = SyntheticClass.multiMethodClass(
            "com/example/Foo",
            List.of(pub("tick", "()V"), pub("getHealth", "()I")));
        byte[] b = SyntheticClass.multiMethodClass(
            "com/example/Foo",
            List.of(pub("tick", "()V"), pub("getHealth", "(J)I"), pub("customName", "()V")));

        List<SignatureDrift> drifts = new MethodSignatureAnalyzer().detect(a, b);
        assertEquals(2, drifts.size());
        long added = drifts.stream().filter(d -> d.kind() == SignatureDrift.Kind.ADDED).count();
        long modified = drifts.stream().filter(d -> d.kind() == SignatureDrift.Kind.MODIFIED).count();
        assertEquals(1, added);
        assertEquals(1, modified);
    }

    @Test
    void differExposesAccessFlagsOnBothSides() {
        // AsmClassDiffer is the underlying engine — pin that it surfaces the
        // raw access flags for both A and B so callers can decide what to do.
        byte[] a = SyntheticClass.singleMethodClass(
            "com/example/Foo", "tick", "()V", PUBLIC);
        byte[] b = SyntheticClass.singleMethodClass(
            "com/example/Foo", "tick", "()V", PROTECTED);

        List<SignatureDrift> drifts = new AsmClassDiffer().differ(a, b);
        assertEquals(1, drifts.size());
        SignatureDrift d = drifts.get(0);
        assertTrue((d.accessA() & org.objectweb.asm.Opcodes.ACC_PUBLIC) != 0);
        assertTrue((d.accessB() & org.objectweb.asm.Opcodes.ACC_PROTECTED) != 0);
    }

    @Test
    void annotationDetectorOnlyKeepsAnnotatedMethods() {
        // Same class on both sides: tick()V is @NebulaRW, customName()V is plain.
        // New version drops the @NebulaRW from tick(). Detector should report it.
        List<String> rwAnno = List.of("Lorg/nebula/annotations/NebulaRW;");
        byte[] a = SyntheticClass.multiMethodClass(
            "com/example/Foo",
            List.of(new SyntheticClass.MethodSpec("tick", "()V", PUBLIC, rwAnno),
                    new SyntheticClass.MethodSpec("customName", "()V", PUBLIC, List.of())));
        byte[] b = SyntheticClass.multiMethodClass(
            "com/example/Foo",
            List.of(new SyntheticClass.MethodSpec("customName", "()V", PUBLIC, List.of())));

        // Without filtering, the diff reports REMOVED for tick.
        List<SignatureDrift> unfiltered = new MethodSignatureAnalyzer().detect(a, b);
        assertEquals(1, unfiltered.size());
        assertEquals("tick", unfiltered.get(0).methodName());

        // With annotation filtering, the same drift is kept because tick carries @NebulaRW in A.
        List<SignatureDrift> filtered = new AnnotationDriftDetector().detect(a, b);
        assertEquals(1, filtered.size());
        assertEquals("tick", filtered.get(0).methodName());
    }

    @Test
    void annotationDetectorSkipsUnannotatedMethods() {
        // Neither side carries @NebulaRW → detector drops everything.
        byte[] a = SyntheticClass.multiMethodClass(
            "com/example/Foo",
            List.of(pub("tick", "()V"), pub("customName", "()V")));
        byte[] b = SyntheticClass.multiMethodClass(
            "com/example/Foo",
            List.of(pub("tick", "()V"), pub("customName", "(I)V")));

        List<SignatureDrift> unfiltered = new MethodSignatureAnalyzer().detect(a, b);
        assertEquals(1, unfiltered.size());

        List<SignatureDrift> filtered = new AnnotationDriftDetector().detect(a, b);
        assertTrue(filtered.isEmpty(),
            "no method carries @NebulaRW on either side → detector emits nothing");
    }

    @Test
    void annotationDetectorReportsAddedAnnotatedMethod() {
        // Old has unannotated tick. New adds @NebulaRW on customName (ADDED).
        List<String> rwAnno = List.of("Lorg/nebula/annotations/NebulaRW;");
        byte[] a = SyntheticClass.multiMethodClass(
            "com/example/Foo",
            List.of(pub("tick", "()V")));
        byte[] b = SyntheticClass.multiMethodClass(
            "com/example/Foo",
            List.of(pub("tick", "()V"),
                    new SyntheticClass.MethodSpec("customName", "()V", PUBLIC, rwAnno)));

        List<SignatureDrift> filtered = new AnnotationDriftDetector().detect(a, b);
        assertEquals(1, filtered.size());
        assertEquals(SignatureDrift.Kind.ADDED, filtered.get(0).kind());
        assertEquals("customName", filtered.get(0).methodName());
    }
}
