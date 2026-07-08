package org.nebula.plugin;

import org.nebula.core.state.WorldPos;
import org.nebula.replay.CanonicalToggleSources;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ToggleSourceRegistry} — the separately-tracked lever/button
 * position set that feeds the live-load driver, plus its bridge to
 * {@link CanonicalToggleSources}.
 */
class ToggleSourceRegistryTest {

    private static final WorldPos A = new WorldPos(0, 1, -60, 1);
    private static final WorldPos B = new WorldPos(0, 5, -60, 1);
    private static final WorldPos C = new WorldPos(0, 5, -60, 9);

    @Test
    void registerTracksPosition() {
        ToggleSourceRegistry reg = new ToggleSourceRegistry();
        assertTrue(reg.register(A));
        assertTrue(reg.contains(A));
        assertEquals(1, reg.size());
    }

    @Test
    void registerIsIdempotent() {
        ToggleSourceRegistry reg = new ToggleSourceRegistry();
        assertTrue(reg.register(A));
        assertFalse(reg.register(A), "second register of same pos returns false");
        assertEquals(1, reg.size());
    }

    @Test
    void unregisterRemovesPosition() {
        ToggleSourceRegistry reg = new ToggleSourceRegistry();
        reg.register(A);
        assertTrue(reg.unregister(A));
        assertFalse(reg.contains(A));
        assertEquals(0, reg.size());
        assertFalse(reg.unregister(A), "removing an absent pos returns false");
    }

    @Test
    void nullRegisterRejected() {
        ToggleSourceRegistry reg = new ToggleSourceRegistry();
        assertThrows(IllegalArgumentException.class, () -> reg.register(null));
    }

    @Test
    void nullUnregisterAndContainsAreSafe() {
        ToggleSourceRegistry reg = new ToggleSourceRegistry();
        assertFalse(reg.unregister(null));
        assertFalse(reg.contains(null));
    }

    @Test
    void snapshotIsAStableCopy() {
        ToggleSourceRegistry reg = new ToggleSourceRegistry();
        reg.register(A);
        Set<WorldPos> snap = reg.snapshot();
        // Mutating the registry after snapshot must not affect the returned view.
        reg.register(B);
        assertEquals(Set.of(A), snap);
        assertThrows(UnsupportedOperationException.class, () -> snap.add(C));
    }

    @Test
    void canonicalIsOrderIndependent() {
        // The registry's whole reason to exist: feed CanonicalToggleSources so the
        // driven toggle stream depends only on the SET of positions, not on the
        // order they were registered. Register in two different orders, expect the
        // identical canonical source-id list.
        ToggleSourceRegistry forward = new ToggleSourceRegistry();
        forward.register(A);
        forward.register(B);
        forward.register(C);

        ToggleSourceRegistry reverse = new ToggleSourceRegistry();
        reverse.register(C);
        reverse.register(B);
        reverse.register(A);

        List<String> idsForward = forward.canonical().sourceIds();
        List<String> idsReverse = reverse.canonical().sourceIds();
        assertEquals(idsForward, idsReverse);
        // And the ids resolve back to exactly the registered positions.
        assertEquals(
            Set.of(CanonicalToggleSources.sourceIdFor(A),
                   CanonicalToggleSources.sourceIdFor(B),
                   CanonicalToggleSources.sourceIdFor(C)),
            Set.copyOf(idsForward));
    }

    @Test
    void emptyRegistryProducesEmptyPlan() {
        ToggleSourceRegistry reg = new ToggleSourceRegistry();
        assertEquals(0, reg.canonical().size());
    }
}
