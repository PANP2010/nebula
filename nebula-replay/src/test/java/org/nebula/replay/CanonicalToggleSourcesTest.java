package org.nebula.replay;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalToggleSourcesTest {

    private static final WorldPos A = new WorldPos(0, 0, -60, 0);
    private static final WorldPos B = new WorldPos(0, 16, -60, 0);
    private static final WorldPos C = new WorldPos(0, 16, -60, 5);
    private static final WorldPos D = new WorldPos(1, -100, 64, -100);

    @Test
    void sourceIdMatchesWorldPosParseFormat() {
        String id = CanonicalToggleSources.sourceIdFor(B);
        assertEquals("0:16,-60,0", id);
        // The id round-trips through WorldPos.parse — the game layer can resolve a
        // sourceId back to a position without a side map.
        assertEquals(B, WorldPos.parse(id));
    }

    @Test
    void ordersSourcesByWorldPosTotalOrder() {
        // Feed them out of order; expect canonical (dim,x,y,z) order out.
        var plan = CanonicalToggleSources.of(List.of(D, C, A, B));
        assertEquals(
            List.of("0:0,-60,0", "0:16,-60,0", "0:16,-60,5", "1:-100,64,-100"),
            plan.sourceIds());
    }

    @Test
    void bindingsCoverEverySourceAndRoundTrip() {
        var plan = CanonicalToggleSources.of(List.of(A, B, C, D));
        assertEquals(4, plan.size());
        for (String id : plan.sourceIds()) {
            WorldPos bound = plan.bindings().get(id);
            assertNotNull(bound, "every source must be bound");
            assertEquals(id, CanonicalToggleSources.sourceIdFor(bound),
                "binding must be the position the id was derived from");
        }
    }

    /**
     * The core correctness property of this slice: the produced sourceIds depend
     * only on the SET of positions, never on the order they were enumerated in.
     * This is what prevents ConcurrentHashMap.keySet() iteration order from
     * silently producing different schedules (and false zero-diff divergence)
     * between two runs of the same world.
     */
    @Test
    void sourceOrderIsIndependentOfInputEnumerationOrder() {
        List<WorldPos> positions = new ArrayList<>(List.of(A, B, C, D));
        List<String> reference = CanonicalToggleSources.of(positions).sourceIds();

        // Every permutation must yield the identical canonical order.
        List<List<WorldPos>> shuffles = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            List<WorldPos> copy = new ArrayList<>(positions);
            Collections.shuffle(copy, new java.util.Random(i));
            shuffles.add(copy);
        }
        // Also feed a Set with non-insertion iteration semantics.
        shuffles.add(new ArrayList<>(new LinkedHashSet<>(List.of(D, A, C, B))));

        for (List<WorldPos> shuffle : shuffles) {
            assertEquals(reference, CanonicalToggleSources.of(shuffle).sourceIds(),
                "canonical order must not depend on input order: " + shuffle);
        }
    }

    @Test
    void deduplicatesRepeatedPositions() {
        var plan = CanonicalToggleSources.of(Arrays.asList(A, A, B, A, B));
        assertEquals(List.of("0:0,-60,0", "0:16,-60,0"), plan.sourceIds());
        assertEquals(2, plan.size());
    }

    /**
     * The whole reason canonical ordering matters: DeterministicToggleSchedule
     * derives per-source phase from list INDEX, so two runs must produce not just
     * the same ids but the same TOGGLE STREAM. Build the schedule two ways from
     * differently-ordered inputs and assert the streams are identical tick-for-tick.
     */
    @Test
    void scheduleStreamIsIdenticalRegardlessOfInputOrder() {
        long seed = 0xC0FFEEL;
        int period = 7;
        var planFwd = CanonicalToggleSources.of(List.of(A, B, C, D));
        var planRev = CanonicalToggleSources.of(List.of(D, C, B, A));
        var schedFwd = planFwd.newSchedule(seed, period);
        var schedRev = planRev.newSchedule(seed, period);

        for (long tick = 0; tick < 100; tick++) {
            assertEquals(schedFwd.actionsForTick(tick), schedRev.actionsForTick(tick),
                "toggle stream must match at tick " + tick);
        }
    }

    @Test
    void newDriverResolvesToggleStreamToBoundPositions() {
        var plan = CanonicalToggleSources.of(List.of(A, B));
        // period 1 => every source flips every tick; tick 0 => all powered=true.
        var driver = plan.newDriver(1L, 1, null);
        List<ResolvedToggle> resolved = driver.resolveForTick(0);
        Set<WorldPos> positions = new LinkedHashSet<>();
        for (ResolvedToggle t : resolved) {
            positions.add(t.position());
            assertTrue(t.powered(), "first flip drives powered=true");
        }
        assertEquals(Set.of(A, B), positions);
    }

    @Test
    void newDriverDrivesThroughApplier() {
        var plan = CanonicalToggleSources.of(List.of(A, B));
        List<String> applied = new ArrayList<>();
        ToggleApplier applier = (tick, toggle) ->
            applied.add(tick + ":" + toggle.position() + "=" + toggle.powered());
        var driver = plan.newDriver(1L, 1, applier);
        driver.driveTick(0);
        // Both sources fire on tick 0, in canonical source order (A before B).
        assertEquals(List.of(
            "0:" + A + "=true",
            "0:" + B + "=true"), applied);
    }

    @Test
    void newDriverBindingsSatisfyFailFast() {
        // Every schedule source is bound by construction, so the driver's
        // fail-fast (unbound source) must never trip from this path.
        var plan = CanonicalToggleSources.of(List.of(A, B, C));
        assertDoesNotThrow(() -> plan.newDriver(99L, 3, null));
    }

    @Test
    void emptyPlanHasNoSourcesAndScheduleRejectsIt() {
        var plan = CanonicalToggleSources.of(List.of());
        assertEquals(0, plan.size());
        assertTrue(plan.sourceIds().isEmpty());
        assertTrue(plan.bindings().isEmpty());
        // DeterministicToggleSchedule requires ≥1 source, so building one from an
        // empty plan fails loudly rather than producing a no-op driver.
        assertThrows(IllegalArgumentException.class, () -> plan.newSchedule(1L, 1));
    }

    @Test
    void rejectsNullCollectionAndNullElements() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalToggleSources.of(null));
        List<WorldPos> withNull = new ArrayList<>();
        withNull.add(A);
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> CanonicalToggleSources.of(withNull));
    }

    @Test
    void bindingsAndSourceIdsAreUnmodifiable() {
        var plan = CanonicalToggleSources.of(List.of(A));
        assertThrows(UnsupportedOperationException.class,
            () -> plan.sourceIds().add("x"));
        assertThrows(UnsupportedOperationException.class,
            () -> plan.bindings().put("x", A));
    }
}
