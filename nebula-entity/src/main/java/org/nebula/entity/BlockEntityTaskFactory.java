package org.nebula.entity;

import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskAction;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.EventType;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.RandomUsage;
import org.nebula.core.state.WorldPos;

import java.util.Objects;

/**
 * Creates {@link TaskNode}s for block entity tick tasks (arch doc §3.3, §14.3 Month 4-6).
 *
 * <h3>RW-set templates</h3>
 * <ul>
 *   <li><b>HOPPER:</b> reads above container slots, reads self block state,
 *       writes self inventory + output container inventory</li>
 *   <li><b>FURNACE:</b> reads input+fuel slots, writes output slot + cook progress + fuel time</li>
 *   <li><b>BREWING_STAND:</b> reads ingredient+fuel, writes output slots + brew time</li>
 *   <li><b>DROPPER:</b> reads self inventory, writes self inventory + spawns item or transfers</li>
 *   <li><b>DISPENSER:</b> reads self inventory, writes self inventory + may spawn entity</li>
 * </ul>
 */
public final class BlockEntityTaskFactory {

    private BlockEntityTaskFactory() {}

    public static TaskNode hopper(BlockEntitySnapshot snapshot, TaskAction action) {
        Objects.requireNonNull(snapshot);
        return new TaskNode(
            snapshot.taskId(),
            BlockEntityTaskType.HOPPER.taskType(),
            hopperRw(snapshot),
            action
        );
    }

    public static TaskNode hopperInert(BlockEntitySnapshot snapshot) {
        return hopper(snapshot, () -> {});
    }

    public static TaskNode furnace(BlockEntitySnapshot snapshot, TaskAction action) {
        Objects.requireNonNull(snapshot);
        return new TaskNode(
            snapshot.taskId(),
            BlockEntityTaskType.FURNACE.taskType(),
            furnaceRw(snapshot),
            action
        );
    }

    public static TaskNode furnaceInert(BlockEntitySnapshot snapshot) {
        return furnace(snapshot, () -> {});
    }

    public static TaskNode brewingStand(BlockEntitySnapshot snapshot, TaskAction action) {
        Objects.requireNonNull(snapshot);
        return new TaskNode(
            snapshot.taskId(),
            BlockEntityTaskType.BREWING_STAND.taskType(),
            brewingStandRw(snapshot),
            action
        );
    }

    public static TaskNode brewingStandInert(BlockEntitySnapshot snapshot) {
        return brewingStand(snapshot, () -> {});
    }

    public static TaskNode dropper(BlockEntitySnapshot snapshot, TaskAction action) {
        Objects.requireNonNull(snapshot);
        return new TaskNode(
            snapshot.taskId(),
            BlockEntityTaskType.DROPPER.taskType(),
            dropperRw(snapshot),
            action
        );
    }

    public static TaskNode dropperInert(BlockEntitySnapshot snapshot) {
        return dropper(snapshot, () -> {});
    }

    public static TaskNode dispenser(BlockEntitySnapshot snapshot, TaskAction action) {
        Objects.requireNonNull(snapshot);
        return new TaskNode(
            snapshot.taskId(),
            BlockEntityTaskType.DISPENSER.taskType(),
            dispenserRw(snapshot),
            action
        );
    }

    public static TaskNode dispenserInert(BlockEntitySnapshot snapshot) {
        return dispenser(snapshot, () -> {});
    }

    /**
     * Dispatches to the correct inert task factory for a snapshot's
     * {@link BlockEntityTaskType}. This is the snapshot→{@link TaskNode} seam the
     * block-entity tick hook resolves each dirty position through (B8 C3), the
     * analogue of {@code EntityTaskFactory.moveInert(...)} for the entity hook.
     *
     * @throws NullPointerException if {@code snapshot} or its type is null
     */
    public static TaskNode inert(BlockEntitySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return switch (snapshot.type()) {
            case HOPPER -> hopperInert(snapshot);
            case FURNACE -> furnaceInert(snapshot);
            case BREWING_STAND -> brewingStandInert(snapshot);
            case DROPPER -> dropperInert(snapshot);
            case DISPENSER -> dispenserInert(snapshot);
        };
    }

    // ── RW-set templates ──────────────────────────────────────────────────────

