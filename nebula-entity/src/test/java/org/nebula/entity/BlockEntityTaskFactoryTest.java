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
    void hopperDeclaresEveryFieldTheActionTouches() {
        // Regression guard: the hopper ACTION (BlockEntityActions.hopper) reads AND
        // writes transfer_cooldown, WRITES the pull-source (above) slot, and READS the
        // push-target (output) slot for its capacity check. The RW-set template used to
        // omit all three — an incomplete RW-set on the serialized-inventory path (B8 C3),
        // the highest corruption risk. Pin the corrected conservative envelope.
        BlockEntitySnapshot snap = BlockEntitySnapshot.hopper(HOPPER_POS, 0, -1, 0);
        RWSet rw = BlockEntityTaskFactory.hopperInert(snap).declaredRWSet();
        WorldPos above = new WorldPos(0, 100, 51, 200);
        WorldPos output = new WorldPos(0, 100, 49, 200);

        // transfer_cooldown: read + write
        assertTrue(rw.declaresBlockEntityRead(new BlockEntityField(HOPPER_POS, "transfer_cooldown")),
            "cooldown is read as the transfer gate");
        assertTrue(rw.declaresBlockEntityWrite(new BlockEntityField(HOPPER_POS, "transfer_cooldown")),
            "cooldown is armed/decremented");

        // above (pull source) slot 0: the pull decrements it, so it must be a declared WRITE
        assertTrue(rw.declaresBlockEntityWrite(new BlockEntityField(above, "inventory.slots[0]")),
            "the pull decrements the source slot — must be a declared write, not read-only");

        // output (push target) slot 0: the out<64 capacity check reads it
        assertTrue(rw.declaresBlockEntityRead(new BlockEntityField(output, "inventory.slots[0]")),
            "the capacity check reads the output slot — must be a declared read, not write-only");
    }

    @Test
    void furnaceDeclaresEveryFieldTheActionTouches() {
        // The furnace ACTION reads slot 2 (output, for the >=64 capacity gate) and both
        // timers (cook_progress, fuel_time) before writing them — those reads were
        // undeclared. Pin that every read the action performs is now declared read.
        BlockEntitySnapshot snap = BlockEntitySnapshot.furnace(FURNACE_POS);
        RWSet rw = BlockEntityTaskFactory.furnaceInert(snap).declaredRWSet();

        assertTrue(rw.declaresBlockEntityRead(new BlockEntityField(FURNACE_POS, "inventory.slots[2]")),
            "the capacity check reads the output slot");
        assertTrue(rw.declaresBlockEntityRead(new BlockEntityField(FURNACE_POS, "cook_progress")),
            "progress is read before it is advanced");
        assertTrue(rw.declaresBlockEntityRead(new BlockEntityField(FURNACE_POS, "fuel_time")),
            "fuel time is read before it is burned down");
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
        // getRandomSlot draws once per non-empty slot (reservoir sampling), so the budget
        // is the slot count (worst case: all loaded), not 1 — see BlockEntityActions.selectDispenseSlot.
        assertEquals(snap.slotCount(), rw.randomUsage().get().maxCallsEstimate());
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
