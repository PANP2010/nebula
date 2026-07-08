package org.nebula.plugin;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;
import org.nebula.redstone.RedstoneComponentType;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for {@link SourceSeedPlanner} — the pure decision behind the DG3
 * multi-region lever-source seeding-race fix. No Folia/NMS types, so it runs on
 * the plain test classpath.
 */
final class SourceSeedPlannerTest {

    private static WorldPos p(int x, int y, int z) {
        return new WorldPos(0, x, y, z);
    }

    @Test
    void seedsALeverSourceAdjacentToAWireSeed() {
        WorldPos lever = p(0, -60, 0);
        WorldPos wire = p(1, -60, 0);
        Map<WorldPos, RedstoneComponentType> comp = Map.of(
            lever, RedstoneComponentType.REDSTONE_TORCH,   // levers register as torch
            wire, RedstoneComponentType.REDSTONE_WIRE);

        Set<WorldPos> extra = SourceSeedPlanner.plan(List.of(wire), comp::get);

        assertEquals(Set.of(lever), extra,
            "the lever neighbour of a wire seed must be pulled into CAS");
    }

    @Test
    void doesNotSeedWireNeighbours() {
        WorldPos w0 = p(0, -60, 0);
        WorldPos w1 = p(1, -60, 0);
        WorldPos w2 = p(2, -60, 0);
        Map<WorldPos, RedstoneComponentType> comp = Map.of(
            w0, RedstoneComponentType.REDSTONE_WIRE,
            w1, RedstoneComponentType.REDSTONE_WIRE,
            w2, RedstoneComponentType.REDSTONE_WIRE);

        // w1 is the seed; its wire neighbours must NOT be added (they decay,
        // they are not sources, and the cascade already handles wire→wire).
        Set<WorldPos> extra = SourceSeedPlanner.plan(List.of(w1), comp::get);

        assertTrue(extra.isEmpty(), "wire neighbours are not source-seeds");
    }

    @Test
    void doesNotSeedPositionsAlreadyAmongSeeds() {
        WorldPos lever = p(0, -60, 0);
        WorldPos wire = p(1, -60, 0);
        Map<WorldPos, RedstoneComponentType> comp = Map.of(
            lever, RedstoneComponentType.REDSTONE_TORCH,
            wire, RedstoneComponentType.REDSTONE_WIRE);

        // Lever already seeded (its own event was drained) — do not double-sync.
        Set<WorldPos> extra = SourceSeedPlanner.plan(List.of(wire, lever), comp::get);

        assertFalse(extra.contains(lever), "a source already among the seeds is skipped");
        assertTrue(extra.isEmpty());
    }

    @Test
    void skipsUnregisteredNeighbours() {
        WorldPos wire = p(1, -60, 0);
        // Only the wire is registered; all its neighbours are air/unknown.
        Map<WorldPos, RedstoneComponentType> comp = Map.of(
            wire, RedstoneComponentType.REDSTONE_WIRE);

        Set<WorldPos> extra = SourceSeedPlanner.plan(List.of(wire), comp::get);

        assertTrue(extra.isEmpty(), "unregistered neighbours are not sources");
    }

    @Test
    void doesNotCrossChunkBoundaries() {
        // Wire at the last column of chunk 0 (x=15); its +X neighbour x=16 is in
        // chunk 1, owned by a possibly-different region — must NOT be read here.
        WorldPos wire = p(15, -60, 0);
        WorldPos crossChunkLever = p(16, -60, 0);
        WorldPos sameChunkLever = p(14, -60, 0);
        Map<WorldPos, RedstoneComponentType> comp = Map.of(
            wire, RedstoneComponentType.REDSTONE_WIRE,
            crossChunkLever, RedstoneComponentType.REDSTONE_TORCH,
            sameChunkLever, RedstoneComponentType.REDSTONE_TORCH);

        Set<WorldPos> extra = SourceSeedPlanner.plan(List.of(wire), comp::get);

        assertEquals(Set.of(sameChunkLever), extra,
            "only the same-chunk source is seeded; the cross-chunk one is skipped");
        assertFalse(extra.contains(crossChunkLever));
    }

    @Test
    void redstoneBlockSourceIsSeeded() {
        WorldPos block = p(0, -60, 0);
        WorldPos wire = p(1, -60, 0);
        Map<WorldPos, RedstoneComponentType> comp = Map.of(
            block, RedstoneComponentType.REDSTONE_BLOCK,
            wire, RedstoneComponentType.REDSTONE_WIRE);

        Set<WorldPos> extra = SourceSeedPlanner.plan(List.of(wire), comp::get);

        assertEquals(Set.of(block), extra,
            "a constant power source (redstone block) is seeded like a lever");
    }

    @Test
    void emptySeedsYieldNoExtra() {
        assertTrue(SourceSeedPlanner.plan(List.of(), pos -> null).isEmpty());
    }
}
