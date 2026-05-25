package org.nebula.folia.bridge;

import org.junit.jupiter.api.Test;
import org.nebula.guard.RWGuard;
import org.nebula.guard.RWGuardConfig;
import org.nebula.guard.RWGuardMode;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NebulaFoliaBootstrapTest {
    @Test
    void configuresRwGuardForFoliaBridge() {
        RWGuardConfig config = RWGuardConfig.enabled(RWGuardMode.WARN);

        NebulaFoliaBootstrap bootstrap = NebulaFoliaBootstrap.configure(config);

        assertEquals(config, bootstrap.guardConfig());
        assertEquals(config, RWGuard.config());
    }
}
