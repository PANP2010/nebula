package org.nebula.guard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

public final class RWGuardReportWriter {
    private RWGuardReportWriter() {
    }

    public static void appendJsonLines(Path logPath, List<RWSetViolation> violations) {
        if (logPath == null || violations.isEmpty()) {
            return;
        }
        try {
            Path parent = logPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            StringBuilder payload = new StringBuilder();
            for (RWSetViolation violation : violations) {
                payload.append(RWSetViolationJson.toJson(violation)).append(System.lineSeparator());
            }
            Files.writeString(
                logPath,
                payload.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException ex) {
            throw new IllegalStateException("failed to write RW guard violation log: " + logPath, ex);
        }
    }
}
