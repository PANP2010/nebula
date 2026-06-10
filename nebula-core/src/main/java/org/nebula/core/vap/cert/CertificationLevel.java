package org.nebula.core.vap.cert;

import org.nebula.core.vap.VapLevel;

/**
 * Plugin certification level (NEBULA-PATCH-2026-001 §变更三, §13.7.1).
 *
 * <p>Maps the user-facing badge tiers onto the technical {@link VapLevel}:
 * <ul>
 *   <li>{@link #NEBULA_READY} (green) — Level 0 compatible, runs without
 *       runtime errors; no speedup (plugin executes serially).</li>
 *   <li>{@link #NEBULA_OPTIMIZED} (silver) — Level 1 adapted, key data
 *       structures use {@code @ManagedState}; partial parallel speedup.</li>
 *   <li>{@link #NEBULA_NATIVE} (gold) — Level 2 adapted, uses the native
 *       data-flow API; full DAG parallel participation.</li>
 * </ul>
 */
public enum CertificationLevel {
    NEBULA_READY("green", VapLevel.LEVEL_0),
    NEBULA_OPTIMIZED("silver", VapLevel.LEVEL_1),
    NEBULA_NATIVE("gold", VapLevel.LEVEL_2);

    private final String badge;
    private final VapLevel vapLevel;

    CertificationLevel(String badge, VapLevel vapLevel) {
        this.badge = badge;
        this.vapLevel = vapLevel;
    }

    /** Badge colour shown in the compatibility catalog. */
    public String badge() {
        return badge;
    }

    /** The technical VAP level this certification corresponds to. */
    public VapLevel vapLevel() {
        return vapLevel;
    }

    /** Maps a {@link VapLevel} to its certification tier, or null for SANDBOXED. */
    public static CertificationLevel fromVapLevel(VapLevel level) {
        for (CertificationLevel c : values()) {
            if (c.vapLevel == level) return c;
        }
        return null; // SANDBOXED has no certification tier (fallback isolation)
    }
}
