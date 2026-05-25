package org.nebula.replay;

/**
 * Interface for the game layer to implement for replay integration (arch doc §12.1).
 *
 * <p>A {@link ReplayPlayer} calls these methods to drive tick-by-tick replay execution.
 * The game implementation feeds player inputs back into the server and returns the
 * resulting state hash after each tick.
 */
public interface ReplayScheduler {

    /**
     * Applies the given player inputs to the current tick and advances the simulation.
     *
     * @param tickNumber the tick being replayed (must match sequence order)
     * @param input      the player inputs from the recorded replay frame
     */
    void applyInputs(long tickNumber, TickInput input);

    /**
     * Computes a deterministic SHA-256 hash of the current world state.
     * Must be called after {@link #applyInputs} advances the tick.
     *
     * @return 32-byte SHA-256 hash of the serialised world state
     */
    byte[] computeStateHash();

    /**
     * Returns the current game tick number.
     * Used to verify sequence alignment between recorder and player.
     */
    long currentTick();
}
