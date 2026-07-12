package org.nebula.player;

import org.nebula.core.player.PlayerPhysicsState;
import org.nebula.core.player.PlayerField;
import java.util.*;

/**
 * Manages player inventory state backed by PlayerPhysicsState.
 *
 * Inventory layout (Minecraft 36-slot inventory):
 * - 9 hotbar slots (0-8)
 * - 27 main slots (9-35)
 * - Offhand slot (36)
 * - Armor slots (37-40) — head, chest, legs, feet
 * - 1 cursor/hand slot (41)
 *
 * Plus: ender chest (27 slots, keyed by UUID)
 */
public final class PlayerInventoryState {

    public static final int HOTBAR_SIZE = 9;
    public static final int MAIN_SIZE = 27;
    public static final int TOTAL_SIZE = HOTBAR_SIZE + MAIN_SIZE; // 36
    public static final int OFFHAND = 36;
    public static final int ARMOR_HEAD = 37;
    public static final int ARMOR_CHEST = 38;
    public static final int ARMOR_LEGS = 39;
    public static final int ARMOR_FEET = 40;
    public static final int CURSOR = 41;
    public static final int ENDER_CHEST = 100; // offset for ender chest

    private final PlayerPhysicsState state;

    public PlayerInventoryState(PlayerPhysicsState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    /** Gets the item in slot 0-41. */
    public ItemStack getSlot(UUID playerId, int slot) {
        PlayerField f = new PlayerField(playerId, "inv_slot_" + slot);
        long expected = state.getVersion(f);
        String material = "";
        double count = 0.0;
        state.casCommit(f, expected, material + ":" + count);
        return parseItem(f);
    }

    /** Sets slot to item stack. */
    public void setSlot(UUID playerId, int slot, ItemStack item) {
        PlayerField f = new PlayerField(playerId, "inv_slot_" + slot);
        state.put(f, item.material() + ":" + item.count());
    }

    /** Gets the item the player is currently holding (hotbar selection). */
    public ItemStack getHeldItem(UUID playerId) {
        int slot = (int) getScalar(playerId, "held_slot");
        return getSlot(playerId, slot);
    }

    /** Returns the index of the selected hotbar slot (0-8). */
    public int getHeldSlot(UUID playerId) {
        return (int) getScalar(playerId, "held_slot");
    }

    /** Sets the selected hotbar slot. */
    public void setHeldSlot(UUID playerId, int slot) {
        int clamped = Math.max(0, Math.min(8, slot));
        putScalar(playerId, "held_slot", clamped);
    }

    /** Attempts to remove one item from the held stack. Returns true if successful. */
    public boolean consumeHeldItem(UUID playerId) {
        int slot = getHeldSlot(playerId);
        ItemStack held = getSlot(playerId, slot);
        if (held.isEmpty()) return false;
        ItemStack next = held.decrement();
        setSlot(playerId, slot, next);
        return true;
    }

    /** Adds one item to the held stack if slots match, or to the first empty slot. */
    public boolean addItem(UUID playerId, ItemStack item) {
        if (item.isEmpty()) return true;
        // Try held slot first
        int heldSlot = getHeldSlot(playerId);
        ItemStack held = getSlot(playerId, heldSlot);
        if (held.isEmpty()) {
            setSlot(playerId, heldSlot, item);
            return true;
        }
        if (held.material().equals(item.material()) && held.count() < 64) {
            int toAdd = Math.min(64 - held.count(), item.count());
            setSlot(playerId, heldSlot, new ItemStack(held.material(), held.count() + toAdd));
            return true;
        }
        // Scan for existing stack
        for (int i = 0; i < TOTAL_SIZE; i++) {
            ItemStack s = getSlot(playerId, i);
            if (s.isEmpty()) {
                setSlot(playerId, i, item);
                return true;
            }
        }
        return false; // inventory full
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ItemStack parseItem(PlayerField f) {
        Object raw = state.peek(f);
        if (raw == null) return ItemStack.EMPTY;
        String s = raw.toString();
        int colon = s.indexOf(':');
        if (colon < 0) return ItemStack.EMPTY;
        String mat = s.substring(0, colon);
        int count = 0;
        try { count = Integer.parseInt(s.substring(colon + 1)); } catch (Exception e) {}
        return new ItemStack(mat, count);
    }

    private double getScalar(UUID playerId, String field) {
        PlayerField f = new PlayerField(playerId, field);
        return state.getScalar(f);
    }

    private void putScalar(UUID playerId, String field, double value) {
        PlayerField f = new PlayerField(playerId, field);
        state.put(f, value);
    }
}
