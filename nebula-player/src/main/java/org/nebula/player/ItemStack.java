package org.nebula.player;

/**
 * Immutable item stack. Holds material ID + stack count.
 * The material string is the Bukkit Material name (e.g. "DIAMOND_PICKAXE", "DIRT").
 * Stack count of 0 = empty slot.
 */
public record ItemStack(String material, int count) {
    public static final ItemStack EMPTY = new ItemStack("AIR", 0);

    public boolean isEmpty() { return count <= 0 || "AIR".equals(material); }

    public ItemStack withCount(int newCount) { return new ItemStack(material, newCount); }

    /** Returns a new stack with count decremented by 1. If count was 1, returns EMPTY. */
    public ItemStack decrement() {
        if (count <= 1) return EMPTY;
        return new ItemStack(material, count - 1);
    }

    /** Returns a new stack with count incremented by 1, capped at 64. */
    public ItemStack increment() {
        if (isEmpty()) return EMPTY;
        return new ItemStack(material, Math.min(count + 1, 64));
    }
}
