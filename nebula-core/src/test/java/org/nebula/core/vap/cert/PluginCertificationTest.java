package org.nebula.core.vap.cert;

import org.junit.jupiter.api.Test;
import org.nebula.core.vap.VapLevel;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginCertificationTest {

    private static PluginCertification cert(String name, CertificationLevel level, LocalDate verified) {
        return new PluginCertification(name, "1.0.0", level, "", verified, 0.5);
    }

    // ── CertificationLevel ↔ VapLevel mapping ────────────────────────────────

    @Test
    void certificationLevelsMapToVapLevels() {
        assertEquals(VapLevel.LEVEL_0, CertificationLevel.NEBULA_READY.vapLevel());
        assertEquals(VapLevel.LEVEL_1, CertificationLevel.NEBULA_OPTIMIZED.vapLevel());
        assertEquals(VapLevel.LEVEL_2, CertificationLevel.NEBULA_NATIVE.vapLevel());
    }

    @Test
    void badgesMatchPatchSpec() {
        assertEquals("green", CertificationLevel.NEBULA_READY.badge());
        assertEquals("silver", CertificationLevel.NEBULA_OPTIMIZED.badge());
        assertEquals("gold", CertificationLevel.NEBULA_NATIVE.badge());
    }

    @Test
    void fromVapLevelRoundTrips() {
        for (CertificationLevel c : CertificationLevel.values()) {
            assertEquals(c, CertificationLevel.fromVapLevel(c.vapLevel()));
        }
    }

    @Test
    void sandboxedHasNoCertificationTier() {
        assertNull(CertificationLevel.fromVapLevel(VapLevel.SANDBOXED));
    }

    // ── PluginCertification staleness ─────────────────────────────────────────

    @Test
    void certificationStaleAfterOneYear() {
        PluginCertification c = cert("EssentialsX", CertificationLevel.NEBULA_READY,
            LocalDate.of(2026, 1, 1));
        assertFalse(c.isStale(LocalDate.of(2026, 6, 1)), "5 months old is fresh");
        assertFalse(c.isStale(LocalDate.of(2027, 1, 1)), "exactly one year is not yet stale");
        assertTrue(c.isStale(LocalDate.of(2027, 1, 2)), "past one year is stale");
    }

    @Test
    void blankNameRejected() {
        try {
            new PluginCertification("  ", "1.0", CertificationLevel.NEBULA_READY, "",
                LocalDate.now(), 0.0);
            org.junit.jupiter.api.Assertions.fail("blank name should be rejected");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ── Catalog ───────────────────────────────────────────────────────────────

    @Test
    void catalogLookupIsCaseInsensitive() {
        PluginCertificationCatalog cat = new PluginCertificationCatalog();
        cat.register(cert("WorldGuard", CertificationLevel.NEBULA_OPTIMIZED, LocalDate.now()));
        assertTrue(cat.find("worldguard").isPresent());
        assertTrue(cat.find("WORLDGUARD").isPresent());
        assertEquals(CertificationLevel.NEBULA_OPTIMIZED, cat.find("WorldGuard").get().level());
    }

    @Test
    void catalogLatestRegistrationWins() {
        PluginCertificationCatalog cat = new PluginCertificationCatalog();
        cat.register(cert("LuckPerms", CertificationLevel.NEBULA_READY, LocalDate.now()));
        cat.register(cert("LuckPerms", CertificationLevel.NEBULA_NATIVE, LocalDate.now()));
        assertEquals(1, cat.size());
        assertEquals(CertificationLevel.NEBULA_NATIVE, cat.find("LuckPerms").get().level());
    }

    @Test
    void catalogFiltersByLevel() {
        PluginCertificationCatalog cat = new PluginCertificationCatalog();
        cat.register(cert("A", CertificationLevel.NEBULA_READY, LocalDate.now()));
        cat.register(cert("B", CertificationLevel.NEBULA_NATIVE, LocalDate.now()));
        cat.register(cert("C", CertificationLevel.NEBULA_NATIVE, LocalDate.now()));

        List<PluginCertification> gold = cat.atLevel(CertificationLevel.NEBULA_NATIVE);
        assertEquals(2, gold.size());
        assertEquals("B", gold.get(0).pluginName());
        assertEquals("C", gold.get(1).pluginName());
    }

    @Test
    void catalogReportsStaleEntries() {
        PluginCertificationCatalog cat = new PluginCertificationCatalog();
        cat.register(cert("Old", CertificationLevel.NEBULA_READY, LocalDate.of(2024, 1, 1)));
        cat.register(cert("Fresh", CertificationLevel.NEBULA_READY, LocalDate.of(2026, 6, 1)));

        List<PluginCertification> stale = cat.staleAsOf(LocalDate.of(2026, 6, 10));
        assertEquals(1, stale.size());
        assertEquals("Old", stale.get(0).pluginName());
    }

    @Test
    void missingPluginReturnsEmpty() {
        assertEquals(Optional.empty(), new PluginCertificationCatalog().find("Nonexistent"));
    }
}
