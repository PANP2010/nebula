package org.nebula.core.vap.cert;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Searchable plugin compatibility catalog (NEBULA-PATCH-2026-001 §变更三, §13.7.3).
 *
 * <p>Backs the {@code /nebula plugins} in-game query: server admins can look up
 * the certification status of installed plugins. Keyed by plugin name
 * (case-insensitive); the latest registration for a name wins.
 */
public final class PluginCertificationCatalog {

    private final ConcurrentHashMap<String, PluginCertification> byName = new ConcurrentHashMap<>();

    /** Registers or replaces a plugin's certification. */
    public void register(PluginCertification cert) {
        byName.put(key(cert.pluginName()), cert);
    }

    /** Looks up a plugin by name (case-insensitive). */
    public Optional<PluginCertification> find(String pluginName) {
        return Optional.ofNullable(byName.get(key(pluginName)));
    }

    /** All certifications, sorted by plugin name. */
    public List<PluginCertification> all() {
        return byName.values().stream()
            .sorted((a, b) -> a.pluginName().compareToIgnoreCase(b.pluginName()))
            .collect(Collectors.toList());
    }

    /** All certifications at a given tier, sorted by name. */
    public List<PluginCertification> atLevel(CertificationLevel level) {
        return byName.values().stream()
            .filter(c -> c.level() == level)
            .sorted((a, b) -> a.pluginName().compareToIgnoreCase(b.pluginName()))
            .collect(Collectors.toList());
    }

    /** Certifications that are stale as of {@code asOf} and need re-validation. */
    public List<PluginCertification> staleAsOf(LocalDate asOf) {
        List<PluginCertification> stale = new ArrayList<>();
        for (PluginCertification c : byName.values()) {
            if (c.isStale(asOf)) {
                stale.add(c);
            }
        }
        stale.sort((a, b) -> a.pluginName().compareToIgnoreCase(b.pluginName()));
        return stale;
    }

    public int size() {
        return byName.size();
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
