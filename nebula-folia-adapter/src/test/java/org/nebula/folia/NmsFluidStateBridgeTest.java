package org.nebula.folia;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.FluidSnapshot;
import org.nebula.entity.FluidState;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NmsFluidStateBridgeTest {

    private static Object def(Method method) {
        Class<?> type = method.getReturnType();
        if (type == boolean.class) return false;
        if (type == int.class || type == long.class || type == short.class || type == byte.class) return 0;
        return null;
    }

    private static Block block(Material material, int level) {
        Levelled data = (Levelled) Proxy.newProxyInstance(Levelled.class.getClassLoader(),
            new Class<?>[]{Levelled.class}, (proxy, method, args) -> {
                if (method.getName().equals("getLevel")) return level;
                if (method.getName().equals("getMaximumLevel")) return 15;
                if (method.getName().equals("getMaterial")) return material;
                return def(method);
            });
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(),
            new Class<?>[]{Block.class}, (proxy, method, args) -> {
                if (method.getName().equals("getType")) return material;
                if (method.getName().equals("getBlockData")) return data;
                return def(method);
            });
    }

    private static World worldFor(WorldPos pos, Block block) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(),
            new Class<?>[]{World.class}, (proxy, method, args) -> {
                if (method.getName().equals("getBlockAt") && args != null && args.length == 3
                        && (int) args[0] == pos.x() && (int) args[1] == pos.y()
                        && (int) args[2] == pos.z()) {
                    return block;
                }
                return def(method);
            });
    }

    @Test
    void waterLevelIsSampledIntoFluidState() {
        WorldPos pos = new WorldPos(0, 10, 64, 10);
        FluidState state = new FluidState();
        NmsFluidStateBridge bridge = new NmsFluidStateBridge(state);

        FluidSnapshot snapshot = bridge.syncFromNms(worldFor(pos, block(Material.WATER, 3)), pos);

        assertEquals(FluidSnapshot.water(pos, 3, false), snapshot);
        assertEquals(snapshot, state.get(pos));
    }

    @Test
    void sourceLavaIsMarkedAsSource() {
        WorldPos pos = new WorldPos(0, 10, 64, 10);
        FluidState state = new FluidState();
        NmsFluidStateBridge bridge = new NmsFluidStateBridge(state);

        assertEquals(FluidSnapshot.lava(pos, 0, true),
            bridge.syncFromNms(worldFor(pos, block(Material.LAVA, 0)), pos));
    }

    @Test
    void nonFluidClearsTheCasPosition() {
        WorldPos pos = new WorldPos(0, 10, 64, 10);
        FluidState state = new FluidState();
        state.put(pos, FluidSnapshot.water(pos, 0, true));
        NmsFluidStateBridge bridge = new NmsFluidStateBridge(state);

        assertNull(bridge.syncFromNms(worldFor(pos, block(Material.STONE, 0)), pos));
        assertNull(state.get(pos));
    }
}
