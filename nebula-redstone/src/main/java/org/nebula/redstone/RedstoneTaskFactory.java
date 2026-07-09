package org.nebula.redstone;

import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EventType;
import org.nebula.core.state.GlobalKey;
import org.nebula.core.state.WorldPos;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Creates {@link TaskNode}s for redstone components.
 *
 * <p>Each component type has a fixed RW-set template (arch doc §5.2).  This
 * factory instantiates those templates with concrete positions.
 *
 * <h3>RW-set templates</h3>
 * <ul>
 *   <li><b>REDSTONE_WIRE:</b> reads 6 neighbours, writes self, fires BLOCK_UPDATE</li>
 *   <li><b>REPEATER:</b> reads input-side block + internal state, writes output-side block + internal state, fires BLOCK_UPDATE only on output change (deferred)</li>
 *   <li><b>COMPARATOR:</b> reads input + side-input blocks, writes output block, fires BLOCK_UPDATE</li>
 *   <li><b>REDSTONE_TORCH:</b> reads attached block, writes self + internal burnout timer, fires BLOCK_UPDATE</li>
 *   <li><b>PISTON:</b> reads power + front blocks + entities, writes piston head + pushed block, fires BLOCK_UPDATE</li>
 * </ul>
 */
public final class RedstoneTaskFactory {

    private RedstoneTaskFactory() {
    }

    /**
     * Creates a TaskNode for the given redstone component at {@code pos}.
     *
     * @param componentType type of redstone component
     * @param pos           world position of the component
     * @param action        the tick logic to execute (provided by the game layer)
     * @return a fully-annotated TaskNode
     */
    public static TaskNode create(RedstoneComponentType componentType, WorldPos pos, Runnable action) {
        Objects.requireNonNull(componentType, "componentType");
        Objects.requireNonNull(pos, "pos");
        RWSet rw = switch (componentType) {
            case REDSTONE_WIRE -> wireRw(pos);
            case REPEATER -> repeaterRw(pos);
            case COMPARATOR -> comparatorRw(pos);
            case REDSTONE_TORCH -> torchRw(pos);
            case PISTON, STICKY_PISTON -> pistonRw(pos);
            case OBSERVER -> observerRw(pos);
            case NOTE_BLOCK -> trivialRw(pos);
            case POWERED_RAIL, ACTIVATOR_RAIL -> railRw(pos);
            case TRIPWIRE_HOOK -> tripwireHookRw(pos);
            case TRIPWIRE -> tripwireRw(pos);
            case REDSTONE_LAMP -> lampRw(pos);
            case DAYLIGHT_DETECTOR -> daylightDetectorRw(pos);
            case HOPPER -> hopperRw(pos);
            case DISPENSER, DROPPER -> dispenserRw(pos);
            case TNT -> tntRw(pos);
            case REDSTONE_BLOCK -> redstoneBlockRw(pos);
            case LEVER, BUTTON -> leverRw(pos);
            case PRESSURE_PLATE -> pressurePlateRw(pos);
            case FENCE_GATE, TRAPDOOR, IRON_DOOR -> gateRw(pos);
            case PISTON_HEAD -> pistonHeadRw(pos);
            case DETECTOR_RAIL -> detectorRailRw(pos);
        };
        return new TaskNode(taskId(componentType, pos), componentType.taskType(), rw,
            () -> action.run());
    }

    /**
     * Creates an inert TaskNode (no-op action) — useful for graph construction
     * and testing without requiring real game logic.
     */
    public static TaskNode inert(RedstoneComponentType componentType, WorldPos pos) {
        return create(componentType, pos, () -> { });
    }

    /** Deterministic task ID: {@code <TYPE>@<dim>:<x>,<y>,<z>}. */
    public static String taskId(RedstoneComponentType type, WorldPos pos) {
        return type.taskType() + "@" + pos.dimensionId() + ":" + pos.x() + "," + pos.y() + "," + pos.z();
    }

    // ── RW-set templates ─────────────────────────────────────────────────────

