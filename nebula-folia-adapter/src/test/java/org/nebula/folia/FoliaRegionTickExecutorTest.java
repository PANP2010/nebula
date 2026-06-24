package org.nebula.folia;

import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link FoliaRegionTickExecutor} — the {@link org.nebula.folia.bridge.RedstoneTickHook.TickExecutor}
 * that dispatches every dirty task to its owning region thread via
 * {@link RegionScheduler#execute RegionScheduler.execute()}.
 *
 * <p>Because {@code executeTasks} runs on the global tick thread (not a region
 * thread), there is no owned/foreign partition — every task goes through the
 * scheduler. Backed by a stubbed Folia {@link Server}.
 */
class FoliaRegionTickExecutorTest {

    private static final int DIM = 0;
    private static final String WORLD_NAME = "minecraft:overworld";

    private static final Function<TaskNode, WorldPos> POS_OF =
        t -> WorldPos.parse(t.taskId().substring(t.taskId().indexOf('@') + 1));

    private static TaskNode taskAt(WorldPos pos) {
        RWSet rw = RWSet.builder().writeBlock(pos).build();
        String id = "T@" + pos.dimensionId() + "," + pos.x() + "," + pos.y() + "," + pos.z();
        return TaskNode.inert(id, "test", rw);
    }

    private static World stubWorld() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(),
            new Class<?>[]{World.class}, (p, m, a) -> def(m));
    }

    private static Object def(Method m) {
        Class<?> r = m.getReturnType();
        if (r == boolean.class) return false;
        if (r == int.class || r == long.class || r == short.class || r == byte.class) return 0;
        return null;
    }

    // ── Combined server fixture ─────────────────────────────────────────────

    private record Fixture(Server server, List<int[]> scheduled, List<Runnable> workItems, Plugin plugin) {}

    /**
     * Creates a stub Server whose RegionScheduler records every
     * {@code execute(plugin, world, chunkX, chunkZ, runnable)} call and runs
     * the runnable inline (so tests can verify both scheduling and execution).
     */
    private static Fixture fixture(World world, String resolvableName) {
        List<int[]> scheduled = new ArrayList<>();
        List<Runnable> workItems = new ArrayList<>();
        RegionScheduler scheduler = (RegionScheduler) Proxy.newProxyInstance(
            RegionScheduler.class.getClassLoader(), new Class<?>[]{RegionScheduler.class},
            (p, m, a) -> {
                if (m.getName().equals("execute") && a != null && a.length == 5) {
                    scheduled.add(new int[]{(int) a[2], (int) a[3]});
                    Runnable r = (Runnable) a[4];
                    workItems.add(r);
                    r.run(); // execute inline for test assertions
                    return null;
                }
                return def(m);
            });
        Plugin plugin = (Plugin) Proxy.newProxyInstance(
            Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
            (p, m, a) -> def(m));
        Server server = (Server) Proxy.newProxyInstance(
            Server.class.getClassLoader(), new Class<?>[]{Server.class},
            (p, m, a) -> {
                if (m.getName().equals("getRegionScheduler")) {
                    return scheduler;
                }
                if (m.getName().equals("getWorld") && a != null && a.length == 1
                        && a[0] instanceof String name) {
                    return name.equals(resolvableName) ? world : null;
                }
                return def(m);
            });
        return new Fixture(server, scheduled, workItems, plugin);
    }

    @Test
    void everyTaskIsDispatchedToOwningRegion() throws Exception {
        World world = stubWorld();
        Fixture f = fixture(world, WORLD_NAME);

        List<TaskNode> executed = new ArrayList<>();
        FoliaRegionTickExecutor exec = new FoliaRegionTickExecutor(
            f.server(), POS_OF,
            (w, name, tasks) -> executed.addAll(tasks), f.plugin());

        List<TaskNode> dirty = List.of(
            taskAt(new WorldPos(DIM, 0, 64, 0)),
            taskAt(new WorldPos(DIM, 1, 64, 0)),
            taskAt(new WorldPos(DIM, 2, 64, 0)),
            taskAt(new WorldPos(DIM, 33, 64, 0)));

        exec.executeTasks("region-1", WORLD_NAME, dirty);

        // All 4 tasks were dispatched and executed.
        assertEquals(4, executed.size());
        assertEquals(4, f.scheduled().size());
        // Each task was scheduled at the correct chunk.
        assertEquals(0, f.scheduled().get(0)[0]);  // x=0  → chunk 0
        assertEquals(0, f.scheduled().get(1)[0]);  // x=1  → chunk 0
        assertEquals(0, f.scheduled().get(2)[0]);  // x=2  → chunk 0
        assertEquals(2, f.scheduled().get(3)[0]);  // x=33 → chunk 2
    }

    @Test
    void unresolvableWorldDropsTasksWithoutRunning() throws Exception {
        World world = stubWorld();
        Fixture f = fixture(world, WORLD_NAME);

        boolean[] ran = {false};
        FoliaRegionTickExecutor exec = new FoliaRegionTickExecutor(
            f.server(), POS_OF, (w, name, tasks) -> ran[0] = true, f.plugin());

        // Ask for a world the stub does not resolve.
        exec.executeTasks("region-1", "minecraft:the_void",
            List.of(taskAt(new WorldPos(DIM, 0, 64, 0))));

        assertTrue(!ran[0], "no execution when the world cannot be resolved");
        assertEquals(0, f.scheduled().size());
    }

    @Test
    void emptyDirtySetIsNoOp() throws Exception {
        World world = stubWorld();
        Fixture f = fixture(world, WORLD_NAME);
        boolean[] ran = {false};
        FoliaRegionTickExecutor exec = new FoliaRegionTickExecutor(
            f.server(), POS_OF, (w, name, tasks) -> ran[0] = true, f.plugin());

        exec.executeTasks("region-1", WORLD_NAME, List.of());

        assertTrue(!ran[0]);
        assertEquals(0, f.scheduled().size());
    }

    @Test
    void tasksAreDispatchedToCorrectChunks() throws Exception {
        World world = stubWorld();
        Fixture f = fixture(world, WORLD_NAME);

        List<TaskNode> executed = new ArrayList<>();
        FoliaRegionTickExecutor exec = new FoliaRegionTickExecutor(
            f.server(), POS_OF,
            (w, name, tasks) -> executed.addAll(tasks), f.plugin());

        exec.executeTasks("region-1", WORLD_NAME, List.of(
            taskAt(new WorldPos(DIM, 137, 64, -42)),
            taskAt(new WorldPos(DIM, 15, 64, 0)),
            taskAt(new WorldPos(DIM, -1, 64, 0))));

        assertEquals(3, executed.size());
        assertEquals(3, f.scheduled().size());
        assertEquals(8, f.scheduled().get(0)[0]);   // 137 >> 4
        assertEquals(-3, f.scheduled().get(0)[1]);  // -42 >> 4
        assertEquals(0, f.scheduled().get(1)[0]);   // 15 >> 4
        assertEquals(0, f.scheduled().get(2)[0]);   // -1 >> 4
    }
}
