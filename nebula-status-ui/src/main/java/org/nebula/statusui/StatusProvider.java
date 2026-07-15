package org.nebula.statusui;

import org.nebula.core.metrics.MicroStepRecorder;
import org.nebula.core.metrics.TickTimeRecorder;

/**
 * Interface for providing status data to the Nebula Status Dashboard.
 * Implement this interface to connect any data source to the dashboard.
 */
public interface StatusProvider {

    TickTimeRecorder.Snapshot getTickTimeSnapshot();

    MicroStepRecorder.Snapshot getMicroStepSnapshot();

    int getLastLayersExecuted();

    int getFidelityTier();

    int getComponentCount();

    int getToggleSourceCount();

    int getRedstoneStateSize();

    int getEntityStateSize();

    int getBlockEntityStateSize();

    long getServerTick();

    int getOnlinePlayers();

    int getLoadedChunks();
}
