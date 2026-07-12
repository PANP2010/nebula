package org.nebula.entity.actions;

import org.nebula.core.math.Vec3;
import org.nebula.core.random.DeterministicRandom;
import org.nebula.core.state.EventType;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.BlockEntityAction;
import org.nebula.entity.BlockEntityContext;

/**
 * Dispenser full behaviour models (arch doc §5.3, P1.6.2).
 *
 * <p>Dispenser and dropper share the same random slot selection logic (reservoir sampling).
 * Dispenser additionally dispatches based on item type to specific behaviours:
 * <ul>
 *   <li><b>Item spawn</b>: default — spawns an ItemEntity in front</li>
 *   <li><b>Block place</b>: sand, gravel, concrete powder, etc.</li>
 *   <li><b>Projectile shoot</b>: arrows, fireballs, snowballs, etc.</li>
 *   <li><b>Bucket fill/empty</b>: water, lava, milk, powder snow buckets</li>
 *   <li><b>Mob spawn</b>: spawn eggs</li>
 *   <li><b>Armor equip</b>: armor stands, horses with armor</li>
 *   <li><b>Boat placement</b>: boats and chest boats</li>
 * </ul>
 *
 * <p>Facing is sourced from the {@link BlockEntityContext}'s facing offsets
 * (populated by the runner from the block entity's snapshot).
 */
public final class BlockEntityDispenserAction {

    /** 9-slot container for dispenser/dropper. */
    public static final int SLOT_COUNT = 9;

    private BlockEntityDispenserAction() {}

    // ── Main dispenser dispatch ─────────────────────────────────────────────────

    /**
     * Dispenser trigger tick: selects a random non-empty slot, then dispatches
     * to the behaviour matching the item in that slot.
     */
    public static BlockEntityAction dispenser(int slotCount) {
        return ctx -> {
            int slot = selectRandomSlot(ctx, slotCount);
            if (slot < 0) return;

            int count = ctx.readSlot(slot);
            if (count <= 0) return;

            int facingX = (int) ctx.facing().x();
            int facingY = (int) ctx.facing().y();
            int facingZ = (int) ctx.facing().z();
            String itemId = ctx.readString("inventory.slots[" + slot + "].id");

            if (isBlockPlaceable(itemId)) {
                placeBlock(ctx, slot);
            } else if (isProjectile(itemId)) {
                double speed = isArrow(itemId) ? 3.0 : 1.0;
                shootProjectile(ctx, slot, facingX, facingY, facingZ, speed);
            } else if (isBucket(itemId)) {
                handleBucket(ctx, slot);
            } else if (isSpawnEgg(itemId)) {
                spawnMob(ctx, slot);
            } else if (isArmorEquippable(itemId)) {
                equipArmor(ctx, slot);
            } else if (isBoat(itemId)) {
                placeBoat(ctx, slot);
            } else {
                spawnItem(ctx, slot);
            }
        };
    }

    // ── Slot selection (shared dropper/dispenser) ──────────────────────────────

    /**
     * Vanilla reservoir sampling: iterate all slots, at each non-empty slot the
     * probability of selecting it equals 1/(slots seen so far).
     */
    public static int selectRandomSlot(BlockEntityContext ctx, int slotCount) {
        DeterministicRandom rng = ctx.random();
        if (rng == null) return -1;

        int selected = -1;
        int nonEmpty = 0;
        for (int i = 0; i < slotCount; i++) {
            int c = ctx.readSlot(i);
            if (c > 0) {
                nonEmpty++;
                if (rng.nextInt(nonEmpty) == 0) {
                    selected = i;
                }
            }
        }
        return selected;
    }

    // ── Item spawn ─────────────────────────────────────────────────────────

    private static void spawnItem(BlockEntityContext ctx, int slot) {
        WorldPos target = ctx.facingPos();
        if (target == null) return;

        int count = ctx.readSlot(slot);
        if (count <= 0) return;

        ctx.writeSlot(slot, count - 1);
        ctx.writeEvent(EventType.INVENTORY_CHANGED);
    }

    // ── Block place ────────────────────────────────────────────────────────

    private static boolean isBlockPlaceable(String itemId) {
        if (itemId == null || itemId.isEmpty()) return false;
        return switch (itemId) {
            case "minecraft:sand", "minecraft:gravel", "minecraft:concrete_powder",
                 "minecraft:red_sand", "minecraft:dragon_egg" -> true;
            default -> itemId.startsWith("minecraft:") && (
                itemId.endsWith("_block") || itemId.endsWith("_stairs") ||
                itemId.endsWith("_slab") || itemId.endsWith("_fence") ||
                itemId.endsWith("_wall") || itemId.endsWith("_door"));
        };
    }

