package org.nebula.core.vap.cert;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PluginCertificationHarness} (arch doc §13.7; patch
 * §13.7.3). The harness drives the
 * {@link PluginCertificationCatalog} from a synthetic probe set — the
 * missing wiring that left {@code /nebula plugins} with an empty catalog
 * when the project boots up.
 */
class PluginCertificationHarnessTest {

    @Test
    void fullPassRateRegistersAsNebulaReady() {
        PluginCertificationCatalog cat = new PluginCertificationCatalog();
        PluginCertificationHarness h = new PluginCertificationHarness(cat);

        List<PluginCertificationHarness.Probe> probes = List.of(
            passProbe("a"),
            passProbe("b"),
            passProbe("c")
        );
        PluginCertificationHarness.CertificationResult r = h.runAndRegister(
            "EssentialsX", "1.0.0", probes, "no known issues");

        assertEquals(3, r.totalProbes());
        assertEquals(3, r.passedProbes());
        assertEquals(CertificationLevel.NEBULA_READY, r.level());
        assertEquals(CertificationLevel.NEBULA_READY, cat.find("EssentialsX").orElseThrow().level());
    }

    @Test
    void singleFailureDowngradesToOptimized() {
        PluginCertificationCatalog cat = new PluginCertificationCatalog();
        PluginCertificationHarness h = new PluginCertificationHarness(cat);

        List<PluginCertificationHarness.Probe> probes = List.of(
            passProbe("ok1"),
            passProbe("ok2"),
            passProbe("ok3"),
            passProbe("ok4"),
            failProbe("boom")
        );
        PluginCertificationHarness.CertificationResult r = h.runAndRegister(
            "WorldGuard", "7.0", probes, "");

        assertEquals(5, r.totalProbes());
        assertEquals(4, r.passedProbes());
        assertEquals(CertificationLevel.NEBULA_OPTIMIZED, r.level());
        assertNotNull(cat.find("WorldGuard"));
    }

    @Test
    void majorityFailureDropsBelowOptimization() {
        PluginCertificationCatalog cat = new PluginCertificationCatalog();
        PluginCertificationHarness h = new PluginCertificationHarness(cat);

        // 1 pass / 3 probes = 33% → below L2 threshold, no certification.
        List<PluginCertificationHarness.Probe> probes = List.of(
            passProbe("ok"),
            failProbe("boom1"),
            failProbe("boom2")
        );
        PluginCertificationHarness.CertificationResult r = h.runAndRegister(
            "FaultyPlugin", "0.1", probes, "");

        assertNull(r.level(), "<50% must yield no certification");
        // The catalog still records the attempt; the level slot stays
        // null because decideLevel returned null — registering a
        // PluginCertification requires a non-null level, so the harness
        // must NOT register this run. Verify the catalog is empty.
        assertFalse(cat.find("FaultyPlugin").isPresent(),
            "below-50% runs must not pollute the catalog");
    }

    @Test
    void exactThresholdBoundaryAt50Percent() {
        // 1 pass + 1 fail = 50% exactly → L2 (>= L2 threshold inclusive).
        PluginCertificationCatalog cat = new PluginCertificationCatalog();
        PluginCertificationHarness h = new PluginCertificationHarness(cat);

        List<PluginCertificationHarness.Probe> probes = List.of(
            passProbe("p"),
            failProbe("f")
        );
        PluginCertificationHarness.CertificationResult r = h.runAndRegister(
            "Boundary", "0.0", probes, "");
        assertEquals(CertificationLevel.NEBULA_NATIVE, r.level());
    }

    @Test
    void emptyProbeSetRegistersNothing() {
        PluginCertificationCatalog cat = new PluginCertificationCatalog();
        PluginCertificationHarness h = new PluginCertificationHarness(cat);
        PluginCertificationHarness.CertificationResult r = h.runAndRegister(
            "Empty", "1.0", List.of(), "");
        assertEquals(0, r.totalProbes());
        assertNull(r.level());
        assertFalse(cat.find("Empty").isPresent());
    }

