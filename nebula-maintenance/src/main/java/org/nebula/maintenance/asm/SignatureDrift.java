package org.nebula.maintenance.asm;

import java.util.Objects;

/**
 * A single method-signature drift between two bytecode versions, in the
 * lightweight form used by {@link MethodSignatureAnalyzer} and
 * {@link AsmClassDiffer} (Phase 1.5, NEBULA-PATCH-2026-001 §14.3.5 组件 A).
 *
 * <p>This record is the simple, byte-array-level counterpart to
 * {@link MethodDelta}. It carries only what a caller reading raw class files
 * cares about: method name, JVM descriptor, the kind of drift, and a
 * human-readable detail string for reports. The richer
 * {@code Optional<BytecodeMethodDescriptor>} view used by the in-memory
 * detector is intentionally absent here — the {@code accessA} /
 * {@code accessB} flags are surfaced as raw {@code int}s so a downstream
 * tool can decide which bits matter (visibility vs {@code static} vs
 * {@code final}) without juggling nested records.
 *
 * <h2>Kind taxonomy</h2>
 * <ul>
 *   <li>{@link Kind#ADDED} — present in B but absent from A.</li>
 *   <li>{@link Kind#REMOVED} — present in A but absent from B.</li>
 *   <li>{@link Kind#MODIFIED} — present in both with the same name but a
 *       different JVM descriptor, or with access flags that changed.
 *       Crucially, overloads (same name, different descriptor) on either
 *       side are <em>not</em> collapsed into {@code MODIFIED}: the
 *       detector treats each (name, descriptor) pair independently and
 *       reports overload additions/removals as {@code ADDED}/{@code REMOVED}.</li>
 * </ul>
 *
 * @param methodName  the method's name (e.g. {@code "tick"})
 * @param descriptor  the JVM descriptor (e.g. {@code "()V"})
 * @param kind        the {@link Kind} classification
 * @param detail      human-readable explanation (e.g. {@code "added in B"})
 * @param accessA     access flags from class A (0 if {@link Kind#ADDED})
 * @param accessB     access flags from class B (0 if {@link Kind#REMOVED})
 */
public record SignatureDrift(
    String methodName,
    String descriptor,
    Kind kind,
    String detail,
    int accessA,
    int accessB
) {
    public SignatureDrift {
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(kind, "kind");
        detail = detail == null ? "" : detail;
    }

    /** Convenience constructor for the common case where access flags are not needed. */
    public SignatureDrift(String methodName, String descriptor, Kind kind, String detail) {
        this(methodName, descriptor, kind, detail, 0, 0);
    }

    /** Drift classification. */
    public enum Kind {
        /** Method exists in B but not in A. */
        ADDED,
        /** Method exists in A but not in B. */
        REMOVED,
        /** Method exists in both but the descriptor or access flags differ. */
        MODIFIED
    }
}
