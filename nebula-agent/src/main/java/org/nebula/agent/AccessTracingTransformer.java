package org.nebula.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class AccessTracingTransformer {
    private AccessTracingTransformer() {
    }

    public static byte[] instrument(String className, byte[] bytecode) {
        ClassReader reader = new ClassReader(bytecode);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = new AccessTracingClassVisitor(writer);
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    private static final class AccessTracingClassVisitor extends ClassVisitor {
        private AccessTracingClassVisitor(ClassVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new AccessTracingMethodVisitor(delegate);
        }
    }

    private static final class AccessTracingMethodVisitor extends MethodVisitor {
        private AccessTracingMethodVisitor(MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC) {
                insertTrace(owner, name, descriptor, false);
            } else if (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) {
                insertTrace(owner, name, descriptor, true);
            }
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        private void insertTrace(String owner, String name, String descriptor, boolean write) {
            super.visitLdcInsn(owner);
            super.visitLdcInsn(name);
            super.visitLdcInsn(descriptor);
            super.visitInsn(write ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "org/nebula/agent/TraceHooks",
                "traceFieldAccess",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V",
                false
            );
        }
    }
}
