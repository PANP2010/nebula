package org.nebula.entity.actions;

import org.nebula.core.state.WorldPos;
import org.nebula.entity.EntityTaskAction;
import org.nebula.entity.EntityTaskContext;
import org.nebula.entity.LightState;

/**
 * Light propagation task action (arch doc §10, P2.1).
 *
 * <p>Executes one BFS propagation step for block light or sky light.
 * Reads current light at self and neighbours from the supplied
 * {@link LightState}, computes new levels using the vanilla propagation
 * rule (level = max_neighbour - 1, min 0), writes updated light levels
 * back to the {@link LightState}.
 */
public final class LightPropagateAction implements EntityTaskAction {

    private final LightState lightState;
    private final WorldPos pos;
    private final int dimId;
    private final boolean isBlockLight;

    public LightPropagateAction(LightState lightState, WorldPos pos, int dimId, boolean isBlockLight) {
        this.lightState = lightState;
        this.pos = pos;
        this.dimId = dimId;
        this.isBlockLight = isBlockLight;
    }

    @Override
    public void execute(EntityTaskContext ctx) {
        // Read current light at neighbours
        int currentSelf = isBlockLight
            ? lightState.blockLightAt(pos)
            : lightState.skyLightAt(pos);

        if (currentSelf <= 1) return; // minimum light, nothing to propagate

        // Propagate to each neighbour: neighbour_light = max_neighbour - 1
        int north = isBlockLight
            ? lightState.blockLightAt(north(pos))
            : lightState.skyLightAt(north(pos));
        int south = isBlockLight
            ? lightState.blockLightAt(south(pos))
            : lightState.skyLightAt(south(pos));
        int east  = isBlockLight
            ? lightState.blockLightAt(east(pos))
            : lightState.skyLightAt(east(pos));
        int west  = isBlockLight
            ? lightState.blockLightAt(west(pos))
            : lightState.skyLightAt(west(pos));
        int up    = isBlockLight
            ? lightState.blockLightAt(up(pos))
            : lightState.skyLightAt(up(pos));
        int down  = isBlockLight
            ? lightState.blockLightAt(down(pos))
            : lightState.skyLightAt(down(pos));

        int newNorth = Math.max(0, currentSelf - 1);
        int newSouth = Math.max(0, currentSelf - 1);
        int newEast  = Math.max(0, currentSelf - 1);
        int newWest  = Math.max(0, currentSelf - 1);
        int newUp    = Math.max(0, currentSelf - 1);
        int newDown  = Math.max(0, currentSelf - 1);

        // Write updated light levels (only if higher than current)
        if (isBlockLight) {
            if (newNorth > north) lightState.setBlockLight(north(pos), newNorth);
            if (newSouth > south) lightState.setBlockLight(south(pos), newSouth);
            if (newEast  > east)  lightState.setBlockLight(east(pos),  newEast);
            if (newWest  > west)  lightState.setBlockLight(west(pos),  newWest);
            if (newUp    > up)    lightState.setBlockLight(up(pos),    newUp);
            if (newDown  > down)  lightState.setBlockLight(down(pos),  newDown);
        } else {
            if (newNorth > north) lightState.setSkyLight(north(pos), newNorth);
            if (newSouth > south) lightState.setSkyLight(south(pos), newSouth);
            if (newEast  > east)  lightState.setSkyLight(east(pos),  newEast);
            if (newWest  > west)  lightState.setSkyLight(west(pos),  newWest);
            if (newUp    > up)    lightState.setSkyLight(up(pos),    newUp);
            if (newDown  > down)  lightState.setSkyLight(down(pos),  newDown);
        }
    }

    private WorldPos north(WorldPos p) { return new WorldPos(dimId, p.x(), p.y(), p.z() + 1); }
    private WorldPos south(WorldPos p) { return new WorldPos(dimId, p.x(), p.y(), p.z() - 1); }
    private WorldPos east(WorldPos p)  { return new WorldPos(dimId, p.x() + 1, p.y(), p.z()); }
    private WorldPos west(WorldPos p)  { return new WorldPos(dimId, p.x() - 1, p.y(), p.z()); }
    private WorldPos up(WorldPos p)    { return new WorldPos(dimId, p.x(), p.y() + 1, p.z()); }
    private WorldPos down(WorldPos p)  { return new WorldPos(dimId, p.x(), p.y() - 1, p.z()); }
}
