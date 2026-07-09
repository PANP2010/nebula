package org.nebula.folia;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
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
 *
 * <p><b>Field-path naming is canonical, not ad-hoc.</b> The bridge reads/writes
 * the SAME {@link BlockEntityField} paths the pure action math uses, so a synced
 * NMS value feeds the DAG and a DAG mutation flows back to NMS:
 * <ul>
 *   <li>slots → {@code "inventory.slots[N]"} (see {@link #slotPath}), matching
 *       {@link org.nebula.entity.BlockEntityContext#readSlot};</li>
 *   <li>hopper cooldown → {@code "transfer_cooldown"} (matches
 *       {@code BlockEntityActions.hopper});</li>
 *   <li>furnace: Bukkit's {@code burnTime} (remaining fuel ticks) ↔ {@code "fuel_time"},
 *       {@code cookTime} (progress) ↔ {@code "cook_progress"}, {@code cookTimeTotal} ↔
 *       {@code "cook_total"} (matches {@code BlockEntityActions.furnace}).</li>
 * </ul>
 * Diverging from these names silently severs the bridge from the DAG — the exact
 * key-mismatch failure mode (B3) the project was built to catch.
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

    /**
     * Reads ONLY the inventory slot counts of any {@link Container} tile entity at
     * {@code pos} into the CAS store — the read a hopper's transfer math needs of its
     * <em>neighbours</em>, not its own cooldown/timers.
     *
     * <p>Why a distinct entry point and not {@link #syncFromNms}: a hopper's above/output
     * neighbour is frequently a plain chest/barrel, which is neither a {@link Hopper} nor
     * a {@link Furnace}, so {@code syncFromNms} silently skips it — leaving the transfer
     * action reading a phantom-empty neighbour (the exact silent no-op the block-entity
     * subsystem keeps re-learning). {@link Container} is the common Bukkit supertype of
     * hopper/furnace/chest/dropper/dispenser (all expose {@code getInventory()}), so this
     * reads the slot counts uniformly regardless of the neighbour's concrete type. It
     * deliberately does NOT touch cooldown/timer fields — those belong to the neighbour's
     * own {@code syncFromNms} when it is itself a ticking task, and clobbering them here
     * would corrupt a neighbour hopper's cooldown.
     *
     * <p>Must be called on the region thread that owns {@code pos}. A non-container block
     * (e.g. air above a bottom hopper) is a clean no-op.
     */
    public void syncInventoryFromNms(World world, WorldPos pos) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");

        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        BlockState state = block.getState();

        if (state instanceof Container container) {
            Inventory inv = container.getInventory();
            for (int slot = 0; slot < inv.getSize(); slot++) {
                ItemStack item = inv.getItem(slot);
                int amount = item == null || item.getType() == Material.AIR ? 0 : item.getAmount();
                casCommitField(pos, slotPath(slot), amount);
            }
        }
        // Non-container blocks (air, solid) are silently skipped.
    }

    private void syncHopperFromNms(WorldPos pos, Hopper hopper) {
        // Transfer cooldown — canonical model field name (matches BlockEntityActions.hopper).
        casCommitField(pos, "transfer_cooldown", hopper.getTransferCooldown());

        // Inventory slots (5 slots for a hopper)
        Inventory inv = hopper.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            int amount = item == null || item.getType() == Material.AIR ? 0 : item.getAmount();
            casCommitField(pos, slotPath(slot), amount);
        }
    }

    private void syncFurnaceFromNms(WorldPos pos, Furnace furnace) {
        // Timers — map the Bukkit API names onto the canonical model field names the
        // furnace action reads: getBurnTime()==remaining fuel ticks == "fuel_time";
        // getCookTime()==progress counting up to total == "cook_progress". cook_total
        // is a constant in the model (BlockEntityActions.COOK_TOTAL), not read from CAS,
        // so it round-trips through the bridge under its own name harmlessly.
        casCommitField(pos, "fuel_time", furnace.getBurnTime());
        casCommitField(pos, "cook_progress", furnace.getCookTime());
        casCommitField(pos, "cook_total", furnace.getCookTimeTotal());

        // Inventory slots (3 slots: input=0, fuel=1, result=2)
        Inventory inv = furnace.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            int amount = item == null || item.getType() == Material.AIR ? 0 : item.getAmount();
            casCommitField(pos, slotPath(slot), amount);
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
        // Transfer cooldown — canonical model field name (matches the action's write).
        int cooldown = casStore.get(new BlockEntityField(pos, "transfer_cooldown"));
        hopper.setTransferCooldown(cooldown);

        // Inventory slots
        Inventory inv = hopper.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            int amount = casStore.get(new BlockEntityField(pos, slotPath(slot)));
            setSlotAmount(inv, slot, amount);
        }

        hopper.update();
    }

    private void syncFurnaceToNms(WorldPos pos, Furnace furnace, World world) {
        // Timers — read back under the canonical model names the furnace action writes.
        furnace.setBurnTime((short) casStore.get(new BlockEntityField(pos, "fuel_time")));
        furnace.setCookTime((short) casStore.get(new BlockEntityField(pos, "cook_progress")));
        furnace.setCookTimeTotal(casStore.get(new BlockEntityField(pos, "cook_total")));

        // Inventory slots — skip write for empty slots (material type unknown)
        // Only resize existing items
        Inventory inv = furnace.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            int amount = casStore.get(new BlockEntityField(pos, slotPath(slot)));
            setSlotAmount(inv, slot, amount);
        }

        furnace.update();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Canonical inventory-slot field path — must match {@link org.nebula.entity.BlockEntityContext#readSlot}
     * / {@code writeSlot}, which key slots as {@code "inventory.slots[N]"}. The bridge
     * and the pure action math read/write the SAME {@link BlockEntityField}, so a hopper
     * action's slot mutation is visible to {@code syncToNms} and vice-versa. Diverging
     * here (the old {@code "slot_N"}) would silently sever the bridge from the DAG — the
     * key-mismatch bug class that severed the redstone path before B3.
     */
    private static String slotPath(int slot) {
        return "inventory.slots[" + slot + "]";
    }

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