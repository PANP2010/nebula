package org.nebula.core.vap;

/**
 * A request from a sandboxed plugin to the kernel (arch doc §13.6).
 *
 * @param operationType the API operation category (e.g. "teleport", "getBlock", "setHealth")
 * @param targetId      identifier of the target (entity ID, block position string, etc.)
 * @param payload       serialized operation arguments (format depends on operationType)
 */
public record SandboxRequest(
    String operationType,
    String targetId,
    byte[] payload
) {
    public SandboxRequest(String operationType, String targetId) {
        this(operationType, targetId, new byte[0]);
    }
}
