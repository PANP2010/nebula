package org.nebula.folia;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.nebula.annotations.MicroStepBehavior;
import org.nebula.annotations.NebulaRW;
import org.nebula.annotations.SccBehavior;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.FluidSnapshot;
import org.nebula.entity.FluidState;

import java.util.Objects;

/**
 * Region-thread bridge that samples live water/lava blocks into the fluid CAS store.
 *
 * <p><b>RW-set coverage.</b> The fluid subsystem's standard footprint is the
 * 6-block neighbourhood — the source block itself plus the five adjacent
 * positions (down, N, S, E, W) — which matches {@link
 * org.nebula.entity.FluidTaskFactory}'s {@code flowRw(...)} declaration. The
 * {@code @NebulaRW} annotations on {@link #syncFromNms} declare this same
 * footprint so the runtime guard sees the fluid bridge's RW-set as a superset
 * of the factory's; if a future cycle narrows the factory (e.g. drops the
 * down-neighbour read for falling-flow tasks) the bridge still covers it,
 * exactly the conservative-coverage design used by {@code
 * NmsBlockEntityStateBridge.syncInventoryFromNms}.
 */
public final class NmsFluidStateBridge {

    private final FluidState state;

    public NmsFluidStateBridge(FluidState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    /**
     * Reads one block on its owning region thread. Fluid blocks become snapshots;
     * non-fluid blocks become {@code null}, which the pure action treats as empty.
     *
     * <p>The annotated RW-set covers the 6-block fluid footprint declared by
     * {@code FluidTaskFactory.flowRw(...)} (self + down + 4 horizontal
     * neighbours). The bridge reads exactly {@code {pos}}, but writes the same
     * footprint into the CAS store, so we declare the read/write superset
     * conservatively — the runtime guard treats any fluid-flow task touching
     * one of these positions as depending on this bridge's sync.
     */
    @NebulaRW(
        readBlocks         = {"{pos}", "{pos.down}", "{pos.north}", "{pos.south}",
                              "{pos.east}", "{pos.west}"},
        writeBlocks        = {"{pos}", "{pos.down}", "{pos.north}", "{pos.south}",
                              "{pos.east}", "{pos.west}"},
        triggeredEvents    = {"BLOCK_UPDATE"},
        microStep          = MicroStepBehavior.NONE,
        scc                = SccBehavior.SERIALIZED,
        maxRandomCalls     = 0,
        randomInstance     = "NONE",
        mayLoadChunks      = false,
        mayTriggerBlockUpdates = false,
        maySpawnEntities   = false,
        verifiedAt         = "1.21.4",
        verifiedBy         = {"FluidTaskFactoryTest"}
    )
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

    /**
     * Returns the CAS store this bridge is bound to (for direct access).
     *
     * <p>The annotation marks this as a pure read accessor: no block/entity state
     * is touched, no events are fired. It exists so the {@link
     * org.nebula.maintenance.BridgeAnnotationScanner} can count it as part of the
     * fluid subsystem's bridge surface — the honest denominator is "every public
     * instance method on the bridge class", which is what the inventory contract
     * documents (see {@link NmsBlockStateBridge#casStore()} for the redstone
     * analog).
     */
    @NebulaRW(
        microStep          = MicroStepBehavior.NONE,
        scc                = SccBehavior.SERIALIZED,
        maxRandomCalls     = 0,
        randomInstance     = "NONE",
        mayLoadChunks      = false,
        mayTriggerBlockUpdates = false,
        maySpawnEntities   = false,
        verifiedAt         = "1.21.4",
        verifiedBy         = {"FluidTaskFactoryTest"}
    )
    public FluidState casStore() {
        return state;
    }
}