    private static RWSet wireRw(WorldPos pos) {
        return RWSet.builder()
            .readBlock(pos)
            .readBlock(neighbour(pos, 0, 0, -1))
            .readBlock(neighbour(pos, 0, 0, 1))
            .readBlock(neighbour(pos, -1, 0, 0))
            .readBlock(neighbour(pos, 1, 0, 0))
            .readBlock(neighbour(pos, 0, -1, 0))
            .readBlock(neighbour(pos, 0, 1, 0))
            .writeBlock(pos)
            // Folia per-region wire state — see GlobalKey & RedstoneAnnotations.WIRE_*.
            .readGlobal(GlobalKey.REGION_SHOULD_SIGNAL)
            .writeGlobal(GlobalKey.REGION_SHOULD_SIGNAL)   // toggled false/true in getBlockSignal
            .readGlobal(GlobalKey.REGION_WIRE_TURBO)
            .writeGlobal(GlobalKey.REGION_NEIGHBOR_UPDATER)
            .writeEvent(EventType.BLOCK_UPDATE)
            .build();
    }

    private static RWSet repeaterRw(WorldPos pos) {
        return RWSet.builder()
            .readBlock(inputSide(pos))        // input signal
            .readBlock(pos)                   // internal state read
            .readBlock(outputSide(pos))       // version capture for CAS write
            .writeBlock(outputSide(pos))      // output signal
            .writeBlock(pos)                  // internal delay counter
            // Repeater always schedules a delayed tick; never propagates same-tick.
            .writeGlobal(GlobalKey.REGION_BLOCK_LEVEL_TICKS)
            .writeGlobal(GlobalKey.REGION_NEIGHBOR_UPDATER)
            .writeEvent(EventType.BLOCK_UPDATE)
            .build();
    }

    private static RWSet comparatorRw(WorldPos pos) {
        return RWSet.builder()
            .readBlock(inputSide(pos))
            .readBlock(sideInput(pos))        // -X side input
            .readBlock(neighbour(pos, 1, 0, 0)) // +X side input
            .readBlock(pos)                   // internal mode + current output
            .readBlock(outputSide(pos))       // version capture for CAS write
            .writeBlock(outputSide(pos))
            .writeBlock(pos)
            // Comparator defers state changes through scheduled ticks like repeater.
            .writeGlobal(GlobalKey.REGION_BLOCK_LEVEL_TICKS)
            .writeGlobal(GlobalKey.REGION_NEIGHBOR_UPDATER)
            .writeEvent(EventType.BLOCK_UPDATE)
            .build();
    }

    private static RWSet torchRw(WorldPos pos) {
        return RWSet.builder()
            .readBlock(pos)                   // current power + internal state
            .readBlock(attachedBlock(pos))    // block the torch is attached to
            .writeBlock(pos)                  // torch on/off state + burnout timer
            // Burnout deque + per-region game time (RedstoneTorchBlock.isToggledTooFrequently).
            .readGlobal(GlobalKey.REGION_REDSTONE_TORCH_TOGGLES)
            .writeGlobal(GlobalKey.REGION_REDSTONE_TORCH_TOGGLES)
            .readGlobal(GlobalKey.REGION_REDSTONE_GAME_TIME)
            .writeGlobal(GlobalKey.REGION_BLOCK_LEVEL_TICKS)   // schedules 1-tick delay
            .writeGlobal(GlobalKey.REGION_NEIGHBOR_UPDATER)
            .writeEvent(EventType.BLOCK_UPDATE)
            .build();
    }

    private static RWSet pistonRw(WorldPos pos) {
        return RWSet.builder()
            .readBlock(pos)                   // current piston state
            .readBlock(frontBlock(pos))       // block in front of piston
            .readBlock(attachedBlock(pos))    // powering block behind
            .writeBlock(frontBlock(pos))      // piston head or retracted
            .writeBlock(pos)                  // piston body state
            // Piston neighborChanged schedules; tick(...) writes movement updates.
            .writeGlobal(GlobalKey.REGION_BLOCK_LEVEL_TICKS)
            .writeGlobal(GlobalKey.REGION_NEIGHBOR_UPDATER)
            .writeEvent(EventType.BLOCK_UPDATE)
            .build();
    }

