package org.nebula.guard;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record AnnotationPatchSuggestion(String taskId, String taskType, AccessTarget accessTarget, long occurrences, String suggestedFix) {
    public static List<AnnotationPatchSuggestion> fromViolations(List<RWSetViolation> violations) {
        Map<Key, List<RWSetViolation>> grouped = violations.stream()
            .collect(Collectors.groupingBy(v -> new Key(v.taskId(), v.taskType(), v.accessTarget())));

        return grouped.entrySet().stream()
            .map(entry -> new AnnotationPatchSuggestion(
                entry.getKey().taskId(),
                entry.getKey().taskType(),
                entry.getKey().accessTarget(),
                entry.getValue().size(),
                entry.getValue().getFirst().suggestedFix()
            ))
            .toList();
    }

    private record Key(String taskId, String taskType, AccessTarget accessTarget) {
    }
}
