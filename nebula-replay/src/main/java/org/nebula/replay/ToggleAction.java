package org.nebula.replay;

/**
 * A single deterministic input to apply during a live-load capture: set a named
 * toggle-able source (typically a lever) to a target powered state at a tick.
 *
 * <p>Produced by {@link DeterministicToggleSchedule#actionsForTick(long)}. The
 * {@code sourceId} is an opaque, stable identifier the game layer maps back to a
 * concrete block position (e.g. {@code "world:0,-60,0"}). Keeping it a string
 * here keeps the schedule pure and free of any Folia/NMS types so it stays
 * unit-testable.
 *
 * @param sourceId opaque stable identifier of the toggle source
 * @param powered  the target powered state to apply
 */
public record ToggleAction(String sourceId, boolean powered) {
    public ToggleAction {
        if (sourceId == null) {
            throw new IllegalArgumentException("sourceId must not be null");
        }
    }
}
