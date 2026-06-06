package org.nebula.redstone.annotations;

import org.nebula.annotations.MicroStepBehavior;
import org.nebula.annotations.SccBehavior;
import org.nebula.redstone.RedstoneComponentType;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Complete @NebulaRW annotation library for Minecraft 1.21.4 / Folia 26.1.x redstone methods.
 *
 * <p>Based on source analysis of Folia-ver-26.1.x patches. Each entry specifies:
 * <ul>
 *   <li>The target class and method</li>
 *   <li>Read positions (in terms of {pos} placeholder)</li>
 *   <li>Write positions</li>
 *   <li>Global keys (Folia per-region state)</li>
 *   <li>Triggered events and microstep behavior</li>
 * </ul>
 *
 * <h2>Position Conventions</h2>
 * All positions relative to the component's own position {@code {pos}}:
 * <pre>
 *   {pos}              = component's own coordinate
 *   {pos.north}        = (x, y, z-1)
 *   {pos.south}        = (x, y, z+1)
 *   {pos.west}         = (x, y, x-1)
 *   {pos.east}         = (x, y, z+1)
 *   {pos.down}         = (x, y-1, z)
 *   {pos.up}           = (x, y+1, z)
 *   {pos.input}        = default input side = {pos.north} = (x, y, z-1)
 *   {pos.output}       = default output side = {pos.south} = (x, y, z+1)
 *   {pos.side_input}   = comparator side input = {pos.west} = (x-1, y, z)
 *   {pos.attached}     = torch attached block = {pos.down} = (x, y-1, z)
 * </pre>
 *
 * <h2>Global Key Conventions</h2>
 * <ul>
 *   <li>{@code region.should_signal} — Folia per-region shouldSignal flag</li>
 *   <li>{@code region.redstone_torch_toggles} — torch burnout deque</li>
 *   <li>{@code region.wire_turbo} — Eigencraft wire algorithm instance</li>
 *   <li>{@code region.block_level_ticks} — scheduled tick queue (repeater/comparator delays)</li>
 *   <li>{@code region.neighbor_updater} — CollectingNeighborUpdater queue</li>
 * </ul>
 */
public final class RedstoneAnnotations {

    private RedstoneAnnotations() {}

    /**
     * Structured RW metadata mirroring {@code RedstoneTaskFactory}'s concrete templates.
     * Paths use the same placeholders documented above and remain conservative when
     * exact facing/runtime state is unavailable in the snapshot.
     */
    public record ComponentTemplate(
        RedstoneComponentType componentType,
        List<String> methods,
        List<String> readBlocks,
        List<String> writeBlocks,
        List<String> readGlobals,
        List<String> writeGlobals,
        List<String> events,
        MicroStepBehavior microStep,
        SccBehavior scc,
        String notes
    ) {
        public ComponentTemplate {
            Objects.requireNonNull(componentType, "componentType");
            methods = List.copyOf(methods);
            readBlocks = List.copyOf(readBlocks);
            writeBlocks = List.copyOf(writeBlocks);
            readGlobals = List.copyOf(readGlobals);
            writeGlobals = List.copyOf(writeGlobals);
            events = List.copyOf(events);
            Objects.requireNonNull(microStep, "microStep");
            Objects.requireNonNull(scc, "scc");
            notes = notes == null ? "" : notes;
        }
    }

    private static ComponentTemplate template(
        RedstoneComponentType type,
        List<String> methods,
        List<String> readBlocks,
        List<String> writeBlocks,
        List<String> readGlobals,
        List<String> writeGlobals,
        List<String> events,
        String notes
    ) {
        return new ComponentTemplate(
            type,
            methods,
            readBlocks,
            writeBlocks,
            readGlobals,
            writeGlobals,
            events,
            type.microStepBehavior(),
            type.sccBehavior(),
            notes);
    }

    private static final Map<RedstoneComponentType, ComponentTemplate> COMPONENT_TEMPLATES = buildComponentTemplates();

    public static ComponentTemplate componentTemplate(RedstoneComponentType type) {
        return COMPONENT_TEMPLATES.get(Objects.requireNonNull(type, "type"));
    }

    public static Map<RedstoneComponentType, ComponentTemplate> componentTemplates() {
        return Map.copyOf(COMPONENT_TEMPLATES);
    }

