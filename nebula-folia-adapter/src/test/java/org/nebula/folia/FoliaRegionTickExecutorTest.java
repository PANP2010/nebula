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
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link FoliaRegionTickExecutor} — the {@link org.nebula.folia.bridge.RedstoneTickHook.TickExecutor}
 * that routes a region-blind dirty-task set through {@link FoliaRegionTickDriver}
 * so owned tasks run inline and foreign tasks are dispatched to their owning
 * region threads. Backed by a stubbed Folia {@link Server}.
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

    // ── Combined server+bridge fixture ──────────────────────────────────────────

    private record Fixture(Server server, FoliaRegionBridge bridge, List<int[]> scheduled) {}

    private static Fixture fixture(IntPredicate ownsX, World world, String resolvableName) {
        List<int[]> scheduled = new ArrayList<>();
        RegionScheduler scheduler = (RegionScheduler) Proxy.newProxyInstance(
            RegionScheduler.class.getClassLoader(), new Class<?>[]{RegionScheduler.class},
            (p, m, a) -> {
                if (m.getName().equals("execute") && a != null && a.length == 5) {
                    scheduled.add(new int[]{(int) a[2], (int) a[3]});
                    ((Runnable) a[4]).run();
                    return null;
                }
                return def(m);
            });
        Server server = (Server) Proxy.newProxyInstance(
            Server.class.getClassLoader(), new Class<?>[]{Server.class},
            (p, m, a) -> {
                if (m.getName().equals("isOwnedByCurrentRegion")
                        && a != null && a.length == 3 && a[1] instanceof Integer) {
                    return ownsX.test((int) a[1]);
                }
                if (m.getName().equals("getRegionScheduler")) {
                    return scheduler;
                }
                if (m.getName().equals("getWorld") && a != null && a.length == 1
                        && a[0] instanceof String name) {
                    return name.equals(resolvableName) ? world : null;
                }
                return def(m);
            });
        Plugin plugin = (Plugin) Proxy.newProxyInstance(
            Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (p, m, a) -> def(m));
        return new Fixture(server, new FoliaRegionBridge(server, plugin), scheduled);
    }

    @Test
    void ownedTasksRunInlineForeignTasksDispatchedToOwningRegion() throws Exception {
        World world = stubWorld();
        Fixture f = fixture(x -> x % 2 == 0, world, WORLD_NAME); // owns even X
        FoliaRegionTickDriver driver = new FoliaRegionTickDriver(f.bridge());

        List<TaskNode> ranInline = new ArrayList<>();
        List<TaskNode> ranForeign = new ArrayList<>();
        // Owned partition arrives as a batch (size > 1); foreign arrive one-at-a-time.
        FoliaRegionTickExecutor exec = new FoliaRegionTickExecutor(
            f.server(), driver, POS_OF,
            (w, name, tasks) -> {
                if (tasks.size() == 1) ranForeign.add(tasks.get(0));
                else ranInline.addAll(tasks);
            });

        List<TaskNode> dirty = List.of(
            taskAt(new WorldPos(DIM, 0, 64, 0)),    // owned
            taskAt(new WorldPos(DIM, 1, 64, 0)),    // foreign
            taskAt(new WorldPos(DIM, 2, 64, 0)),    // owned
            taskAt(new WorldPos(DIM, 33, 64, 0)));  // foreign (chunk 2)

        exec.executeTasks("region-1", WORLD_NAME, dirty);

        // Owned (even X) ran inline.
        assertEquals(2, ranInline.size());
        assertTrue(ranInline.stream().allMatch(t -> POS_OF.apply(t).x() % 2 == 0));
        // Foreign (odd X) were dispatched and ran (stub scheduler executes inline).
        assertEquals(2, ranForeign.size());
        assertTrue(ranForeign.stream().allMatch(t -> POS_OF.apply(t).x() % 2 == 1));
        // Two foreign dispatches → two scheduler.execute calls at the right chunks.
        assertEquals(2, f.scheduled().size());
        assertEquals(0, f.scheduled().get(0)[0]);  // x=1  → chunk 0
        assertEquals(2, f.scheduled().get(1)[0]);  // x=33 → chunk 2
    }

    @Test
    void unresolvableWorldDropsTasksWithoutRunning() throws Exception {
        World world = stubWorld();
        Fixture f = fixture(x -> true, world, WORLD_NAME);
        FoliaRegionTickDriver driver = new FoliaRegionTickDriver(f.bridge());

        boolean[] ran = {false};
        FoliaRegionTickExecutor exec = new FoliaRegionTickExecutor(
            f.server(), driver, POS_OF, (w, name, tasks) -> ran[0] = true);

        // Ask for a world the stub does not resolve.
        exec.executeTasks("region-1", "minecraft:the_void",
            List.of(taskAt(new WorldPos(DIM, 0, 64, 0))));

        assertTrue(!ran[0], "no execution when the world cannot be resolved");
        assertEquals(0, f.scheduled().size());
    }

    @Test
    void emptyDirtySetIsNoOp() throws Exception {
        World world = stubWorld();
        Fixture f = fixture(x -> true, world, WORLD_NAME);
        FoliaRegionTickDriver driver = new FoliaRegionTickDriver(f.bridge());
        boolean[] ran = {false};
        FoliaRegionTickExecutor exec = new FoliaRegionTickExecutor(
            f.server(), driver, POS_OF, (w, name, tasks) -> ran[0] = true);

        exec.executeTasks("region-1", WORLD_NAME, List.of());

        assertTrue(!ran[0]);
        assertEquals(0, f.scheduled().size());
    }

    @Test
    void allOwnedMeansNoForeignDispatch() throws Exception {
        World world = stubWorld();
        Fixture f = fixture(x -> true, world, WORLD_NAME); // owns everything
        FoliaRegionTickDriver driver = new FoliaRegionTickDriver(f.bridge());

        List<TaskNode> ranInline = new ArrayList<>();
        FoliaRegionTickExecutor exec = new FoliaRegionTickExecutor(
            f.server(), driver, POS_OF, (w, name, tasks) -> ranInline.addAll(tasks));

        exec.executeTasks("region-1", WORLD_NAME, List.of(
            taskAt(new WorldPos(DIM, 5, 64, 5)),
            taskAt(new WorldPos(DIM, 6, 64, 6))));

        assertEquals(2, ranInline.size());
        assertEquals(0, f.scheduled().size(), "nothing foreign → no scheduler dispatch");
    }
}
