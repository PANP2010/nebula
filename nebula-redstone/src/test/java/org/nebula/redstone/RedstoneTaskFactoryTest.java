package org.nebula.redstone;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EventType;
import org.nebula.core.state.GlobalKey;
import org.nebula.core.state.WorldPos;
import org.nebula.redstone.annotations.RedstoneAnnotations;

import static org.junit.jupiter.api.Assertions.*;

class RedstoneTaskFactoryTest {

    private static final int DIM = 0;
    private static final WorldPos ORIGIN = new WorldPos(DIM, 0, 64, 0);

    @Test
    void wireTaskIdFormat() {
        TaskNode node = RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, ORIGIN);
        assertEquals("REDSTONE_WIRE@0:0,64,0", node.taskId());
        assertEquals("REDSTONE_WIRE", node.taskType());
    }

    @Test
    void wireRwSetHasSixNeighbours() {
        TaskNode node = RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, ORIGIN);
        RWSet rw = node.declaredRWSet();

        // Should read 6 adjacent blocks
        assertTrue(rw.declaresBlockRead(new WorldPos(DIM, 0, 64, -1)));  // north
        assertTrue(rw.declaresBlockRead(new WorldPos(DIM, 0, 64, 1)));   // south
        assertTrue(rw.declaresBlockRead(new WorldPos(DIM, -1, 64, 0)));  // west
        assertTrue(rw.declaresBlockRead(new WorldPos(DIM, 1, 64, 0)));   // east
        assertTrue(rw.declaresBlockRead(new WorldPos(DIM, 0, 63, 0)));   // below
        assertTrue(rw.declaresBlockRead(new WorldPos(DIM, 0, 65, 0)));   // above

        // Should write self
        assertTrue(rw.declaresBlockWrite(ORIGIN));
    }

    @Test
    void wireFiresBlockUpdate() {
        TaskNode node = RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, ORIGIN);
        assertTrue(node.declaredRWSet().writtenEvents().contains(EventType.BLOCK_UPDATE));
    }

    @Test
    void repeaterRwSetCoversInputOutputAndInternalState() {
        TaskNode node = RedstoneTaskFactory.inert(RedstoneComponentType.REPEATER, ORIGIN);
        RWSet rw = node.declaredRWSet();

        // Input side (-Z)
        assertTrue(rw.declaresBlockRead(new WorldPos(DIM, 0, 64, -1)));
        // Output side (+Z)
        assertTrue(rw.declaresBlockWrite(new WorldPos(DIM, 0, 64, 1)));
        // Self (internal state)
        assertTrue(rw.declaresBlockWrite(ORIGIN));
        assertTrue(rw.declaresBlockRead(ORIGIN));
    }

    @Test
    void comparatorRwSetIncludesSideInput() {
        TaskNode node = RedstoneTaskFactory.inert(RedstoneComponentType.COMPARATOR, ORIGIN);
        RWSet rw = node.declaredRWSet();

        // Side input (-X)
        assertTrue(rw.declaresBlockRead(new WorldPos(DIM, -1, 64, 0)));
        // Input side (-Z)
        assertTrue(rw.declaresBlockRead(new WorldPos(DIM, 0, 64, -1)));
        // Output (+Z)
        assertTrue(rw.declaresBlockWrite(new WorldPos(DIM, 0, 64, 1)));
    }

    @Test
    void torchRwSetCoversAttachedBlock() {
        TaskNode node = RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_TORCH, ORIGIN);
        RWSet rw = node.declaredRWSet();

        // Attached block (below)
        assertTrue(rw.declaresBlockRead(new WorldPos(DIM, 0, 63, 0)));
        // Self
        assertTrue(rw.declaresBlockWrite(ORIGIN));
    }

    @Test
    void pistonRwSetCoversFrontAndAttached() {
        TaskNode node = RedstoneTaskFactory.inert(RedstoneComponentType.PISTON, ORIGIN);
        RWSet rw = node.declaredRWSet();

        // Front (+Z)
        assertTrue(rw.declaresBlockWrite(new WorldPos(DIM, 0, 64, 1)));
        // Attached (below)
        assertTrue(rw.declaresBlockRead(new WorldPos(DIM, 0, 63, 0)));
    }

    @Test
    void differentPositionsYieldDifferentTaskIds() {
        WorldPos pos1 = new WorldPos(DIM, 10, 64, 20);
        WorldPos pos2 = new WorldPos(DIM, 10, 64, 21);
        TaskNode a = RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, pos1);
        TaskNode b = RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, pos2);
        assertNotEquals(a.taskId(), b.taskId());
    }

    @Test
    void deterministicTaskIdFromSameInputs() {
        String id1 = RedstoneTaskFactory.taskId(RedstoneComponentType.REPEATER, ORIGIN);
        String id2 = RedstoneTaskFactory.taskId(RedstoneComponentType.REPEATER, ORIGIN);
        assertEquals(id1, id2);
    }

    @Test
    void adjacentWiresShareReadWritePosition() {
        WorldPos wireA = new WorldPos(DIM, 0, 64, 0);
        WorldPos wireB = new WorldPos(DIM, 0, 64, 1);
        TaskNode a = RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, wireA);
        TaskNode b = RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, wireB);

        // A reads B's position and B reads A's position → both should be detected
        assertTrue(a.declaredRWSet().declaresBlockRead(wireB));
        assertTrue(b.declaredRWSet().declaresBlockRead(wireA));
    }

    @Test
    void railRwSetCoversHorizontalPowerSearchAndSupport() {
        TaskNode node = RedstoneTaskFactory.inert(RedstoneComponentType.POWERED_RAIL, ORIGIN);
        RWSet rw = node.declaredRWSet();

        assertTrue(rw.declaresBlockRead(new WorldPos(DIM, 0, 64, -1)));
        assertTrue(rw.declaresBlockRead(new WorldPos(DIM, 0, 64, 1)));
        assertTrue(rw.declaresBlockRead(new WorldPos(DIM, -1, 64, 0)));
        assertTrue(rw.declaresBlockRead(new WorldPos(DIM, 1, 64, 0)));
        assertTrue(rw.declaresBlockRead(new WorldPos(DIM, 0, 63, 0)));
        assertTrue(rw.declaresBlockWrite(ORIGIN));
        assertTrue(rw.writtenEvents().contains(EventType.BLOCK_UPDATE));
    }

    @Test
    void allComponentTypesHaveRuntimeRwAndAnnotationTemplates() {
        for (RedstoneComponentType type : RedstoneComponentType.values()) {
            TaskNode node = RedstoneTaskFactory.inert(type, ORIGIN);
            RWSet rw = node.declaredRWSet();
            assertFalse(rw.readBlocks().isEmpty() && rw.writtenBlocks().isEmpty(),
                type + " should declare a block footprint");

            RedstoneAnnotations.ComponentTemplate template = RedstoneAnnotations.componentTemplate(type);
            assertNotNull(template, type + " should have annotation metadata");
            assertEquals(type, template.componentType());
            assertEquals(type.microStepBehavior(), template.microStep());
            assertEquals(type.sccBehavior(), template.scc());
            assertFalse(template.methods().isEmpty(), type + " should list source methods");
            assertFalse(template.readBlocks().isEmpty() && template.writeBlocks().isEmpty(),
                type + " metadata should declare a block footprint");
        }
        assertEquals(RedstoneComponentType.values().length, RedstoneAnnotations.componentTemplates().size());
    }

    @Test
    void annotationMetadataMatchesRepresentativeRuntimeSideEffects() {
        RedstoneAnnotations.ComponentTemplate wire =
            RedstoneAnnotations.componentTemplate(RedstoneComponentType.REDSTONE_WIRE);
        assertTrue(wire.readBlocks().contains("{pos.north}"));
        assertTrue(wire.writeGlobals().contains(GlobalKey.REGION_NEIGHBOR_UPDATER.value()));
        assertTrue(wire.events().contains(EventType.BLOCK_UPDATE.name()));

        RedstoneAnnotations.ComponentTemplate hopper =
            RedstoneAnnotations.componentTemplate(RedstoneComponentType.HOPPER);
        assertTrue(hopper.readBlocks().contains("{pos.up}"));
        assertTrue(hopper.readBlocks().contains("{pos.down}"));
        assertTrue(hopper.events().contains(EventType.INVENTORY_CHANGED.name()));

        RedstoneAnnotations.ComponentTemplate pressurePlate =
            RedstoneAnnotations.componentTemplate(RedstoneComponentType.PRESSURE_PLATE);
        assertTrue(pressurePlate.readBlocks().contains("{pos.down}"));
        assertTrue(pressurePlate.writeGlobals().contains(GlobalKey.REGION_BLOCK_LEVEL_TICKS.value()));
        assertTrue(pressurePlate.writeGlobals().contains(GlobalKey.REGION_NEIGHBOR_UPDATER.value()));
    }
}