    private static Map<RedstoneComponentType, ComponentTemplate> buildComponentTemplates() {
        EnumMap<RedstoneComponentType, ComponentTemplate> templates = new EnumMap<>(RedstoneComponentType.class);
        put(templates, RedstoneComponentType.REDSTONE_WIRE,
            methods(WIRE_NEIGHBOR_CHANGED, WIRE_GET_BLOCK_SIGNAL, WIRE_TURBO_UPDATE),
            blocks("{pos}", "{pos.north}", "{pos.south}", "{pos.west}", "{pos.east}", "{pos.down}", "{pos.up}"),
            blocks("{pos}"),
            globals("region.should_signal", "region.wire_turbo"),
            globals("region.should_signal", "region.neighbor_updater"),
            events("BLOCK_UPDATE"),
            "Wire reads self plus six neighbours and writes its own power level.");
        put(templates, RedstoneComponentType.REPEATER,
            methods(REPEATER_TICK, REPEATER_NEIGHBOR_CHANGED),
            blocks("{pos.input}", "{pos}", "{pos.output}"),
            blocks("{pos.output}", "{pos}"),
            globals(),
            globals("region.block_level_ticks", "region.neighbor_updater"),
            events("BLOCK_UPDATE"),
            "Default orientation uses north input and south output; output changes are delayed.");
        put(templates, RedstoneComponentType.COMPARATOR,
            methods(COMPARATOR_TICK, COMPARATOR_NEIGHBOR_CHANGED),
            blocks("{pos.input}", "{pos.west}", "{pos.east}", "{pos}", "{pos.output}"),
            blocks("{pos.output}", "{pos}"),
            globals(),
            globals("region.block_level_ticks", "region.neighbor_updater"),
            events("BLOCK_UPDATE"),
            "Comparator includes both side inputs and the output position for version capture.");
        put(templates, RedstoneComponentType.REDSTONE_TORCH,
            methods(TORCH_TICK, TORCH_NEIGHBOR_CHANGED, TORCH_BURNOUT_CHECK),
            blocks("{pos}", "{pos.attached}"),
            blocks("{pos}"),
            globals("region.redstone_torch_toggles", "region.redstone_game_time"),
            globals("region.redstone_torch_toggles", "region.block_level_ticks", "region.neighbor_updater"),
            events("BLOCK_UPDATE"),
            "Torch burnout state is region-global; attached block defaults to below.");
        put(templates, RedstoneComponentType.PISTON,
            methods(PISTON_NEIGHBOR_CHANGED, PISTON_TICK),
            blocks("{pos}", "{pos.front}", "{pos.attached}"),
            blocks("{pos.front}", "{pos}"),
            globals(),
            globals("region.block_level_ticks", "region.neighbor_updater"),
            events("BLOCK_UPDATE"),
            "Runtime template conservatively uses default front plus attached/power block.");
        put(templates, RedstoneComponentType.STICKY_PISTON,
            methods(PISTON_NEIGHBOR_CHANGED, PISTON_TICK),
            blocks("{pos}", "{pos.front}", "{pos.attached}"),
            blocks("{pos.front}", "{pos}"),
            globals(),
            globals("region.block_level_ticks", "region.neighbor_updater"),
            events("BLOCK_UPDATE"),
            "Sticky piston shares the piston footprint; pull-specific effects are serialized.");
        put(templates, RedstoneComponentType.OBSERVER,
            methods(OBSERVER_UPDATE_SHAPE, OBSERVER_TICK),
            blocks("{pos.attached}"),
            blocks("{pos.front}", "{pos}"),
            globals(),
            globals("region.neighbor_updater"),
            events("BLOCK_UPDATE"),
            "Observer reads the observed block and writes its pulse/output state.");
        put(templates, RedstoneComponentType.NOTE_BLOCK,
            methods(NOTE_BLOCK_TRIGGER_EVENT),
            blocks("{pos}"),
            blocks("{pos}"), globals(), globals(), events(),
            "Runtime redstone trigger template is self-contained; sound event is not modeled as a DAG event.");
        put(templates, RedstoneComponentType.POWERED_RAIL,
            methods(POWERED_RAIL_UPDATE_POWER, POWERED_RAIL_FIND_SIGNAL),
            blocks("{pos}", "{pos.north}", "{pos.south}", "{pos.west}", "{pos.east}", "{pos.down}"),
            blocks("{pos}"),
            globals(),
            globals("region.neighbor_updater"),
            events("BLOCK_UPDATE"),
            "Rail power propagation reads horizontal neighbours plus support and writes self state.");
        put(templates, RedstoneComponentType.TRIPWIRE_HOOK,
            methods(TRIPWIRE_HOOK_CALCULATE_STATE, TRIPWIRE_HOOK_NEIGHBOR_CHANGED),
            blocks("{pos}", "{pos.attached}"), blocks("{pos}"), globals(), globals("region.neighbor_updater"), events("BLOCK_UPDATE"),
            "Hook scans are represented by own state plus the supporting wall block.");
        put(templates, RedstoneComponentType.TRIPWIRE,
            methods(TRIPWIRE_CALCULATE_STATE),
            blocks("{pos}", "{pos.north}", "{pos.south}", "{pos.west}", "{pos.east}"), blocks("{pos}"), globals(), globals("region.neighbor_updater"), events("BLOCK_UPDATE"),
            "Tripwire string uses the four horizontal connection neighbours.");
        put(templates, RedstoneComponentType.REDSTONE_LAMP,
            methods(REDSTONE_LAMP_NEIGHBOR_CHANGED, REDSTONE_LAMP_TICK),
            blocks("{pos}"), blocks("{pos}"), globals(), globals(), events(),
            "Lamp is modeled as a sink that only mutates its own lit state.");
        put(templates, RedstoneComponentType.DAYLIGHT_DETECTOR,
            methods(DAYLIGHT_DETECTOR_TICK_ENTITY, DAYLIGHT_DETECTOR_USE, DAYLIGHT_DETECTOR_GET_SIGNAL),
            blocks("{pos}"), blocks("{pos}"), globals(), globals(), events("BLOCK_UPDATE"),
            "Sky/day-time input is represented through the detector block state template.");
        put(templates, RedstoneComponentType.HOPPER,
            methods(HOPPER_TICK),
            blocks("{pos}", "{pos.up}", "{pos.down}"), blocks("{pos}"), globals(), globals(), events("INVENTORY_CHANGED"),
            "Redstone-level trigger template; slot-level inventory mutation lives in BlockEntityTaskFactory.");
        put(templates, RedstoneComponentType.DISPENSER,
            methods(DISPENSER_TICK, DISPENSER_NEIGHBOR_CHANGED),
            blocks("{pos}", "{pos.front}"), blocks("{pos}"), globals(), globals(), events("INVENTORY_CHANGED"),
            "Block trigger footprint; slot-level dispenser effects are modeled by BlockEntityTaskFactory.");
        put(templates, RedstoneComponentType.DROPPER,
            methods(DROPPER_DISPENSE, DISPENSER_NEIGHBOR_CHANGED),
            blocks("{pos}", "{pos.front}"), blocks("{pos}"), globals(), globals(), events("INVENTORY_CHANGED"),
            "Shares dispenser block trigger footprint; inventory transfer is slot-level elsewhere.");
        put(templates, RedstoneComponentType.TNT,
            methods(TNT_NEIGHBOR_CHANGED, TNT_CATCH_FIRE),
            blocks("{pos}"), blocks("{pos}"), globals(), globals(), events("BLOCK_UPDATE"),
            "TNT ignition removes/updates its own block; explosion is a separate sub-DAG.");
        put(templates, RedstoneComponentType.ACTIVATOR_RAIL,
            methods(POWERED_RAIL_UPDATE_POWER),
            blocks("{pos}", "{pos.north}", "{pos.south}", "{pos.west}", "{pos.east}", "{pos.down}"),
            blocks("{pos}"),
            globals(),
            globals("region.neighbor_updater"),
            events("BLOCK_UPDATE"),
            "Activator rail shares the conservative powered-rail footprint.");
        put(templates, RedstoneComponentType.REDSTONE_BLOCK,
            methods("RedstoneBlock.getSignal"),
            blocks("{pos}"), blocks(), globals(), globals(), events(),
            "Constant power source; no block mutation.");
        put(templates, RedstoneComponentType.LEVER,
            methods(LEVER_USE, LEVER_NEIGHBOR_CHANGED, LEVER_GET_SIGNAL, LEVER_GET_DIRECT_SIGNAL),
            blocks("{pos}"), blocks("{pos}"), globals(), globals("region.neighbor_updater"), events("BLOCK_UPDATE"),
            "Manual toggle writes self and queues neighbour updates.");
        put(templates, RedstoneComponentType.BUTTON,
            methods(BUTTON_USE, BUTTON_TICK, BUTTON_GET_SIGNAL, BUTTON_GET_DIRECT_SIGNAL),
            blocks("{pos}"), blocks("{pos}"), globals(), globals("region.neighbor_updater"), events("BLOCK_UPDATE"),
            "Press/release writes self; scheduled release is approximated by the component metadata.");
        put(templates, RedstoneComponentType.PRESSURE_PLATE,
            methods(PRESSURE_PLATE_ENTITY_INSIDE, PRESSURE_PLATE_TICK, PRESSURE_PLATE_GET_SIGNAL),
            blocks("{pos}", "{pos.down}"), blocks("{pos}"), globals(), globals("region.block_level_ticks", "region.neighbor_updater"), events("BLOCK_UPDATE"),
            "Entity collision is approximated by the block below/floor position in RWSet.");
        put(templates, RedstoneComponentType.FENCE_GATE,
            methods("FenceGateBlock.neighborChanged"),
            blocks("{pos}"), blocks("{pos}"), globals(), globals(), events(),
            "Sink-like open/close template writes only self.");
        put(templates, RedstoneComponentType.TRAPDOOR,
            methods("TrapDoorBlock.neighborChanged"),
            blocks("{pos}"), blocks("{pos}"), globals(), globals(), events(),
            "Sink-like open/close template writes only self.");
        put(templates, RedstoneComponentType.IRON_DOOR,
            methods("DoorBlock.neighborChanged"),
            blocks("{pos}"), blocks("{pos}"), globals(), globals(), events(),
            "Sink-like open/close template writes only self.");
        put(templates, RedstoneComponentType.PISTON_HEAD,
            methods("PistonHeadBlock.onRemove"),
            blocks("{pos}", "{pos.attached}"), blocks("{pos}"), globals(), globals(), events("BLOCK_UPDATE"),
            "Piston head reads the body behind and writes/removes itself.");
        return Map.copyOf(templates);
    }

