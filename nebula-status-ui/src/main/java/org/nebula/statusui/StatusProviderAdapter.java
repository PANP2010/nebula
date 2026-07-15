package org.nebula.statusui;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.nebula.core.metrics.MicroStepRecorder;
import org.nebula.core.metrics.TickTimeRecorder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Adapter that bridges the Nebula plugin's internal state to the StatusProvider interface.
 * Uses reflection to access Nebula plugin internals without creating hard dependencies.
 */
public final class StatusProviderAdapter implements StatusProvider {

    private final Plugin nebulaPlugin;
    private final AtomicInteger lastLayersExecuted = new AtomicInteger(0);

    // Cached fields and methods
    private Field tickTimeRecorderField;
    private Field microStepRecorderField;
    private Field fidelityControllerField;
    private Field redstoneStateField;
    private Field entityStateField;
    private Field blockEntityStateField;
    private Method componentCountMethod;
    private Method toggleSourceCountMethod;

    public StatusProviderAdapter(Plugin nebulaPlugin) {
        this.nebulaPlugin = nebulaPlugin;
        initializeReflection();
    }

    private void initializeReflection() {
        Class<?> pluginClass = nebulaPlugin.getClass();

        try {
            tickTimeRecorderField = pluginClass.getDeclaredField("tickTimeRecorder");
            tickTimeRecorderField.setAccessible(true);

            microStepRecorderField = pluginClass.getDeclaredField("microStepRecorder");
            microStepRecorderField.setAccessible(true);

            fidelityControllerField = pluginClass.getDeclaredField("fidelityController");
            fidelityControllerField.setAccessible(true);

            redstoneStateField = pluginClass.getDeclaredField("redstoneState");
            redstoneStateField.setAccessible(true);

            entityStateField = pluginClass.getDeclaredField("entityState");
            entityStateField.setAccessible(true);

            blockEntityStateField = pluginClass.getDeclaredField("blockEntityState");
            blockEntityStateField.setAccessible(true);

            componentCountMethod = pluginClass.getMethod("componentCount");
            toggleSourceCountMethod = pluginClass.getMethod("toggleSourceCount");
        } catch (NoSuchFieldException | NoSuchMethodException e) {
            // Fields may not exist on all plugin versions
        }
    }

    private <T> T getField(Object obj, Field field) {
        try {
            @SuppressWarnings("unchecked")
            T value = (T) field.get(obj);
            return value;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    @Override
    public TickTimeRecorder.Snapshot getTickTimeSnapshot() {
        if (tickTimeRecorderField != null) {
            TickTimeRecorder recorder = getField(nebulaPlugin, tickTimeRecorderField);
            if (recorder != null) {
                return recorder.snapshot();
            }
        }
        return TickTimeRecorder.Snapshot.EMPTY;
    }

    @Override
    public MicroStepRecorder.Snapshot getMicroStepSnapshot() {
        if (microStepRecorderField != null) {
            MicroStepRecorder recorder = getField(nebulaPlugin, microStepRecorderField);
            if (recorder != null) {
                return recorder.snapshot();
            }
        }
        return MicroStepRecorder.Snapshot.EMPTY;
    }

    @Override
    public int getLastLayersExecuted() {
        return lastLayersExecuted.get();
    }

    public void setLastLayersExecuted(int layers) {
        lastLayersExecuted.set(layers);
    }

    @Override
    public int getFidelityTier() {
        if (fidelityControllerField != null) {
            try {
                Object controller = fidelityControllerField.get(nebulaPlugin);
                if (controller != null) {
                    Method currentTierMethod = controller.getClass().getMethod("currentTier");
                    return (int) currentTierMethod.invoke(controller);
                }
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    @Override
    public int getComponentCount() {
        if (componentCountMethod != null) {
            try {
                return (int) componentCountMethod.invoke(nebulaPlugin);
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    @Override
    public int getToggleSourceCount() {
        if (toggleSourceCountMethod != null) {
            try {
                return (int) toggleSourceCountMethod.invoke(nebulaPlugin);
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    @Override
    public int getRedstoneStateSize() {
        return getMapSize(nebulaPlugin, redstoneStateField);
    }

    @Override
    public int getEntityStateSize() {
        return getMapSize(nebulaPlugin, entityStateField);
    }

    @Override
    public int getBlockEntityStateSize() {
        return getMapSize(nebulaPlugin, blockEntityStateField);
    }

    private int getMapSize(Object obj, Field field) {
        if (field != null) {
            try {
                Object map = field.get(obj);
                if (map instanceof java.util.Map<?, ?> m) {
                    return m.size();
                }
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    @Override
    public long getServerTick() {
        try {
            return Bukkit.getServer().getCurrentTick();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public int getOnlinePlayers() {
        return Bukkit.getServer().getOnlinePlayers().size();
    }

    @Override
    public int getLoadedChunks() {
        int total = 0;
        for (var world : Bukkit.getServer().getWorlds()) {
            total += world.getLoadedChunks().length;
        }
        return total;
    }
}
