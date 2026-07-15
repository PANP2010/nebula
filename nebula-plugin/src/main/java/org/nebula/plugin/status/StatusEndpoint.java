package org.nebula.plugin.status;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.nebula.core.metrics.MicroStepRecorder;
import org.nebula.core.metrics.TickTimeRecorder;
import org.nebula.core.scheduler.FidelityDowngradeController;
import org.nebula.entity.BlockEntityState;
import org.nebula.entity.EntityPhysicsState;
import org.nebula.redstone.RedstoneWorldState;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.Headers;

/**
 * Lightweight HTTP server exposing Nebula metrics as JSON.
 * Uses Java's built-in com.sun.net.httpserver (no external dependencies).
 */
public final class StatusEndpoint {

    private final NebulaPluginMetrics metrics;
    private HttpServer server;
    private final int port;

    public StatusEndpoint(int port) {
        this.port = port;
        this.metrics = new NebulaPluginMetrics();
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/nebula/status", new StatusHandler());
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        System.out.println("[Nebula] Status endpoint started on port " + port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            System.out.println("[Nebula] Status endpoint stopped");
        }
    }

    public NebulaPluginMetrics getMetrics() {
        return metrics;
    }

    private final class StatusHandler implements HttpHandler {
        @Override
        public void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            // CORS headers
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
            exchange.getResponseHeaders().add("Content-Type", "application/json");

            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String response = metrics.toJson();
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    /**
     * Data class holding all metrics from NebulaPlugin.
     * Updated each tick by the plugin.
     */
    public static final class NebulaPluginMetrics {
        // Tick time
        private long tickCount;
        private double tickAvgMs;
        private double tickP50Ms;
        private double tickP95Ms;
        private double tickP99Ms;
        private double tickMaxMs;
        private double tickMinMs;

        // Microsteps
        private double microAvg;
        private double microP50;
        private double microP95;
        private double microP99;
        private double microMax;

        // DAG
        private int layersExecuted;
        private int fidelityTier;

        // Components
        private int componentCount;
        private int toggleSourceCount;

        // State sizes
        private int redstoneStateSize;
        private int entityStateSize;
        private int blockEntityStateSize;

        // Server
        private long serverTick;
        private int onlinePlayers;
        private int loadedChunks;

        // Timestamps
        private long timestamp;
        private long uptimeMs;

        public void update(
                TickTimeRecorder tickTimeRecorder,
                MicroStepRecorder microStepRecorder,
                int layersExecuted,
                FidelityDowngradeController fidelityController,
                int componentCount,
                int toggleSourceCount,
                RedstoneWorldState redstoneState,
                EntityPhysicsState entityState,
                BlockEntityState blockEntityState,
                long serverTick,
                int onlinePlayers,
                int loadedChunks,
                long startTimeMs
        ) {
            // Tick time
            TickTimeRecorder.Snapshot tickSnap = tickTimeRecorder.snapshot();
            this.tickCount = tickSnap.count();
            this.tickAvgMs = tickSnap.avgNs() / 1_000_000.0;
            this.tickP50Ms = tickSnap.p50Ns() / 1_000_000.0;
            this.tickP95Ms = tickSnap.p95Ns() / 1_000_000.0;
            this.tickP99Ms = tickSnap.p99Ns() / 1_000_000.0;
            this.tickMaxMs = tickSnap.maxNs() / 1_000_000.0;
            this.tickMinMs = tickSnap.minNs() / 1_000_000.0;

            // Microsteps
            MicroStepRecorder.Snapshot microSnap = microStepRecorder.snapshot();
            this.microAvg = microSnap.avg();
            this.microP50 = microSnap.p50();
            this.microP95 = microSnap.p95();
            this.microP99 = microSnap.p99();
            this.microMax = microSnap.max();

            // DAG
            this.layersExecuted = layersExecuted;
            this.fidelityTier = fidelityController.currentTier().ordinal();

            // Components
            this.componentCount = componentCount;
            this.toggleSourceCount = toggleSourceCount;

            // State sizes
            this.redstoneStateSize = redstoneState.size();
            this.entityStateSize = entityState.size();
            this.blockEntityStateSize = blockEntityState.size();

            // Server
            this.serverTick = serverTick;
            this.onlinePlayers = onlinePlayers;
            this.loadedChunks = loadedChunks;

            // Time
            this.timestamp = System.currentTimeMillis();
            this.uptimeMs = System.currentTimeMillis() - startTimeMs;
        }

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"tick\":{");
            sb.append("\"count\":").append(tickCount).append(",");
            sb.append("\"avg\":").append(String.format("%.2f", tickAvgMs)).append(",");
            sb.append("\"p50\":").append(String.format("%.2f", tickP50Ms)).append(",");
            sb.append("\"p95\":").append(String.format("%.2f", tickP95Ms)).append(",");
            sb.append("\"p99\":").append(String.format("%.2f", tickP99Ms)).append(",");
            sb.append("\"max\":").append(String.format("%.2f", tickMaxMs)).append(",");
            sb.append("\"min\":").append(String.format("%.2f", tickMinMs));
            sb.append("},");

            sb.append("\"micro\":{");
            sb.append("\"avg\":").append(String.format("%.1f", microAvg)).append(",");
            sb.append("\"p50\":").append(microP50).append(",");
            sb.append("\"p95\":").append(microP95).append(",");
            sb.append("\"p99\":").append(microP99).append(",");
            sb.append("\"max\":").append(microMax).append(",");
            sb.append("\"cap\":256");
            sb.append("},");

            sb.append("\"dag\":{");
            sb.append("\"layers\":").append(layersExecuted).append(",");
            sb.append("\"fidelityTier\":").append(fidelityTier);
            sb.append("},");

            sb.append("\"components\":{");
            sb.append("\"count\":").append(componentCount).append(",");
            sb.append("\"toggles\":").append(toggleSourceCount);
            sb.append("},");

            sb.append("\"state\":{");
            sb.append("\"redstone\":").append(redstoneStateSize).append(",");
            sb.append("\"entities\":").append(entityStateSize).append(",");
            sb.append("\"blockEntities\":").append(blockEntityStateSize);
            sb.append("},");

            sb.append("\"server\":{");
            sb.append("\"tick\":").append(serverTick).append(",");
            sb.append("\"players\":").append(onlinePlayers).append(",");
            sb.append("\"chunks\":").append(loadedChunks);
            sb.append("},");

            sb.append("\"meta\":{");
            sb.append("\"timestamp\":").append(timestamp).append(",");
            sb.append("\"uptimeMs\":").append(uptimeMs);
            sb.append("}");

            sb.append("}");
            return sb.toString();
        }
    }
}