    private static void put(EnumMap<RedstoneComponentType, ComponentTemplate> templates, RedstoneComponentType type,
        List<String> methods, List<String> reads, List<String> writes, List<String> readGlobals,
        List<String> writeGlobals, List<String> events, String notes) {
        templates.put(type, template(type, methods, reads, writes, readGlobals, writeGlobals, events, notes));
    }

    private static List<String> methods(String... values) {
        return Arrays.asList(values);
    }

    private static List<String> blocks(String... values) {
        return Arrays.asList(values);
    }

    private static List<String> globals(String... values) {
        return Arrays.asList(values);
    }

    private static List<String> events(String... values) {
        return Arrays.asList(values);
    }

    // =========================================================================
    // REDSTONE WIRE (RedStoneWireBlock / BlockRedstoneWire)
    // =========================================================================

    /**
     * <pre>
     * Target:  RedStoneWireBlock.neighborChanged(BlockState, Level, BlockPos, Block, BlockPos, boolean)
     *          RedStoneWireBlock.updateShape(...)
     *
     * Read:    {pos.north}, {pos.south}, {pos.west}, {pos.east}, {pos.down}, {pos.up}  — 6 neighbours
     *          {pos}  — own state (power level, connection sides)
     * Write:   {pos}  — updated power level
     *          region.neighbor_updater  — new FullNeighborUpdate records pushed for neighbours
     * Global:  READ  region.should_signal
     *          WRITE region.should_signal (temporarily set false during getBestNeighborSignal)
     *          READ  region.wire_turbo
     * Events:  BLOCK_UPDATE → all 6 neighbours if power changes
     * Micro:   PROPAGATES (triggers downstream wire/components in same tick)
     * SCC:     CONTRACTIBLE
     * </pre>
     */
    public static final String WIRE_NEIGHBOR_CHANGED = "RedStoneWireBlock.neighborChanged";

    /**
     * <pre>
     * Target:  RedStoneWireBlock.getSignal(BlockState, BlockGetter, BlockPos, Direction)
     *
     * Read:    {pos}  — own power level (BlockStateInteger f = POWER)
     *          region.should_signal  — returns 0 if false (recursion guard)
     * Write:   (none)
     * Notes:   Called by adjacent blocks when they query this wire's output signal.
     *          Pure read when shouldSignal=true; returns 0 otherwise.
     * </pre>
     */
    public static final String WIRE_GET_SIGNAL = "RedStoneWireBlock.getSignal";

    /**
     * <pre>
     * Target:  RedStoneWireBlock.getDirectSignal(BlockState, BlockGetter, BlockPos, Direction)
     *
     * Read:    {pos}  — own power level
     *          region.should_signal  — returns 0 if false
     * Write:   (none)
     * Notes:   Only returns signal when direction != DOWN and shouldSignal=true.
     * </pre>
     */
    public static final String WIRE_GET_DIRECT_SIGNAL = "RedStoneWireBlock.getDirectSignal";

    /**
     * <pre>
     * Target:  RedStoneWireBlock.getBlockSignal(Level, BlockPos)
     *          (called inside updateSurroundingRedstone to compute incoming power)
     *
     * Read:    {pos.north}, {pos.south}, {pos.west}, {pos.east}, {pos.down}, {pos.up}
     *          — all 6 neighbours queried via Level.getBestNeighborSignal
     * Write:   region.should_signal = false (enter), = true (exit)
     * Global:  READ+WRITE region.should_signal
     * Notes:   Sets shouldSignal=false to prevent this wire from counting itself,
     *          then calls getBestNeighborSignal, then restores shouldSignal=true.
     * </pre>
     */
    public static final String WIRE_GET_BLOCK_SIGNAL = "RedStoneWireBlock.getBlockSignal";

    // =========================================================================
    // REDSTONE WIRE TURBO (io.papermc.paper.redstone.RedstoneWireTurbo)
    // =========================================================================

    /**
     * <pre>
     * Target:  RedstoneWireTurbo.updateSurroundingRedstone(Level, BlockPos, BlockState, BlockPos)
     *
     * Read:    All wire positions in the connected wire network (BFS from {pos})
     *          For each wire in BFS: 6 neighbours (signal strength queries)
     * Write:   All wire positions in the connected network (updated power levels)
     *          region.neighbor_updater — neighbor updates pushed for each changed wire
     * Global:  READ+WRITE region.should_signal (set false during signal computation)
     *          READ  region.wire_turbo
     * Events:  BLOCK_UPDATE for each changed wire position → its 6 neighbours
     * Micro:   PROPAGATES (updates entire wire network in a single pass)
     * Notes:   Eigencraft's O(changed) algorithm — only re-evaluates wires whose
     *          power actually changed, not all reachable wires.
     * </pre>
     */
    public static final String WIRE_TURBO_UPDATE = "RedstoneWireTurbo.updateSurroundingRedstone";

    /**
     * <pre>
     * Target:  RedstoneWireTurbo.updateNeighborShapes(Level, BlockPos, BlockState)
     *
     * Read:    {pos} and all connected wire positions
     * Write:   region.neighbor_updater (ShapeUpdate records)
     * Notes:   Called after updateSurroundingRedstone to fix connection side states.
     * </pre>
     */
    public static final String WIRE_TURBO_SHAPE = "RedstoneWireTurbo.updateNeighborShapes";

    // =========================================================================
    // REDSTONE TORCH (RedstoneTorchBlock / BlockRedstoneTorch)
    // =========================================================================

