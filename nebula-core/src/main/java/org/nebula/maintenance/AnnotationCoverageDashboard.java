package org.nebula.maintenance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Annotation coverage dashboard (NEBULA-PATCH-2026-001 §变更一, §14.3.5 组件 C).
 *
 * <p>Tracks the health of the {@code @NebulaRW} annotation assets per subsystem:
 * coverage ratio (annotated / total hotspot methods), annotation "debt" (Level 2
 * methods awaiting re-annotation), and migration progress after a Minecraft
 * update. The patch designates this the project's core health indicator.
 *
 * <p>Targets the patch's decay goal: keep annotation decay below 5%/year.
 */
public final class AnnotationCoverageDashboard {

    /** Per-subsystem coverage stats. */
    public record SubsystemCoverage(
        String subsystem,
        int annotatedMethods,
        int totalHotspotMethods,
        int level2DebtMethods
    ) {
        public SubsystemCoverage {
            if (annotatedMethods < 0 || totalHotspotMethods < 0 || level2DebtMethods < 0) {
                throw new IllegalArgumentException("counts must be non-negative");
            }
            if (annotatedMethods > totalHotspotMethods) {
                throw new IllegalArgumentException("annotated cannot exceed total hotspots");
            }
        }

        /** Coverage ratio in [0,1]; 1.0 when there are no hotspot methods. */
        public double coverageRatio() {
            return totalHotspotMethods == 0 ? 1.0 : (double) annotatedMethods / totalHotspotMethods;
        }
    }

    private final Map<String, SubsystemCoverage> subsystems = new ConcurrentHashMap<>();

    public void report(SubsystemCoverage coverage) {
        subsystems.put(coverage.subsystem(), coverage);
    }

    public List<SubsystemCoverage> subsystems() {
        return subsystems.values().stream()
            .sorted((a, b) -> a.subsystem().compareTo(b.subsystem()))
            .toList();
    }

    /** Overall coverage across all subsystems (total annotated / total hotspots). */
    public double overallCoverageRatio() {
        long annotated = 0;
        long total = 0;
        for (SubsystemCoverage c : subsystems.values()) {
            annotated += c.annotatedMethods();
            total += c.totalHotspotMethods();
        }
        return total == 0 ? 1.0 : (double) annotated / total;
    }

    /** Total Level 2 annotation debt awaiting re-annotation across subsystems. */
    public int totalLevel2Debt() {
        int debt = 0;
        for (SubsystemCoverage c : subsystems.values()) {
            debt += c.level2DebtMethods();
        }
        return debt;
    }

    /**
     * Whether overall coverage still meets the health bar after a version update.
     * The patch's decay goal is &lt;5%/year, i.e. coverage should stay above
     * {@code 1 - 0.05 = 0.95} of its prior baseline. Returns true if current
     * overall coverage is at least {@code 0.95 * priorBaseline}.
     */
    public boolean meetsDecayTarget(double priorBaseline) {
        return overallCoverageRatio() >= 0.95 * priorBaseline;
    }

    /** Diagnostic summary for the dashboard view. */
    public String summary() {
        if (subsystems.isEmpty()) return "Annotation coverage: no subsystems reported";
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Annotation coverage: overall=%.1f%%, level2-debt=%d%n",
            overallCoverageRatio() * 100, totalLevel2Debt()));
        Map<String, SubsystemCoverage> ordered = new LinkedHashMap<>();
        subsystems().forEach(c -> ordered.put(c.subsystem(), c));
        for (SubsystemCoverage c : ordered.values()) {
            sb.append(String.format("  %-16s %.1f%% (%d/%d), debt=%d%n",
                c.subsystem(), c.coverageRatio() * 100,
                c.annotatedMethods(), c.totalHotspotMethods(), c.level2DebtMethods()));
        }
        return sb.toString();
    }
}
