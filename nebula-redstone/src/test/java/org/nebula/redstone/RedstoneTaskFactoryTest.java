package org.nebula.redstone;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.DagBuilder;
import org.nebula.core.scheduler.SccContractor;
import org.nebula.core.scheduler.SccStats;
import org.nebula.core.scheduler.TaskGraph;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EventType;
import org.nebula.core.state.GlobalKey;
import org.nebula.core.state.WorldPos;
import org.nebula.redstone.annotations.RedstoneAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

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
    void denseWireRegionProducesOversizedSccDiagnostics() {
        SccStats.reset();
        List<TaskNode> wires = List.of(
            RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, new WorldPos(DIM, 0, 64, 0)),
            RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, new WorldPos(DIM, 1, 64, 0)),
            RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, new WorldPos(DIM, 0, 64, 1)),
            RedstoneTaskFactory.inert(RedstoneComponentType.REDSTONE_WIRE, new WorldPos(DIM, 1, 64, 1))
        );

        TaskGraph graph = DagBuilder.build(wires, new SccContractor(3));

        assertEquals(4, graph.tasks().size());
        assertDoesNotThrow(() -> graph.topologicalLayers());
        assertEquals(1, SccStats.builds());
        assertEquals(1, SccStats.buildsWithCycles());
        assertEquals(1, SccStats.totalSccs());
        assertEquals(0, SccStats.contracted());
        assertEquals(1, SccStats.serialised());
        assertEquals(4, SccStats.maxSccSize());
    }

    @Test
    void separatedSelfContainedRedstoneComponentsAvoidSccDiagnostics() {
        SccStats.reset();
        List<TaskNode> lamps = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            lamps.add(RedstoneTaskFactory.inert(
                RedstoneComponentType.REDSTONE_LAMP,
                new WorldPos(DIM, i * 10, 64, 0)));
        }

        TaskGraph graph = DagBuilder.build(lamps, new SccContractor(3));

        assertEquals(4, graph.tasks().size());
        assertTrue(graph.edges().isEmpty());
        assertEquals(0, SccStats.totalSccs());
        assertEquals(0, SccStats.serialised());
        assertEquals(0, SccStats.maxSccSize());
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

    // ── Drift guard: the factory RWSet and the annotation ComponentTemplate are
    //    two independent representations of the same RW-set. They agree today;
    //    this pins that agreement for EVERY component so a future one-sided edit
    //    (a global/event added to the factory but not the template, or vice
    //    versa) fails the build instead of drifting silently. Block footprints
    //    are compared as physical (dx,dy,dz) deltas so that placeholder aliases
    //    like {pos.input}=={pos.north} line up with the factory's neighbour()
    //    offsets. Position placeholders that the annotation library documents
    //    but the factory does not model at block granularity (inventory slots,
    //    multi-block push chains, entity AABBs) are excluded — see
    //    NON_POSITIONAL_PLACEHOLDERS below.

    /**
     * Placeholders that describe conceptual reads the factory deliberately does
     * NOT model as a single concrete block (inventory slots, entity/minecart
     * AABBs, variable-length piston push chains, block-entity behind a
     * comparator). These are documentation-only in the template and have no
     * factory counterpart, so they are dropped before the delta comparison.
     */
    private static final java.util.Set<String> NON_POSITIONAL_PLACEHOLDERS = java.util.Set.of(
        "{pos.6 neighbours}",
        "{pos.facing + 12 blocks}",
        "{pos.power_source}",
        "{pos.input_be}",
        "{pos.facing.inventory}",
        "{pos.facing.inventory.slots[*]}",
        "{pos.inventory.slots[*]}",
        "{pos.inventory.slots[0..4]}",
        "{pos.inventory.slots[0..8]}",
        "{pos.up.inventory.slots[*]}");

    /** Map a documented position placeholder to its (dx,dy,dz) delta from {pos}. */
    private static WorldPos placeholderDelta(String placeholder) {
        return switch (placeholder) {
            case "{pos}" -> new WorldPos(DIM, 0, 0, 0);
            case "{pos.north}" -> new WorldPos(DIM, 0, 0, -1);
            case "{pos.south}" -> new WorldPos(DIM, 0, 0, 1);
            case "{pos.west}" -> new WorldPos(DIM, -1, 0, 0);
            case "{pos.east}" -> new WorldPos(DIM, 1, 0, 0);
            case "{pos.down}" -> new WorldPos(DIM, 0, -1, 0);
            case "{pos.up}" -> new WorldPos(DIM, 0, 1, 0);
            // Aliases that resolve onto the factory's default facing convention
            // (RedstoneTaskFactory: input=-Z, output=+Z, side_input=-X,
            // attached=-Y, front=+Z). Documented in RedstoneAnnotations header.
            case "{pos.input}" -> new WorldPos(DIM, 0, 0, -1);
            case "{pos.output}" -> new WorldPos(DIM, 0, 0, 1);
            case "{pos.side_input}", "{pos.side2}" -> new WorldPos(DIM, -1, 0, 0);
            case "{pos.side1}" -> new WorldPos(DIM, 1, 0, 0);
            case "{pos.attached}" -> new WorldPos(DIM, 0, -1, 0);
            case "{pos.front}", "{pos.facing}" -> new WorldPos(DIM, 0, 0, 1);
            default -> throw new AssertionError("unmapped position placeholder: " + placeholder
                + " — add it to placeholderDelta() or NON_POSITIONAL_PLACEHOLDERS");
        };
    }

    /** Template placeholder block list → the concrete delta positions the factory would use. */
    private static java.util.Set<WorldPos> templateDeltas(List<String> placeholders) {
        return placeholders.stream()
            .filter(p -> !NON_POSITIONAL_PLACEHOLDERS.contains(p))
            .map(RedstoneTaskFactoryTest::placeholderDelta)
            .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Factory RWSet block positions (absolute) → deltas relative to ORIGIN. */
    private static java.util.Set<WorldPos> factoryDeltas(java.util.Set<WorldPos> abs) {
        return abs.stream()
            .map(p -> new WorldPos(DIM, p.x() - ORIGIN.x(), p.y() - ORIGIN.y(), p.z() - ORIGIN.z()))
            .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void annotationTemplateBlockFootprintMatchesFactoryForEveryComponent() {
        for (RedstoneComponentType type : RedstoneComponentType.values()) {
            RWSet rw = RedstoneTaskFactory.inert(type, ORIGIN).declaredRWSet();
            RedstoneAnnotations.ComponentTemplate t = RedstoneAnnotations.componentTemplate(type);

            assertEquals(templateDeltas(t.readBlocks()), factoryDeltas(rw.readBlocks()),
                type + ": annotation readBlocks disagree with factory read footprint");
            assertEquals(templateDeltas(t.writeBlocks()), factoryDeltas(rw.writtenBlocks()),
                type + ": annotation writeBlocks disagree with factory write footprint");
        }
    }

    @Test
    void annotationTemplateGlobalsAndEventsMatchFactoryForEveryComponent() {
        for (RedstoneComponentType type : RedstoneComponentType.values()) {
            RWSet rw = RedstoneTaskFactory.inert(type, ORIGIN).declaredRWSet();
            RedstoneAnnotations.ComponentTemplate t = RedstoneAnnotations.componentTemplate(type);

            assertEquals(globalValues(rw.readGlobalKeys()), new TreeSet<>(t.readGlobals()),
                type + ": annotation readGlobals disagree with factory read globals");
            assertEquals(globalValues(rw.writtenGlobalKeys()), new TreeSet<>(t.writeGlobals()),
                type + ": annotation writeGlobals disagree with factory write globals");

            java.util.Set<String> factoryEvents = rw.writtenEvents().stream()
                .map(EventType::name).collect(Collectors.toCollection(TreeSet::new));
            assertEquals(factoryEvents, new TreeSet<>(t.events()),
                type + ": annotation events disagree with factory written events");
        }
    }

    private static java.util.Set<String> globalValues(java.util.Set<GlobalKey> keys) {
        return keys.stream().map(GlobalKey::value).collect(Collectors.toCollection(TreeSet::new));
    }
}
