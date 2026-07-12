package org.nebula.maintenance.asm;

import java.util.Objects;

/**
 * Coarse classification of a method-signature change detected by
 * {@link MethodSignatureDeltaDetector} (Phase 1.5, NEBULA-PATCH-2026-001 §14.3.5 组件 A).
 *
 * <p>This is the <em>bytecode-level</em> counterpart to
 * {@code org.nebula.maintenance.ChangeLevel} (the source-level classification
 * produced by {@code SignatureDiffer}). The two classifiers share the same
 * 0/1/2 numbering but differ in input:
 *
 * <ul>
 *   <li>{@link ChangeLevel} (source) — reads decompiled {@code .java} sources;
 *       operates on the textual signature only.</li>
 *   <li>{@link DiffLevel} (bytecode) — reads compiled {@code .class} files via
 *       ASM; operates on the JVM-level descriptor, access flags, and
 *       presence/absence of the method itself.</li>
 * </ul>
 *
 * <p>The bytecode view is the durable one: a Mojang release ships as compiled
 * jars and never as decompiled sources, so the bytecode diff is what the
 * maintenance toolchain must ultimately trust. The source diff is a debug-time
 * aid; the bytecode diff is the production pipeline.
 *
 * <h2>Classification rules</h2>
 * <ul>
 *   <li>{@link #NEW} (Level 0) — method exists in the new version but not in
 *       the old. A pure addition. Candidate for {@code @NebulaRW} annotation
 *       auto-draft (no existing annotation to migrate; just produce a new one).</li>
 *   <li>{@link #DESCRIPTOR_CHANGED} (Level 1) — method exists in both, with
 *       the same name, but the JVM descriptor (parameter types or return type)
 *       differs. The {@code @NebulaRW} read/write-set on the old method may no
 *       longer match; the patcher generates a draft for human confirmation.</li>
 *   <li>{@link #VISIBILITY_CHANGED} (Level 2) — method exists in both with the
 *       same descriptor, but an access flag changed (e.g.
 *       {@code public→protected}, or {@code final→non-final}). The semantics
 *       of who-can-call and how-the-method-binds changed, so the annotation
 *       needs human review.</li>
 *   <li>{@link #REMOVED} (Level 2) — method existed in old, gone in new. The
 *       old {@code @NebulaRW} is now orphaned and must be deleted or rebound
 *       to a successor (Level 2 review).</li>
 * </ul>
 *
 * <h2>Expected automation coverage</h2>
 * Per the patch:
 * {@code NEW} (40-50%) + {@code DESCRIPTOR_CHANGED} (20-30%) ≈ 60-80% of
 * changes auto-migrate; {@code VISIBILITY_CHANGED} and {@code REMOVED} always
 * need human review.
 */
public enum DiffLevel {
    /** Level 0 — method was added in the new version (pure addition). */
    NEW(0),
    /** Level 1 — method exists but its JVM descriptor changed. */
    DESCRIPTOR_CHANGED(1),
    /** Level 2 — method exists but its access flags changed. */
    VISIBILITY_CHANGED(2),
    /** Level 2 — method existed in old but is gone in new. */
    REMOVED(2);

    private final int numericLevel;

    DiffLevel(int numericLevel) {
        this.numericLevel = numericLevel;
    }

    /**
     * The numeric L0/L1/L2 level used to align with
     * {@code org.nebula.maintenance.ChangeLevel}. Note that {@link #VISIBILITY_CHANGED}
     * and {@link #REMOVED} both report {@code 2}, matching the source-level
     * scheme where any "semantic change" is L2.
     */
    public int numericLevel() {
        return numericLevel;
    }

    /** True if a {@code @NebulaRW} patch draft can be auto-generated for this change. */
    public boolean isAutoDraftable() {
        return this == NEW || this == DESCRIPTOR_CHANGED;
    }

    /** True if the change needs human review before any annotation is touched. */
    public boolean requiresHumanReview() {
        return numericLevel >= 2;
    }

    /** True iff {@code a} and {@code b} agree on whether they need human review. */
    public static boolean sameReviewClass(DiffLevel a, DiffLevel b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        return a.requiresHumanReview() == b.requiresHumanReview();
    }
}