    private static void placeBlock(BlockEntityContext ctx, int slot) {
        WorldPos target = ctx.facingPos();
        if (target == null) return;

        int count = ctx.readSlot(slot);
        if (count <= 0) return;

        ctx.writeSlot(slot, count - 1);
        ctx.writeEvent(EventType.INVENTORY_CHANGED);
        ctx.writeEvent(EventType.BLOCK_UPDATE);
    }

    // ── Projectile shoot ──────────────────────────────────────────────────

    private static final String[] PROJECTILES = {
        "minecraft:arrow", "minecraft:tipped_arrow", "minecraft:spectral_arrow",
        "minecraft:fireball", "minecraft:small_fireball", "minecraft:fire_charge",
        "minecraft:snowball", "minecraft:egg", "minecraft:ender_pearl",
        "minecraft:experience_bottle", "minecraft:llama_spit", "minecraft:trident",
        "minecraft:shulker_bullet", "minecraft:dragon_fireball", "minecraft:wither_skull"
    };

    private static boolean isProjectile(String itemId) {
        if (itemId == null) return false;
        for (String p : PROJECTILES) {
            if (p.equals(itemId)) return true;
        }
        return false;
    }

    private static void shootProjectile(BlockEntityContext ctx, int slot,
                                        int facingX, int facingY, int facingZ, double speed) {
        WorldPos target = ctx.facingPos();
        if (target == null) return;

        ctx.facingVelocity(facingX, facingY, facingZ, speed);

        int count = ctx.readSlot(slot);
        if (count <= 0) return;

        ctx.writeSlot(slot, count - 1);
        ctx.writeEvent(EventType.INVENTORY_CHANGED);
        ctx.writeEvent(EventType.ENTITY_SPAWNED);
    }

    private static boolean isArrow(String itemId) {
        return itemId != null && (itemId.contains("arrow") || itemId.contains("trident"));
    }

    // ── Bucket ───────────────────────────────────────────────────────────

    private static final String[] BUCKETS = {
        "minecraft:water_bucket", "minecraft:lava_bucket",
        "minecraft:milk_bucket", "minecraft:powder_snow_bucket",
        "minecraft:axolotl_bucket", "minecraft:tadpole_bucket"
    };

    private static boolean isBucket(String itemId) {
        if (itemId == null) return false;
        for (String b : BUCKETS) {
            if (b.equals(itemId)) return true;
        }
        return false;
    }

    private static void handleBucket(BlockEntityContext ctx, int slot) {
        int count = ctx.readSlot(slot);
        if (count <= 0) return;

        ctx.writeSlot(slot, count - 1);
        ctx.writeEvent(EventType.INVENTORY_CHANGED);
        ctx.writeEvent(EventType.BLOCK_UPDATE);
    }

    // ── Mob spawn ────────────────────────────────────────────────────────

    private static boolean isSpawnEgg(String itemId) {
        return itemId != null && itemId.contains("spawn_egg");
    }

    private static void spawnMob(BlockEntityContext ctx, int slot) {
        WorldPos target = ctx.facingPos();
        if (target == null) return;

        int count = ctx.readSlot(slot);
        if (count <= 0) return;

        ctx.writeSlot(slot, count - 1);
        ctx.writeEvent(EventType.INVENTORY_CHANGED);
        ctx.writeEvent(EventType.ENTITY_SPAWNED);
    }

    // ── Armor equip ─────────────────────────────────────────────────────

    private static boolean isArmorEquippable(String itemId) {
        if (itemId == null) return false;
        return itemId.contains("_helmet") || itemId.contains("_chestplate") ||
               itemId.contains("_leggings") || itemId.contains("_boots") ||
               itemId.contains("_horse_armor") || itemId.contains("armor_stand");
    }

    private static void equipArmor(BlockEntityContext ctx, int slot) {
        int count = ctx.readSlot(slot);
        if (count <= 0) return;

        ctx.writeSlot(slot, count - 1);
        ctx.writeEvent(EventType.INVENTORY_CHANGED);
        ctx.writeEvent(EventType.ENTITY_SPAWNED);
    }

    // ── Boat ────────────────────────────────────────────────────────────

    private static boolean isBoat(String itemId) {
        return itemId != null && itemId.contains("_boat");
    }

    private static void placeBoat(BlockEntityContext ctx, int slot) {
        WorldPos target = ctx.facingPos();
        if (target == null) return;

        int count = ctx.readSlot(slot);
        if (count <= 0) return;

        ctx.writeSlot(slot, count - 1);
        ctx.writeEvent(EventType.INVENTORY_CHANGED);
        ctx.writeEvent(EventType.ENTITY_SPAWNED);
    }
}
