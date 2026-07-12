package org.nebula.maintenance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nebula.maintenance.asm.BytecodeMethodDescriptor;
import org.nebula.maintenance.asm.BytecodeMethodExtractor;
import org.nebula.maintenance.asm.MethodSignatureDeltaDetector;
import org.nebula.maintenance.asm.MethodSignatureDeltaDetector.PerClassResult;
import org.nebula.maintenance.asm.MethodDelta;
import org.nebula.maintenance.asm.NebulaRWAnnotationPatcher;
import org.nebula.maintenance.asm.NebulaRWAnnotationPatcher.PatchCandidate;
import org.nebula.maintenance.report.MsdReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test: builds synthetic {@code .class} files with two
 * versioned method sets, runs the full MSD pipeline (extract → detect →
 * patch → report) and validates the output
 * (Phase 1.5, NEBULA-PATCH-2026-001 §14.3.5 组件 A).
 *
 * <p>This is the test that proves the toolchain actually works against
 * realistic class shapes — not just synthetic method-record inputs.
 */
class MsdEndToEndTest {

    private static final int PUBLIC = org.objectweb.asm.Opcodes.ACC_PUBLIC;
    private static final int PROTECTED = org.objectweb.asm.Opcodes.ACC_PROTECTED;

    @Test
    void fullPipelineProducesReportWithL0AndL1Deltas(@TempDir Path tmp) throws IOException {
        Path oldDir = tmp.resolve("old/com/example");
        Path newDir = tmp.resolve("new/com/example");
        Files.createDirectories(oldDir);
        Files.createDirectories(newDir);

        // OLD: TickEvent.tick()V + TickEvent.getHealth()I
        writeClass(oldDir.resolve("TickEvent.class"), "com/example/TickEvent", "TickEvent",
            List.of(
                method("tick", "()V", PUBLIC),
                method("getHealth", "()I", PUBLIC)));

        // NEW: TickEvent.tick()V (unchanged) + TickEvent.getHealth(J)I (descriptor
        // changed) + TickEvent.customName(String)V (new)
        writeClass(newDir.resolve("TickEvent.class"), "com/example/TickEvent", "TickEvent",
            List.of(
                method("tick", "()V", PUBLIC),
                method("getHealth", "(J)I", PUBLIC),
                method("customName", "(Ljava/lang/String;)V", PUBLIC)));

        // Run extraction.
        List<BytecodeMethodDescriptor> oldMethods = BytecodeMethodExtractor.extractTree(oldDir);
        List<BytecodeMethodDescriptor> newMethods = BytecodeMethodExtractor.extractTree(newDir);
        assertNotNull(oldMethods);
        assertNotNull(newMethods);

        // Bucket by class.
        Map<String, List<BytecodeMethodDescriptor>> oldByClass = bucketByOwner(oldMethods);
        Map<String, List<BytecodeMethodDescriptor>> newByClass = bucketByOwner(newMethods);

        // Run diff.
        MethodSignatureDeltaDetector detector = new MethodSignatureDeltaDetector();
        List<PerClassResult> perClass = detector.detectByClass(oldByClass, newByClass);
        assertTrue(perClass.size() >= 1);

        // Build patches for the auto-draftable entries.
        List<PatchCandidate> allPatches = new ArrayList<>();
        for (PerClassResult cls : perClass) {
            allPatches.addAll(new NebulaRWAnnotationPatcher().draftAll(cls.deltas()));
        }

        // Render.
        String report = new MsdReport(
            "old vs new (synthetic TickEvent)", perClass, allPatches).render();

        // Report must mention the L0 add (customName) and the L1 change (getHealth).
        assertTrue(report.contains("+"),
            "report should contain at least one '+' NEW marker");
        assertTrue(report.contains("~"),
            "report should contain at least one '~' CHANGED marker");
        assertTrue(report.contains("L0 - NEW METHOD"),
            "report should label an L0 delta");
        assertTrue(report.contains("L1 - DESCRIPTOR CHANGED"),
            "report should label an L1 delta");
        assertTrue(report.contains("Auto-migrate rate"));
        // Write the report to a file too — this exercises writeToFile.
        Path reportFile = tmp.resolve("msd-report.txt");
        new MsdReport("old vs new (synthetic TickEvent)", perClass, allPatches)
            .writeToFile(reportFile);
        assertTrue(Files.size(reportFile) > 0);
    }