    private static RWSet observerRw(WorldPos pos) {
        return RWSet.builder()
            .readBlock(attachedBlock(pos))    // block behind observer
            .writeBlock(frontBlock(pos))      // observer output pulse
            .writeBlock(pos)                  // observer internal state
            .writeGlobal(GlobalKey.REGION_NEIGHBOR_UPDATER)
            .writeEvent(EventType.BLOCK_UPDATE)
            .build();
    }

    private static RWSet trivialRw(WorldPos pos) {
        return RWSet.builder()
            .readBlock(pos)
            .writeBlock(pos)
            .build();
    }

    private static RWSet railRw(WorldPos pos) {
        return RWSet.builder()
            .readBlock(pos)
            .readBlock(neighbour(pos, 0, 0, -1))
            .readBlock(neighbour(pos, 0, 0, 1))
            .readBlock(neighbour(pos, -1, 0, 0))
            .readBlock(neighbour(pos, 1, 0, 0))
            .readBlock(neighbour(pos, 0, -1, 0))
            .writeBlock(pos)
            .writeGlobal(GlobalKey.REGION_NEIGHBOR_UPDATER)
            .writeEvent(EventType.BLOCK_UPDATE)
            .build();
    }

    /**
     * Detector rail (DetectorRailBlock.checkPressed / updatePowerToConnected).
     * Reads its own POWERED state plus the rail-connected neighbours and the block
     * below (support), and inspects minecarts in its search AABB (approximated by
     * the {pos} footprint since entity reads are not modelled at block granularity).
     * On a press/release it writes POWERED, fans neighbour updates to {pos} and
     * {pos.below}, and schedules a 20-tick release check.
     */
    private static RWSet detectorRailRw(WorldPos pos) {
        return RWSet.builder()
            .readBlock(pos)                       // own POWERED state
            .readBlock(neighbour(pos, 0, 0, -1))  // rail-connected neighbours
            .readBlock(neighbour(pos, 0, 0, 1))
            .readBlock(neighbour(pos, -1, 0, 0))
            .readBlock(neighbour(pos, 1, 0, 0))
            .readBlock(attachedBlock(pos))        // support block below (canSurvive)
            .writeBlock(pos)                      // POWERED toggled
            // updateNeighborsAt(pos) + updateNeighborsAt(pos.below); scheduleTick(pos, 20).
            .writeGlobal(GlobalKey.REGION_BLOCK_LEVEL_TICKS)
            .writeGlobal(GlobalKey.REGION_NEIGHBOR_UPDATER)
            .writeEvent(EventType.BLOCK_UPDATE)
            .build();
    }

    private static RWSet tripwireHookRw(WorldPos pos) {
        return RWSet.builder()
            .readBlock(pos)
            .readBlock(attachedBlock(pos))    // attached wall block
            .writeBlock(pos)
            .writeGlobal(GlobalKey.REGION_NEIGHBOR_UPDATER)
            .writeEvent(EventType.BLOCK_UPDATE)
            .build();
    }

    private static RWSet tripwireRw(WorldPos pos) {
        // Reads all 4 horizontal neighbours (potential string connections)
        return RWSet.builder()
            .readBlock(pos)
            .readBlock(neighbour(pos, 0, 0, -1))
            .readBlock(neighbour(pos, 0, 0, 1))
            .readBlock(neighbour(pos, -1, 0, 0))
            .readBlock(neighbour(pos, 1, 0, 0))
            .writeBlock(pos)
            .writeGlobal(GlobalKey.REGION_NEIGHBOR_UPDATER)
            .writeEvent(EventType.BLOCK_UPDATE)
            .build();
    }

    private static RWSet lampRw(WorldPos pos) {
        return RWSet.builder()
            .readBlock(pos)                   // current on/off state
            .writeBlock(pos)
            .build();
    }

