package org.nebula.maintenance.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * ASM-based extractor that walks a compiled {@code .class} file and produces
 * one {@link BytecodeMethodDescriptor} per declared method
 * (Phase 1.5, NEBULA-PATCH-2026-001 §14.3.5 组件 A).
 *
 * <p>This is the bytecode counterpart to
 * {@code org.nebula.maintenance.JavaSignatureExtractor}. Where the source
 * extractor reads decompiled {@code .java} files, this one reads raw
 * {@code .class} files via ASM's {@link ClassReader}. The bytecode view is
 * the canonical one: Mojang ships compiled jars, never decompiled sources,
 * so this is what the maintenance toolchain must use in production.
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li>Constructors ({@code <init>}) and static initializers ({@code <clinit>})
 *       are skipped — they don't carry {@code @NebulaRW} annotations.</li>
 *   <li>Bridge and synthetic methods are kept (they mirror user-visible methods
 *       and the differ must see them to avoid false "REMOVED" deltas when a
 *       generic supertype changes), but their {@code bridge}/{@code synthetic}
 *       access flags are preserved on the descriptor so the report can flag
 *       them.</li>
 *   <li>Access flags are reported in their raw {@code int} form (as ASM emits
 *       them) so the differ can do bitmask comparisons.</li>
 *   <li>The extractor is single-pass: it does not retain any bytecode body,
 *       only the declaration metadata.</li>
 * </ul>
 *
 * <h2>Thread-safety</h2>
 * The visitor is stateless (no mutable fields); instances are safe to reuse
 * across threads.
 */
public final class BytecodeMethodExtractor {

    private static final int ASM_API = Opcodes.ASM9;

    private BytecodeMethodExtractor() {}

    /** Extract method descriptors from a raw {@code .class} byte array. */
    public static List<BytecodeMethodDescriptor> extract(byte[] bytecode) {
        Objects.requireNonNull(bytecode, "bytecode");
        ClassReader reader = new ClassReader(bytecode);
        CollectingVisitor cv = new CollectingVisitor();
        reader.accept(cv, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return Collections.unmodifiableList(cv.methods);
    }

    /** Extract method descriptors from a {@code .class} file on disk. */
    public static List<BytecodeMethodDescriptor> extract(Path classFile) throws IOException {
        Objects.requireNonNull(classFile, "classFile");
        return extract(Files.readAllBytes(classFile));
    }

    /**
     * Extract method descriptors from a class on the classpath, looked up by
     * its internal binary name (e.g. {@code java/util/HashMap}). Returns an
     * empty list if the class is not on the classpath (does not throw).
     */
    public static List<BytecodeMethodDescriptor> extractFromClasspath(String internalName) {
        Objects.requireNonNull(internalName, "internalName");
        String resourcePath = "/" + internalName + ".class";
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = BytecodeMethodExtractor.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(resourcePath)) {
            if (in == null) return List.of();
            return extract(in.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException("Failed reading classpath resource " + resourcePath, e);
        }
    }

    /**
     * Recursively extract method descriptors from every {@code .class} file
     * under {@code root}. Used to scan an entire compiled Minecraft jar.
     */
    public static List<BytecodeMethodDescriptor> extractTree(Path root) throws IOException {
        Objects.requireNonNull(root, "root");
        List<BytecodeMethodDescriptor> all = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : (Iterable<Path>) paths.filter(p -> p.toString().endsWith(".class"))::iterator) {
                all.addAll(extract(p));
            }
        }
        return all;
    }

    /**
     * Visitor that records the {@link BytecodeMethodDescriptor} for each
     * declared method. The visitor body is intentionally trivial because the
     * differ downstream needs only the metadata, not the bytecode itself.
     */
    private static final class CollectingVisitor extends ClassVisitor {
        private final List<BytecodeMethodDescriptor> methods = new ArrayList<>();
        private String internalName;
        private String simpleName;

        CollectingVisitor() {
            super(ASM_API);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.internalName = name;
            int slash = name.lastIndexOf('/');
            this.simpleName = slash < 0 ? name : name.substring(slash + 1);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            // Skip <init> / <clinit> — they cannot carry @NebulaRW.
            if ("<init>".equals(name) || "<clinit>".equals(name)) {
                return null;
            }
            methods.add(new BytecodeMethodDescriptor(
                internalName, simpleName, name, descriptor, access, signature));
            return null; // no body inspection
        }
    }
}