    /**
     * <pre>
     * Target:  RedstoneTorchBlock.tick(BlockState, ServerLevel, BlockPos, RandomSource)
     *
     * Read:    {pos.attached} = {pos.down}  — attached block's redstone power
     *          {pos}  — torch's own on/off state + internal burnout counter
     *          region.redstone_torch_toggles  — deque of recent toggles (burnout check)
     *          region.redstone_game_time  — current region tick time
     * Write:   {pos}  — torch state (lit/unlit)
     *          region.redstone_torch_toggles  — add new Toggle record if state changes
     *          region.neighbor_updater  — BLOCK_UPDATE to neighbours
     * Events:  BLOCK_UPDATE → {pos.north}, {pos.south}, {pos.west}, {pos.east}, {pos.up}
     * Micro:   PROPAGATES (output change triggers adjacent components)
     * SCC:     CONTRACTIBLE
     * Random:  NONE
     * Notes:   Burnout: if > 8 toggles in 60 redstone-game-ticks, torch stays off.
     *          Scheduled as a delayed tick (1 tick), not immediate propagation.
     * </pre>
     */
    public static final String TORCH_TICK = "RedstoneTorchBlock.tick";

    /**
     * <pre>
     * Target:  RedstoneTorchBlock.isToggledTooFrequently(Level, BlockPos, long)
     *
     * Read:    region.redstone_torch_toggles  — reads deque to count recent toggles
     *          region.redstone_game_time  — to prune old entries
     * Write:   region.redstone_torch_toggles  — adds new Toggle record
     * Notes:   Pure burnout-detection helper called inside tick().
     * </pre>
     */
    public static final String TORCH_BURNOUT_CHECK = "RedstoneTorchBlock.isToggledTooFrequently";

    /**
     * <pre>
     * Target:  RedstoneTorchBlock.neighborChanged(BlockState, Level, BlockPos, Block, BlockPos, boolean)
     *
     * Read:    {pos.attached} = {pos.down}  — is the supporting block now powered?
     *          {pos}  — current torch state
     * Write:   region.block_level_ticks  — schedules a tick 1 game-tick later
     * Notes:   Torch doesn't update immediately; it schedules a 1-tick delay.
     *          Actual state change happens in tick().
     * </pre>
     */
    public static final String TORCH_NEIGHBOR_CHANGED = "RedstoneTorchBlock.neighborChanged";

    // =========================================================================
    // REPEATER (RepeaterBlock / BlockRepeater)
    // =========================================================================

    /**
     * <pre>
     * Target:  RepeaterBlock.tick(BlockState, ServerLevel, BlockPos, RandomSource)
     *
     * Read:    {pos.input} = {pos.north}  — input signal strength
     *          {pos}  — own state (powered, locked, delay)
     * Write:   {pos}  — update powered state
     *          {pos.output} = {pos.south}  — output signal via BLOCK_UPDATE
     *          region.neighbor_updater  — BLOCK_UPDATE records
     *          region.block_level_ticks  — may schedule a future tick if signal will change
     * Events:  BLOCK_UPDATE → {pos.output} and its neighbours
     * Micro:   DEFERRED (output change scheduled delay 1-4 ticks, not immediate)
     * SCC:     CONTRACTIBLE (repeater rings form SCCs, contractible after delay)
     * Random:  NONE
     * Notes:   The repeater's tick is already scheduled by block_level_ticks
     *          (1-4 game tick delay set by player). The DEFERRED microStep means
     *          its output changes never trigger same-tick propagation.
     * </pre>
     */
    public static final String REPEATER_TICK = "RepeaterBlock.tick";

    /**
     * <pre>
     * Target:  RepeaterBlock.neighborChanged(BlockState, Level, BlockPos, Block, BlockPos, boolean)
     *
     * Read:    {pos.input}  — new input signal
     *          {pos}  — current powered/locked state
     * Write:   region.block_level_ticks  — schedules tick at (now + delay)
     * Notes:   Queues a scheduled tick; does not change state immediately.
     * </pre>
     */
    public static final String REPEATER_NEIGHBOR_CHANGED = "RepeaterBlock.neighborChanged";

    /**
     * <pre>
     * Target:  RepeaterBlock.getSignal(BlockState, BlockGetter, BlockPos, Direction)
     *
     * Read:    {pos}  — powered state
     * Write:   (none)
     * Notes:   Returns 15 if powered and direction == output side, else 0.
     * </pre>
     */
    public static final String REPEATER_GET_SIGNAL = "RepeaterBlock.getSignal";

    // =========================================================================
    // COMPARATOR (ComparatorBlock / BlockRedstoneComparator + TileEntityComparator)
    // =========================================================================

    /**
     * <pre>
     * Target:  ComparatorBlock.tick(BlockState, ServerLevel, BlockPos, RandomSource)
     *
     * Read:    {pos.input}   = {pos.north}  — front input signal
     *          {pos.side1}   = {pos.east}   — right side signal (compare/subtract)
     *          {pos.side2}   = {pos.west}   — left side signal
     *          {pos}  — mode (compare/subtract), powered state, output strength
     *          {pos.input_be} — block entity directly behind (e.g. chest comparator output)
     * Write:   {pos}  — updated output strength
     *          {pos.output}  = {pos.south}  — BLOCK_UPDATE
     *          region.neighbor_updater
     *          region.block_level_ticks  — if state changed, schedule future tick
     * Events:  BLOCK_UPDATE → {pos.output}
     * Micro:   PROPAGATES (output change propagates immediately if != DEFERRED)
     * SCC:     CONTRACTIBLE
     * Random:  NONE
     * Notes:   Compare mode: output = max(0, front - max(side1, side2)).
     *          Subtract mode: output = max(0, front - max(side1, side2)).
     *          Block-entity behind: can read container fill level via
     *          AbstractContainerMenu.getRedstoneSignalFromContainer().
     * </pre>
     */
    public static final String COMPARATOR_TICK = "ComparatorBlock.tick";

    /**
     * <pre>
     * Target:  ComparatorBlock.neighborChanged(BlockState, Level, BlockPos, Block, BlockPos, boolean)
     *
     * Read:    {pos.input}, {pos.side1}, {pos.side2}, {pos}
     * Write:   region.block_level_ticks  — schedule 1-tick delay
     * Notes:   Same pattern as repeater — defers via scheduled tick.
     * </pre>
     */
    public static final String COMPARATOR_NEIGHBOR_CHANGED = "ComparatorBlock.neighborChanged";

    // =========================================================================
    // OBSERVER (ObserverBlock)
    // =========================================================================

    /**
     * <pre>
     * Target:  ObserverBlock.tick(BlockState, ServerLevel, BlockPos, RandomSource)
     *
     * Read:    {pos.attached} = block behind observer (facing direction)
     *          {pos}  — powered state, facing
     * Write:   {pos}  — toggle powered state
     *          {pos.output}  = block in front — BLOCK_UPDATE
     *          region.neighbor_updater
     * Events:  BLOCK_UPDATE → {pos.output} and its 6 neighbours
     * Micro:   PROPAGATES (emits 1-tick pulse, immediately propagates)
     * SCC:     CONTRACTIBLE
     * Random:  NONE
     * Notes:   Observer emits a 1-tick pulse when the block it faces changes state.
     *          First tick: powers on. Second tick (scheduled immediately): powers off.
     * </pre>
     */
    public static final String OBSERVER_TICK = "ObserverBlock.tick";