    /**
     * Hopper tick: pulls from above container, pushes to output container (arch doc §3.3 example 3).
     * Slot-level granularity for both self and neighbours.
     */
    private static RWSet hopperRw(BlockEntitySnapshot s) {
        WorldPos self = s.pos();
        WorldPos above = s.abovePos();
        WorldPos output = s.outputPos();

        RWSet.Builder b = RWSet.builder()
            .readBlock(self);

        // Read above container (source for pull)
        for (int slot = 0; slot < 27; slot++) {
            b.readBlockEntity(new BlockEntityField(above, "inventory.slots[" + slot + "]"));
        }

        // Write self slots
        for (int slot = 0; slot < s.slotCount(); slot++) {
            b.readBlockEntity(new BlockEntityField(self, "inventory.slots[" + slot + "]"));
            b.writeBlockEntity(new BlockEntityField(self, "inventory.slots[" + slot + "]"));
        }

        // Write output container slots
        for (int slot = 0; slot < 27; slot++) {
            b.writeBlockEntity(new BlockEntityField(output, "inventory.slots[" + slot + "]"));
        }

        b.writeEvent(EventType.INVENTORY_CHANGED);
        return b.build();
    }

    /**
     * Furnace tick: smelts input using fuel, produces output (arch doc §3.3).
     * Slots: 0=input, 1=fuel, 2=output.
     */
    private static RWSet furnaceRw(BlockEntitySnapshot s) {
        WorldPos self = s.pos();
        RWSet.Builder b = RWSet.builder()
            .readBlock(self)
            .readBlockEntity(new BlockEntityField(self, "inventory.slots[0]"))
            .readBlockEntity(new BlockEntityField(self, "inventory.slots[1]"))
            .writeBlockEntity(new BlockEntityField(self, "inventory.slots[0]"))
            .writeBlockEntity(new BlockEntityField(self, "inventory.slots[1]"))
            .writeBlockEntity(new BlockEntityField(self, "inventory.slots[2]"))
            .writeBlockEntity(new BlockEntityField(self, "cook_progress"))
            .writeBlockEntity(new BlockEntityField(self, "fuel_time"));
        return b.build();
    }

    /**
     * Brewing stand tick: brews potions from ingredient + blaze powder.
     * Slots: 0-2=output bottles, 3=ingredient, 4=fuel (blaze powder).
     */
    private static RWSet brewingStandRw(BlockEntitySnapshot s) {
        WorldPos self = s.pos();
        RWSet.Builder b = RWSet.builder()
            .readBlock(self)
            .readBlockEntity(new BlockEntityField(self, "inventory.slots[3]"))
            .readBlockEntity(new BlockEntityField(self, "inventory.slots[4]"))
            .writeBlockEntity(new BlockEntityField(self, "inventory.slots[0]"))
            .writeBlockEntity(new BlockEntityField(self, "inventory.slots[1]"))
            .writeBlockEntity(new BlockEntityField(self, "inventory.slots[2]"))
            .writeBlockEntity(new BlockEntityField(self, "inventory.slots[3]"))
            .writeBlockEntity(new BlockEntityField(self, "inventory.slots[4]"))
            .writeBlockEntity(new BlockEntityField(self, "brew_time"));
        return b.build();
    }

    /**
     * Dropper tick: ejects a random item from its 9-slot inventory.
     */
    private static RWSet dropperRw(BlockEntitySnapshot s) {
        WorldPos self = s.pos();
        WorldPos output = s.outputPos();
        RWSet.Builder b = RWSet.builder()
            .readBlock(self)
            .readBlock(output);

        for (int slot = 0; slot < s.slotCount(); slot++) {
            b.readBlockEntity(new BlockEntityField(self, "inventory.slots[" + slot + "]"));
            b.writeBlockEntity(new BlockEntityField(self, "inventory.slots[" + slot + "]"));
        }

        b.randomUsage(new RandomUsage(RandomInstance.WORLD_RANDOM, 1))
            .writeEvent(EventType.INVENTORY_CHANGED);
        return b.build();
    }

    /**
     * Dispenser tick: dispenses a random item, potentially spawning an entity.
     */
    private static RWSet dispenserRw(BlockEntitySnapshot s) {
        WorldPos self = s.pos();
        WorldPos output = s.outputPos();
        RWSet.Builder b = RWSet.builder()
            .readBlock(self)
            .readBlock(output);

        for (int slot = 0; slot < s.slotCount(); slot++) {
            b.readBlockEntity(new BlockEntityField(self, "inventory.slots[" + slot + "]"));
            b.writeBlockEntity(new BlockEntityField(self, "inventory.slots[" + slot + "]"));
        }

        b.randomUsage(new RandomUsage(RandomInstance.WORLD_RANDOM, 1))
            .writeEvent(EventType.ENTITY_SPAWNED);
        return b.build();
    }
}
