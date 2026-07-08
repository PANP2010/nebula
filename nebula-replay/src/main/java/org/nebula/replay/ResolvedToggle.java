package org.nebula.replay;

import org.nebula.core.state.WorldPos;

/**
 * A {@link ToggleAction} whose opaque {@code sourceId} has been resolved to a
 * concrete block position, ready for the game layer to apply on the position's
 * owning region thread.
 *
 * <p>Produced by {@link LiveLoadToggleDriver#resolveForTick(long)}. This is the
 * last purely-computable step before the tick pipeline: it carries a real
 * {@link WorldPos} but still no Folia/NMS types, so the resolution stays
 * unit-testable and the only remaining work is the region-thread apply.
 *
 * @param position the block position of the toggle source (e.g. a lever)
 * @param powered  the target powered state to apply
 */
public record ResolvedToggle(WorldPos position, boolean powered) {
    public ResolvedToggle {
        if (position == null) {
            throw new IllegalArgumentException("position must not be null");
        }
    }
}
