package org.nebula.maintenance;

/**
 * Method signature change classification (NEBULA-PATCH-2026-001 §变更一, §14.3.5).
 *
 * <p>The Method Signature change Detector (MSD) compares decompiled Minecraft
 * sources across versions and classifies each changed method by how it affects
 * the {@code @NebulaRW} annotation assets:
 *
 * <ul>
 *   <li>{@link #LEVEL_0} — no impact: method body unchanged, or the change does
 *       not touch read/write-set logic. The old annotation migrates
 *       automatically.</li>
 *   <li>{@link #LEVEL_1} — signature change: method renamed or parameters
 *       reordered. If an old annotation exists, a new annotation draft is
 *       generated automatically (requires human confirmation).</li>
 *   <li>{@link #LEVEL_2} — semantic change: the method's read/write-set
 *       behaviour changed. Flagged "needs re-annotation" and queued for human
 *       review.</li>
 * </ul>
 *
 * <p>Expected automation coverage (per the patch): LEVEL_0 (40-50%) + LEVEL_1
 * (20-30%) = 60-80% of methods migrate automatically.
 */
public enum ChangeLevel {
    LEVEL_0(MigrationAction.AUTO_MIGRATE),
    LEVEL_1(MigrationAction.AUTO_DRAFT),
    LEVEL_2(MigrationAction.MANUAL_REVIEW);

    /** How the maintenance toolchain handles a method at this change level. */
    public enum MigrationAction {
        /** Carry the old annotation over unchanged. */
        AUTO_MIGRATE,
        /** Generate a new annotation draft for human confirmation. */
        AUTO_DRAFT,
        /** Queue for manual re-annotation. */
        MANUAL_REVIEW
    }

    private final MigrationAction action;

    ChangeLevel(MigrationAction action) {
        this.action = action;
    }

    public MigrationAction migrationAction() {
        return action;
    }

    /** True if this change level can be migrated without human re-annotation. */
    public boolean isAutomatable() {
        return action != MigrationAction.MANUAL_REVIEW;
    }
}
