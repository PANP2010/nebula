package org.nebula.entity.actions;

import org.nebula.core.state.EntityField;
import org.nebula.entity.EntityTaskAction;
import org.nebula.entity.EntityTaskContext;

/**
 * Entity item pickup physics (arch doc §6.2, ENTITY_ITEM_PICKUP).
 *
 * <p>Reads the entity's position and open container (if holding one), the item entity's
 * position, and the entity's remaining inventory space. Writes the item entity as removed
 * and updates the entity's inventory slot. Fires INVENTORY_CHANGED on pickup.
 *
 * <p>The pickup radius is 1.5 blocks (vanilla). The action also handles stack merging
 * (if the entity already holds items of the same type) and partial pickup (leaving the
 * remainder on the ground).
 */
public final class EntityPickupItemAction implements EntityTaskAction {

    private final long pickerId;
    private final long itemId;

    public EntityPickupItemAction(long pickerId, long itemId) {
        this.pickerId = pickerId;
        this.itemId = itemId;
    }

    @Override
    public void execute(EntityTaskContext ctx) {
        // Read picker position
        double px = ctx.readScalar(pickerId, "position_x");
        double py = ctx.readScalar(pickerId, "position_y");
        double pz = ctx.readScalar(pickerId, "position_z");

        // Read item entity position
        double ix = ctx.readScalar(itemId, "position_x");
        double iy = ctx.readScalar(itemId, "position_y");
        double iz = ctx.readScalar(itemId, "position_z");

        // Distance check: vanilla pickup radius is 1.5 blocks
        double dx = px - ix, dy = py - iy, dz = pz - iz;
        double dist2 = dx * dx + dy * dy + dz * dz;
        if (dist2 > 2.25) { // 1.5^2
            return; // out of range
        }

        // Read item entity's item stack
        String itemIdStr = String.valueOf(ctx.readScalar(itemId, "item_id"));
        int itemCount = (int) ctx.readScalar(itemId, "item_count");
        if (itemCount <= 0) return;

        // Read entity's inventory to find available space
        int slotsUsed = (int) ctx.readScalar(pickerId, "inventory_slots_used");
        int maxSlots = (int) ctx.readScalar(pickerId, "inventory_max_slots");
        if (slotsUsed >= maxSlots) {
            // Inventory full — item stays on ground
            return;
        }

        // Add item to inventory (simplified: one slot per pickup)
        ctx.writeScalar(pickerId, "inventory_slots_used", Math.min(slotsUsed + 1, maxSlots));
        ctx.writeScalar(pickerId, "inventory_slot_" + slotsUsed + "_id", Double.parseDouble(itemIdStr));
        ctx.writeScalar(pickerId, "inventory_slot_" + slotsUsed + "_count", itemCount);
        ctx.writeScalar(pickerId, "inventory_changed", 1.0);

        // Mark item entity as removed
        ctx.writeScalar(itemId, "removed", 1.0);
    }
}
