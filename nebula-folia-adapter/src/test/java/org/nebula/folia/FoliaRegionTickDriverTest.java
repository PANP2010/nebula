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
 * Tests {@link FoliaRegionTickDriver} against the real Folia API types, with a
 * {@link FoliaRegionBridge} backed by a stubbed {@link Server} whose ownership
 * verdict is controllable per block-X coordinate.
 */
class FoliaRegionTickDriverTest {

    private static final int DIM = 0;

    /** Maps a task to its position by parsing "type@dim:x,y,z"-ish ids we build below. */
    private static final Function<TaskNode, WorldPos> POS_OF =
        t -> WorldPos.parse(t.taskId().substring(t.taskId().indexOf('@') + 1));

    private static TaskNode taskAt(WorldPos pos) {
        RWSet rw = RWSet.builder().writeBlock(pos).build();
        String id = "T@" + pos.dimensionId() + "," + pos.x() + "," + pos.y() + "," + pos.z();
        return TaskNode.inert(id, "test", rw);
    }

    /** A bridge whose current region owns exactly the block-X values matching {@code ownsX}. */
    private static FoliaRegionBridge bridgeOwning(IntPredicate ownsX,
                                                  List<int[]> scheduled) {
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
                    return ownsX.test((int) a[1]); // ownership keyed on block X
                }
                if (m.getName().equals("getRegionScheduler")) {
                    return scheduler;
                }
                return def(m);
            });
        Plugin plugin = (Plugin) Proxy.newProxyInstance(
            Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (p, m, a) -> def(m));
        return new FoliaRegionBridge(server, plugin);
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

    @Test
    void executesOnlyRegionOwnedTasksAndReturnsForeign() throws Exception {
        // Region owns even block-X; tasks at x=0,1,2,3.
        FoliaRegionBridge bridge = bridgeOwning(x -> x % 2 == 0, new ArrayList<>());
        FoliaRegionTickDriver driver = new FoliaRegionTickDriver(bridge);
        World world = stubWorld();

        List<TaskNode> dirty = List.of(
            taskAt(new WorldPos(DIM, 0, 64, 0)),
            taskAt(new WorldPos(DIM, 1, 64, 0)),
            taskAt(new WorldPos(DIM, 2, 64, 0)),
            taskAt(new WorldPos(DIM, 3, 64, 0)));

        List<TaskNode> executed = new ArrayList<>();
        List<TaskNode> foreign = driver.tickOwnedTasks(world, dirty, POS_OF,
            (w, owned) -> executed.addAll(owned));

        // Owned (even X): x=0, x=2 executed.
        assertEquals(2, executed.size());
        assertTrue(executed.stream().allMatch(t -> POS_OF.apply(t).x() % 2 == 0));
        // Foreign (odd X): x=1, x=3 deferred.
        assertEquals(2, foreign.size());
        assertTrue(foreign.stream().allMatch(t -> POS_OF.apply(t).x() % 2 == 1));
    }

    @Test
    void noOwnedTasksMeansExecutorNotCalled() throws Exception {
        FoliaRegionBridge bridge = bridgeOwning(x -> false, new ArrayList<>()); // owns nothing
        FoliaRegionTickDriver driver = new FoliaRegionTickDriver(bridge);

        boolean[] called = {false};
        List<TaskNode> foreign = driver.tickOwnedTasks(stubWorld(),
            List.of(taskAt(new WorldPos(DIM, 5, 64, 5))), POS_OF,
            (w, owned) -> called[0] = true);

        assertTrue(!called[0], "executor must not run when nothing is owned");
        assertEquals(1, foreign.size());
    }

    @Test
    void dispatchForeignTasksSchedulesOnOwningRegionChunk() {
        List<int[]> scheduled = new ArrayList<>();
        FoliaRegionBridge bridge = bridgeOwning(x -> true, scheduled);
        FoliaRegionTickDriver driver = new FoliaRegionTickDriver(bridge);

        List<TaskNode> ran = new ArrayList<>();
        // Block x=137 → chunk 8; x=-42... use a clear case: x=137,z=-42 → chunk (8,-3).
        driver.dispatchForeignTasks(stubWorld(),
            List.of(taskAt(new WorldPos(DIM, 137, 64, -42))), POS_OF, ran::add);

        assertEquals(1, scheduled.size());
        assertEquals(8, scheduled.get(0)[0]);   // 137 >> 4
        assertEquals(-3, scheduled.get(0)[1]);  // -42 >> 4
        assertEquals(1, ran.size(), "scheduled work ran (stub executes inline)");
    }
}
