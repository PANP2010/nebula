package org.nebula.replay;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LiveLoadToggleDriverTest {

    private static final List<String> SOURCES = List.of("a", "b", "c");

    private static Map<String, WorldPos> bindingsFor(List<String> ids) {
        Map<String, WorldPos> m = new HashMap<>();
        int i = 0;
        for (String id : ids) {
            m.put(id, new WorldPos(0, i * 16, -60, 0));
            i++;
        }
        return m;
    }

    /** A ToggleApplier that records everything applied, for asserting on the stream. */
    private static final class RecordingApplier implements ToggleApplier {
        final List<String> log = new ArrayList<>();
        @Override public void apply(long tick, ResolvedToggle toggle) {
            log.add(tick + ":" + toggle.position() + "=" + toggle.powered());
        }
    }

    @Test
    void resolveMapsEachSourceToItsBoundPosition() {
        var schedule = new DeterministicToggleSchedule(SOURCES, 42L, 1); // period 1: all fire every tick
        var bindings = bindingsFor(SOURCES);
        var driver = new LiveLoadToggleDriver(schedule, bindings, null);

        List<ResolvedToggle> resolved = driver.resolveForTick(5);
        assertEquals(SOURCES.size(), resolved.size());
        // Order matches schedule source-list order, positions match bindings.
        for (int i = 0; i < SOURCES.size(); i++) {
            assertEquals(bindings.get(SOURCES.get(i)), resolved.get(i).position(),
                "resolved position must be the bound position, in source order");
        }
    }

    @Test
    void resolvePreservesScheduleActionsExactly() {
        var schedule = new DeterministicToggleSchedule(SOURCES, 777L, 6);
        var bindings = bindingsFor(SOURCES);
        var driver = new LiveLoadToggleDriver(schedule, bindings, null);

        for (long t = 0; t < 200; t++) {
            List<ToggleAction> actions = schedule.actionsForTick(t);
            List<ResolvedToggle> resolved = driver.resolveForTick(t);
            assertEquals(actions.size(), resolved.size(), "count must match at t=" + t);
            for (int i = 0; i < actions.size(); i++) {
                assertEquals(actions.get(i).powered(), resolved.get(i).powered(),
                    "powered state must be preserved at t=" + t + " idx " + i);
                assertEquals(bindings.get(actions.get(i).sourceId()), resolved.get(i).position(),
                    "position must resolve the schedule's source at t=" + t + " idx " + i);
            }
        }
    }

    @Test
    void sameSeedAndBindingsResolveIdenticalStreamTickForTick() {
        var bindings = bindingsFor(SOURCES);
        var d1 = new LiveLoadToggleDriver(
            new DeterministicToggleSchedule(SOURCES, 12345L, 8), bindings, null);
        var d2 = new LiveLoadToggleDriver(
            new DeterministicToggleSchedule(SOURCES, 12345L, 8), bindings, null);
        for (long t = 0; t < 500; t++) {
            assertEquals(d1.resolveForTick(t), d2.resolveForTick(t),
                "two runs with same seed+bindings must resolve identically at t=" + t);
        }
    }

    @Test
    void driveTickAppliesEachResolvedToggleInOrder() {
        var schedule = new DeterministicToggleSchedule(SOURCES, 42L, 1);
        var bindings = bindingsFor(SOURCES);
        var applier = new RecordingApplier();
        var driver = new LiveLoadToggleDriver(schedule, bindings, applier);

        List<ResolvedToggle> applied = driver.driveTick(3);
        assertEquals(applied.size(), applier.log.size(),
            "applier must be invoked once per resolved toggle");
        // The recorded log must mirror the resolved list exactly and in order.
        for (int i = 0; i < applied.size(); i++) {
            assertEquals("3:" + applied.get(i).position() + "=" + applied.get(i).powered(),
                applier.log.get(i));
        }
    }

    @Test
    void driveTickWithoutApplierIsRejected() {
        var driver = new LiveLoadToggleDriver(
            new DeterministicToggleSchedule(SOURCES, 1L, 4), bindingsFor(SOURCES), null);
        assertThrows(IllegalStateException.class, () -> driver.driveTick(0));
    }

    @Test
    void missingBindingFailsFastAtConstruction() {
        var schedule = new DeterministicToggleSchedule(SOURCES, 1L, 4);
        Map<String, WorldPos> partial = new HashMap<>();
        partial.put("a", new WorldPos(0, 0, -60, 0));
        partial.put("b", new WorldPos(0, 16, -60, 0));
        // "c" is unbound — must throw rather than silently drop it.
        var ex = assertThrows(IllegalArgumentException.class,
            () -> new LiveLoadToggleDriver(schedule, partial, null));
        assertTrue(ex.getMessage().contains("c"),
            "error should name the unbound source, got: " + ex.getMessage());
    }

    @Test
    void extraBindingsAreHarmless() {
        var schedule = new DeterministicToggleSchedule(List.of("a"), 1L, 2);
        Map<String, WorldPos> bindings = new HashMap<>();
        bindings.put("a", new WorldPos(0, 0, -60, 0));
        bindings.put("unused", new WorldPos(0, 999, -60, 0));
        var driver = new LiveLoadToggleDriver(schedule, bindings, null); // must not throw
        for (ResolvedToggle t : driver.resolveForTick(0)) {
            assertEquals(new WorldPos(0, 0, -60, 0), t.position());
        }
    }

    @Test
    void rejectsNullArguments() {
        var schedule = new DeterministicToggleSchedule(SOURCES, 1L, 4);
        assertThrows(IllegalArgumentException.class,
            () -> new LiveLoadToggleDriver(null, bindingsFor(SOURCES), null));
        assertThrows(IllegalArgumentException.class,
            () -> new LiveLoadToggleDriver(schedule, null, null));
    }

    @Test
    void resolvedToggleRejectsNullPosition() {
        assertThrows(IllegalArgumentException.class, () -> new ResolvedToggle(null, true));
    }
}