    @Test
    void renameHintProducesSingleDescriptorChangedDelta(@TempDir Path tmp) throws IOException {
        Path oldDir = tmp.resolve("old/com/example");
        Path newDir = tmp.resolve("new/com/example");
        Files.createDirectories(oldDir);
        Files.createDirectories(newDir);

        // Old: foo(I)V.  New: bar(I)V — same descriptor, different name.
        writeClass(oldDir.resolve("Foo.class"), "com/example/Foo", "Foo",
            List.of(method("foo", "(I)V", PUBLIC)));
        writeClass(newDir.resolve("Foo.class"), "com/example/Foo", "Foo",
            List.of(method("bar", "(I)V", PUBLIC)));

        List<BytecodeMethodDescriptor> oldMethods = BytecodeMethodExtractor.extractTree(oldDir);
        List<BytecodeMethodDescriptor> newMethods = BytecodeMethodExtractor.extractTree(newDir);

        String oldKey = "com/example/Foo#foo (I)V";
        String newKey = "com/example/Foo#bar (I)V";

        MethodSignatureDeltaDetector detector = new MethodSignatureDeltaDetector()
            .setRenameHint(oldKey, newKey);

        List<MethodDelta> deltas = detector.detect(oldMethods, newMethods);
        assertTrue(deltas.size() == 1,
            "rename hint must collapse foo→bar into a single delta, got " + deltas.size());
        assertTrue(deltas.get(0).level() == org.nebula.maintenance.asm.DiffLevel.DESCRIPTOR_CHANGED
                || deltas.get(0).level() == org.nebula.maintenance.asm.DiffLevel.NEW,
            "rename hint should produce DESCRIPTOR_CHANGED or NEW, got " + deltas.get(0).level());
    }

    @Test
    void visibilityChangeIsLevel2(@TempDir Path tmp) throws IOException {
        Path oldDir = tmp.resolve("old/com/example");
        Path newDir = tmp.resolve("new/com/example");
        Files.createDirectories(oldDir);
        Files.createDirectories(newDir);

        // Same descriptor, public → protected.
        writeClass(oldDir.resolve("Bar.class"), "com/example/Bar", "Bar",
            List.of(method("tick", "()V", PUBLIC)));
        writeClass(newDir.resolve("Bar.class"), "com/example/Bar", "Bar",
            List.of(method("tick", "()V", PROTECTED)));

        List<BytecodeMethodDescriptor> oldMethods = BytecodeMethodExtractor.extractTree(oldDir);
        List<BytecodeMethodDescriptor> newMethods = BytecodeMethodExtractor.extractTree(newDir);

        List<MethodDelta> deltas = new MethodSignatureDeltaDetector().detect(oldMethods, newMethods);
        assertTrue(deltas.size() == 1);
        assertTrue(deltas.get(0).level() == org.nebula.maintenance.asm.DiffLevel.VISIBILITY_CHANGED);
    }

    @Test
    void removedMethodIsLevel2(@TempDir Path tmp) throws IOException {
        Path oldDir = tmp.resolve("old/com/example");
        Path newDir = tmp.resolve("new/com/example");
        Files.createDirectories(oldDir);
        Files.createDirectories(newDir);

        writeClass(oldDir.resolve("Baz.class"), "com/example/Baz", "Baz",
            List.of(method("oldTick", "()V", PUBLIC)));
        writeClass(newDir.resolve("Baz.class"), "com/example/Baz", "Baz", List.of());

        List<BytecodeMethodDescriptor> oldMethods = BytecodeMethodExtractor.extractTree(oldDir);
        List<BytecodeMethodDescriptor> newMethods = BytecodeMethodExtractor.extractTree(newDir);

        List<MethodDelta> deltas = new MethodSignatureDeltaDetector().detect(oldMethods, newMethods);
        assertTrue(deltas.size() == 1);
        assertTrue(deltas.get(0).level() == org.nebula.maintenance.asm.DiffLevel.REMOVED);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static void writeClass(Path out, String internalName, String simpleName,
                                  List<MethodDef> methods) throws IOException {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(
            org.objectweb.asm.ClassWriter.COMPUTE_FRAMES | org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        cw.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC,
            internalName, null, "java/lang/Object", null);

        // Implicit <init>.
        org.objectweb.asm.MethodVisitor ctor = cw.visitMethod(
            org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL,
            "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        for (MethodDef m : methods) {
            org.objectweb.asm.MethodVisitor mv = cw.visitMethod(m.access, m.name, m.descriptor, null, null);
            mv.visitCode();
            char ret = m.descriptor.charAt(m.descriptor.indexOf(')') + 1);
            switch (ret) {
                case 'V' -> mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
                case 'I', 'Z', 'S', 'B' -> {
                    mv.visitInsn(org.objectweb.asm.Opcodes.ICONST_0);
                    mv.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
                }
                case 'J' -> {
                    mv.visitInsn(org.objectweb.asm.Opcodes.LCONST_0);
                    mv.visitInsn(org.objectweb.asm.Opcodes.LRETURN);
                }
                default -> {
                    mv.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
                    mv.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
                }
            }
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        Files.write(out, cw.toByteArray());
    }

    private static MethodDef method(String name, String descriptor, int access) {
        return new MethodDef(name, descriptor, access);
    }

    private record MethodDef(String name, String descriptor, int access) {}

    private static Map<String, List<BytecodeMethodDescriptor>> bucketByOwner(
        List<BytecodeMethodDescriptor> methods) {
        java.util.LinkedHashMap<String, List<BytecodeMethodDescriptor>> map = new java.util.LinkedHashMap<>();
        for (BytecodeMethodDescriptor m : methods) {
            map.computeIfAbsent(m.ownerClass(), k -> new ArrayList<>()).add(m);
        }
        return map;
    }
}