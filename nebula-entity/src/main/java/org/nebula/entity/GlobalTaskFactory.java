package org.nebula.entity;

import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskAction;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.GlobalKey;
import org.nebula.core.state.WorldPos;

/**
 * Creates {@link TaskNode}s for global tasks (arch doc §4.5, P1.10.2).
 *
 * <p>Global tasks touch world-wide state and must serialize globally.
 * Examples: command block execution, /reload resets, world border changes.
 *
 * <h3>RW-set templates</h3>
 * <ul>
 *   <li><b>COMMAND_BLOCK:</b> reads entity state, writes block/entity state, fires events</li>
 *   <li><b>WORLD_BORDER:</b> reads+writes global world border state</li>
 *   <li><b>RELOAD_RESET:</b> reads+writes global plugin state</li>
 * </ul>
 */
public final class GlobalTaskFactory {

    private GlobalTaskFactory() {}

    /**
     * Command block task: reads command inputs, writes output state, fires events.
     * SERIALIZED because command blocks run in global sequence.
     */
    public static TaskNode commandBlock(WorldPos pos, TaskAction action) {
        String taskId = "COMMAND_BLOCK@" + pos.dimensionId()
            + ":" + pos.x() + "," + pos.y() + "," + pos.z();
        RWSet rw = RWSet.builder()
            .readBlock(pos)
            .writeBlock(pos)
            .writeGlobal(GlobalKey.ALL)
            .writeEvent(org.nebula.core.state.EventType.BLOCK_UPDATE)
            .build();
        return new TaskNode(taskId, "COMMAND_BLOCK", rw, action);
    }

    /**
     * World border task: reads+writes the world border center and size.
     */
    public static TaskNode worldBorder(WorldPos center, TaskAction action) {
        String taskId = "WORLD_BORDER@" + center.dimensionId();
        RWSet rw = RWSet.builder()
            .writeGlobal(GlobalKey.WORLD_BORDER)
            .build();
        return new TaskNode(taskId, "WORLD_BORDER", rw, action);
    }

    /**
     * Reload reset task: reads+writes all plugin-managed global state.
     */
    public static TaskNode reloadReset(TaskAction action) {
        String taskId = "RELOAD_RESET";
        RWSet rw = RWSet.builder()
            .writeGlobal(GlobalKey.ALL)
            .build();
        return new TaskNode(taskId, "RELOAD_RESET", rw, action);
    }

    /**
     * Weather change task: reads+writes global weather state.
     */
    public static TaskNode weatherChange(String worldName, TaskAction action) {
        String taskId = "WEATHER_CHANGE@" + worldName;
        RWSet rw = RWSet.builder()
            .writeGlobal(GlobalKey.WEATHER)
            .build();
        return new TaskNode(taskId, "WEATHER_CHANGE", rw, action);
    }

    /**
     * Time tick task: advances the world time counter.
     */
    public static TaskNode timeTick(long worldSeed, TaskAction action) {
        String taskId = "TIME_TICK@" + worldSeed;
        RWSet rw = RWSet.builder()
            .writeGlobal(GlobalKey.GAME_TIME)
            .build();
        return new TaskNode(taskId, "TIME_TICK", rw, action);
    }
}
