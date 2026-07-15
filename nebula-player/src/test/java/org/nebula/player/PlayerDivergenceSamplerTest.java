package org.nebula.player;

import org.junit.jupiter.api.Test;
import org.nebula.core.math.Vec3;
import org.nebula.core.player.PlayerField;
import org.nebula.core.player.PlayerPhysicsState;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PlayerDivergenceSampler}.
 *
 * <p>{@link PlayerAuthorityGate} is {@code final}, so these tests drive the
 * real gate and assert on its observable counters ({@code matchedTicks},
 * {@code totalTicks}, {@code consecutiveMatches}, {@code isOpen}) rather than
 * a stub. The Player→location→CAS comparison is bypassed via the
 * package-private {@code sample(List, UUID, Vec3)} overload so we don't need
 * to stub the ~200-method Bukkit {@code Player} interface.
 */
class PlayerDivergenceSamplerTest {

    private static PlayerAuthorityGate gate(int threshold) {
        return new PlayerAuthorityGate("PLAYER_MOVE", threshold);
    }

    @Test
    void matchedPlayerFeedsMatchedTick() {
        PlayerPhysicsState cas = new PlayerPhysicsState();
        UUID id = UUID.randomUUID();
        cas.put(new PlayerField(id, "position"), new Vec3(10, 64, 10));

        PlayerAuthorityGate moveGate = gate(1);
        PlayerAuthorityGate blockGate = gate(1);
        PlayerDivergenceSampler sampler = new PlayerDivergenceSampler(cas, moveGate, blockGate);

        int[] result = sampler.sample(List.of(), id, new Vec3(10, 64, 10));

        assertEquals(1, result[0]);
        assertEquals(1, result[1]);
        assertEquals(1, moveGate.matchedTicks());
        assertEquals(1, moveGate.totalTicks());
        assertEquals(1, moveGate.consecutiveMatches());
    }

    @Test
    void mismatchedPositionIsRecordedAsUnmatched() {
        PlayerPhysicsState cas = new PlayerPhysicsState();
        UUID id = UUID.randomUUID();
        cas.put(new PlayerField(id, "position"), new Vec3(10, 64, 10));

        PlayerAuthorityGate moveGate = gate(1);
        PlayerAuthorityGate blockGate = gate(1);
        PlayerDivergenceSampler sampler = new PlayerDivergenceSampler(cas, moveGate, blockGate);

        int[] result = sampler.sample(List.of(), id, new Vec3(50, 64, 10));

        assertEquals(0, result[0]);
        assertEquals(1, result[1]);
        assertEquals(0, moveGate.matchedTicks());
        assertEquals(1, moveGate.totalTicks());
        assertEquals(0, moveGate.consecutiveMatches());
    }

    @Test
    void missingCasEntryIsRecordedAsUnmatched() {
        PlayerPhysicsState cas = new PlayerPhysicsState(); // no entries

        PlayerAuthorityGate moveGate = gate(1);
        PlayerAuthorityGate blockGate = gate(1);
        PlayerDivergenceSampler sampler = new PlayerDivergenceSampler(cas, moveGate, blockGate);

        int[] result = sampler.sample(List.of(), UUID.randomUUID(), new Vec3(0, 0, 0));

        assertEquals(0, result[0]);
        assertEquals(1, result[1]);
        assertEquals(0, moveGate.matchedTicks());
    }

    @Test
    void epsilonToleranceSubBlock() {
        PlayerPhysicsState cas = new PlayerPhysicsState();
        UUID id = UUID.randomUUID();
        cas.put(new PlayerField(id, "position"), new Vec3(10, 64, 10));

        PlayerAuthorityGate moveGate = gate(1);
        PlayerAuthorityGate blockGate = gate(1);
        PlayerDivergenceSampler sampler = new PlayerDivergenceSampler(cas, moveGate, blockGate);

        int[] result = sampler.sample(List.of(), id,
            new Vec3(10 + PlayerDivergenceSampler.EPSILON * 0.5, 64, 10));

        assertEquals(1, result[0]);
        assertEquals(1, result[1]);
    }

    @Test
    void consecutiveMatchesOpenTheGate() {
        PlayerPhysicsState cas = new PlayerPhysicsState();
        UUID id = UUID.randomUUID();
        cas.put(new PlayerField(id, "position"), new Vec3(10, 64, 10));

        PlayerAuthorityGate moveGate = gate(3);
        PlayerAuthorityGate blockGate = gate(3);
        PlayerDivergenceSampler sampler = new PlayerDivergenceSampler(cas, moveGate, blockGate);

        assertFalse(moveGate.isOpen());
        for (int i = 0; i < 3; i++) {
            sampler.sample(List.of(), id, new Vec3(10, 64, 10));
        }
        assertTrue(moveGate.isOpen(), "gate should open after 3 consecutive matched ticks");
    }

    @Test
    void mismatchResetsStreak() {
        PlayerPhysicsState cas = new PlayerPhysicsState();
        UUID id = UUID.randomUUID();
        cas.put(new PlayerField(id, "position"), new Vec3(10, 64, 10));

        PlayerAuthorityGate moveGate = gate(3);
        PlayerAuthorityGate blockGate = gate(3);
        PlayerDivergenceSampler sampler = new PlayerDivergenceSampler(cas, moveGate, blockGate);

        sampler.sample(List.of(), id, new Vec3(10, 64, 10));
        sampler.sample(List.of(), id, new Vec3(10, 64, 10));
        sampler.sample(List.of(), id, new Vec3(999, 64, 10)); // mismatch
        assertEquals(0, moveGate.consecutiveMatches());
        assertFalse(moveGate.isOpen());
    }
}
