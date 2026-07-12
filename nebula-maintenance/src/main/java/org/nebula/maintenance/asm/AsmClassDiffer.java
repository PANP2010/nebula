package org.nebula.maintenance.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ASM-based diff of two class files, producing a flat list of
 * {@link SignatureDrift} entries (Phase 1.5, NEBULA-PATCH-2026-001
 * §14.3.5 组件 A).
 *
 * <p>Where {@link BytecodeMethodExtractor} feeds the heavier
 * {@link MethodSignatureDeltaDetector}, this class is the lean per-class
 * diff for callers that already have the two byte arrays in hand and want a
 * simple addition/removal/modification report. It uses ASM's
 * {@link ClassReader} → {@link ClassNode} tree API rather than the
 * skip-debug visitor stream used by the extractor, because the diff benefits
 * from the structural {@code ClassNode} (methods, annotations, signatures
 * available as plain fields) when reasoning about overloads.
 *
 * <h2>What it compares</h2>
 * <ul>
 *   <li>Only declared methods (the {@code <init>} / {@code <clinit>}
 *       initializers are skipped — they never carry {@code @NebulaRW}).</li>
 *   <li>Visibility set is public, protected, and package-private. Private
 *       methods are excluded because they cannot affect downstream plugin
 *       callers and {@code @NebulaRW} is by contract never applied to
 *       private code. Bridge and synthetic methods are kept: dropping them
 *       would cause a spurious {@code REMOVED} when a generic supertype
 *       shifted.</li>
 *   <li>Access flags are compared at the {@code public/protected/package}
 *       bit. {@code static} and {@code final} are tracked but do not, by
 *       themselves, classify a method as {@code MODIFIED} — see
 *       {@link #isVisibilityChange} for the exact rule.</li>
 *   <li>Identity is {@code name + descriptor}. Two same-named methods with
 *       different descriptors are overloads, not modifications.</li>
 * </ul>
 *
 * <h2>MODIFIED vs overload handling (P1.5.1 bug fix)</h2>
 * Earlier drafts conflated two distinct signals: a same-name descriptor
 * change on a single method, and the addition/removal of an overload. Both
 * have the same name but different descriptors, so a naive "match by name"
 * strategy cannot tell them apart. The rule this class enforces:
 *
 * <ul>
 *   <li>If exactly one method with name {@code N} exists in A <em>and</em>
 *       exactly one with name {@code N} exists in B, and their descriptors
 *       differ, the drift is reported as {@code MODIFIED} (single-method
 *       descriptor change).</li>
 *   <li>If there are multiple methods with name {@code N} on either side
 *       (an overload set), each overload is tracked independently. A
 *       descriptor present in B but absent from A is reported as
 *       {@code ADDED}; the symmetric case is {@code REMOVED}. This is the
 *       load-bearing rule for the test
 *       {@code MethodSignatureAnalyzerTest.overloadAdditionIsAddedNotModified}.</li>
 * </ul>
 *
 * <h2>Pure function</h2>
 * The class is stateless and thread-safe; instances are safe to reuse
 * across threads. None of the public methods retain references to the
 * input byte arrays after returning.
 */
public final class AsmClassDiffer {

    private static final int ASM_API = Opcodes.ASM9;

    /**
     * Compare two class files and return every detected drift.
     *
     * @param bytecodeA  class A's raw bytes (must be a valid {@code .class} file)
     * @param bytecodeB  class B's raw bytes (must be a valid {@code .class} file)
     * @return ordered list of {@link SignatureDrift}; empty when both sides
     *         declare identical method sets
     */
    public List<SignatureDrift> differ(byte[] bytecodeA, byte[] bytecodeB) {
        Objects.requireNonNull(bytecodeA, "bytecodeA");
        Objects.requireNonNull(bytecodeB, "bytecodeB");

        ClassNode classA = parse(bytecodeA);
        ClassNode classB = parse(bytecodeB);

        Map<String, Map<String, MethodInfo>> byNameA = indexMethods(classA);
        Map<String, Map<String, MethodInfo>> byNameB = indexMethods(classB);

        Set<String> allNames = new LinkedHashSet<>();
        allNames.addAll(byNameA.keySet());
        allNames.addAll(byNameB.keySet());

        List<SignatureDrift> drifts = new ArrayList<>();
        for (String name : allNames) {
            Map<String, MethodInfo> overloadsA = byNameA.getOrDefault(name, Map.of());
            Map<String, MethodInfo> overloadsB = byNameB.getOrDefault(name, Map.of());

            boolean singleA = overloadsA.size() == 1;
            boolean singleB = overloadsB.size() == 1;
            if (singleA && singleB) {
                String descA = overloadsA.keySet().iterator().next();
                String descB = overloadsB.keySet().iterator().next();
                MethodInfo infoA = overloadsA.get(descA);
                MethodInfo infoB = overloadsB.get(descB);
                if (descA.equals(descB)) {
                    if (isVisibilityChange(infoA.access, infoB.access)) {
                        drifts.add(new SignatureDrift(
                            name, descB, SignatureDrift.Kind.MODIFIED,
                            "visibility: " + visibilityLabel(infoA.access)
                                + " -> " + visibilityLabel(infoB.access),
                            infoA.access, infoB.access));
                    }
                } else {
                    StringBuilder detail = new StringBuilder();
                    detail.append("descriptor: ").append(descA).append(" -> ").append(descB);
                    if (isVisibilityChange(infoA.access, infoB.access)) {
                        detail.append("; visibility: ")
                              .append(visibilityLabel(infoA.access))
                              .append(" -> ")
                              .append(visibilityLabel(infoB.access));
                    }
                    drifts.add(new SignatureDrift(
                        name, descB, SignatureDrift.Kind.MODIFIED,
                        detail.toString(), infoA.access, infoB.access));
                }
            } else {
                // Overload set on at least one side. Treat each descriptor
                // independently — that's the bug-fix rule from P1.5.1.
                for (Map.Entry<String, MethodInfo> entry : overloadsB.entrySet()) {
                    if (!overloadsA.containsKey(entry.getKey())) {
                        drifts.add(new SignatureDrift(
                            name, entry.getKey(), SignatureDrift.Kind.ADDED,
                            "new in B", 0, entry.getValue().access));
                    }
                }
                for (Map.Entry<String, MethodInfo> entry : overloadsA.entrySet()) {
                    if (!overloadsB.containsKey(entry.getKey())) {
                        drifts.add(new SignatureDrift(
                            name, entry.getKey(), SignatureDrift.Kind.REMOVED,
                            "removed in B", entry.getValue().access, 0));
                    }
                }
            }
        }

        return drifts;
    }

    /**
     * Decide whether two access values represent a meaningful visibility
     * change. The {@code public} ↔ {@code protected} boundary counts;
     * toggles of {@code static}/{@code final} do not, because those are
     * usually source-level refactors that don't change call dispatch.
     *
     * <p>Package-private is encoded as "neither public nor protected nor
     * private" in the access bits, so any protected→package or
     * public→package flip will be detected here.
     */
    static boolean isVisibilityChange(int accessA, int accessB) {
        boolean pubA = (accessA & Opcodes.ACC_PUBLIC) != 0;
        boolean protA = (accessA & Opcodes.ACC_PROTECTED) != 0;
        boolean pubB = (accessB & Opcodes.ACC_PUBLIC) != 0;
        boolean protB = (accessB & Opcodes.ACC_PROTECTED) != 0;
        boolean packageA = !pubA && !protA && (accessA & Opcodes.ACC_PRIVATE) == 0;
        boolean packageB = !pubB && !protB && (accessB & Opcodes.ACC_PRIVATE) == 0;

        if (pubA != pubB) return true;
        if (protA != protB) return true;
        return packageA != packageB;
    }

    static String visibilityLabel(int access) {
        if ((access & Opcodes.ACC_PUBLIC) != 0) return "public";
        if ((access & Opcodes.ACC_PROTECTED) != 0) return "protected";
        if ((access & Opcodes.ACC_PRIVATE) != 0) return "private";
        return "package";
    }

    private static ClassNode parse(byte[] bytecode) {
        ClassReader reader = new ClassReader(bytecode);
        ClassNode node = new ClassNode(ASM_API);
        reader.accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    /**
     * Index methods grouped by name, with each overload's {@link MethodInfo}
     * keyed by descriptor. The double-map shape makes the "single method on
     * both sides" path O(1) and the overload set path a clean per-descriptor
     * walk.
     */
    private static Map<String, Map<String, MethodInfo>> indexMethods(ClassNode cls) {
        Map<String, Map<String, MethodInfo>> byName = new LinkedHashMap<>();
        if (cls.methods == null) return byName;
        for (MethodNode mn : cls.methods) {
            if (mn.name.equals("<init>") || mn.name.equals("<clinit>")) continue;
            if ((mn.access & Opcodes.ACC_PRIVATE) != 0) continue;
            String desc = mn.desc == null ? "()" : mn.desc;
            byName.computeIfAbsent(mn.name, k -> new LinkedHashMap<>())
                .put(desc, new MethodInfo(mn.name, desc, mn.access));
        }
        return byName;
    }

    /** Compact form of a method relevant to the diff (no body, no annotations). */
    private record MethodInfo(String name, String descriptor, int access) {}
}