    // =========================================================================
    // PISTON (PistonBaseBlock)
    // =========================================================================

    /**
     * <pre>
     * Target:  PistonBaseBlock.neighborChanged(BlockState, Level, BlockPos, Block, BlockPos, boolean)
     *
     * Read:    {pos}  — current state (extended/retracted, facing)
     *          {pos.power_source}  — whichever of the 6 faces could be powering it
     *          {pos.facing}  — block immediately in front (to push)
     *          entity collisions at {pos.facing} chain
     * Write:   region.block_level_ticks  — schedule movement tick
     * Notes:   Does not move immediately; schedules a 1-tick delay.
     * </pre>
     */
    public static final String PISTON_NEIGHBOR_CHANGED = "PistonBaseBlock.neighborChanged";

    /**
     * <pre>
     * Target:  PistonBaseBlock.tick(BlockState, ServerLevel, BlockPos, RandomSource)
     *
     * Read:    {pos}  — piston state
     *          {pos.facing} through {pos.facing + 12 blocks}  — push/pull chain
     *          entity positions in push column
     * Write:   {pos}  — new extended/retracted state
     *          {pos.facing} chain  — moved block positions
     *          entity positions  — pushed entities
     *          region.neighbor_updater  — BLOCK_UPDATE for affected positions
     * Events:  BLOCK_UPDATE → {pos} and entire push column
     * Micro:   DEFERRED
     * SCC:     SERIALIZED
     * Random:  NONE
     * </pre>
     */
    public static final String PISTON_TICK = "PistonBaseBlock.tick";

    // =========================================================================
    // NEIGHBOR UPDATER (CollectingNeighborUpdater)
    // =========================================================================

    /**
     * <pre>
     * Target:  CollectingNeighborUpdater.neighbor*Update methods
     *
     * Folia change: ALL update records check isTickThreadFor(level, pos, 25) before executing.
     * If the target position is NOT owned by the current region thread, the update is DROPPED.
     *
     * Read:    neighbour block state at target position
     * Write:   region.neighbor_updater  — pops from queue
     *          target block state  — via NeighborUpdater.executeUpdate
     * Notes:   This is the fan-out point for all block updates. In Nebula:
     *          - Each BLOCK_UPDATE event generated by a task becomes a
     *            new TaskNode in the DAG via MicroStepExtender.extend()
     *          - The ownership check maps to: only process if the target
     *            position is in the current BucketDagBuilder's scope
     * </pre>
     */
    public static final String COLLECTING_NEIGHBOR_UPDATER = "CollectingNeighborUpdater.runNext";

    // =========================================================================
    // TRIPWIRE (TripWireBlock)
    // =========================================================================

    /**
     * <pre>
     * Target:  TripWireBlock.calculateState(Level, BlockPos, BlockState)
     *
     * Folia change: Checks isTickThreadFor(level, testPos, 4) before scanning string.
     *
     * Read:    {pos.north}, {pos.south}, {pos.east}, {pos.west}  — string attachment
     *          connected hook positions (up to 40 blocks in each direction)
     * Write:   {pos}  — updated attached/powered state
     *          region.neighbor_updater
     * Events:  BLOCK_UPDATE
     * Micro:   PROPAGATES
     * SCC:     CONTRACTIBLE
     * </pre>
     */
    public static final String TRIPWIRE_CALCULATE_STATE = "TripWireBlock.calculateState";

    /**
     * <pre>
     * Target:  TripWireHookBlock.calculateState(Level, BlockPos, BlockState)
     *
     * Read:    {pos}  — own attached/powered state
     *          string positions extending in facing direction (up to 40 blocks)
     * Write:   {pos}  — updated attached/powered/hanging state
     *          region.neighbor_updater
     * Global:  WRITE region.neighbor_updater
     * Events:  BLOCK_UPDATE → {pos.attached}, {pos} and neighbours
     * Micro:   PROPAGATES
     * SCC:     CONTRACTIBLE
     * Random:  NONE
     * </pre>
     */
    public static final String TRIPWIRE_HOOK_CALCULATE_STATE = "TripWireHookBlock.calculateState";

    /**
     * <pre>
     * Target:  TripWireHookBlock.neighborChanged(BlockState, Level, BlockPos, Block, BlockPos, boolean)
     *
     * Read:    {pos}  — current attached/powered state
     *          {pos.attached}  — supporting wall block
     * Write:   region.block_level_ticks  — schedules detach check when wall is broken
     * Global:  WRITE region.block_level_ticks
     * Notes:   Defers state change to scheduled tick; does not propagate immediately.
     * </pre>
     */
    public static final String TRIPWIRE_HOOK_NEIGHBOR_CHANGED = "TripWireHookBlock.neighborChanged";

    // =========================================================================
    // LEVER (LeverBlock)
    // =========================================================================

    /**
     * <pre>
     * Target:  LeverBlock.useWithoutItem(BlockState, Level, BlockPos, Player, BlockHitResult)
     *
     * Read:    {pos}  — current powered state
     * Write:   {pos}  — toggled powered state
     *          region.neighbor_updater  — BLOCK_UPDATE to 6 neighbours + supporting block
     * Global:  WRITE region.neighbor_updater
     * Events:  BLOCK_UPDATE → {pos.attached} and {pos}'s 6 neighbours
     * Micro:   PROPAGATES (immediate signal change)
     * SCC:     CONTRACTIBLE
     * Random:  NONE
     * Notes:   Player-driven, not tick-driven. Fires when player interacts.
     * </pre>
     */
    public static final String LEVER_USE = "LeverBlock.useWithoutItem";

    /**
     * <pre>
     * Target:  LeverBlock.onRemove(BlockState, Level, BlockPos, BlockState, boolean)
     *          (Folia: neighborChanged is unused for lever — onRemove drives updates)
     *
     * Read:    {pos}  — previous powered state
     * Write:   region.neighbor_updater  — BLOCK_UPDATE when lever destroyed
     * Global:  WRITE region.neighbor_updater
     * Events:  BLOCK_UPDATE on destroy if was powered
     * Notes:   No tick scheduling; lever has no delayed behavior.
     * </pre>
     */
    public static final String LEVER_NEIGHBOR_CHANGED = "LeverBlock.onRemove";

    /**
     * <pre>
     * Target:  LeverBlock.getSignal(BlockState, BlockGetter, BlockPos, Direction)
     *
     * Read:    {pos}  — powered state
     * Write:   (none)
     * Notes:   Returns 15 if powered, 0 if not. All 6 directions.
     * </pre>
     */
    public static final String LEVER_GET_SIGNAL = "LeverBlock.getSignal";

    /**
     * <pre>
     * Target:  LeverBlock.getDirectSignal(BlockState, BlockGetter, BlockPos, Direction)
     *
     * Read:    {pos}  — powered state + facing direction
     * Write:   (none)
     * Notes:   Returns 15 only when direction matches the lever's attachment face;
     *          0 otherwise. Used by adjacent blocks for "strong" power.
     * </pre>
     */
    public static final String LEVER_GET_DIRECT_SIGNAL = "LeverBlock.getDirectSignal";

