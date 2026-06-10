package org.nebula.core.vap.cert;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One entry in the Nebula plugin compatibility catalog
 * (NEBULA-PATCH-2026-001 §变更三, §13.7.3).
 *
 * @param pluginName     plugin name
 * @param version        certified plugin version
 * @param level          certification tier
 * @param knownLimits    human-readable known limitations (may be blank)
 * @param lastVerified   date this certification was last validated
 * @param msptContribution measured MSPT contribution under the standard test
 *                         load, in milliseconds (negative if unknown)
 */
public record PluginCertification(
    String pluginName,
    String version,
    CertificationLevel level,
    String knownLimits,
    LocalDate lastVerified,
    double msptContribution
) {
    public PluginCertification {
        Objects.requireNonNull(pluginName, "pluginName");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(lastVerified, "lastVerified");
        knownLimits = knownLimits == null ? "" : knownLimits;
        if (pluginName.isBlank()) {
            throw new IllegalArgumentException("pluginName must not be blank");
        }
    }

    /**
     * Per §13.7.2, certifications are re-validated yearly (or after a Minecraft
     * major update). Returns true if the certification is older than one year
     * relative to {@code asOf} and should be re-verified.
     */
    public boolean isStale(LocalDate asOf) {
        return lastVerified.plusYears(1).isBefore(asOf);
    }
}
