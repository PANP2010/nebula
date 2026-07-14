package org.nebula.player;

import org.junit.jupiter.api.Test;
import org.nebula.core.math.Vec3;
import org.nebula.core.player.PlayerField;
import org.nebula.core.player.PlayerPhysicsState;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Unit tests for {@link PlayerDivergenceSampler}.
 *
 * <p>The sampler feeds {@link PlayerAuthorityGate#recordTick(int, int)} per
 * tick. These tests bypass the Player entity entirely by overriding the
 * sampler to expose its {@code matches(...)} decision directly — the
 * Player→location→CAS comparison is just plumbing, and testing it requires
 * either a mocking library or 200 lines of interface stubs.
 *
 * <p>Since {@link PlayerAuthorityGate} is final we can't extend it; we use
 * a real gate with a deliberately-unreachable threshold so it never
 * transitions, then assert on its public counters after each tick.
 */
class PlayerDivergenceSamplerTest {

    /** A threshold high enough that the gate never transitions. */
    private static final int HIGH_THRESHOLD = Integer.MAX_VALUE;

    @Test
    void matchedPlayerFeedsMatchedTick() {
        PlayerPhysicsState cas = new PlayerPhysicsState();
        UUID id = UUID.randomUUID();
        cas.put(new PlayerField(id, "position"), new Vec3(10, 64, 10));

        PlayerAuthorityGate gate = new PlayerAuthorityGate("PLAYER_MOVE", HIGH_THRESHOLD);
        PlayerDivergenceSampler sampler = new PlayerDivergenceSampler(cas, gate, gate);

        int[] result = sampler.sample(List.of(), id, new Vec3(10, 64, 10));

        assertEquals(1, result[0]);
        assertEquals(1, result[1]);
        assertEquals(1L, gate.totalTicks());
        assertEquals(1L, gate.matchedTicks());
        assertEquals(1, gate.consecutiveMatches());
    }

    @Test
    void mismatchedPositionIsRecordedAsUnmatched() {
        PlayerPhysicsState cas = new PlayerPhysicsState();
        UUID id = UUID.randomUUID();
        cas.put(new PlayerField(id, "position"), new Vec3(10, 64, 10));

        PlayerAuthorityGate gate = new PlayerAuthorityGate("PLAYER_MOVE", HIGH_THRESHOLD);
        PlayerDivergenceSampler sampler = new PlayerDivergenceSampler(cas, gate, gate);

        int[] result = sampler.sample(List.of(), id, new Vec3(50, 64, 10));

        assertEquals(0, result[0]);
        assertEquals(1, result[1]);
        assertEquals(1L, gate.totalTicks());
        assertEquals(0L, gate.matchedTicks());
        assertEquals(0, gate.consecutiveMatches());
    }

    @Test
    void missingCasEntryIsRecordedAsUnmatched() {
        // CAS has no entries — sentinel is Vec3.ZERO.
        PlayerPhysicsState cas = new PlayerPhysicsState();
        PlayerAuthorityGate gate = new PlayerAuthorityGate("PLAYER_MOVE", HIGH_THRESHOLD);
        PlayerDivergenceSampler sampler = new PlayerDivergenceSampler(cas, gate, gate);

        int[] result = sampler.sample(List.of(), UUID.randomUUID(), new Vec3(0, 0, 0));

        assertEquals(0, result[0]);
        assertEquals(1, result[1]);
        assertEquals(1L, gate.totalTicks());
        assertEquals(0L, gate.matchedTicks());
    }

    @Test
    void epsilonToleranceSubBlock() {
        PlayerPhysicsState cas = new PlayerPhysicsState();
        UUID id = UUID.randomUUID();
        cas.put(new PlayerField(id, "position"), new Vec3(10, 64, 10));

        PlayerAuthorityGate gate = new PlayerAuthorityGate("PLAYER_MOVE", HIGH_THRESHOLD);
        PlayerDivergenceSampler sampler = new PlayerDivergenceSampler(cas, gate, gate);

        // Off by 0.5 * EPSILON — should still match.
        int[] result = sampler.sample(List.of(), id,
            new Vec3(10 + PlayerDivergenceSampler.EPSILON * 0.5, 64, 10));

        assertEquals(1, result[0]);
        assertEquals(1, result[1]);
        assertEquals(1, gate.consecutiveMatches());
    }

    @Test
    void epsilonToleranceBeyondThreshold() {
        PlayerPhysicsState cas = new PlayerPhysicsState();
        UUID id = UUID.randomUUID();
        cas.put(new PlayerField(id, "position"), new Vec3(10, 64, 10));

        PlayerAuthorityGate gate = new PlayerAuthorityGate("PLAYER_MOVE", HIGH_THRESHOLD);
        PlayerDivergenceSampler sampler = new PlayerDivergenceSampler(cas, gate, gate);

        // Off by 2 * EPSILON — clearly outside tolerance.
        int[] result = sampler.sample(List.of(), id,
            new Vec3(10 + PlayerDivergenceSampler.EPSILON * 2, 64, 10));

        assertEquals(0, result[0]);
        assertEquals(1, result[1]);
        assertEquals(0, gate.consecutiveMatches());
    }

    @Test
    void streakAccumulatesAcrossMatchedTicks() {
        PlayerPhysicsState cas = new PlayerPhysicsState();
        UUID id = UUID.randomUUID();
        cas.put(new PlayerField(id, "position"), new Vec3(10, 64, 10));

        PlayerAuthorityGate gate = new PlayerAuthorityGate("PLAYER_MOVE", HIGH_THRESHOLD);
        PlayerDivergenceSampler sampler = new PlayerDivergenceSampler(cas, gate, gate);

        for (int i = 0; i < 5; i++) {
            sampler.sample(List.of(), id, new Vec3(10, 64, 10));
        }
        assertEquals(5L, gate.totalTicks());
        assertEquals(5L, gate.matchedTicks());
        assertEquals(5, gate.consecutiveMatches());
    }

    @Test
    void streakResetsOnMismatch() {
        PlayerPhysicsState cas = new PlayerPhysicsState();
        UUID id = UUID.randomUUID();
        cas.put(new PlayerField(id, "position"), new Vec3(10, 64, 10));

        PlayerAuthorityGate gate = new PlayerAuthorityGate("PLAYER_MOVE", HIGH_THRESHOLD);
        PlayerDivergenceSampler sampler = new PlayerDivergenceSampler(cas, gate, gate);

        sampler.sample(List.of(), id, new Vec3(10, 64, 10));
        sampler.sample(List.of(), id, new Vec3(10, 64, 10));
        assertEquals(2, gate.consecutiveMatches());

        // Diverging tick resets the streak.
        sampler.sample(List.of(), id, new Vec3(99, 64, 10));
        assertEquals(0, gate.consecutiveMatches());

        // Next matched tick restarts the streak from 1.
        sampler.sample(List.of(), id, new Vec3(10, 64, 10));
        assertEquals(1, gate.consecutiveMatches());
    }

    @Test
    void diagnosticsIsNonEmpty() {
        PlayerAuthorityGate gate = new PlayerAuthorityGate("PLAYER_MOVE", 100);
        PlayerDivergenceSampler sampler = new PlayerDivergenceSampler(new PlayerPhysicsState(), gate, gate);
        String diag = sampler.diagnostics();
        assertNotEquals("", diag);
        assertEquals(true, diag.contains("PLAYER_MOVE"));
    }
}