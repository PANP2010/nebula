package org.nebula.player;

import org.bukkit.entity.Player;
import org.nebula.core.math.Vec3;
import org.nebula.core.player.PlayerField;
import org.nebula.core.player.PlayerPhysicsState;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Compares Nebula's computed player position against Folia's authoritative
 * position after each tick, then feeds the result into a
 * {@link PlayerAuthorityGate} so the gate can open once K consecutive
 * matched ticks have been observed.
 *
 * <p>The earlier design left this hook unwired: {@code NebulaPlugin}
 * constructed the gate but never called {@code recordTick}, so it was
 * stuck in {@code OBSERVING} forever and the {@code syncPhysicsToNms}
 * write-back path stayed cold. This sampler closes the loop without
 * changing the gate's API.
 *
 * <p>Match criterion: per-axis Euclidean distance ≤ {@link #EPSILON}
 * (1e-3 blocks — well under sub-pixel). If {@link PlayerPhysicsState}
 * has no entry for the player (e.g. first tick after teleport), the
 * sample is recorded as unmatched so the gate doesn't get a free streak.
 */
public final class PlayerDivergenceSampler {

    /** Per-axis tolerance in blocks. */
    public static final double EPSILON = 1e-3;

    private static final Logger LOG = Logger.getLogger(PlayerDivergenceSampler.class.getName());

    private final PlayerPhysicsState cas;
    private final PlayerAuthorityGate moveGate;
    private final PlayerAuthorityGate blockGate;

    public PlayerDivergenceSampler(PlayerPhysicsState cas,
                                   PlayerAuthorityGate moveGate,
                                   PlayerAuthorityGate blockGate) {
        this.cas = Objects.requireNonNull(cas, "cas");
        this.moveGate = Objects.requireNonNull(moveGate, "moveGate");
        this.blockGate = Objects.requireNonNull(blockGate, "blockGate");
    }

    /**
     * Sample one tick's worth of players.
     *
     * @param players the players that participated in this tick's DAG
     * @return a 2-int array {@code [matched, total]} for diagnostics
     */
    public int[] sample(List<Player> players) {
        int matched = 0;
        int total = players.size();
        if (total == 0) {
            // Don't feed a 0/0 into the gate — a "matched=total=0" tick
            // would otherwise reset the streak prematurely on a quiet tick.
            moveGate.recordTick(0, 1);
            blockGate.recordTick(0, 1);
            return new int[]{0, 1};
        }
        for (Player p : players) {
            if (matches(p)) matched++;
        }
        final int unmatched = total - matched;
        moveGate.recordTick(matched, total);
        blockGate.recordTick(matched, total);
        if (unmatched > 0) {
            LOG.fine(() -> "Player divergence: " + unmatched
                + "/" + total + " mismatched positions this tick");
        }
        return new int[]{matched, total};
    }

    /**
     * Test-bypass overload: feed a single {@code (UUID, Vec3)} pair as if
     * it were one Player's CAS-recorded position vs. the live Folia
     * position. Kept package-private so the production {@link #sample} path
     * stays the only way the gate is fed from real runtime code.
     */
    int[] sample(List<Player> ignored, UUID playerId, Vec3 livePos) {
        int matched = matches(playerId, livePos) ? 1 : 0;
        moveGate.recordTick(matched, 1);
        blockGate.recordTick(matched, 1);
        return new int[]{matched, 1};
    }

    private boolean matches(Player player) {
        return matches(player.getUniqueId(), new Vec3(
            player.getLocation().getX(),
            player.getLocation().getY(),
            player.getLocation().getZ()));
    }

    private boolean matches(UUID id, Vec3 livePos) {
        PlayerField field = new PlayerField(id, "position");
        Vec3 nebulaPos = cas.getVec(field);
        // Sentinel: Vec3.ZERO means CAS has no record for this player.
        if (nebulaPos == null || nebulaPos.equals(Vec3.ZERO)) {
            return false;
        }
        double dx = Math.abs(livePos.x() - nebulaPos.x());
        double dy = Math.abs(livePos.y() - nebulaPos.y());
        double dz = Math.abs(livePos.z() - nebulaPos.z());
        return dx <= EPSILON && dy <= EPSILON && dz <= EPSILON;
    }

    /** Diagnostics string for {@code /nebula player status}. */
    public String diagnostics() {
        return "move=" + moveGate.diagnostics()
            + " block=" + blockGate.diagnostics();
    }
}