    private static RWSet daylightDetectorRw(WorldPos pos) {
        return RWSet.builder()
            .readBlock(pos)                   // sky light value (via block state)
            .writeBlock(pos)                  // output signal strength
            .writeEvent(EventType.BLOCK_UPDATE)
            .build();
    }

    private static RWSet hopperRw(WorldPos pos) {
        return RWSet.builder()
            .readBlock(pos)
            .readBlock(neighbour(pos, 0, 1, 0))   // block above (source)
            .readBlock(neighbour(pos, 0, -1, 0))  // block below (target)
            .writeBlock(pos)                      // inventory + lock state
            .writeEvent(EventType.INVENTORY_CHANGED)
            .build();
    }

    private static RWSet dispenserRw(WorldPos pos) {
        return RWSet.builder()
            .readBlock(pos)
            .readBlock(frontBlock(pos))       // target block for ejection
            .writeBlock(pos)                  // inventory + triggered state
            .writeEvent(EventType.INVENTORY_CHANGED)
            .build();
    }

    private static RWSet tntRw(WorldPos pos) {
        return RWSet.builder()
            .readBlock(pos)
            .writeBlock(pos)
            .writeEvent(EventType.BLOCK_UPDATE)
            .build();
    }

    private static RWSet redstoneBlockRw(WorldPos pos) {
        // Redstone block is a constant power source — it only reads itself
        return RWSet.builder()
            .readBlock(pos)
            .build();
    }

    private static RWSet leverRw(WorldPos pos) {
        return RWSet.builder()
            .readBlock(pos)
            .writeBlock(pos)
            .writeGlobal(GlobalKey.REGION_NEIGHBOR_UPDATER)
            .writeEvent(EventType.BLOCK_UPDATE)
            .build();
    }

    private static RWSet pressurePlateRw(WorldPos pos) {
        return RWSet.builder()
            .readBlock(pos)
            .readBlock(neighbour(pos, 0, -1, 0)) // entity collision (floor)
            .writeBlock(pos)
            .writeGlobal(GlobalKey.REGION_BLOCK_LEVEL_TICKS)   // pressure plates re-tick to release
            .writeGlobal(GlobalKey.REGION_NEIGHBOR_UPDATER)
            .writeEvent(EventType.BLOCK_UPDATE)
            .build();
    }

    private static RWSet gateRw(WorldPos pos) {
        return RWSet.builder()
            .readBlock(pos)
            .writeBlock(pos)
            .build();
    }

    private static RWSet pistonHeadRw(WorldPos pos) {
        return RWSet.builder()
            .readBlock(pos)
            .readBlock(attachedBlock(pos))    // piston body behind
            .writeBlock(pos)
            .writeEvent(EventType.BLOCK_UPDATE)
            .build();
    }

    // ── Position helpers (arch doc §5.2, §5.3 conventions) ────────────────────

    /** Neighbour at (dx, dy, dz) from pos. */
    public static WorldPos neighbour(WorldPos pos, int dx, int dy, int dz) {
        return new WorldPos(pos.dimensionId(), pos.x() + dx, pos.y() + dy, pos.z() + dz);
    }

    /**
     * Input side: -Z direction (south-facing input, consistent with vanilla
     * repeater / comparator facing when placed at origin looking north).
     * The actual side depends on block state; this is the default orientation.
     */
    static WorldPos inputSide(WorldPos pos) {
        return neighbour(pos, 0, 0, -1);
    }

    /** Output side: +Z direction. */
    static WorldPos outputSide(WorldPos pos) {
        return neighbour(pos, 0, 0, 1);
    }

    /** Side input for comparator: -X direction. */
    static WorldPos sideInput(WorldPos pos) {
        return neighbour(pos, -1, 0, 0);
    }

    /** Block the torch / piston is attached to: -Y (below). */
    static WorldPos attachedBlock(WorldPos pos) {
        return neighbour(pos, 0, -1, 0);
    }

    /** Block in front of a piston: +Z direction. */
    static WorldPos frontBlock(WorldPos pos) {
        return neighbour(pos, 0, 0, 1);
    }
}
