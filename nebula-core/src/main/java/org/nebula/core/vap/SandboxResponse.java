package org.nebula.core.vap;

/**
 * Kernel's response to a sandbox request (arch doc §13.6).
 *
 * @param success  whether the operation completed without error
 * @param payload  serialized result data (format depends on request type)
 * @param error    error message if success=false, null otherwise
 */
public record SandboxResponse(
    boolean success,
    byte[] payload,
    String error
) {
    public static SandboxResponse ok(byte[] payload) {
        return new SandboxResponse(true, payload, null);
    }

    public static SandboxResponse ok() {
        return new SandboxResponse(true, new byte[0], null);
    }

    public static SandboxResponse error(String message) {
        return new SandboxResponse(false, new byte[0], message);
    }
}
