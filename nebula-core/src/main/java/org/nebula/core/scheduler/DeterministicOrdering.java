package org.nebula.core.scheduler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class DeterministicOrdering {
    private DeterministicOrdering() {
    }

    public static int compareTaskIds(String left, String right) {
        int byHash = stableHash(left).compareTo(stableHash(right));
        if (byHash != 0) {
            return byHash;
        }
        return left.compareTo(right);
    }

    private static String stableHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the JVM", ex);
        }
    }
}
