package org.nebula.maintenance.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Variant of {@link MethodSignatureAnalyzer} that only reports drift for
 * methods that carry a Nebula annotation on at least one side
 * (Phase 1.5, NEBULA-PATCH-2026-001 §14.3.5 组件 A).
 *
 * <p>The full diff runs first, then the result is filtered down to those
 * entries whose method (in A or in B, whichever exists) carries one of the
 * configured annotation descriptors. By default the detector watches for
 * {@code Lorg/nebula/annotations/NebulaRW;}, which is the production
 * marker for hot-path Minecraft methods.
 *
 * <h2>Why filter at all?</h2>
 * Two Minecraft releases typically differ in thousands of methods, but
 * only a few dozen carry {@code @NebulaRW}. Filtering to those makes the
 * diff output tractable for a human reviewer (a single-screen report
 * instead of a 50-page log) and aligns the bytecode diff with the
 * source-side {@code SignatureDiffer}, which already only emits entries for
 * annotated methods.
 *
 * <h2>Symmetry</h2>
 * A drift is kept if either side carries the annotation. That way an
 * {@code ADDED} method that newly gained an annotation in B is reported,
 * and a {@code REMOVED} method that lost one in B is also reported —
 * both are exactly the cases the human review workflow cares about.
 *
 * <h2>Thread safety</h2>
 * The detector is stateless across {@link #detect} calls — the configured
 * annotation set is finalised at construction time and per-call state is
 * held in local variables only — so concurrent invocations are safe.
 *
 * <p>Requires {@code org.ow2.asm:asm-tree} for the {@link AnnotationNode}
 * walking.
 */
public final class AnnotationDriftDetector {

    private static final int ASM_API = Opcodes.ASM9;

    /** Default annotation descriptor watched by the detector. */
    public static final String NEBULA_RW_DESCRIPTOR = "Lorg/nebula/annotations/NebulaRW;";

    private final MethodSignatureAnalyzer analyzer = new MethodSignatureAnalyzer();
    private final Set<String> annotationDescriptors = new LinkedHashSet<>();

    public AnnotationDriftDetector() {
        annotationDescriptors.add(NEBULA_RW_DESCRIPTOR);
    }

    /**
     * Add an additional annotation descriptor to watch (e.g.
     * {@code "Lorg/nebula/annotations/NebulaReadOnly;"}). Pass descriptors
     * in JVM internal form (the {@code L...;} prefix).
     */
    public AnnotationDriftDetector watchAnnotation(String descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        annotationDescriptors.add(descriptor);
        return this;
    }

    /**
     * Run the diff and keep only entries whose method carries one of the
     * configured annotations on at least one side.
     */
    public List<SignatureDrift> detect(byte[] bytecodeA, byte[] bytecodeB) {
        Objects.requireNonNull(bytecodeA, "bytecodeA");
        Objects.requireNonNull(bytecodeB, "bytecodeB");

        Set<String> annotatedInA = annotatedMethodKeys(bytecodeA);
        Set<String> annotatedInB = annotatedMethodKeys(bytecodeB);

        List<SignatureDrift> all = analyzer.detect(bytecodeA, bytecodeB);
        List<SignatureDrift> filtered = new ArrayList<>();
        for (SignatureDrift drift : all) {
            // For MODIFIED/REMOVED, drift.descriptor() is the post-update (or
            // the only) descriptor and accessA is non-zero → keyA is the
            // candidate key into annotatedInA. For ADDED, accessA is 0 and
            // we only consult annotatedInB.
            String keyA = drift.accessA() != 0 ? keyOf(drift.methodName(), drift.descriptor()) : null;
            String keyB = keyOf(drift.methodName(), drift.descriptor());
            if ((keyA != null && annotatedInA.contains(keyA))
                || annotatedInB.contains(keyB)) {
                filtered.add(drift);
            }
        }
        return filtered;
    }

    private Set<String> annotatedMethodKeys(byte[] bytecode) {
        ClassReader reader = new ClassReader(bytecode);
        ClassNode node = new ClassNode(ASM_API);
        reader.accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        // Class-level Nebula annotations apply to every method by Java
        // semantics. The reverse (a method-level annotation on a method
        // declared in an annotated class) is already covered below.
        boolean classIsAnnotated = hasClassAnnotation(node);

        Set<String> keys = new LinkedHashSet<>();
        if (node.methods == null) return keys;
        for (MethodNode mn : node.methods) {
            if (mn.name.equals("<init>") || mn.name.equals("<clinit>")) continue;
            if (classIsAnnotated || hasNebulaAnnotation(mn)) {
                keys.add(keyOf(mn.name, mn.desc));
            }
        }
        return keys;
    }

    private boolean hasClassAnnotation(ClassNode cls) {
        if (cls.visibleAnnotations != null) {
            for (AnnotationNode an : cls.visibleAnnotations) {
                if (annotationDescriptors.contains(an.desc)) return true;
            }
        }
        if (cls.invisibleAnnotations != null) {
            for (AnnotationNode an : cls.invisibleAnnotations) {
                if (annotationDescriptors.contains(an.desc)) return true;
            }
        }
        return false;
    }

    private boolean hasNebulaAnnotation(MethodNode mn) {
        if (mn.visibleAnnotations != null) {
            for (AnnotationNode an : mn.visibleAnnotations) {
                if (annotationDescriptors.contains(an.desc)) return true;
            }
        }
        if (mn.invisibleAnnotations != null) {
            for (AnnotationNode an : mn.invisibleAnnotations) {
                if (annotationDescriptors.contains(an.desc)) return true;
            }
        }
        return false;
    }

    private static String keyOf(String name, String descriptor) {
        return name + "#" + (descriptor == null ? "()" : descriptor);
    }
}
