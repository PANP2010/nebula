package org.nebula.replay;

import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of all player inputs for a single tick.
 * Captures movement packets, interaction packets, chat packets — everything
 * that feeds into {@code F(S_t, I_t)}.
 *
 * @param tickNumber the game tick this input belongs to
 * @param playerInputs per-player ordered list of packet payloads (serialised bytes)
 */
public record TickInput(long tickNumber, Map<String, List<byte[]>> playerInputs) {
    public TickInput {
        playerInputs = Map.copyOf(playerInputs);
    }
}
