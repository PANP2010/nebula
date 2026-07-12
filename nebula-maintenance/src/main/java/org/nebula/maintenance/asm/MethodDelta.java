package org.nebula.maintenance.asm;

import java.util.Objects;
import java.util.Optional;

/**
 * A single classified method-signature change between two Minecraft versions
 * (Phase 1.5, NEBULA-PATCH-2026-001 §14.3.5 组件 A).
 *
 * <p>Each delta is anchored by the method's identity key (owner + name +
 * descriptor at the new version) and classifies how the method changed
 * relative to the old version. Exactly one of {@link #oldDescriptor()} or
 * {@link #newDescriptor()} is empty depending on the diff direction:
 *
 * <ul>
 *   <li>{@link DiffLevel#NEW} — {@link #oldDescriptor()} is empty.</li>
 *   <li>{@link DiffLevel#REMOVED} — {@link #newDescriptor()} is empty.</li>
 *   <li>Other levels — both sides present (potentially with a changed descriptor
 *       or changed access flags).</li>
 * </ul>
 *
 * @param ownerClass       internal binary name of the declaring class
 * @param methodName       method name
 * @param newDescriptor    the post-update method (absent for REMOVED)
 * @param oldDescriptor    the pre-update method (absent for NEW)
 * @param level            the classified {@link DiffLevel}
 * @param note             human-readable explanation (e.g. {@code "was ()I"})
 */
public record MethodDelta(
    String ownerClass,
    String methodName,
    Optional<BytecodeMethodDescriptor> oldDescriptor,
    Optional<BytecodeMethodDescriptor> newDescriptor,
    DiffLevel level,
    String note
) {
    public MethodDelta {
        Objects.requireNonNull(ownerClass, "ownerClass");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(oldDescriptor, "oldDescriptor");
        Objects.requireNonNull(newDescriptor, "newDescriptor");
        Objects.requireNonNull(level, "level");
        note = note == null ? "" : note;
    }

    /**
     * The identity key of the new (post-update) descriptor, if any. Used by
     * the report to bucket deltas by class. Empty for {@link DiffLevel#REMOVED}.
     */
    public String newKey() {
        return newDescriptor.map(BytecodeMethodDescriptor::identityKey).orElse(null);
    }

    /**
     * The identity key of the old (pre-update) descriptor, if any. Used to
     * match deltas against the source-side {@code MethodSignature.identityKey()}
     * so a removed method's stale {@code @NebulaRW} can be located.
     */
    public String oldKey() {
        return oldDescriptor.map(BytecodeMethodDescriptor::identityKey).orElse(null);
    }

    /** Convenience: returns the post-update descriptor when present. */
    public BytecodeMethodDescriptor newDescOrNull() {
        return newDescriptor.orElse(null);
    }

    /** Convenience: returns the pre-update descriptor when present. */
    public BytecodeMethodDescriptor oldDescOrNull() {
        return oldDescriptor.orElse(null);
    }

    /** Human-readable level tag for the report (e.g. {@code "L0 - NEW METHOD"}). */
    public String levelTag() {
        return switch (level) {
            case NEW -> "L0 - NEW METHOD";
            case DESCRIPTOR_CHANGED -> "L1 - DESCRIPTOR CHANGED";
            case VISIBILITY_CHANGED -> "L2 - VISIBILITY CHANGED";
            case REMOVED -> "L2 - REMOVED";
        };
    }

    /** Convenience: the current (new) descriptor's JVM descriptor, or empty for REMOVED. */
    public String newJvmDescriptor() {
        return newDescriptor.map(BytecodeMethodDescriptor::descriptor).orElse("");
    }

    /** Convenience: the previous (old) descriptor's JVM descriptor, or empty for NEW. */
    public String oldJvmDescriptor() {
        return oldDescriptor.map(BytecodeMethodDescriptor::descriptor).orElse("");
    }
}