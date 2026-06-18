package org.nebula.plugin;

import org.nebula.core.state.WorldPos;
import org.nebula.entity.BlockEntityState;
import org.nebula.entity.EntityPhysicsState;
import org.nebula.folia.NmsBlockEntityStateBridge;
import org.nebula.folia.NmsBlockStateBridge;
import org.nebula.folia.NmsEntityStateBridge;
import org.nebula.folia.WorldStateHasher;
import org.nebula.redstone.MicroStepScheduler;
import org.nebula.redstone.RedstoneComponentType;
import org.nebula.redstone.RedstoneTaskGenerator;
import org.nebula.redstone.RedstoneTaskRunner;
import org.nebula.redstone.RedstoneWorldState;
import org.nebula.redstone.actions.RedstoneActions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for NebulaPlugin's component wiring (without JavaPlugin instance).
 * Tests that all components work together correctly.
 */
class E2eIntegrationTest {

    private RedstoneWorldState redstoneState;
    private EntityPhysicsState entityState;
    private BlockEntityState blockEntityState;
    private NmsBlockStateBridge blockBridge;
    private NmsEntityStateBridge entityBridge;
    private NmsBlockEntityStateBridge blockEntityBridge;
    private WorldStateHasher stateHasher;
    private MicroStepScheduler microStepScheduler;
    private RedstoneTaskGenerator taskGenerator;
    private Map<WorldPos, RedstoneComponentType> componentMap;

    @BeforeEach
    void setUpComponents() {
        // Initialize CAS state stores
        redstoneState = new RedstoneWorldState();
        entityState = new EntityPhysicsState();
        blockEntityState = new BlockEntityState();

        // Create NMS bridges
        blockBridge = new NmsBlockStateBridge(redstoneState);
        entityBridge = new NmsEntityStateBridge(entityState);
        blockEntityBridge = new NmsBlockEntityStateBridge(blockEntityState);

        // Create state hasher
        stateHasher = new WorldStateHasher(bytes -> WorldPos.parse(new String(bytes)));

        // Create DAG execution pipeline
        componentMap = new ConcurrentHashMap<>();
        Map<String, org.nebula.redstone.RedstoneTaskAction> actionRegistry = RedstoneActions.defaults();
        taskGenerator = new RedstoneTaskGenerator(componentMap, actionRegistry);
        RedstoneTaskRunner redstoneRunner = new RedstoneTaskRunner(redstoneState, actionRegistry);
        microStepScheduler = new MicroStepScheduler(taskGenerator, redstoneRunner);
    }

    @Test
    void allComponentsInitialized() {
        assertNotNull(redstoneState, "RedstoneWorldState");
        assertNotNull(entityState, "EntityPhysicsState");
        assertNotNull(blockEntityState, "BlockEntityState");
        assertNotNull(blockBridge, "NmsBlockStateBridge");
        assertNotNull(entityBridge, "NmsEntityStateBridge");
        assertNotNull(blockEntityBridge, "NmsBlockEntityStateBridge");
        assertNotNull(stateHasher, "WorldStateHasher");
        assertNotNull(microStepScheduler, "MicroStepScheduler");
        assertNotNull(taskGenerator, "RedstoneTaskGenerator");
    }

    @Test
    void registerComponentWorks() {
        WorldPos pos = new WorldPos(0, 10, 64, 20);
        componentMap.put(pos, RedstoneComponentType.REDSTONE_WIRE);
        assertTrue(componentMap.containsKey(pos), "Component should be registered");
        assertEquals(RedstoneComponentType.REDSTONE_WIRE, componentMap.get(pos));
        componentMap.remove(pos);
        assertTrue(!componentMap.containsKey(pos), "Component should be unregistered");
    }

    @Test
    void casStoreRoundTrip() {
        WorldPos pos = new WorldPos(0, 10, 64, 20);
        redstoneState.putPowerLevel(pos, 7);
        assertEquals(7, redstoneState.getPowerLevel(pos));

        // Put again (unconditional write)
        redstoneState.putPowerLevel(pos, 10);
        assertEquals(10, redstoneState.getPowerLevel(pos));
    }

    @Test
    void stateHasherCreatedWithTrackedPositions() {
        WorldPos pos = new WorldPos(0, 10, 64, 20);
        stateHasher.trackPosition(pos);
        assertTrue(stateHasher.trackedPositions().contains(pos));

        stateHasher.untrackPosition(pos);
        assertTrue(!stateHasher.trackedPositions().contains(pos));
    }

    @Test
    void redstoneActionsRegistryIsComplete() {
        Map<String, org.nebula.redstone.RedstoneTaskAction> actions = RedstoneActions.defaults();
        assertTrue(actions.containsKey(RedstoneComponentType.REDSTONE_WIRE.taskType()));
        assertTrue(actions.containsKey(RedstoneComponentType.REDSTONE_TORCH.taskType()));
        assertTrue(actions.containsKey(RedstoneComponentType.REPEATER.taskType()));
        assertTrue(actions.containsKey(RedstoneComponentType.COMPARATOR.taskType()));
    }

    @Test
    void emptyDagExecutionSucceeds() throws org.nebula.core.scheduler.DagExecutionException {
        var result = microStepScheduler.executeTick(java.util.List.of());
        assertEquals(0, result.totalTasks());
        assertEquals(0, result.microSteps());
        assertTrue(!result.hasCommitFailures());
    }
}