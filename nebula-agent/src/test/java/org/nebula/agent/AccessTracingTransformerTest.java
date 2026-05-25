package org.nebula.agent;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.GlobalKey;
import org.nebula.guard.ThreadLocalAccessTrace;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessTracingTransformerTest {
    @Test
    void instrumentsFieldReadsAndWritesIntoTraceHooks() throws Exception {
        String binaryName = "org.nebula.agent.InstrumentedFixture";
        String internalName = binaryName.replace('.', '/');
        byte[] original = generateFixtureClass(internalName);
        byte[] instrumented = AccessTracingTransformer.instrument(internalName, original);

        ByteArrayClassLoader loader = new ByteArrayClassLoader();
        Class<?> fixtureClass = loader.define(binaryName, instrumented);
        Constructor<?> constructor = fixtureClass.getConstructor();
        Object fixture = constructor.newInstance();
        Method write = fixtureClass.getMethod("write", int.class);
        Method read = fixtureClass.getMethod("read");

        ThreadLocalAccessTrace.reset();
        write.invoke(fixture, 7);
        read.invoke(fixture);

        GlobalKey key = new GlobalKey("field:" + binaryName + "#value:I");
        assertTrue(ThreadLocalAccessTrace.snapshot().writtenGlobalKeys().contains(key));
        assertTrue(ThreadLocalAccessTrace.snapshot().readGlobalKeys().contains(key));
    }

    private static byte[] generateFixtureClass(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "value", "I", null, null).visitEnd();

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor write = writer.visitMethod(Opcodes.ACC_PUBLIC, "write", "(I)V", null, null);
        write.visitCode();
        write.visitVarInsn(Opcodes.ALOAD, 0);
        write.visitVarInsn(Opcodes.ILOAD, 1);
        write.visitFieldInsn(Opcodes.PUTFIELD, internalName, "value", "I");
        write.visitInsn(Opcodes.RETURN);
        write.visitMaxs(0, 0);
        write.visitEnd();

        MethodVisitor read = writer.visitMethod(Opcodes.ACC_PUBLIC, "read", "()I", null, null);
        read.visitCode();
        read.visitVarInsn(Opcodes.ALOAD, 0);
        read.visitFieldInsn(Opcodes.GETFIELD, internalName, "value", "I");
        read.visitInsn(Opcodes.IRETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class ByteArrayClassLoader extends ClassLoader {
        Class<?> define(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }
}
