package org.nebula.folia;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Dispenser;
import org.bukkit.block.Dropper;
import org.bukkit.block.Furnace;
import org.bukkit.block.Hopper;
import org.bukkit.block.TileState;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.nebula.annotations.MicroStepBehavior;
import org.nebula.annotations.NebulaRW;
import org.nebula.annotations.SccBehavior;
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
 *   <li><b>Dropper/Dispenser</b>: its own inventory slot counts only — the eject
 *       action ({@code BlockEntityActions.dropper}/{@code dispenser}) reads
 *       {@code readSlot(self, s)}, so without this self-read it draws from a
 *       phantom-empty container and ejects nothing.</li>
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
        } else if (state instanceof Dropper || state instanceof Dispenser) {
            // A ticking dropper/dispenser's OWN inventory must reach CAS or its eject
            // action reads a phantom-empty container and draws nothing (the dropper
            // self-sync gap). Unlike a plain chest — which never ticks and stays a
            // no-op below — a dropper/dispenser is a ticking task whose self slots the
            // eject math reads via readSlot(self, s). Read only the slots: a
            // dropper/dispenser's cooldown is Folia-authoritative and not modelled as a
            // CAS field the eject action consumes.
            syncContainerSlotsFromNms(pos, (Container) state);
        }
        // Unknown tile entity types (and plain chests/barrels, which never tick) are
        // silently skipped — a non-ticking container is read as a hopper NEIGHBOUR via
        // syncInventoryFromNms, not as a ticking self here.
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
    @NebulaRW(
        readBlockEntities  = {"{pos}.inventory.slots[0]", "{pos}.inventory.slots[1]",
                              "{pos}.inventory.slots[2]", "{pos}.inventory.slots[3]",
                              "{pos}.inventory.slots[4]", "{pos}.inventory.slots[5]",
                              "{pos}.inventory.slots[6]", "{pos}.inventory.slots[7]",
                              "{pos}.inventory.slots[8]"},
        writeBlockEntities = {"{pos}.inventory.slots[0]", "{pos}.inventory.slots[1]",
                              "{pos}.inventory.slots[2]", "{pos}.inventory.slots[3]",
                              "{pos}.inventory.slots[4]", "{pos}.inventory.slots[5]",
                              "{pos}.inventory.slots[6]", "{pos}.inventory.slots[7]",
                              "{pos}.inventory.slots[8]"},
        triggeredEvents    = {"INVENTORY_CHANGED"},
        microStep          = MicroStepBehavior.NONE,
        scc                = SccBehavior.SERIALIZED,
        maxRandomCalls     = 0,
        randomInstance     = "NONE",
        verifiedAt         = "1.21.4",
        verifiedBy         = {"BlockEntityRwGuardBridgeTest"}
    )
    public void syncInventoryFromNms(World world, WorldPos pos) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");

        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        BlockState state = block.getState();

        if (state instanceof Container container) {
            syncContainerSlotsFromNms(pos, container);
        }
        // Non-container blocks (air, solid) are silently skipped.
    }

    /**
     * Reads the summed inventory item count of any {@link Container} tile entity at
     * {@code pos} <em>without touching the CAS store</em>. Returns the total item
     * amount across all inventory slots, or {@code -1} if the block is not a container
     * (air, solid, a non-tile block).
     *
     * <p>This is the read-only complement to {@link #syncFromNms} / {@link
     * #syncInventoryFromNms} — the block-entity twin of {@link
     * NmsBlockStateBridge#readNmsPower}. It samples Folia's authoritative inventory
     * count for a {@code BE-SETTLED} snapshot so the caller can compare it against
     * Nebula's independently-computed CAS count. Going through {@code syncFromNms}
     * instead would commit Folia's counts <em>into</em> the CAS store, overwriting the
     * shadow value and making {@code nebula == folia} by construction — the
     * settled-state divergence tautology (see {@link
     * org.nebula.replay.BlockEntitySettledGrader}). This method reads and sums only; it
     * never writes CAS.
     *
     * <p>The sum matches the CAS model's quantity exactly: {@link #syncInventoryFromNms}
     * commits each slot's item {@code amount} under {@code inventory.slots[N]}, so the
     * summed CAS slots the plugin reports as {@code nebula=} and this summed live count
     * ({@code folia=}) are the same quantity at quiescence.
     *
     * <p>Must be called on the region thread that owns {@code pos} (Folia block reads
     * NPE off the owning region thread).
     */
    public int readNmsInventoryCount(World world, WorldPos pos) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");

        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        BlockState state = block.getState();

        if (!(state instanceof Container container)) {
            return -1;
        }
        Inventory inv = container.getInventory();
        int sum = 0;
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            if (item != null && item.getType() != Material.AIR) {
                sum += item.getAmount();
            }
        }
        return sum;
    }

    /**
     * A read-only snapshot of a furnace's three authoritative timers, in the canonical
     * model units the furnace action computes on: {@code fuelTime} (remaining burn ticks,
     * Bukkit {@code getBurnTime()} ↔ model {@code "fuel_time"}), {@code cookProgress}
     * (Bukkit {@code getCookTime()} ↔ model {@code "cook_progress"}) and {@code cookTotal}
     * (Bukkit {@code getCookTimeTotal()} ↔ model {@code "cook_total"}).
     *
     * <p>The value {@link #readNmsFurnaceTimers} returns for Folia's authoritative furnace,
     * to be compared against the DAG's independently-computed CAS timers WITHOUT writing
     * either side — see that method for why a read-only sampler (not {@code syncFromNms})
     * is the honest surface for a timer-divergence grade.
     */
    public record FurnaceTimerSample(int fuelTime, int cookProgress, int cookTotal) {}

    /**
     * Reads a furnace's three authoritative timers at {@code pos} <em>without touching the
     * CAS store</em>, returning them under the canonical model names, or {@code null} if
     * the block is not a {@link Furnace} (air, solid, a hopper, a non-tile block).
     *
     * <p>This is the timer twin of {@link #readNmsInventoryCount}: the read-only settled
     * sampler a {@code BE-SETTLED}-style grade needs to compare Folia's authoritative
     * furnace {@code fuel_time}/{@code cook_progress} against the DAG's independently
     * computed CAS timers. Going through {@link #syncFromNms} instead would commit Folia's
     * timers <em>into</em> CAS, overwriting the shadow value and making {@code
     * nebula == folia} by construction — the settled-state divergence tautology the
     * inventory sampler was carved out of {@code syncFromNms} to avoid.
     *
     * <p><b>Why this must exist before furnace-timer write-back is armed.</b> Nebula is
     * observe-only on the block-entity path: Folia advances the furnace's timers
     * authoritatively every game tick, and the DAG re-derives them in CAS from a fresh
     * {@code syncFromNms} read. Arming {@link #syncFurnaceToNms} to push the DAG's
     * {@code cook_progress + 1} back onto the live tile would race Folia's own advance and
     * risk over-advancing the timer (a divergence, not convergence) — exactly the trap the
     * entity path avoided by measuring drift with {@code EntityDivergenceTracker} and
     * proving it ≈0 <em>before</em> arming the narrow vertical mirror. This sampler is that
     * measurement instrument for furnace timers: it lets a later cycle grade the live
     * timer gap first, so any write-back arm rests on evidence, not assumption.
     *
     * <p>Must be called on the region thread that owns {@code pos} (Folia block reads NPE
     * off the owning region thread).
     */
    public FurnaceTimerSample readNmsFurnaceTimers(World world, WorldPos pos) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");

        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        BlockState state = block.getState();

        if (!(state instanceof Furnace furnace)) {
            return null;
        }
        // Map the Bukkit API names onto the canonical model names, exactly as
        // syncFurnaceFromNms does — but return them instead of committing to CAS.
        return new FurnaceTimerSample(
            furnace.getBurnTime(), furnace.getCookTime(), furnace.getCookTimeTotal());
    }

    /**
     * Reads a {@link Container}'s per-slot item counts into CAS under the canonical
     * {@code inventory.slots[N]} path — the slots-only read shared by a ticking
     * dropper/dispenser's own {@code syncFromNms} and a hopper neighbour's
     * {@link #syncInventoryFromNms}. Deliberately touches NO cooldown/timer field:
     * the caller decides whether the container is a ticking self (dropper/dispenser)
     * or a passive neighbour, and neither needs its cooldown clobbered here.
     */
    private void syncContainerSlotsFromNms(WorldPos pos, Container container) {
        Inventory inv = container.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            int amount = item == null || item.getType() == Material.AIR ? 0 : item.getAmount();
            casCommitField(pos, slotPath(slot), amount);
        }
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