package org.nebula.replay;

import java.util.HexFormat;

/**
 * One tick in a replay file: the input that went in and the state hash that came out.
 *
 * @param tickNumber the game tick
 * @param input      player inputs for this tick
 * @param stateHash  SHA-256 of the serialised world state after this tick
 */
public record ReplayFrame(long tickNumber, TickInput input, byte[] stateHash) {
    public ReplayFrame {
        stateHash = stateHash.clone();
    }

    /** Hex-encoded state hash for logging and comparison. */
    public String stateHashHex() {
        return HexFormat.of().formatHex(stateHash);
    }
}
