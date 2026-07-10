package org.nebula.folia;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.FluidSnapshot;
import org.nebula.entity.FluidState;

import java.util.Objects;

/** Region-thread bridge that samples live water/lava blocks into the fluid CAS store. */
public final class NmsFluidStateBridge {

    private final FluidState state;

    public NmsFluidStateBridge(FluidState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    /**
     * Reads one block on its owning region thread. Fluid blocks become snapshots;
     * non-fluid blocks become {@code null}, which the pure action treats as empty.
     */
    public FluidSnapshot syncFromNms(World world, WorldPos pos) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");

        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        int level = level(block);
        FluidSnapshot snapshot = switch (block.getType()) {
            case WATER -> FluidSnapshot.water(pos, level, level == 0);
            case LAVA -> FluidSnapshot.lava(pos, level, level == 0);
            default -> null;
        };
        state.put(pos, snapshot);
        return snapshot;
    }

    private static int level(Block block) {
        return block.getBlockData() instanceof Levelled levelled ? levelled.getLevel() : 0;
    }
}
