package org.nebula.replay;

/**
 * The single game-layer seam that the live-load zero-diff driver
 * ({@link LiveLoadToggleDriver}) delegates to. Applying a lever toggle to a
 * concrete block position must happen on that position's owning region thread
 * on Folia, which requires Folia/NMS types the pure {@code nebula-replay}
 * module deliberately does not depend on — so that final step is expressed
 * here as an interface for the plugin/adapter layer to implement.
 *
 * <p>Splitting this out keeps {@link LiveLoadToggleDriver}'s
 * schedule→resolution logic fully unit-testable (a test supplies a recording
 * fake), while isolating the one part that genuinely needs the live-Folia
 * decisive experiment into a small, well-scoped implementation.
 *
 * <h3>Contract for implementors</h3>
 * <ul>
 *   <li>The apply must be dispatched to the region thread that owns
 *       {@code toggle.position()} (e.g. via {@code RegionScheduler.execute}),
 *       never performed inline on the global tick thread.</li>
 *   <li>Setting {@code powered} must fire the same neighbour-update path a
 *       player pulling the lever would, so the DAG shadow observes it — a bare
 *       block-state write that suppresses updates would defeat the purpose.</li>
 * </ul>
 */
@FunctionalInterface
public interface ToggleApplier {

    /**
     * Applies a single resolved toggle to the world at the scheduled tick.
     *
     * @param tick   the game tick this toggle belongs to (for logging/ordering)
     * @param toggle the resolved position + target powered state
     */
    void apply(long tick, ResolvedToggle toggle);
}
