package org.nebula.core.vap;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Message-passing sandbox for isolated plugin execution (arch doc §13.6).
 *
 * <p>Sandboxed plugins run in their own single-threaded region. All API calls
 * are serialized through this channel: the plugin thread enqueues a request,
 * the kernel processes it in the appropriate DAG phase, and sends the response
 * back through the response channel.
 *
 * <p>Overhead per API call: ~50-200μs (message serialization + queue latency).
 * Suitable for low-frequency plugins (chat formatting, decorations, etc.).
 */
public final class PluginSandbox {

    private static final long DEFAULT_TIMEOUT_MS = 5000;

    private final String pluginName;
    private final BlockingQueue<SandboxRequest> requestQueue;
    private final BlockingQueue<SandboxResponse> responseQueue;
    private final long timeoutMs;
    private volatile boolean active = true;

    public PluginSandbox(String pluginName, int queueCapacity, long timeoutMs) {
        this.pluginName = Objects.requireNonNull(pluginName);
        this.requestQueue = new ArrayBlockingQueue<>(queueCapacity);
        this.responseQueue = new ArrayBlockingQueue<>(queueCapacity);
        this.timeoutMs = timeoutMs;
    }

    public PluginSandbox(String pluginName) {
        this(pluginName, 256, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Called by the sandboxed plugin thread to submit an API request.
     * Blocks until a response is available or timeout is reached.
     *
     * @param request the API operation to perform
     * @return the kernel's response
     * @throws SandboxTimeoutException if the kernel doesn't respond in time
     */
    public SandboxResponse call(SandboxRequest request) throws SandboxTimeoutException {
        if (!active) {
            return SandboxResponse.error("sandbox is shut down");
        }
        if (!requestQueue.offer(request)) {
            return SandboxResponse.error("request queue full");
        }
        try {
            SandboxResponse response = responseQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (response == null) {
                throw new SandboxTimeoutException(pluginName, timeoutMs);
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SandboxResponse.error("interrupted");
        }
    }

    /**
     * Called by the kernel's plugin-phase to drain and process pending requests.
     * Processes at most {@code maxRequests} per tick.
     *
     * @param processor function that executes the request and returns a response
     * @param maxRequests max requests to process per tick
     * @return number of requests processed
     */
    public int processRequests(SandboxRequestProcessor processor, int maxRequests) {
        int processed = 0;
        SandboxRequest request;
        while (processed < maxRequests && (request = requestQueue.poll()) != null) {
            SandboxResponse response;
            try {
                response = processor.process(request);
            } catch (Exception e) {
                response = SandboxResponse.error(e.getMessage());
            }
            responseQueue.offer(response);
            processed++;
        }
        return processed;
    }

    /**
     * Shuts down the sandbox. Pending requests will receive error responses.
     */
    public void shutdown() {
        active = false;
        SandboxRequest req;
        while ((req = requestQueue.poll()) != null) {
            responseQueue.offer(SandboxResponse.error("sandbox shut down"));
        }
    }

    public String pluginName() {
        return pluginName;
    }

    public boolean isActive() {
        return active;
    }

    public int pendingRequests() {
        return requestQueue.size();
    }

    @FunctionalInterface
    public interface SandboxRequestProcessor {
        SandboxResponse process(SandboxRequest request) throws Exception;
    }
}
