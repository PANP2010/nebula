package org.nebula.entity;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.BlockEntityField;
import org.nebula.core.state.EventType;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.WorldPos;

import static org.junit.jupiter.api.Assertions.*;

class BlockEntityTaskFactoryTest {

    private static final WorldPos HOPPER_POS = new WorldPos(0, 100, 50, 200);
    private static final WorldPos FURNACE_POS = new WorldPos(0, 80, 60, 150);
    private static final WorldPos BREWER_POS = new WorldPos(0, 90, 55, 180);
    private static final WorldPos DROPPER_POS = new WorldPos(0, 70, 40, 120);

    @Test
    void hopperTaskId() {
        BlockEntitySnapshot snap = BlockEntitySnapshot.hopper(HOPPER_POS, 0, -1, 0);
        TaskNode node = BlockEntityTaskFactory.hopperInert(snap);
        assertEquals("BLOCK_ENTITY_HOPPER@0:100,50,200", node.taskId());
        assertEquals("BLOCK_ENTITY_HOPPER", node.taskType());
    }

    @Test
    void hopperReadsAboveAndWritesSelfAndOutput() {
        BlockEntitySnapshot snap = BlockEntitySnapshot.hopper(HOPPER_POS, 0, -1, 0);
        TaskNode node = BlockEntityTaskFactory.hopperInert(snap);
        RWSet rw = node.declaredRWSet();

        // Reads self block
        assertTrue(rw.declaresBlockRead(HOPPER_POS));

        // Reads above container slots
        WorldPos above = new WorldPos(0, 100, 51, 200);
        assertTrue(rw.declaresBlockEntityRead(new BlockEntityField(above, "inventory.slots[0]")));
        assertTrue(rw.declaresBlockEntityRead(new BlockEntityField(above, "inventory.slots[26]")));

        // Writes self slots (hopper has 5 slots)
        assertTrue(rw.declaresBlockEntityWrite(new BlockEntityField(HOPPER_POS, "inventory.slots[0]")));
        assertTrue(rw.declaresBlockEntityWrite(new BlockEntityField(HOPPER_POS, "inventory.slots[4]")));

        // Writes output container (facing down: 0,-1,0)
        WorldPos output = new WorldPos(0, 100, 49, 200);
        assertTrue(rw.declaresBlockEntityWrite(new BlockEntityField(output, "inventory.slots[0]")));

        assertTrue(rw.writtenEvents().contains(EventType.INVENTORY_CHANGED));
    }

    @Test
    void furnaceReadsInputFuelWritesOutputAndProgress() {
        BlockEntitySnapshot snap = BlockEntitySnapshot.furnace(FURNACE_POS);
        TaskNode node = BlockEntityTaskFactory.furnaceInert(snap);
        RWSet rw = node.declaredRWSet();

        assertTrue(rw.declaresBlockRead(FURNACE_POS));
        assertTrue(rw.declaresBlockEntityRead(new BlockEntityField(FURNACE_POS, "inventory.slots[0]")));
        assertTrue(rw.declaresBlockEntityRead(new BlockEntityField(FURNACE_POS, "inventory.slots[1]")));
        assertTrue(rw.declaresBlockEntityWrite(new BlockEntityField(FURNACE_POS, "inventory.slots[2]")));
        assertTrue(rw.declaresBlockEntityWrite(new BlockEntityField(FURNACE_POS, "cook_progress")));
        assertTrue(rw.declaresBlockEntityWrite(new BlockEntityField(FURNACE_POS, "fuel_time")));
    }

    @Test
    void brewingStandSlots() {
        BlockEntitySnapshot snap = BlockEntitySnapshot.brewingStand(BREWER_POS);
        TaskNode node = BlockEntityTaskFactory.brewingStandInert(snap);
        RWSet rw = node.declaredRWSet();

        // Reads ingredient (slot 3) and fuel (slot 4)
        assertTrue(rw.declaresBlockEntityRead(new BlockEntityField(BREWER_POS, "inventory.slots[3]")));
        assertTrue(rw.declaresBlockEntityRead(new BlockEntityField(BREWER_POS, "inventory.slots[4]")));

        // Writes all output bottles (0-2) + ingredient + fuel + brew_time
        assertTrue(rw.declaresBlockEntityWrite(new BlockEntityField(BREWER_POS, "inventory.slots[0]")));
        assertTrue(rw.declaresBlockEntityWrite(new BlockEntityField(BREWER_POS, "inventory.slots[1]")));
        assertTrue(rw.declaresBlockEntityWrite(new BlockEntityField(BREWER_POS, "inventory.slots[2]")));
        assertTrue(rw.declaresBlockEntityWrite(new BlockEntityField(BREWER_POS, "brew_time")));
    }

