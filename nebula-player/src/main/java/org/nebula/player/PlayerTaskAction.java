package org.nebula.player;

/**
 * Executable logic for a player task. Mirrors {@link org.nebula.entity.EntityTaskAction}:
 * actions read and write player state exclusively through {@link PlayerTaskContext},
 * so all reads are versioned and all writes buffered until layer commit.
 */
@FunctionalInterface
public interface PlayerTaskAction {
    void execute(PlayerTaskContext ctx) throws Exception;
}