    // =========================================================================
    // BUTTON (ButtonBlock — stone, wooden, polished, etc.)
    // =========================================================================

    /**
     * <pre>
     * Target:  ButtonBlock.useWithoutItem(BlockState, Level, BlockPos, Player, BlockHitResult)
     *
     * Read:    {pos}  — current pressed state
     * Write:   {pos}  — pressed=true
     *          region.block_level_ticks  — schedule release (20 or 30 ticks)
     *          region.neighbor_updater   — BLOCK_UPDATE
     * Global:  WRITE region.block_level_ticks, region.neighbor_updater
     * Events:  BLOCK_UPDATE → 6 neighbours + attached block
     * Micro:   PROPAGATES (immediate press signal); release is DEFERRED via scheduled tick
     * SCC:     CONTRACTIBLE
     * Random:  NONE
     * Notes:   Stone button: 20 tick (1s) duration. Wooden button: 30 tick.
     * </pre>
     */
    public static final String BUTTON_USE = "ButtonBlock.useWithoutItem";

    /**
     * <pre>
     * Target:  ButtonBlock.tick(BlockState, ServerLevel, BlockPos, RandomSource)
     *
     * Read:    {pos}  — pressed state
     *          arrow entities in {pos} bounding box (wooden buttons)
     * Write:   {pos}  — pressed=false (release)
     *          region.neighbor_updater  — BLOCK_UPDATE on release
     * Global:  WRITE region.neighbor_updater
     * Events:  BLOCK_UPDATE → 6 neighbours + attached block
     * Micro:   PROPAGATES (release propagates immediately)
     * SCC:     CONTRACTIBLE
     * Random:  NONE
     * Notes:   Wooden buttons check for arrow entities and keep pressed if any
     *          intersect, re-scheduling the tick. Stone buttons release unconditionally.
     * </pre>
     */
    public static final String BUTTON_TICK = "ButtonBlock.tick";

    /**
     * <pre>
     * Target:  ButtonBlock.getSignal(BlockState, BlockGetter, BlockPos, Direction)
     *
     * Read:    {pos}  — pressed state
     * Write:   (none)
     * Notes:   Returns 15 if pressed, 0 otherwise.
     * </pre>
     */
    public static final String BUTTON_GET_SIGNAL = "ButtonBlock.getSignal";

    /**
     * <pre>
     * Target:  ButtonBlock.getDirectSignal(BlockState, BlockGetter, BlockPos, Direction)
     *
     * Read:    {pos}  — pressed state + facing
     * Write:   (none)
     * Notes:   Strong signal only on the attachment face direction.
     * </pre>
     */
    public static final String BUTTON_GET_DIRECT_SIGNAL = "ButtonBlock.getDirectSignal";

    // =========================================================================
    // PRESSURE PLATE (BasePressurePlateBlock / PressurePlateBlock)
    // =========================================================================

    /**
     * <pre>
     * Target:  BasePressurePlateBlock.entityInside(BlockState, Level, BlockPos, Entity)
     *
     * Read:    {pos}  — current power
     *          entity at {pos}  — entity type (some plates only react to specific entities)
     * Write:   {pos}  — new power if pressed
     *          region.block_level_ticks  — schedule release check
     *          region.neighbor_updater   — BLOCK_UPDATE on power change
     * Global:  WRITE region.block_level_ticks, region.neighbor_updater
     * Events:  BLOCK_UPDATE → {pos.attached}, {pos}'s neighbours
     * Micro:   PROPAGATES (press) / DEFERRED (release via tick)
     * SCC:     CONTRACTIBLE
     * Random:  NONE
     * Notes:   Different plate types: wooden = any entity, stone = mob only,
     *          weighted = item count proportional power.
     * </pre>
     */
    public static final String PRESSURE_PLATE_ENTITY_INSIDE = "BasePressurePlateBlock.entityInside";

    /**
     * <pre>
     * Target:  BasePressurePlateBlock.tick(BlockState, ServerLevel, BlockPos, RandomSource)
     *
     * Read:    {pos}  — current signal strength
     *          entities in {pos}+1 block AABB  — to recompute power
     * Write:   {pos}  — new signal strength
     *          region.block_level_ticks  — re-schedule if entities still present
     *          region.neighbor_updater   — BLOCK_UPDATE on change
     * Global:  WRITE region.block_level_ticks, region.neighbor_updater
     * Events:  BLOCK_UPDATE on power change
     * Micro:   DEFERRED
     * SCC:     CONTRACTIBLE
     * Random:  NONE
     * </pre>
     */
    public static final String PRESSURE_PLATE_TICK = "BasePressurePlateBlock.tick";

    /**
     * <pre>
     * Target:  BasePressurePlateBlock.getSignal(BlockState, BlockGetter, BlockPos, Direction)
     *
     * Read:    {pos}  — signal strength state
     * Write:   (none)
     * Notes:   Returns stored signal strength (0..15) for all directions.
     * </pre>
     */
    public static final String PRESSURE_PLATE_GET_SIGNAL = "BasePressurePlateBlock.getSignal";

    /**
     * <pre>
     * Target:  WeightedPressurePlateBlock.getSignalForState(BlockState)
     *
     * Read:    {pos}  — POWER property
     * Write:   (none)
     * Notes:   Pure derivation from BlockState; no world I/O. Used by signal queries.
     * </pre>
     */
    public static final String WEIGHTED_PRESSURE_PLATE_SIGNAL_FOR_STATE =
        "WeightedPressurePlateBlock.getSignalForState";

    // =========================================================================
    // DAYLIGHT DETECTOR (DaylightDetectorBlock + BlockEntity)
    // =========================================================================

    /**
     * <pre>
     * Target:  DaylightDetectorBlock.tickEntity(Level, BlockPos, BlockState, DaylightDetectorBlockEntity)
     *
     * Read:    {pos}  — current power + inverted mode
     *          sky light level at {pos}  — derived from level + day time
     *          region.day_time  — affects sky-light calculation
     * Write:   {pos}  — new POWER (0..15)
     *          region.neighbor_updater  — BLOCK_UPDATE on change
     *          region.block_level_ticks — re-schedule next sample (20 tick interval)
     * Global:  READ  day_time
     *          WRITE region.neighbor_updater, region.block_level_ticks
     * Events:  BLOCK_UPDATE → 6 neighbours
     * Micro:   DEFERRED
     * SCC:     CONTRACTIBLE
     * Random:  NONE
     * Notes:   Re-evaluates power every 20 ticks (1 second). Inverted mode flips
     *          (sky-light → night-light).
     * </pre>
     */
    public static final String DAYLIGHT_DETECTOR_TICK_ENTITY = "DaylightDetectorBlock.tickEntity";

