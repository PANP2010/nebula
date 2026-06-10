package org.nebula.entity;

import org.nebula.core.state.WorldPos;

import java.util.Set;

/**
 * Read-only terrain oracle for entity physics: tells an entity action whether a
 * block position is solid (collidable).
 *
 * <p>Terrain is treated as immutable for the duration of an entity tick — block
 * mutation is a separate subsystem with its own DAG phase — so this view needs
 * no version stamps. Entity MOVE reads the blocks its RW-set declares
 * (the cell it stands in and its neighbours) through this view to resolve
 * terrain collision deterministically.
 */
@FunctionalInterface
public interface TerrainView {

    /** True if the block at {@code pos} is solid (an entity cannot occupy it). */
    boolean isSolid(WorldPos pos);

    /** A view where nothing is solid (open void) — entities fall freely. */
    TerrainView EMPTY = pos -> false;

    /** Builds a view backed by an explicit set of solid block positions. */
    static TerrainView ofSolids(Set<WorldPos> solids) {
        Set<WorldPos> copy = Set.copyOf(solids);
        return copy::contains;
    }

    /**
     * A flat floor: every block at {@code y <= floorY} is solid, everything
     * above is air. The common deterministic test terrain.
     */
    static TerrainView flatFloor(int floorY) {
        return pos -> pos.y() <= floorY;
    }
}
