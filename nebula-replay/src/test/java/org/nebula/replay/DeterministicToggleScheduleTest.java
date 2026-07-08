package org.nebula.replay;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeterministicToggleScheduleTest {

    private static final List<String> SOURCES = List.of("a", "b", "c");

    @Test
    void sameSeedProducesIdenticalStream() {
        DeterministicToggleSchedule s1 = new DeterministicToggleSchedule(SOURCES, 12345L, 8);
        DeterministicToggleSchedule s2 = new DeterministicToggleSchedule(SOURCES, 12345L, 8);

        for (long t = 0; t < 500; t++) {
            assertEquals(s1.actionsForTick(t), s2.actionsForTick(t),
                "streams must match tick-for-tick at t=" + t);
        }
    }

    @Test
    void differentSeedGenerallyDivergesInPhase() {
        DeterministicToggleSchedule s1 = new DeterministicToggleSchedule(SOURCES, 1L, 8);
        DeterministicToggleSchedule s2 = new DeterministicToggleSchedule(SOURCES, 2L, 8);
        // Not a hard guarantee for every seed pair, but these two must differ
        // somewhere in a full period window, otherwise the phase derivation is
        // not seed-sensitive.
        boolean anyDiff = false;
        for (long t = 0; t < 64 && !anyDiff; t++) {
            if (!s1.actionsForTick(t).equals(s2.actionsForTick(t))) {
                anyDiff = true;
            }
        }
        assertTrue(anyDiff, "distinct seeds should yield distinct phasing");
    }

    @Test
    void firstFlipOfEachSourcePowersOn() {
        DeterministicToggleSchedule s = new DeterministicToggleSchedule(SOURCES, 99L, 5);
        // Track the first action seen per source across a window; it must be powered=true.
        var seen = new java.util.HashMap<String, Boolean>();
        for (long t = 0; t < 200; t++) {
            for (ToggleAction a : s.actionsForTick(t)) {
                seen.putIfAbsent(a.sourceId(), a.powered());
            }
        }
        assertEquals(SOURCES.size(), seen.size(), "every source must flip at least once");
        for (var e : seen.entrySet()) {
            assertTrue(e.getValue(), "first flip of " + e.getKey() + " must be powered=true");
        }
    }

    @Test
    void flipsAlternateForASingleSource() {
        DeterministicToggleSchedule s = new DeterministicToggleSchedule(List.of("solo"), 7L, 3);
        List<Boolean> states = new ArrayList<>();
        for (long t = 0; t < 300; t++) {
            for (ToggleAction a : s.actionsForTick(t)) {
                states.add(a.powered());
            }
        }
        assertFalse(states.isEmpty());
        for (int i = 0; i < states.size(); i++) {
            assertEquals(i % 2 == 0, states.get(i),
                "flip " + i + " should alternate starting from powered=true");
        }
    }

    @Test
    void periodOneFlipsEveryTick() {
        DeterministicToggleSchedule s = new DeterministicToggleSchedule(List.of("x"), 0L, 1);
        for (long t = 0; t < 50; t++) {
            List<ToggleAction> actions = s.actionsForTick(t);
            assertEquals(1, actions.size(), "period=1 fires every tick at t=" + t);
            assertEquals(t % 2 == 0, actions.get(0).powered());
        }
    }

    @Test
    void actionsAreInSourceListOrder() {
        // period=1 makes every source fire every tick, so order is directly observable.
        DeterministicToggleSchedule s = new DeterministicToggleSchedule(SOURCES, 42L, 1);
        List<ToggleAction> actions = s.actionsForTick(10);
        assertEquals(SOURCES.size(), actions.size());
        for (int i = 0; i < SOURCES.size(); i++) {
            assertEquals(SOURCES.get(i), actions.get(i).sourceId());
        }
    }

    @Test
    void totalActionsMatchesEnumeratedStream() {
        DeterministicToggleSchedule s = new DeterministicToggleSchedule(SOURCES, 555L, 7);
        long tickCount = 1000;
        long enumerated = 0;
        for (long t = 0; t < tickCount; t++) {
            enumerated += s.actionsForTick(t).size();
        }
        assertEquals(enumerated, s.totalActions(tickCount),
            "closed-form totalActions must match the enumerated stream");
    }

    @Test
    void phasesStaySpreadWithinPeriod() {
        // With many sources and a modest period, at least some ticks should carry
        // no action — proving phases spread activity rather than firing all at once.
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) many.add("s" + i);
        DeterministicToggleSchedule s = new DeterministicToggleSchedule(many, 314159L, 16);
        boolean sawEmptyTick = false;
        for (long t = 0; t < 16; t++) {
            if (s.actionsForTick(t).isEmpty()) { sawEmptyTick = true; break; }
        }
        assertTrue(sawEmptyTick, "phase spreading should leave some ticks idle within a period");
    }

    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class,
            () -> new DeterministicToggleSchedule(List.of(), 0L, 4));
        assertThrows(IllegalArgumentException.class,
            () -> new DeterministicToggleSchedule(SOURCES, 0L, 0));
        DeterministicToggleSchedule s = new DeterministicToggleSchedule(SOURCES, 0L, 4);
        assertThrows(IllegalArgumentException.class, () -> s.actionsForTick(-1));
        assertThrows(IllegalArgumentException.class, () -> s.totalActions(-1));
    }
}
