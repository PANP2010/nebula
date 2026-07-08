package org.nebula.plugin;

import org.nebula.core.state.WorldPos;
import org.nebula.redstone.RedstoneComponentType;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Pure decision for the DG3 multi-region lever-source seeding race: given the
 * dirty seed positions of one {@code executeOwnedDag} invocation, returns the
 * additional <em>source-neighbour</em> positions whose authoritative NMS power
 * must be pulled into the CAS store <em>before</em> the microstep cascade reads
 * them.
 *
 * <h3>The race this closes</h3>
 * {@code executeOwnedDag}'s Phase 1 {@code syncFromNms}es only the seed
 * positions; the cascade ({@code RedstoneWireAction}) then reads every
 * neighbour's power from the CAS store. A <b>source</b> (lever/button/torch/
 * redstone block — anything that is not a wire) feeds an adjacent wire its power
 * <em>undecayed</em>. But a source only lands in CAS if the source position
 * itself fired a {@code BLOCK_UPDATE} and was drained as a seed this tick — and
 * whether that happens depends on region-thread timing during an OFF→ON toggle.
 * When it does not, the wire reads the source's CAS as {@code -1} (unset →
 * treated as 0) and the whole downstream line collapses to 0. That is the
 * run-varying {@code nebula=-1}-at-the-lever divergence the {@code --settled}
 * gate exposes (memory dg3-settled-gate-multiregion-race).
 *
 * <p>Seeding a source's power into CAS from its <em>wire</em> neighbour's seed
 * task is deterministic: a wire directly next to a toggled lever reliably fires
 * a {@code BLOCK_UPDATE} when the lever flips (its own power changes), so the
 * wire is always a seed — regardless of whether the lever's own event was
 * drained in time. Pulling the source into CAS off that reliable wire seed makes
 * every circuit converge, not a timing-dependent subset.
 *
 * <h3>Same-chunk only</h3>
 * {@code executeOwnedDag} runs on the region thread that owns the seed's chunk.
 * A neighbour in a <em>different</em> chunk may be owned by a different region,
 * and reading a block off its owning region thread NPEs on Folia. So this planner
 * returns only neighbours sharing the seed's chunk coordinates — always safe to
 * {@code syncFromNms} from the same region task. Cross-chunk source seeding
 * (dispatching a sub-task to the source's owning region) is a later slice; the
 * common lever-adjacent-to-wire case is same-chunk.
 *
 * <p>This is a pure function over positions and a component-type lookup — no
 * Folia/NMS types — so it is fully unit-testable off the hot path.
 */
public final class SourceSeedPlanner {

    private static final int[][] NEIGHBOURS = {
        {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}
    };

    private SourceSeedPlanner() {
    }

    /**
     * Computes the extra source-neighbour positions to seed into CAS.
     *
     * @param seeds  the dirty seed positions already scheduled to sync this tick
     * @param typeOf resolves a position's registered {@link RedstoneComponentType},
     *               or returns {@code null} for an unregistered position
     * @return the same-chunk neighbours of {@code seeds} that are registered
     *         non-wire sources and are not themselves seeds; deterministic
     *         insertion order
     */
    public static Set<WorldPos> plan(Collection<WorldPos> seeds,
                                     Function<WorldPos, RedstoneComponentType> typeOf) {
        Objects.requireNonNull(seeds, "seeds");
        Objects.requireNonNull(typeOf, "typeOf");

        Set<WorldPos> extra = new LinkedHashSet<>();
        for (WorldPos seed : seeds) {
            if (seed == null) {
                continue;
            }
            int seedChunkX = seed.x() >> 4;
            int seedChunkZ = seed.z() >> 4;
            for (int[] d : NEIGHBOURS) {
                WorldPos n = new WorldPos(seed.dimensionId(),
                    seed.x() + d[0], seed.y() + d[1], seed.z() + d[2]);
                // Same chunk as the seed → same region → safe to read here.
                if ((n.x() >> 4) != seedChunkX || (n.z() >> 4) != seedChunkZ) {
                    continue;
                }
                if (seeds.contains(n)) {
                    continue; // already synced in Phase 1
                }
                RedstoneComponentType t = typeOf.apply(n);
                if (t != null && t != RedstoneComponentType.REDSTONE_WIRE) {
                    extra.add(n);
                }
            }
        }
        return extra;
    }
}
