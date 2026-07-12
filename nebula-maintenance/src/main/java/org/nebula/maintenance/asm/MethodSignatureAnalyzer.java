package org.nebula.maintenance.asm;

import java.util.List;
import java.util.Objects;

/**
 * High-level, byte-array-level method-signature diff for two class files
 * (Phase 1.5, NEBULA-PATCH-2026-001 §14.3.5 组件 A).
 *
 * <p>This is the lightweight entry point most callers want: hand it two
 * class files (as {@code byte[]}, typically loaded straight from
 * {@code .class} resources or a re-compiled jar), and it returns a flat
 * list of {@link SignatureDrift} entries. The class itself is a pure
 * function — no fields, no caches, no side effects — so it is safe to
 * reuse across threads and trivially testable.
 *
 * <h2>Implementation strategy</h2>
 * Internally delegates to {@link AsmClassDiffer} which parses the bytes via
 * ASM's {@code ClassReader} → {@code ClassNode} tree API. Splitting the
 * logic lets {@code MethodSignatureAnalyzer} stay tiny and focused while
 * the heavier ASM walking lives in a separately-testable component.
 *
 * <h2>Overload handling (the bug-fix from P1.5.1)</h2>
 * Earlier drafts of this code indexed methods by name alone, which caused
 * overloads (same name, different descriptor) to be misreported as
 * {@code MODIFIED} when one overload was added on the new side. The
 * correct rule is: identity is {@code name + descriptor}, and each
 * overloaded method is tracked independently. If an overload is removed on
 * the new side it is reported as {@code REMOVED}; if one is added, as
 * {@code ADDED}. A pure descriptor change on a method that has no
 * overloads on either side remains a {@code MODIFIED}, because there is no
 * ambiguity in that case.
 */
public final class MethodSignatureAnalyzer {

    private final AsmClassDiffer inner = new AsmClassDiffer();

    /**
     * Run the diff.
     *
     * @param bytecodeA  class A's raw bytes
     * @param bytecodeB  class B's raw bytes
     * @return ordered list of {@link SignatureDrift} entries; empty when
     *         both classes declare the same public/protected/package-private
     *         method set (modulo overloads)
     */
    public List<SignatureDrift> detect(byte[] bytecodeA, byte[] bytecodeB) {
        Objects.requireNonNull(bytecodeA, "bytecodeA");
        Objects.requireNonNull(bytecodeB, "bytecodeB");
        return inner.differ(bytecodeA, bytecodeB);
    }
}
