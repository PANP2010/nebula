package org.nebula.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VapApiInterceptTransformerTest {

    @Test
    void shouldTransformPluginClasses() {
        assertTrue(VapApiInterceptTransformer.shouldTransform("com/example/myplugin/Main"));
        assertTrue(VapApiInterceptTransformer.shouldTransform("me/author/plugin/Listener"));
    }

    @Test
    void shouldNotTransformBukkitClasses() {
        assertFalse(VapApiInterceptTransformer.shouldTransform("org/bukkit/entity/Player"));
        assertFalse(VapApiInterceptTransformer.shouldTransform("org/bukkit/Bukkit"));
    }

    @Test
    void shouldNotTransformPaperClasses() {
        assertFalse(VapApiInterceptTransformer.shouldTransform("io/papermc/paper/event/Something"));
    }

    @Test
    void shouldNotTransformNebulaClasses() {
        assertFalse(VapApiInterceptTransformer.shouldTransform("org/nebula/core/vap/Something"));
        assertFalse(VapApiInterceptTransformer.shouldTransform("org/nebula/agent/TraceHooks"));
    }

    @Test
    void shouldNotTransformJdkClasses() {
        assertFalse(VapApiInterceptTransformer.shouldTransform("java/lang/String"));
        assertFalse(VapApiInterceptTransformer.shouldTransform("javax/crypto/Cipher"));
        assertFalse(VapApiInterceptTransformer.shouldTransform("jdk/internal/misc/Unsafe"));
        assertFalse(VapApiInterceptTransformer.shouldTransform("sun/misc/Signal"));
    }

    @Test
    void shouldNotTransformMinecraftClasses() {
        assertFalse(VapApiInterceptTransformer.shouldTransform("net/minecraft/server/level/ServerLevel"));
    }

    @Test
    void shouldNotTransformNull() {
        assertFalse(VapApiInterceptTransformer.shouldTransform(null));
    }

    @Test
    void instrumentProducesValidBytecode() {
        byte[] original = generateSimpleClass();
        byte[] transformed = VapApiInterceptTransformer.instrument("com/example/TestPlugin", original);
        assertNotNull(transformed);
        assertTrue(transformed.length >= original.length);
    }

    private byte[] generateSimpleClass() {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC,
            "com/example/TestPlugin", null, "java/lang/Object", null);

        var mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "doSomething",
            "()V", null, null);
        mv.visitCode();
        // Simulate: player.setHealth(20)
        mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        mv.visitLdcInsn(20.0);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEINTERFACE,
            "org/bukkit/entity/Player", "setHealth", "(D)V", true);
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(3, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void instrumentInsertsHookCallsAroundBukkitWrite() {
        byte[] original = generateSimpleClass();
        byte[] transformed = VapApiInterceptTransformer.instrument("com/example/TestPlugin", original);

        // Transformed bytecode should be larger due to inserted hook calls
        assertTrue(transformed.length > original.length,
            "Transformed class should be larger due to hook insertion");

        // Verify hooks class reference is in the constant pool
        String bytecodeStr = new String(transformed, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(bytecodeStr.contains("org/nebula/agent/VapInterceptHooks"),
            "Transformed bytecode should reference VapInterceptHooks");
    }

    @Test
    void instrumentDoesNotModifyReadOnlyCalls() {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC,
            "com/example/ReadOnly", null, "java/lang/Object", null);

        var mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "check",
            "()V", null, null);
        mv.visitCode();
        // Simulate: player.getHealth()
        mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEINTERFACE,
            "org/bukkit/entity/Player", "getHealth", "()D", true);
        mv.visitInsn(org.objectweb.asm.Opcodes.POP2);
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();

        cw.visitEnd();
        byte[] original = cw.toByteArray();
        byte[] transformed = VapApiInterceptTransformer.instrument("com/example/ReadOnly", original);

        // Read-only calls (getHealth) should not have hooks inserted
        String bytecodeStr = new String(transformed, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(bytecodeStr.contains("beforeApiCall"),
            "Read-only Bukkit calls should not be intercepted");
    }
}
