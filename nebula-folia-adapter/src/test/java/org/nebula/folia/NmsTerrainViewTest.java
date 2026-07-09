package org.nebula.folia;

import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link NmsTerrainView} against the real Folia API types, with
 * proxy-stubbed Bukkit {@link Server}/{@link World}/{@link Block} objects whose
 * per-cell material is controllable. Guards the fix for the live "grounded mob
 * over-falls by -0.0784/tick because the runner ran against TerrainView.EMPTY"
 * finding (B8 C1, 2026-07-10): the oracle must report full-cube blocks solid and
 * air non-solid, and must map dimension ids to the right world.
 */
class NmsTerrainViewTest {

    private static final int OVERWORLD = 0;
    private static final int NETHER = -1;

    private static Object def(Method m) {
        Class<?> r = m.getReturnType();
        if (r == boolean.class) return false;
        if (r == int.class || r == long.class) return 0;
        return null;
    }

    /** A Block stub whose {@code getType()} returns the given material. */
    private static Block blockOf(Material mat) {
        return (Block) Proxy.newProxyInstance(
            Block.class.getClassLoader(), new Class<?>[]{Block.class},
            (p, m, a) -> m.getName().equals("getType") ? mat : def(m));
    }

    /**
     * A World stub named {@code name}. {@code getBlockAt(x,y,z)} returns a solid
     * block only for cells whose Y is at or below {@code floorY}; everything above
     * is air — a flat floor, the common live case.
     */
    private static World flatFloorWorld(String name, int floorY) {
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(), new Class<?>[]{World.class},
            (p, m, a) -> {
                switch (m.getName()) {
                    case "getName":
                        return name;
                    case "getBlockAt":
                        int y = (int) a[1];
                        return blockOf(y <= floorY ? Material.STONE : Material.AIR);
                    default:
                        return def(m);
                }
            });
    }

    private static Server serverWith(World... worlds) {
        return (Server) Proxy.newProxyInstance(
            Server.class.getClassLoader(), new Class<?>[]{Server.class},
            (p, m, a) -> m.getName().equals("getWorlds") ? List.of(worlds) : def(m));
    }

    // Registry-free solidity predicate: STONE is solid, everything else (AIR) is not.
    // The real view uses Material::isSolid, which needs the Paper block registry absent
    // from unit tests; this exercises NmsTerrainView's own logic without it.
    private static NmsTerrainView viewOver(Server server) {
        return new NmsTerrainView(server, mat -> mat == Material.STONE);
    }

    @Test
    void solidBlockReadsSolidAirReadsOpen() {
        Server server = serverWith(flatFloorWorld("world", 63));
        NmsTerrainView view = viewOver(server);

        assertTrue(view.isSolid(new WorldPos(OVERWORLD, 0, 63, 0)),
            "a full-cube block (stone) at/below the floor is solid");
        assertFalse(view.isSolid(new WorldPos(OVERWORLD, 0, 64, 0)),
            "air above the floor is not solid");
    }

    @Test
    void mapsDimensionIdToTheCorrectWorld() {
        // Overworld floor at 63, nether floor at 31 — a probe must hit the right one.
        Server server = serverWith(
            flatFloorWorld("world", 63),
            flatFloorWorld("world_nether", 31));
        NmsTerrainView view = viewOver(server);

        assertTrue(view.isSolid(new WorldPos(NETHER, 0, 31, 0)),
            "nether probe resolves to the nether world's floor");
        assertFalse(view.isSolid(new WorldPos(NETHER, 0, 32, 0)),
            "above the nether floor is air");
        assertTrue(view.isSolid(new WorldPos(OVERWORLD, 0, 63, 0)),
            "overworld probe resolves to the overworld's floor");
    }

    @Test
    void unknownDimensionIsOpenVoidNotAnError() {
        Server server = serverWith(flatFloorWorld("world", 63));
        NmsTerrainView view = viewOver(server);
        // Dimension 1 (the end) has no matching world here — must not throw.
        assertFalse(view.isSolid(new WorldPos(1, 0, 0, 0)),
            "a dimension with no loaded world reads as open void");
    }
}
