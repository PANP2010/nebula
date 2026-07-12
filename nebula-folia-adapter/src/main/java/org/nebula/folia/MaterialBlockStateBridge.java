package org.nebula.folia;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.nebula.annotations.MicroStepBehavior;
import org.nebula.annotations.NebulaRW;
import org.nebula.annotations.SccBehavior;
import org.nebula.core.state.WorldPos;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Material-level block write bridge for the survival core.
 *
 * <p>Delegates to {@link NmsBlockStateBridge} for power-level sync.
 * Adds block material operations needed for player survival:
 * <ul>
 *   <li>Block break: set to AIR</li>
 *   <li>Block place: set to held material</li>
 *   <li>Block replace: set to any material</li>
 * </ul>
 */
public final class MaterialBlockStateBridge {

    private static final Logger LOG = Logger.getLogger(MaterialBlockStateBridge.class.getName());

    private final NmsBlockStateBridge delegate;

    public MaterialBlockStateBridge(NmsBlockStateBridge delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @NebulaRW(
        readBlocks           = {"{pos}"},
        writeBlocks         = {"{pos}"},
        triggeredEvents     = {"BLOCK_UPDATE", "BLOCK_BROKEN"},
        microStep           = MicroStepBehavior.PROPAGATES,
        scc                 = SccBehavior.SERIALIZED,
        maxRandomCalls      = 0,
        randomInstance      = "NONE",
        mayLoadChunks       = false,
        mayTriggerBlockUpdates = true,
        maySpawnEntities    = false,
        verifiedAt          = "1.21.4",
        verifiedBy          = {}
    )
    public boolean breakBlock(World world, WorldPos pos) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");
        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (block.getType() == Material.AIR) return false;
        block.setType(Material.AIR);
        LOG.fine(() -> "Broke block at " + pos);
        return true;
    }

    @NebulaRW(
        readBlocks          = {"{pos}"},
        writeBlocks         = {"{pos}"},
        triggeredEvents     = {"BLOCK_UPDATE", "BLOCK_PLACED"},
        microStep           = MicroStepBehavior.PROPAGATES,
        scc                 = SccBehavior.SERIALIZED,
        maxRandomCalls      = 0,
        randomInstance      = "NONE",
        mayLoadChunks       = false,
        mayTriggerBlockUpdates = true,
        maySpawnEntities    = false,
        verifiedAt          = "1.21.4",
        verifiedBy          = {}
    )
    public boolean placeBlock(World world, WorldPos pos, Material material) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(material, "material");
        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (!block.getType().isAir() && block.getType() != Material.WATER) {
            LOG.fine(() -> "Cannot place block at " + pos + " — not empty");
            return false;
        }
        block.setBlockData(material.createBlockData());
        LOG.fine(() -> "Placed " + material + " at " + pos);
        return true;
    }

    @NebulaRW(
        readBlocks          = {"{pos}"},
        writeBlocks         = {"{pos}"},
        triggeredEvents     = {"BLOCK_UPDATE"},
        microStep           = MicroStepBehavior.PROPAGATES,
        scc                 = SccBehavior.SERIALIZED,
        maxRandomCalls      = 0,
        randomInstance      = "NONE",
        mayLoadChunks       = false,
        mayTriggerBlockUpdates = true,
        maySpawnEntities    = false,
        verifiedAt          = "1.21.4",
        verifiedBy          = {}
    )
    public boolean replaceBlock(World world, WorldPos pos, Material material) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(material, "material");
        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        block.setBlockData(material.createBlockData());
        LOG.fine(() -> "Replaced block at " + pos + " with " + material);
        return true;
    }

    public long syncFromNms(World world, WorldPos pos) {
        return delegate.syncFromNms(world, pos);
    }

    public boolean syncToNms(World world, WorldPos pos, int power) {
        return delegate.syncToNms(world, pos, power);
    }
}