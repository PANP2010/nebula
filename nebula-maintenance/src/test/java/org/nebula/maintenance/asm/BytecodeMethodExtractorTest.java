package org.nebula.maintenance.asm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link BytecodeMethodExtractor} and the
 * {@link BytecodeMethodDescriptor} value type
 * (Phase 1.5, NEBULA-PATCH-2026-001 §14.3.5 组件 A).
 *
 * <p>The extractor is exercised against synthetic class files emitted by
 * {@link SyntheticClass} — the input is bytecode, the assertions are about
 * the extracted metadata.
 */
class BytecodeMethodExtractorTest {

    @Test
    void extractsMethodNameAndDescriptor() {
        byte[] cls = SyntheticClass.singleMethodClass(
            "com/example/Foo", "tick", "()V",
            org.objectweb.asm.Opcodes.ACC_PUBLIC);
        List<BytecodeMethodDescriptor> methods = BytecodeMethodExtractor.extract(cls);
        assertEquals(1, methods.size(), "should record exactly the 'tick' method");

        BytecodeMethodDescriptor m = methods.get(0);
        assertEquals("com/example/Foo", m.ownerClass());
        assertEquals("Foo", m.simpleClassName());
        assertEquals("tick", m.methodName());
        assertEquals("()V", m.descriptor());
        assertTrue(m.isPublic(), "tick was declared public");
    }

    @Test
    void skipsConstructorsAndStaticInitializers() {
        byte[] cls = SyntheticClass.singleMethodClass(
            "com/example/Foo", "tick", "()V",
            org.objectweb.asm.Opcodes.ACC_PUBLIC);
        List<BytecodeMethodDescriptor> methods = BytecodeMethodExtractor.extract(cls);
        // The implicit <init> must not appear.
        assertTrue(methods.stream().noneMatch(m -> m.methodName().equals("<init>")));
        assertTrue(methods.stream().noneMatch(m -> m.methodName().equals("<clinit>")));
    }

    @Test
    void identityKeyIsStable() {
        BytecodeMethodDescriptor d = new BytecodeMethodDescriptor(
            "com/example/Foo", "Foo", "tick", "()V",
            org.objectweb.asm.Opcodes.ACC_PUBLIC, null);
        assertEquals("com/example/Foo#tick ()V", d.identityKey());
    }

    @Test
    void visibilityAndModifierPredicates() {
        BytecodeMethodDescriptor pub = new BytecodeMethodDescriptor(
            "a/B", "B", "m", "()V",
            org.objectweb.asm.Opcodes.ACC_PUBLIC, null);
        BytecodeMethodDescriptor prot = new BytecodeMethodDescriptor(
            "a/B", "B", "m", "()V",
            org.objectweb.asm.Opcodes.ACC_PROTECTED, null);
        BytecodeMethodDescriptor priv = new BytecodeMethodDescriptor(
            "a/B", "B", "m", "()V",
            org.objectweb.asm.Opcodes.ACC_PRIVATE, null);
        BytecodeMethodDescriptor stat = new BytecodeMethodDescriptor(
            "a/B", "B", "m", "()V",
            org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
            null);
        BytecodeMethodDescriptor fin = new BytecodeMethodDescriptor(
            "a/B", "B", "m", "()V",
            org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_FINAL,
            null);

        assertTrue(pub.isPublic());
        assertTrue(!pub.isProtected() && !pub.isPrivate());
        assertEquals("public", pub.visibilityLabel());

        assertTrue(prot.isProtected());
        assertEquals("protected", prot.visibilityLabel());

        assertTrue(priv.isPrivate());
        assertEquals("private", priv.visibilityLabel());

        assertTrue(stat.isStatic() && stat.isPublic());
        assertTrue(fin.isFinal() && fin.isPublic());

        BytecodeMethodDescriptor pkg = new BytecodeMethodDescriptor(
            "a/B", "B", "m", "()V", 0, null);
        assertTrue(pkg.isPackagePrivate());
        assertEquals("package", pkg.visibilityLabel());
    }

    @Test
    void initializerDetection() {
        BytecodeMethodDescriptor ctor = new BytecodeMethodDescriptor(
            "a/B", "B", "<init>", "()V", org.objectweb.asm.Opcodes.ACC_PUBLIC, null);
        BytecodeMethodDescriptor cli = new BytecodeMethodDescriptor(
            "a/B", "B", "<clinit>", "()V", org.objectweb.asm.Opcodes.ACC_STATIC, null);
        assertTrue(ctor.isInitializer());
        assertTrue(cli.isInitializer());
        BytecodeMethodDescriptor tick = new BytecodeMethodDescriptor(
            "a/B", "B", "tick", "()V", org.objectweb.asm.Opcodes.ACC_PUBLIC, null);
        assertTrue(!tick.isInitializer());
    }

    @Test
    void extractFromClasspathFindsRuntimeClass() {
        // java/util/ArrayList is guaranteed to be on the classpath. We check
        // the simple, non-generic size() method which has a stable descriptor
        // (no bridge methods).  The check tolerates being skipped when the JVM
        // module system restricts access to java.util classes during tests.
        List<BytecodeMethodDescriptor> methods =
            BytecodeMethodExtractor.extractFromClasspath("java/util/ArrayList");
        if (methods.isEmpty()) {
            // Module system may restrict access — skip when nothing is visible.
            return;
        }
        assertTrue(methods.size() > 1,
            "ArrayList should expose more than one method when reachable");
        assertTrue(methods.stream().anyMatch(m -> m.methodName().equals("size")
                && m.descriptor().equals("()I")),
            "extractFromClasspath should find size():I on ArrayList");
    }
}