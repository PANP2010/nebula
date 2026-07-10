package org.nebula.folia;

import org.bukkit.Server;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link InlineShadowTickExecutor} — the non-Folia (single-thread host)
 * {@link org.nebula.folia.bridge.RedstoneTickHook.TickExecutor} that runs the
 * whole dirty batch through the injected DAG runner inline, with no
 * {@code RegionScheduler} hop.
 *
 * <p>These are the D2 "confirm executeOwnedDag is reached with the dirty tasks"
 * wiring tests: the runner IS the {@code executeOwnedDag} seam in production, so
 * a fake runner recording its arguments proves the shadow path drives the DAG
 * rather than merely logging.
 */
class InlineShadowTickExecutorTest {

    private static final int DIM = 0;
    private static final String WORLD_NAME = "minecraft:overworld";

    private static TaskNode taskAt(WorldPos pos) {
        RWSet rw = RWSet.builder().writeBlock(pos).build();
        String id = "T@" + pos.dimensionId() + "," + pos.x() + "," + pos.y() + "," + pos.z();
        return TaskNode.inert(id, "test", rw);
    }

    private static Object def(Method m) {
        Class<?> r = m.getReturnType();
        if (r == boolean.class) return false;
        if (r == int.class || r == long.class || r == short.class || r == byte.class) return 0;
        return null;
    }

    private static World stubWorld() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(),
            new Class<?>[]{World.class}, (p, m, a) -> def(m));
    }

    /** Server stub that resolves exactly {@code resolvableName} to {@code world}. */
    private static Server serverResolving(World world, String resolvableName) {
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(),
            new Class<?>[]{Server.class}, (p, m, a) -> {
                if (m.getName().equals("getWorld") && a != null && a.length == 1
                        && a[0] instanceof String name) {
                    return name.equals(resolvableName) ? world : null;
                }
                return def(m);
            });
    }

    @Test
    void runsWholeBatchInlineThroughRunner() throws Exception {
        World world = stubWorld();
        Server server = serverResolving(world, WORLD_NAME);

        List<TaskNode> executed = new ArrayList<>();
        List<World> worldsSeen = new ArrayList<>();
        InlineShadowTickExecutor exec = new InlineShadowTickExecutor(server,
            (w, name, tasks) -> { worldsSeen.add(w); executed.addAll(tasks); });

        List<TaskNode> dirty = List.of(
            taskAt(new WorldPos(DIM, 0, 64, 0)),
            taskAt(new WorldPos(DIM, 33, 64, 0)),
            taskAt(new WorldPos(DIM, 137, 64, -42)));

        exec.executeTasks("nebula-global", WORLD_NAME, dirty);

        // Whole batch handed to the runner in ONE inline call (no per-task hop).
        assertEquals(1, worldsSeen.size(), "runner invoked exactly once for the batch");
        assertSame(world, worldsSeen.get(0), "runner receives the resolved world");
        assertEquals(3, executed.size(), "every dirty task reaches the DAG runner");
    }

    @Test
    void unresolvableWorldDropsTasksWithoutRunning() throws Exception {
        World world = stubWorld();
        Server server = serverResolving(world, WORLD_NAME);

        boolean[] ran = {false};
        InlineShadowTickExecutor exec = new InlineShadowTickExecutor(server,
            (w, name, tasks) -> ran[0] = true);

        exec.executeTasks("nebula-global", "minecraft:the_void",
            List.of(taskAt(new WorldPos(DIM, 0, 64, 0))));

        assertTrue(!ran[0], "no DAG run when the world cannot be resolved");
    }

    @Test
    void emptyDirtySetIsNoOp() throws Exception {
        World world = stubWorld();
        Server server = serverResolving(world, WORLD_NAME);
        boolean[] ran = {false};
        InlineShadowTickExecutor exec = new InlineShadowTickExecutor(server,
            (w, name, tasks) -> ran[0] = true);

        exec.executeTasks("nebula-global", WORLD_NAME, List.of());

        assertTrue(!ran[0], "empty dirty set does not touch the runner");
    }

    @Test
    void nullDirtySetIsNoOp() throws Exception {
        World world = stubWorld();
        Server server = serverResolving(world, WORLD_NAME);
        boolean[] ran = {false};
        InlineShadowTickExecutor exec = new InlineShadowTickExecutor(server,
            (w, name, tasks) -> ran[0] = true);

        exec.executeTasks("nebula-global", WORLD_NAME, null);

        assertTrue(!ran[0], "null dirty set does not touch the runner");
    }
}
