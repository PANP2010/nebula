package org.nebula.core.scheduler;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.state.EventType;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-subsystem integration test for TickPipeline.
 *
 * <p>Simulates a tick with redstone, entity, and block-entity tasks running
 * through the unified pipeline with microstep propagation. Verifies:
 * <ul>
 *   <li>Dependency ordering across subsystems</li>
 *   <li>Microstep propagation from redstone → piston → entity move</li>
 *   <li>Block-entity hopper chain propagation</li>
 *   <li>No spurious conflicts between distant subsystems</li>
 * </ul>
 */
class CrossSubsystemIntegrationTest {

    @Test
    void redstoneTriggersEntityMoveViaPiston() throws Exception {
        // Redstone wire update → triggers piston → piston pushes entity
        WorldPos wirePos = new WorldPos(0, 10, 64, 10);
        WorldPos pistonPos = new WorldPos(0, 11, 64, 10);
        WorldPos entityBlockPos = new WorldPos(0, 12, 64, 10);

        List<String> executed = new ArrayList<>();

        // Initial task: redstone wire updates signal
        TaskNode wireTask = new TaskNode("redstone-wire@0:10,64,10", "REDSTONE_WIRE",
            RWSet.builder()
                .readBlock(new WorldPos(0, 9, 64, 10))
                .writeBlock(wirePos)
                .writeEvent(EventType.BLOCK_UPDATE)
                .build(),
            () -> executed.add("wire"));

        // Generator: wire → piston, piston → entity move
        TaskGenerator gen = completed -> {
            if (completed.taskId().contains("redstone-wire")) {
                return List.of(new TaskNode("piston@0:11,64,10", "PISTON",
                    RWSet.builder()
                        .readBlock(wirePos)
                        .writeBlock(pistonPos)
                        .writeBlock(entityBlockPos)
                        .writeEvent(EventType.BLOCK_UPDATE)
                        .build(),
                    () -> executed.add("piston")));
            }
            if (completed.taskId().contains("piston")) {
                return List.of(new TaskNode("entity-move@0:12,64,10", "ENTITY_MOVE",
                    RWSet.builder()
                        .readBlock(entityBlockPos)
                        .writeBlock(new WorldPos(0, 13, 64, 10))
                        .writeEvent(EventType.ENTITY_MOVED)
                        .build(),
                    () -> executed.add("entity-move")));
            }
            return List.of();
        };

        TickPipeline pipeline = new TickPipeline(gen, TaskRunner.DIRECT);
        TickPipeline.TickResult result = pipeline.execute(List.of(wireTask));

        assertEquals(List.of("wire", "piston", "entity-move"), executed);
        assertEquals(3, result.completedTaskIds().size());
        assertEquals(2, result.microStepRounds());
    }

    @Test
    void hopperChainIndependentOfRedstone() throws Exception {
        // A hopper chain and a redstone circuit run in parallel — no conflicts
        WorldPos hopperA = new WorldPos(0, 100, 50, 200);
        WorldPos hopperB = new WorldPos(0, 101, 50, 200);
        WorldPos wirePos = new WorldPos(0, 0, 64, 0);

        List<String> executed = new ArrayList<>();

        TaskNode hopperTask = new TaskNode("hopper@0:100,50,200", "BLOCK_ENTITY_HOPPER",
            RWSet.builder()
                .readBlock(hopperA)
                .writeBlock(hopperA)
                .writeEvent(EventType.INVENTORY_CHANGED)
                .build(),
            () -> executed.add("hopper-A"));

        TaskNode wireTask = new TaskNode("wire@0:0,64,0", "REDSTONE_WIRE",
            RWSet.builder()
                .writeBlock(wirePos)
                .writeEvent(EventType.BLOCK_UPDATE)
                .build(),
            () -> executed.add("wire"));

        // Hopper chain propagation
        TaskGenerator gen = completed -> {
            if (completed.taskId().equals("hopper@0:100,50,200")) {
                return List.of(new TaskNode("hopper@0:101,50,200", "BLOCK_ENTITY_HOPPER",
                    RWSet.builder()
                        .readBlock(hopperB)
                        .writeBlock(hopperB)
                        .build(),
                    () -> executed.add("hopper-B")));
            }
            return List.of();
        };

        TickPipeline pipeline = new TickPipeline(gen, TaskRunner.DIRECT);
        TickPipeline.TickResult result = pipeline.execute(List.of(hopperTask, wireTask));

        // Both initial tasks should be in layer 0 (no conflict)
        assertTrue(result.completedTaskIds().contains("hopper@0:100,50,200"));
        assertTrue(result.completedTaskIds().contains("wire@0:0,64,0"));
        assertTrue(result.completedTaskIds().contains("hopper@0:101,50,200"));
        assertEquals(3, result.completedTaskIds().size());
    }

    @Test
    void multipleSubsystemsMixedDependencies() throws Exception {
        // Redstone powers a hopper (activates it), which then transfers items
        WorldPos redstonePos = new WorldPos(0, 5, 64, 5);
        WorldPos hopperPos = new WorldPos(0, 5, 63, 5);

        List<String> executed = new ArrayList<>();

        // Redstone updates the block that the hopper reads
        TaskNode redstone = new TaskNode("redstone@0:5,64,5", "REDSTONE",
            RWSet.builder()
                .writeBlock(redstonePos)
                .writeBlock(hopperPos) // powers the hopper's block
                .writeEvent(EventType.BLOCK_UPDATE)
                .build(),
            () -> executed.add("redstone"));

        // Hopper reads its own block (to check powered state)
        TaskNode hopper = new TaskNode("hopper@0:5,63,5", "HOPPER",
            RWSet.builder()
                .readBlock(hopperPos)
                .writeBlock(new WorldPos(0, 5, 62, 5))
                .build(),
            () -> executed.add("hopper"));

        TaskGenerator noopGen = c -> List.of();
        TickPipeline pipeline = new TickPipeline(noopGen, TaskRunner.DIRECT);
        TickPipeline.TickResult result = pipeline.execute(List.of(redstone, hopper));

        // Redstone must execute before hopper (RAW: redstone writes hopperPos, hopper reads it)
        assertTrue(executed.indexOf("redstone") < executed.indexOf("hopper"),
            "redstone must execute before hopper due to RAW dependency on hopper block");
    }

    @Test
    void parallelEntityMovesDontConflict() throws Exception {
        // Two distant entities moving independently should be in the same layer
        List<String> executed = new ArrayList<>();

        TaskNode entityA = new TaskNode("move-A", "ENTITY_MOVE",
            RWSet.builder()
                .writeBlock(new WorldPos(0, 0, 64, 0))
                .build(),
            () -> executed.add("A"));

        TaskNode entityB = new TaskNode("move-B", "ENTITY_MOVE",
            RWSet.builder()
                .writeBlock(new WorldPos(0, 500, 64, 500))
                .build(),
            () -> executed.add("B"));

        TaskNode entityC = new TaskNode("move-C", "ENTITY_MOVE",
            RWSet.builder()
                .writeBlock(new WorldPos(0, -200, 64, -200))
                .build(),
            () -> executed.add("C"));

        TaskGenerator noopGen = c -> List.of();
        TickPipeline pipeline = new TickPipeline(noopGen, TaskRunner.DIRECT);
        TickPipeline.TickResult result = pipeline.execute(List.of(entityA, entityB, entityC));

        assertEquals(3, result.completedTaskIds().size());
        // All should be in a single layer (1 layer total, 0 microsteps)
        assertEquals(1, result.layersExecuted());
        assertEquals(0, result.microStepRounds());
    }
}
