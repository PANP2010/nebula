package org.nebula.folia;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.nebula.annotations.MicroStepBehavior;
import org.nebula.annotations.NebulaRW;
import org.nebula.annotations.SccBehavior;
import org.nebula.player.PlayerInventoryState;

import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Bidirectional bridge between Nebula's {@link PlayerInventoryState} and real
 * Bukkit {@link PlayerInventory}.
 *
 * <p><b>Read path:</b> syncFromNms() reads from Bukkit inventory into CAS.
 * <p><b>Write path:</b> syncToNms() writes CAS state back to Bukkit inventory.
 *
 * <p>Must be called on the player's region thread.
 */
public final class NmsInventoryBridge {

    private static final Logger LOG = Logger.getLogger(NmsInventoryBridge.class.getName());

    private final PlayerInventoryState casInventory;

    public NmsInventoryBridge(PlayerInventoryState casInventory) {
        this.casInventory = Objects.requireNonNull(casInventory, "casInventory");
    }

    /**
     * Reads all 42 inventory slots from the Bukkit player into CAS.
     * Called at the START of each tick (before player DAG runs).
     */
    @NebulaRW(
        readEntities     = {"{playerId}.inventory"},
        writeEntities    = {"{playerId}.inventory"},
        triggeredEvents  = {"INVENTORY_CHANGED"},
        microStep        = MicroStepBehavior.NONE,
        scc              = SccBehavior.SERIALIZED,
        maxRandomCalls   = 0,
        randomInstance   = "NONE",
        mayLoadChunks    = false,
        mayTriggerBlockUpdates = false,
        maySpawnEntities = false,
        verifiedAt       = "1.21.4",
        verifiedBy       = {}
    )
    public void syncFromNms(Player player) {
        Objects.requireNonNull(player, "player");
        UUID uuid = player.getUniqueId();
        PlayerInventory inv = player.getInventory();

        // Main inventory: slots 0-35
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < PlayerInventoryState.TOTAL_SIZE && i < contents.length; i++) {
            ItemStack bs = contents[i];
            String material = bs == null ? "AIR" : bs.getType().name();
            int count = bs == null ? 0 : bs.getAmount();
            casInventory.setSlot(uuid, i, new org.nebula.player.ItemStack(material, count));
        }

        // Offhand: slot 36
        ItemStack offhand = inv.getItemInOffHand();
        String offMat = offhand == null ? "AIR" : offhand.getType().name();
        int offCount = offhand == null ? 0 : offhand.getAmount();
        casInventory.setSlot(uuid, PlayerInventoryState.OFFHAND, new org.nebula.player.ItemStack(offMat, offCount));

        // Armor slots: 37-40 (helmet=37, chest=38, legs=39, feet=40)
        ItemStack[] armor = inv.getArmorContents();
        if (armor != null) {
            for (int i = 0; i < armor.length && i < 4; i++) {
                ItemStack a = armor[i];
                String mat = a == null ? "AIR" : a.getType().name();
                int cnt = a == null ? 0 : a.getAmount();
                casInventory.setSlot(uuid, PlayerInventoryState.ARMOR_HEAD + i, new org.nebula.player.ItemStack(mat, cnt));
            }
        }

        // Held item: slot 37
        casInventory.setHeldSlot(uuid, inv.getHeldItemSlot());

        LOG.finest(() -> "Synced inventory from NMS for " + uuid);
    }

    /**
     * Writes CAS inventory state back to Bukkit.
     * Called at the END of each tick (after player DAG commits).
     */
    public void syncToNms(Player player) {
        Objects.requireNonNull(player, "player");
        UUID uuid = player.getUniqueId();
        PlayerInventory inv = player.getInventory();

        // Main inventory: slots 0-35
        ItemStack[] contents = new ItemStack[PlayerInventoryState.TOTAL_SIZE];
        for (int i = 0; i < PlayerInventoryState.TOTAL_SIZE; i++) {
            org.nebula.player.ItemStack nb = casInventory.getSlot(uuid, i);
            if (!nb.isEmpty()) {
                Material mat;
                try { mat = Material.valueOf(nb.material()); }
                catch (IllegalArgumentException e) { mat = Material.AIR; }
                contents[i] = new ItemStack(mat, nb.count());
            } else {
                contents[i] = new ItemStack(Material.AIR, 0);
            }
        }
        inv.setContents(contents);

        // Offhand: slot 36
        org.nebula.player.ItemStack offhand = casInventory.getSlot(uuid, PlayerInventoryState.OFFHAND);
        if (!offhand.isEmpty()) {
            Material mat;
            try { mat = Material.valueOf(offhand.material()); }
            catch (IllegalArgumentException e) { mat = Material.AIR; }
            inv.setItemInOffHand(new ItemStack(mat, offhand.count()));
        } else {
            inv.setItemInOffHand(new ItemStack(Material.AIR, 0));
        }

        // Armor: 37-40
        ItemStack[] armor = new ItemStack[4];
        for (int i = 0; i < 4; i++) {
            org.nebula.player.ItemStack a = casInventory.getSlot(uuid, PlayerInventoryState.ARMOR_HEAD + i);
            if (!a.isEmpty()) {
                Material mat;
                try { mat = Material.valueOf(a.material()); }
                catch (IllegalArgumentException e) { mat = Material.AIR; }
                armor[i] = new ItemStack(mat, a.count());
            } else {
                armor[i] = new ItemStack(Material.AIR, 0);
            }
        }
        inv.setArmorContents(armor);

        // Held slot
        inv.setHeldItemSlot(casInventory.getHeldSlot(uuid));

        LOG.finest(() -> "Synced inventory to NMS for " + uuid);
    }

    public PlayerInventoryState casInventory() {
        return casInventory;
    }
}