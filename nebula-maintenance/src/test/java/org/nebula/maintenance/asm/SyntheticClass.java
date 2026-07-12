package org.nebula.maintenance.asm;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;

/**
 * Test helper that synthesises minimal {@code .class} byte arrays for the MSD
 * detector. Each helper produces a class file containing one method whose
 * name, descriptor, and access flags are specified by the test.
 *
 * <p>This avoids needing real Minecraft classes on the test classpath; the
 * detector and extractor are pure bytecode consumers, so synthetic class
 * files exercise the full path.
 */
final class SyntheticClass {

    /** Synthesises a class with a single named method (no body). */
    static byte[] singleMethodClass(String internalName,
                                    String methodName,
                                    String descriptor,
                                    int access) {
        return multiMethodClass(internalName, List.of(new MethodSpec(methodName, descriptor, access, List.of())));
    }

    /**
     * Synthesises a class containing the given methods (each declared with
     * a stub body that returns the appropriate default for the return type).
     * Used for tests that need overloads or annotation-driven diff cases.
     */
    static byte[] multiMethodClass(String internalName, List<MethodSpec> methods) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        for (MethodSpec spec : methods) {
            if (spec.name == null) continue;
            MethodVisitor mv = cw.visitMethod(spec.access, spec.name, spec.descriptor, null, null);
            for (String anno : spec.annotations) {
                mv.visitAnnotation(anno, true).visitEnd();
            }
            mv.visitCode();
            char ret = spec.descriptor.charAt(spec.descriptor.indexOf(')') + 1);
            switch (ret) {
                case 'V' -> mv.visitInsn(Opcodes.RETURN);
                case 'I', 'Z', 'S', 'B' -> {
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitInsn(Opcodes.IRETURN);
                }
                case 'J' -> {
                    mv.visitInsn(Opcodes.LCONST_0);
                    mv.visitInsn(Opcodes.LRETURN);
                }
                case 'F' -> {
                    mv.visitInsn(Opcodes.FCONST_0);
                    mv.visitInsn(Opcodes.FRETURN);
                }
                case 'D' -> {
                    mv.visitInsn(Opcodes.DCONST_0);
                    mv.visitInsn(Opcodes.DRETURN);
                }
                default -> {
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    mv.visitInsn(Opcodes.ARETURN);
                }
            }
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** One method declaration inside a synthesised class file. */
    record MethodSpec(String name, String descriptor, int access, List<String> annotations) {
        MethodSpec {
            annotations = annotations == null ? List.of() : List.copyOf(annotations);
        }
        MethodSpec(String name, String descriptor, int access) {
            this(name, descriptor, access, List.of());
        }
    }

    private SyntheticClass() {}
}
