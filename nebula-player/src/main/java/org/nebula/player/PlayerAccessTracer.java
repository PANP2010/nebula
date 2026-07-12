package org.nebula.player;

import org.nebula.core.player.PlayerField;
import org.nebula.core.state.WorldPos;

/**
 * Traces player field reads/writes for RW-guard verification.
 * Mirrors {@link org.nebula.entity.EntityAccessTracer}.
 */
public interface PlayerAccessTracer {

    void onFieldRead(PlayerField field);

    void onFieldWrite(PlayerField field);

    void onBlockRead(WorldPos pos);
}
