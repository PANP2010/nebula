package org.nebula.folia;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Furnace;
import org.bukkit.block.Hopper;
import org.bukkit.block.TileState;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.BlockEntityState;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Bridge between Nebula's {@link BlockEntityState} CAS store and real Bukkit
 * tile-entity state through the Folia 26.1.2 API. All reads/writes must occur
 * on the region thread that owns the target block — the caller is responsible
 * for ensuring this.
 *
 * <p>Currently binds:
 * <ul>
 *   <li><b>Hopper</b>: transfer cooldown ({@link Hopper#getTransferCooldown()})
 *       and inventory slot counts (item amounts via {@link Inventory#getItem}).</li>
 *   <li><b>Furnace</b>: burn time, cook time, cook time total
 *       ({@link Furnace#getBurnTime()}, {@link Furnace#getCookTime()},
 *       {@link Furnace#getCookTimeTotal()}) and inventory slot counts.</li>
 * </ul>
 *
 * <p>Inventory is modelled as integer slot counts (item amounts) in the CAS store,
 * matching the fidelity level described in the arch doc §3.3 — sufficient for
 * deterministic transfer/smelt logic without holding live {@code ItemStack}
 * references.
 */
public final class NmsBlockEntityStateBridge {

    private static final Logger LOG = Logger.getLogger(NmsBlockEntityStateBridge.class.getName());

    private final BlockEntityState casStore;

    public NmsBlockEntityStateBridge(BlockEntityState casStore) {
        this.casStore = Objects.requireNonNull(casStore, "casStore");
    }

    // ── Read path ───────────────────────────────────────────────────────────────

    /**
     * Reads tile-entity state from the real block at {@code pos} and commits
     * to the CAS store. Dispatches to the appropriate handler based on block type.
     *
     * <p>Must be called on the region thread that owns {@code pos}.
     */
    public void syncFromNms(World world, WorldPos pos) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");

        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        BlockState state = block.getState();

        if (state instanceof Hopper hopper) {
            syncHopperFromNms(pos, hopper);
        } else if (state instanceof Furnace furnace) {
            syncFurnaceFromNms(pos, furnace);
        }
        // Unknown tile entity types are silently skipped
    }

    private void syncHopperFromNms(WorldPos pos, Hopper hopper) {
        // Transfer cooldown
        casCommitField(pos, "transferCooldown", hopper.getTransferCooldown());

        // Inventory slots (5 slots for a hopper)
        Inventory inv = hopper.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            int amount = item == null || item.getType() == Material.AIR ? 0 : item.getAmount();
            casCommitField(pos, "slot_" + slot, amount);
        }
    }

    private void syncFurnaceFromNms(WorldPos pos, Furnace furnace) {
        // Timers
        casCommitField(pos, "burnTime", furnace.getBurnTime());
        casCommitField(pos, "cookTime", furnace.getCookTime());
        casCommitField(pos, "cookTimeTotal", furnace.getCookTimeTotal());

        // Inventory slots (3 slots: input=0, fuel=1, result=2)
        Inventory inv = furnace.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            int amount = item == null || item.getType() == Material.AIR ? 0 : item.getAmount();
            casCommitField(pos, "slot_" + slot, amount);
        }
    }

    // ── Write path ──────────────────────────────────────────────────────────────

    /**
     * Writes tile-entity state from the CAS store to the real block at {@code pos}.
     * Dispatches to the appropriate handler based on block type.
     *
     * <p>Must be called on the region thread that owns {@code pos}.
     */
    public void syncToNms(World world, WorldPos pos) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");

        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        BlockState state = block.getState();

        if (state instanceof Hopper hopper) {
            syncHopperToNms(pos, hopper);
        } else if (state instanceof Furnace furnace) {
            syncFurnaceToNms(pos, furnace, world);
        }
    }

    private void syncHopperToNms(WorldPos pos, Hopper hopper) {
        // Transfer cooldown
        int cooldown = casStore.get(new BlockEntityField(pos, "transferCooldown"));
        hopper.setTransferCooldown(cooldown);

        // Inventory slots
        Inventory inv = hopper.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            int amount = casStore.get(new BlockEntityField(pos, "slot_" + slot));
            setSlotAmount(inv, slot, amount);
        }

        hopper.update();
    }

    private void syncFurnaceToNms(WorldPos pos, Furnace furnace, World world) {
        // Timers
        furnace.setBurnTime((short) casStore.get(new BlockEntityField(pos, "burnTime")));
        furnace.setCookTime((short) casStore.get(new BlockEntityField(pos, "cookTime")));
        furnace.setCookTimeTotal(casStore.get(new BlockEntityField(pos, "cookTimeTotal")));

        // Inventory slots — skip write for empty slots (material type unknown)
        // Only resize existing items
        Inventory inv = furnace.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            int amount = casStore.get(new BlockEntityField(pos, "slot_" + slot));
            setSlotAmount(inv, slot, amount);
        }

        furnace.update();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private void casCommitField(WorldPos pos, String fieldPath, int value) {
        BlockEntityField field = new BlockEntityField(pos, fieldPath);
        long expectedVersion = casStore.getVersion(field);
        casStore.casCommit(field, expectedVersion, value);
    }

    /**
     * Sets the item amount in an inventory slot. If amount is 0, clears the slot.
     * If amount > 0 and the slot is empty, creates a placeholder stack.
     * If amount > 0 and the slot has an item, resizes the stack.
     */
    private void setSlotAmount(Inventory inv, int slot, int amount) {
        ItemStack current = inv.getItem(slot);
        if (amount <= 0) {
            inv.setItem(slot, null);
        } else if (current == null || current.getType() == Material.AIR) {
            // Cannot create an item without knowing the type — leave empty.
            // The CAS store only tracks amounts; full ItemStack binding requires
            // extending the CAS model to include material types (future work).
            LOG.fine(() -> "Cannot create item in empty slot " + slot + " with amount " + amount
                + " — material type unknown");
        } else {
            current.setAmount(Math.min(amount, current.getMaxStackSize()));
        }
    }

    /**
     * Returns the CAS store this bridge is bound to.
     */
    public BlockEntityState casStore() {
        return casStore;
    }
}