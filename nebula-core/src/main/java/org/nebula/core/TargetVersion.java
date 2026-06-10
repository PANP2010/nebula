package org.nebula.core;

/**
 * Target Minecraft version anchor for Nebula V1.0
 * (NEBULA-PATCH-2026-001 §变更五; arch doc §0.X).
 *
 * <p>The patch locks V1.0 to a single Minecraft version rather than chasing
 * Mojang upstream, so the annotation assets have a stable verification baseline.
 * Version migration to newer releases is the job of the Phase 1.5 annotation
 * maintenance subsystem ({@code org.nebula.maintenance}), not of V1.0.
 *
 * <p>This class centralises the version string so it is declared once rather
 * than scattered as literals across annotations, docs, and build config.
 */
public final class TargetVersion {

    private TargetVersion() {}

    /** The Minecraft version Nebula V1.0 targets (Mojang, December 2024). */
    public static final String MINECRAFT_VERSION = "1.21.4";

    /** The Folia API line that pairs with the target Minecraft version. */
    public static final String FOLIA_API_LINE = "26.1.x";

    /**
     * Returns whether the given {@code @NebulaRW(verifiedAt=...)} value matches
     * the locked target version. Annotations verified against a different
     * version must be re-validated by the Phase 1.5 maintenance toolchain.
     *
     * @param verifiedAt the annotation's {@code verifiedAt} value (may be blank)
     * @return true if it exactly matches {@link #MINECRAFT_VERSION}
     */
    public static boolean isCurrent(String verifiedAt) {
        return MINECRAFT_VERSION.equals(verifiedAt);
    }
}