    @Test
    void decideLevelThresholdMatrix() {
        assertEquals(CertificationLevel.NEBULA_READY, PluginCertificationHarness.decideLevel(1.0));
        assertEquals(CertificationLevel.NEBULA_OPTIMIZED, PluginCertificationHarness.decideLevel(0.95));
        assertEquals(CertificationLevel.NEBULA_OPTIMIZED, PluginCertificationHarness.decideLevel(0.80));
        assertEquals(CertificationLevel.NEBULA_NATIVE, PluginCertificationHarness.decideLevel(0.79));
        assertEquals(CertificationLevel.NEBULA_NATIVE, PluginCertificationHarness.decideLevel(0.50));
        assertNull(PluginCertificationHarness.decideLevel(0.49));
        assertNull(PluginCertificationHarness.decideLevel(0.0));
    }

    @Test
    void knownLimitsFlowIntoCatalog() {
        PluginCertificationCatalog cat = new PluginCertificationCatalog();
        PluginCertificationHarness h = new PluginCertificationHarness(cat);

        PluginCertificationHarness.CertificationResult r = h.runAndRegister(
            "LimPlugin", "1.0", List.of(passProbe("ok")),
            "Cannot consume EVENT_HIGH_PRIORITY");
        assertTrue(r.knownLimits().contains("EVENT_HIGH_PRIORITY"));
        assertEquals("EVENT_HIGH_PRIORITY",
            cat.find("LimPlugin").orElseThrow().knownLimits().contains("EVENT_HIGH_PRIORITY")
                ? "EVENT_HIGH_PRIORITY"
                : "");
    }

    @Test
    void probeResultsPreserveOrderAndPassFlag() {
        PluginCertificationCatalog cat = new PluginCertificationCatalog();
        PluginCertificationHarness h = new PluginCertificationHarness(cat);

        List<PluginCertificationHarness.Probe> probes = List.of(
            passProbe("first"),
            failProbe("second"),
            passProbe("third")
        );
        PluginCertificationHarness.CertificationResult r = h.runAndRegister(
            "ProbeOrder", "1.0", probes, "");

        assertEquals(3, r.probeResults().size());
        assertEquals("first", r.probeResults().get(0).name());
        assertTrue(r.probeResults().get(0).passed());
        assertEquals("second", r.probeResults().get(1).name());
        assertFalse(r.probeResults().get(1).passed());
        assertTrue(r.probeResults().get(1).errorDetail().contains("boom"));
    }

    @Test
    void canonicalProbeSetSmokeRunsClean() {
        // Verify the bundled probe set itself runs without throwing.
        PluginCertificationCatalog cat = new PluginCertificationCatalog();
        PluginCertificationHarness h = new PluginCertificationHarness(cat);
        PluginCertificationHarness.CertificationResult r = h.runAndRegister(
            "CanonicalPlugin", "1.0",
            PluginCertificationHarness.canonicalProbeSet("CanonicalPlugin"),
            "");
        assertEquals(4, r.totalProbes());
        assertEquals(4, r.passedProbes());
        assertEquals(CertificationLevel.NEBULA_READY, r.level());
    }

    @Test
    void multipleCertificationsCoexistInCatalog() {
        PluginCertificationCatalog cat = new PluginCertificationCatalog();
        PluginCertificationHarness h = new PluginCertificationHarness(cat);

        h.runAndRegister("A", "1.0", List.of(passProbe("a")), "");
        h.runAndRegister("B", "1.0", List.of(passProbe("a"), passProbe("b"),
            failProbe("c"), failProbe("d"), failProbe("e")), "");
        h.runAndRegister("C", "1.0",
            List.of(passProbe("a"), failProbe("b"), failProbe("c")), "");

        // A: 1/1 = 100% → L0, registered.
        // B: 2/5 = 40% → below L2 (50%) threshold → NOT registered.
        // C: 1/3 = 33% → below L2 threshold → NOT registered.
        assertEquals(1, cat.size(),
            "only A qualifies (100%); B(40%) and C(33%) fall below L2 threshold");
        assertEquals(CertificationLevel.NEBULA_READY,
            cat.find("A").orElseThrow().level());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static PluginCertificationHarness.Probe passProbe(String name) {
        return new PluginCertificationHarness.Probe() {
            public String name() { return name; }
            public void run() { /* success */ }
        };
    }

    private static PluginCertificationHarness.Probe failProbe(String name) {
        return new PluginCertificationHarness.Probe() {
            public String name() { return name; }
            public void run() { throw new RuntimeException("boom"); }
        };
    }
}