    /**
     * <pre>
     * Target:  DaylightDetectorBlock.useWithoutItem(BlockState, Level, BlockPos, Player, BlockHitResult)
     *
     * Read:    {pos}  — current inverted property
     * Write:   {pos}  — toggled inverted
     *          region.neighbor_updater  — BLOCK_UPDATE
     * Global:  WRITE region.neighbor_updater
     * Events:  BLOCK_UPDATE → 6 neighbours
     * Notes:   Player toggle action; not tick-driven.
     * </pre>
     */
    public static final String DAYLIGHT_DETECTOR_USE = "DaylightDetectorBlock.useWithoutItem";

    /**
     * <pre>
     * Target:  DaylightDetectorBlock.getSignal(BlockState, BlockGetter, BlockPos, Direction)
     *
     * Read:    {pos}  — POWER property
     * Write:   (none)
     * Notes:   Returns stored power level for all 6 directions.
     * </pre>
     */
    public static final String DAYLIGHT_DETECTOR_GET_SIGNAL = "DaylightDetectorBlock.getSignal";

    // =========================================================================
    // REDSTONE LAMP (RedstoneLampBlock)
    // =========================================================================

    /**
     * <pre>
     * Target:  RedstoneLampBlock.neighborChanged(BlockState, Level, BlockPos, Block, BlockPos, boolean)
     *
     * Read:    {pos}  — current LIT state
     *          {pos.6 neighbours}  — incoming redstone signal (Level.hasNeighborSignal)
     * Write:   {pos}  — LIT toggled
     *          region.block_level_ticks  — schedule turn-off (4 tick delay) when unpowered
     * Global:  WRITE region.block_level_ticks
     * Notes:   Turns on immediately when powered. Turn-off is delayed by 4 ticks
     *          via scheduled tick (vanilla feature for momentary pulse smoothing).
     * </pre>
     */
    public static final String REDSTONE_LAMP_NEIGHBOR_CHANGED = "RedstoneLampBlock.neighborChanged";

    /**
     * <pre>
     * Target:  RedstoneLampBlock.tick(BlockState, ServerLevel, BlockPos, RandomSource)
     *
     * Read:    {pos}  — LIT state
     *          {pos.6 neighbours}  — re-check signal
     * Write:   {pos}  — set LIT=false if no longer powered
     * Notes:   Pure read on neighbours, then conditionally turns off.
     *          Does not emit BLOCK_UPDATE itself (lamps are sinks).
     * </pre>
     */
    public static final String REDSTONE_LAMP_TICK = "RedstoneLampBlock.tick";

    // =========================================================================
    // DISPENSER / DROPPER (DispenserBlock, DropperBlock, DispenserBlockEntity)
    // =========================================================================

    /**
     * <pre>
     * Target:  DispenserBlock.tick(BlockState, ServerLevel, BlockPos, RandomSource)
     *          (called via scheduled tick after activation)
     *
     * Read:    {pos}  — facing + triggered state
     *          {pos.inventory.slots[0..8]}  — block entity inventory
     *          {pos.facing}  — block in front (for ejection / block placement)
     * Write:   {pos}  — toggle triggered
     *          {pos.inventory.slots[*]}  — pop selected item
     *          {pos.facing}  — possibly modified (e.g. water bucket placement)
     *          region.neighbor_updater
     * Global:  WRITE region.neighbor_updater
     * Events:  ENTITY_SPAWN if item dropped
     *          BLOCK_UPDATE → {pos.facing} if state changed
     * Micro:   DEFERRED
     * SCC:     SERIALIZED (inventory mutation is order-sensitive)
     * Random:  WORLD_RANDOM (slot selection from non-empty stacks)
     *          maxRandomCalls = 1
     * </pre>
     */
    public static final String DISPENSER_TICK = "DispenserBlock.tick";

    /**
     * <pre>
     * Target:  DispenserBlock.neighborChanged(BlockState, Level, BlockPos, Block, BlockPos, boolean)
     *
     * Read:    {pos}  — current triggered state
     *          {pos.6 neighbours}  — Level.hasNeighborSignal
     * Write:   region.block_level_ticks  — schedule fire (4 tick delay)
     *          {pos}  — set triggered=true
     * Global:  WRITE region.block_level_ticks
     * Notes:   Schedules dispense action; does not eject immediately.
     * </pre>
     */
    public static final String DISPENSER_NEIGHBOR_CHANGED = "DispenserBlock.neighborChanged";

    /**
     * <pre>
     * Target:  DropperBlock.dispenseFrom(ServerLevel, BlockState, BlockPos)
     *
     * Read:    {pos.inventory.slots[*]}
     *          {pos.facing}  — target block (inventory or open space)
     *          {pos.facing.inventory}  — target container if present
     * Write:   {pos.inventory.slots[*]}  — pop item from random non-empty slot
     *          {pos.facing.inventory.slots[*]}  — push if container, else spawn item entity
     * Global:  (depends on outcome)
     * Events:  ENTITY_SPAWN if dropped to ground
     *          INVENTORY_CHANGED on both source and target containers
     * Random:  WORLD_RANDOM  (slot selection); maxRandomCalls = 1
     * Notes:   Unlike Dispenser, Dropper never invokes special-case behaviors.
     *          Pure item transfer / drop.
     * </pre>
     */
    public static final String DROPPER_DISPENSE = "DropperBlock.dispenseFrom";

    // =========================================================================
    // HOPPER (HopperBlockEntity)
    // =========================================================================

    /**
     * <pre>
     * Target:  HopperBlockEntity.tickHopper(Level, BlockPos, BlockState, HopperBlockEntity)
     *          (called every tick by the block-entity ticker)
     *
     * Read:    {pos.inventory.slots[0..4]}  — own 5 slots
     *          {pos.up.inventory.slots[*]}  — source container above (or ground items in AABB)
     *          {pos.facing.inventory.slots[*]}  — target container in facing direction
     *          {pos}  — enabled flag (redstone-locked)
     *          {pos.6 neighbours}  — Level.hasNeighborSignal (lock check)
     * Write:   {pos.inventory.slots[*]}        — pull/push results
     *          {pos.up.inventory.slots[*]}     — popped from source
     *          {pos.facing.inventory.slots[*]} — pushed to target
     *          {pos}  — cooldown timer + enabled state
     * Global:  (none directly — but block updates feed into region.neighbor_updater
     *           if locked-state actually toggles)
     * Events:  INVENTORY_CHANGED for {pos}, {pos.up}, {pos.facing}
     * Micro:   NONE (no propagation — hopper does not fire BLOCK_UPDATE)
     * SCC:     SERIALIZED (inventory transfers are order-sensitive between adjacent hoppers)
     * Random:  NONE
     * Notes:   8-tick cooldown between item moves (vanilla constant).
     *          Locked when receiving redstone signal — only the lock flag changes.
     *          Hopper chains form SCC clusters that must serialize per arch doc §5.2.
     * </pre>
     */
    public static final String HOPPER_TICK = "HopperBlockEntity.tickHopper";

    // =========================================================================
    // TNT (TntBlock + PrimedTnt entity)
    // =========================================================================

