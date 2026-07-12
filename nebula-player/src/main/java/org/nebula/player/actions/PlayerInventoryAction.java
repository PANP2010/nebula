package org.nebula.player.actions;

import org.nebula.player.PlayerTaskAction;
import org.nebula.player.PlayerTaskContext;
import org.nebula.player.PlayerInventoryState;
import org.nebula.player.ItemStack;
import java.util.UUID;

/**
 * Player inventory tick action. Handles:
 * - Container interactions (crafting table, furnace, chest)
 * - Hotbar slot selection
 * - Item pickup from ground
 */
public final class PlayerInventoryAction implements PlayerTaskAction {

    private final UUID playerId;
    private final PlayerInventoryState inventory;

    public PlayerInventoryAction(UUID playerId, PlayerInventoryState inventory) {
        this.playerId = playerId;
        this.inventory = inventory;
    }

    @Override
    public void execute(PlayerTaskContext ctx) {
        String openContainer = ctx.readString(playerId, "open_container");
        if (openContainer != null && !openContainer.isEmpty()) {
            handleContainerInteraction(ctx, openContainer);
        }

        // Check slot selection
        int selectedSlot = (int) ctx.readScalar(playerId, "selected_slot_pending");
        if (selectedSlot >= 0 && selectedSlot <= 8) {
            inventory.setHeldSlot(playerId, selectedSlot);
            ctx.writeScalar(playerId, "selected_slot_pending", -1.0);
        }
    }

    private void handleContainerInteraction(PlayerTaskContext ctx, String containerType) {
        int slot = (int) ctx.readScalar(playerId, "container_click_slot");
        String rawCursor = ctx.readString(playerId, "cursor_item");
        ItemStack cursor = parseCursor(rawCursor);

        switch (containerType) {
            case "CRAFTING_TABLE" -> handleCrafting(ctx, slot, cursor);
            case "FURNACE" -> handleFurnace(ctx, slot, cursor);
            case "CHEST", "BARREL" -> handleChest(ctx, slot, cursor);
            case "ENCHANTING_TABLE" -> handleEnchante(ctx, slot, cursor);
        }
    }

    private void handleCrafting(PlayerTaskContext ctx, int slot, ItemStack cursor) {
        // Crafting table: 9 input slots (0-8), 1 output (9),
        // player inv access (10-35), craft result (36)
        if (slot == 9 || slot == 36) {
            // Taking from output slot — execute crafting
            ctx.writeString(playerId, "craft_attempt", "true");
        }
    }

    private void handleFurnace(PlayerTaskContext ctx, int slot, ItemStack cursor) {
        // Furnace: input (0), fuel (1), output (2)
        if (slot == 0 && !cursor.isEmpty()) {
            // Place item in smelter input
            String mat = cursor.material();
            boolean smeltable = org.nebula.player.CraftingSystem.smeltingRecipes()
                .containsKey(mat);
            ctx.writeBool(playerId, "furnace_input_valid", smeltable);
        } else if (slot == 1 && !cursor.isEmpty()) {
            // Fuel slot
            boolean isFuel = isFuel(cursor.material());
            ctx.writeBool(playerId, "furnace_fuel_valid", isFuel);
        }
    }

    private void handleChest(PlayerTaskContext ctx, int slot, ItemStack cursor) {
        // Chest: 27 slots (0-26)
        // Simple swap: if cursor empty, pick up; if slot empty, place; if both full, swap
        if (slot < 0 || slot >= 27) return;
        ItemStack slotItem = inventory.getSlot(playerId, PlayerInventoryState.ENDER_CHEST + slot);
        if (cursor.isEmpty() && !slotItem.isEmpty()) {
            ctx.writeString(playerId, "cursor_item",
                slotItem.material() + ":" + slotItem.count());
            inventory.setSlot(playerId, PlayerInventoryState.ENDER_CHEST + slot, ItemStack.EMPTY);
        } else if (!cursor.isEmpty() && slotItem.isEmpty()) {
            inventory.setSlot(playerId, PlayerInventoryState.ENDER_CHEST + slot, cursor);
            ctx.writeString(playerId, "cursor_item", "AIR:0");
        } else if (!cursor.isEmpty() && !slotItem.isEmpty()) {
            inventory.setSlot(playerId, PlayerInventoryState.ENDER_CHEST + slot, cursor);
            ctx.writeString(playerId, "cursor_item",
                slotItem.material() + ":" + slotItem.count());
        }
    }

    private void handleEnchante(PlayerTaskContext ctx, int slot, ItemStack cursor) {
        // Enchanting: lapis slot (0), output (1), bookshelf proximity check
        // Simplified: just record the interaction
        if (slot == 0 && !cursor.isEmpty()) {
            int lapisCount = cursor.material().equals("LAPIS_LAZULI") ? cursor.count() : 0;
            ctx.writeScalar(playerId, "lapis_count", lapisCount);
        }
    }

    private static ItemStack parseCursor(String raw) {
        if (raw == null || raw.isEmpty()) return ItemStack.EMPTY;
        int colon = raw.indexOf(':');
        if (colon < 0) return ItemStack.EMPTY;
        String mat = raw.substring(0, colon);
        int count = 0;
        try { count = Integer.parseInt(raw.substring(colon + 1)); } catch (Exception e) {}
        return new ItemStack(mat, count);
    }

    private static boolean isFuel(String material) {
        return material.equals("COAL") || material.equals("CHARCOAL")
            || material.equals("COAL_BLOCK") || material.equals("LAVA_BUCKET")
            || material.equals("BLAZE_ROD") || material.equals("DRIED_KELP")
            || material.equals("HAY_BALE") || material.equals("WOOD")
            || material.equals("PLANKS") || material.equals("LOG")
            || material.equals("STRIPPED_LOG") || material.equals("WOODEN_PLANKS")
            || material.equals("BAMBOO") || material.equals("LAVA")
            || material.equals("BOOKSHELF") || material.equals("SOUL_SAND");
    }
}