    @Test
    void dropperUsesWorldRandom() {
        BlockEntitySnapshot snap = BlockEntitySnapshot.dropper(DROPPER_POS, 1, 0, 0);
        TaskNode node = BlockEntityTaskFactory.dropperInert(snap);
        RWSet rw = node.declaredRWSet();

        assertTrue(rw.randomUsage().isPresent());
        assertEquals(RandomInstance.WORLD_RANDOM, rw.randomUsage().get().instance());
        assertEquals(1, rw.randomUsage().get().maxCallsEstimate());
        assertTrue(rw.writtenEvents().contains(EventType.INVENTORY_CHANGED));
    }

    @Test
    void dispenserFiresEntitySpawned() {
        BlockEntitySnapshot snap = BlockEntitySnapshot.dispenser(DROPPER_POS, 0, 0, 1);
        TaskNode node = BlockEntityTaskFactory.dispenserInert(snap);
        RWSet rw = node.declaredRWSet();

        assertTrue(rw.writtenEvents().contains(EventType.ENTITY_SPAWNED));
        assertTrue(rw.randomUsage().isPresent());
    }

    @Test
    void twoHoppersChainedConflictOnSharedSlots() {
        // Hopper A outputs to position where Hopper B sits
        WorldPos posA = new WorldPos(0, 10, 60, 10);
        WorldPos posB = new WorldPos(0, 11, 60, 10);
        BlockEntitySnapshot hopperA = BlockEntitySnapshot.hopper(posA, 1, 0, 0); // faces east -> posB
        BlockEntitySnapshot hopperB = BlockEntitySnapshot.hopper(posB, 1, 0, 0); // faces east -> posC

        TaskNode nodeA = BlockEntityTaskFactory.hopperInert(hopperA);
        TaskNode nodeB = BlockEntityTaskFactory.hopperInert(hopperB);

        RWSet rwA = nodeA.declaredRWSet();
        RWSet rwB = nodeB.declaredRWSet();

        // A writes to posB's inventory (output), B reads/writes posB's inventory (self)
        // This should create a write-write conflict
        assertTrue(rwA.hasWriteWriteConflictWith(rwB),
            "chained hoppers must conflict — A writes B's slots, B writes B's slots");
    }

    @Test
    void distantFurnacesDoNotConflict() {
        BlockEntitySnapshot furnA = BlockEntitySnapshot.furnace(new WorldPos(0, 10, 60, 10));
        BlockEntitySnapshot furnB = BlockEntitySnapshot.furnace(new WorldPos(0, 500, 60, 500));

        TaskNode nodeA = BlockEntityTaskFactory.furnaceInert(furnA);
        TaskNode nodeB = BlockEntityTaskFactory.furnaceInert(furnB);

        RWSet rwA = nodeA.declaredRWSet();
        RWSet rwB = nodeB.declaredRWSet();

        assertFalse(rwA.hasReadWriteConflictWith(rwB));
        assertFalse(rwA.hasWriteWriteConflictWith(rwB));
        assertFalse(rwA.hasWriteReadConflictWith(rwB));
    }

    @Test
    void blockEntityTaskTypeRoundtrip() {
        assertEquals(BlockEntityTaskType.HOPPER,
            BlockEntityTaskType.fromTaskType("BLOCK_ENTITY_HOPPER"));
        assertEquals(BlockEntityTaskType.FURNACE,
            BlockEntityTaskType.fromTaskType("BLOCK_ENTITY_FURNACE"));
        assertNull(BlockEntityTaskType.fromTaskType("UNKNOWN"));
    }
}