    /**
     * <pre>
     * Target:  TntBlock.neighborChanged(BlockState, Level, BlockPos, Block, BlockPos, boolean)
     *
     * Read:    {pos}  — current state
     *          {pos.6 neighbours}  — Level.hasNeighborSignal
     * Write:   {pos}  — block removed if ignition triggered
     *          entity layer  — PrimedTnt spawned at {pos}
     * Global:  WRITE region.neighbor_updater (when block removed)
     * Events:  ENTITY_SPAWN (PrimedTnt)
     *          BLOCK_UPDATE on removal
     * Micro:   PROPAGATES (immediate ignite + spawn)
     * Random:  WORLD_RANDOM (PrimedTnt fuse jitter ±0.4s); maxRandomCalls = 1
     * </pre>
     */
    public static final String TNT_NEIGHBOR_CHANGED = "TntBlock.neighborChanged";

    /**
     * <pre>
     * Target:  TntBlock.catchFire(BlockState, Level, BlockPos, Direction, LivingEntity)
     *
     * Read:    {pos}  — state
     * Write:   {pos}  — removed
     *          entity layer  — PrimedTnt spawned with custom fuse
     * Global:  WRITE region.neighbor_updater
     * Events:  ENTITY_SPAWN
     * Notes:   Triggered by fire/flame_arrow/lava. Not signal-based.
     * </pre>
     */
    public static final String TNT_CATCH_FIRE = "TntBlock.catchFire";

    // =========================================================================
    // POWERED RAIL / DETECTOR RAIL (PoweredRailBlock, DetectorRailBlock)
    // =========================================================================

    /**
     * <pre>
     * Target:  PoweredRailBlock.updatePowerState(Level, BlockPos, BlockState)
     *          (internal helper called by neighborChanged + onPlace)
     *
     * Read:    {pos}  — current POWERED + SHAPE
     *          {pos.6 neighbours}  — Level.hasNeighborSignal (manual power check)
     *          connected rail positions (BFS up to 8 in each direction)
     * Write:   {pos}  — POWERED toggled
     *          connected rails  — POWERED propagation along the chain
     *          region.neighbor_updater
     * Global:  WRITE region.neighbor_updater
     * Events:  BLOCK_UPDATE → updated rail's neighbours
     * Micro:   PROPAGATES (powered state ripples along chain)
     * SCC:     CONTRACTIBLE (rail chains form bounded SCCs)
     * Random:  NONE
     * </pre>
     */
    public static final String POWERED_RAIL_UPDATE_POWER = "PoweredRailBlock.updatePowerState";

    /**
     * <pre>
     * Target:  PoweredRailBlock.findPoweredRailSignal(Level, BlockPos, BlockState, boolean, int)
     *
     * Read:    {pos}  — current shape + powered
     *          BFS along rail shape (up to 8 blocks)
     *          {pos.6 neighbours} at each step  — find power source
     * Write:   (none)
     * Notes:   Pure read; computes whether any connected rail in the chain is
     *          directly powered. Recursive (bounded by hardcoded 8-rail depth).
     * </pre>
     */
    public static final String POWERED_RAIL_FIND_SIGNAL = "PoweredRailBlock.findPoweredRailSignal";

    /**
     * <pre>
     * Target:  DetectorRailBlock.updateState(BlockState, Level, BlockPos, Block)
     *
     * Read:    {pos}  — current POWERED state
     *          entities in {pos} AABB (specifically AbstractMinecart instances)
     * Write:   {pos}  — POWERED toggled
     *          region.block_level_ticks  — schedule release check (20 ticks)
     *          region.neighbor_updater
     * Global:  WRITE region.block_level_ticks, region.neighbor_updater
     * Events:  BLOCK_UPDATE → {pos.attached}, neighbours
     * Micro:   PROPAGATES (press) / DEFERRED (release via tick)
     * SCC:     CONTRACTIBLE
     * Random:  NONE
     * </pre>
     */
    public static final String DETECTOR_RAIL_UPDATE_STATE = "DetectorRailBlock.updateState";

    // =========================================================================
    // SCULK SENSOR (SculkSensorBlock)
    // =========================================================================

    /**
     * <pre>
     * Target:  SculkSensorBlock.tick(BlockState, ServerLevel, BlockPos, RandomSource)
     *
     * Read:    {pos}  — phase (INACTIVE / ACTIVE / COOLDOWN) + power
     * Write:   {pos}  — phase transition + power decay
     *          region.block_level_ticks  — schedule next phase transition
     *          region.neighbor_updater
     * Global:  WRITE region.block_level_ticks, region.neighbor_updater
     * Events:  BLOCK_UPDATE on phase or power change
     *          VIBRATION events listened-to (read by game-event subsystem, not here)
     * Micro:   DEFERRED
     * SCC:     CONTRACTIBLE
     * Random:  WORLD_RANDOM (particle position jitter); maxRandomCalls = 4
     * Notes:   ACTIVE phase lasts 40 ticks, COOLDOWN 20 ticks (vanilla constants).
     *          Vibration listener is a separate game-event subsystem hook.
     * </pre>
     */
    public static final String SCULK_SENSOR_TICK = "SculkSensorBlock.tick";

    // =========================================================================
    // OBSERVER (additional — updateShape)
    // =========================================================================

    /**
     * <pre>
     * Target:  ObserverBlock.updateShape(BlockState, LevelReader, ScheduledTickAccess,
     *                                     BlockPos, Direction, BlockPos, BlockState, RandomSource)
     *
     * Read:    {pos}  — current powered + facing
     *          {pos.facing}  — observed block state
     * Write:   region.block_level_ticks  — schedule activation if facing block changed
     * Global:  WRITE region.block_level_ticks
     * Notes:   This is the actual hook that detects observed-block changes.
     *          Activation is scheduled (2-tick pulse); no immediate write here.
     * </pre>
     */
    public static final String OBSERVER_UPDATE_SHAPE = "ObserverBlock.updateShape";

    // =========================================================================
    // NOTE BLOCK (NoteBlock)
    // =========================================================================

    /**
     * <pre>
     * Target:  NoteBlock.triggerEvent(BlockState, Level, BlockPos, int, int)
     *
     * Read:    {pos}  — instrument + note + powered
     *          {pos.down}  — block below (determines instrument)
     * Write:   (none — note blocks play sound via LevelEvent, no state mutation in trigger)
     *          particles dispatched via Level.levelEvent
     * Global:  (none — pure event)
     * Events:  LEVEL_EVENT (NOTE_PLAY)
     * Micro:   NONE (sound-only side effect)
     * SCC:     CONTRACTIBLE
     * Random:  NONE
     * Notes:   Note block state mutation (note pitch increment on right-click) is
     *          handled by useWithoutItem, not triggerEvent. triggerEvent is the
     *          play-sound hook called when powered.
     * </pre>
     */
    public static final String NOTE_BLOCK_TRIGGER_EVENT = "NoteBlock.triggerEvent";
}
