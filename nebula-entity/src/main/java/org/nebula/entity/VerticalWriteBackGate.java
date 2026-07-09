package org.nebula.entity;

/**
 * The arming predicate for <em>vertical-only</em> entity write-back (B8 C1,
 * option b of the write-back pointer).
 *
 * <h3>Why this gate exists</h3>
 * The {@link EntityDivergenceTracker} live-verified two facts on Folia 26.1.2
 * (2026-07-10): the vertical (Y) drift of {@link org.nebula.entity.actions.EntityMoveAction}
 * collapses to ~0 on the grounded-rest and straight-fall paths, while the residual
 * horizontal (X/Z) drift graded <b>STOCHASTIC</b> (bias-to-noise ratio well under
 * 1.0 over 683 samples) — i.e. it is AI pathing (wander/knockback target selection),
 * not a missing deterministic friction term. A deterministic mirror therefore
 * <em>can</em> reproduce Folia's Y on the vertical path but <em>can never</em>
 * reproduce its stochastic X/Z. Arming full write-back would teleport wandering mobs
 * onto Nebula's shadow path every tick and fight vanilla AI. So the honest write-back
 * scope is narrow: write back Y only, and only when the entity is actually on the
 * verified vertical path.
 *
 * <h3>What "on the vertical path" means</h3>
 * Both verified-zero-drift cases — a mob resting on a block and a mob in pure free-fall
 * — share one signature: their <em>horizontal</em> velocity is ≈ 0. A wandering or
 * knocked mob, whose Y a vertical mirror cannot be trusted to match (its horizontal AI
 * motion couples into collision and thus into Y), always carries a nonzero horizontal
 * velocity. So a single cheap test on the authoritative (Folia-produced) horizontal
 * speed cleanly separates the trustworthy-vertical case from the stochastic-AI case.
 *
 * <p>The speed is read from Folia's authoritative velocity (captured pre-DAG by
 * {@code syncPhysicsFromNms}), not from Nebula's prediction: the question is "is Folia
 * moving this mob sideways right now?", and only Folia's own value can answer it.
 *
 * <p>Pure and side-effect-free: fully unit-provable without a live server.
 */
public final class VerticalWriteBackGate {

    private VerticalWriteBackGate() {}

    /**
     * Horizontal speed (blocks/tick) at or below which the entity is treated as being
     * on the verified vertical path. Chosen an order of magnitude under one tick of
     * gravity (0.08) so it admits grounded rest and straight fall (both ≈ 0) while
     * excluding any real AI horizontal motion — a wandering cow moves at ~0.1/tick and
     * knockback is larger still, both far above this floor. Well above numeric jitter.
     */
    public static final double HORIZONTAL_SPEED_FLOOR = 1e-3;

    /**
     * True iff {@code authoritativeVelocity} has negligible horizontal speed, i.e. the
     * entity is resting or falling straight down and its Y is safe to mirror. When this
     * is false the entity is under horizontal AI motion (STOCHASTIC per the divergence
     * finding) and vertical write-back must be withheld — leave the whole entity to Folia.
     *
     * @param authoritativeVelocity Folia's own velocity for the entity this tick
     */
    public static boolean onVerticalPath(Vec3 authoritativeVelocity) {
        double vx = authoritativeVelocity.x();
        double vz = authoritativeVelocity.z();
        double horizontalSpeed = Math.sqrt(vx * vx + vz * vz);
        return horizontalSpeed <= HORIZONTAL_SPEED_FLOOR;
    }
}
