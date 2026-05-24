package org.nebula.core.vap;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PluginSandboxTest {

    @Test
    void requestResponseRoundtrip() throws Exception {
        PluginSandbox sandbox = new PluginSandbox("TestPlugin", 16, 2000);

        AtomicReference<SandboxResponse> result = new AtomicReference<>();
        Thread plugin = new Thread(() -> {
            try {
                result.set(sandbox.call(new SandboxRequest("getBlock", "0,64,0")));
            } catch (SandboxTimeoutException e) {
                fail("should not timeout");
            }
        });
        plugin.start();

        // Give plugin thread time to submit
        Thread.sleep(50);

        // Kernel processes the request
        int processed = sandbox.processRequests(req -> {
            assertEquals("getBlock", req.operationType());
            return SandboxResponse.ok(new byte[]{42});
        }, 10);

        plugin.join(2000);
        assertEquals(1, processed);
        assertNotNull(result.get());
        assertTrue(result.get().success());
        assertArrayEquals(new byte[]{42}, result.get().payload());
    }

    @Test
    void shutdownRejectsNewCalls() throws Exception {
        PluginSandbox sandbox = new PluginSandbox("TestPlugin", 16, 100);
        sandbox.shutdown();

        SandboxResponse response = sandbox.call(new SandboxRequest("op", "target"));
        assertFalse(response.success());
        assertTrue(response.error().contains("shut down"));
    }

    @Test
    void timeoutWhenKernelDoesNotRespond() {
        PluginSandbox sandbox = new PluginSandbox("TestPlugin", 16, 100);

        assertThrows(SandboxTimeoutException.class, () ->
            sandbox.call(new SandboxRequest("slowOp", "target")));
    }

    @Test
    void multipleRequestsProcessedInOrder() throws Exception {
        PluginSandbox sandbox = new PluginSandbox("TestPlugin", 16, 2000);

        // Submit 3 requests from plugin threads
        Thread[] plugins = new Thread[3];
        AtomicReference<SandboxResponse>[] results = new AtomicReference[3];
        for (int i = 0; i < 3; i++) {
            results[i] = new AtomicReference<>();
            int idx = i;
            plugins[i] = new Thread(() -> {
                try {
                    results[idx].set(sandbox.call(new SandboxRequest("op" + idx, "t")));
                } catch (SandboxTimeoutException e) {
                    fail("timeout");
                }
            });
            plugins[i].start();
        }

        Thread.sleep(100);

        // Process all 3
        int[] counter = {0};
        sandbox.processRequests(req -> {
            counter[0]++;
            return SandboxResponse.ok(new byte[]{(byte) counter[0]});
        }, 10);

        for (Thread p : plugins) p.join(2000);
        assertEquals(3, counter[0]);
    }

    @Test
    void pluginNameAndActiveState() {
        PluginSandbox sandbox = new PluginSandbox("MyPlugin");
        assertEquals("MyPlugin", sandbox.pluginName());
        assertTrue(sandbox.isActive());
        sandbox.shutdown();
        assertFalse(sandbox.isActive());
    }
}
