package org.nebula.guard;

import org.nebula.core.rw.RWSet;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class RWSetViolationJson {
    private RWSetViolationJson() {
    }

    public static String toJson(RWSetViolation violation) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        field(json, "violation_id", violation.violationId()).append(',');
        field(json, "timestamp", violation.timestamp().toString()).append(',');
        numericField(json, "tick_number", violation.tickNumber()).append(',');
        field(json, "task_id", violation.taskId()).append(',');
        field(json, "task_type", violation.taskType()).append(',');
        field(json, "violation_type", violation.violationType().name()).append(',');
        json.append("\"access_target\":");
        accessTarget(json, violation.accessTarget()).append(',');
        json.append("\"declared_rw_set\":");
        rwSet(json, violation.declaredRWSet()).append(',');
        json.append("\"stack_trace\":");
        stringArray(json, violation.stackTrace().stream().map(StackTraceElement::toString).toList()).append(',');
        field(json, "suggested_fix", violation.suggestedFix());
        json.append('}');
        return json.toString();
    }

    public static RWSetViolation fromJson(String json) {
        String violationId = extractStringField(json, "violation_id");
        String timestampStr = extractStringField(json, "timestamp");
        long tickNumber = extractNumericField(json, "tick_number");
        String taskId = extractStringField(json, "task_id");
        String taskType = extractStringField(json, "task_type");
        String violationTypeStr = extractStringField(json, "violation_type");
        String suggestedFix = extractStringField(json, "suggested_fix");

        ViolationType violationType = ViolationType.valueOf(violationTypeStr);
        Instant timestamp = Instant.parse(timestampStr);

        AccessTarget accessTarget = parseAccessTarget(json);

        return RWSetViolation.reconstruct(
            violationId,
            timestamp,
            tickNumber,
            taskId,
            taskType,
            violationType,
            accessTarget,
            RWSet.empty(),
            suggestedFix
        );
    }

    private static AccessTarget parseAccessTarget(String json) {
        String targetSection = extractObject(json, "access_target");
        String typeStr = extractStringField(targetSection, "type");
        AccessTargetType type = AccessTargetType.valueOf(typeStr);

        return switch (type) {
            case BLOCK -> {
                int dimension = extractIntField(targetSection, "dimension");
                int x = extractIntField(targetSection, "x");
                int y = extractIntField(targetSection, "y");
                int z = extractIntField(targetSection, "z");
                yield AccessTarget.block(new org.nebula.core.state.WorldPos(dimension, x, y, z));
            }
            case ENTITY_FIELD -> AccessTarget.entityField(new org.nebula.core.state.EntityField(
                extractLongField(targetSection, "entity_id"),
                extractStringField(targetSection, "field_path")
            ));
            case BLOCK_ENTITY_FIELD -> AccessTarget.blockEntityField(new org.nebula.core.state.BlockEntityField(
                new org.nebula.core.state.WorldPos(
                    extractIntField(targetSection, "dimension"),
                    extractIntField(targetSection, "x"),
                    extractIntField(targetSection, "y"),
                    extractIntField(targetSection, "z")
                ),
                extractStringField(targetSection, "field_path")
            ));
            case GLOBAL, GLOBAL_KEY -> AccessTarget.globalKey(new org.nebula.core.state.GlobalKey(
                extractStringField(targetSection, "key")
            ));
            case RANDOM -> AccessTarget.random(org.nebula.core.state.RandomInstance.valueOf(
                extractStringField(targetSection, "instance")
            ));
        };
    }

    public static String suggestionsToJson(Collection<AnnotationPatchSuggestion> suggestions) {
        StringBuilder json = new StringBuilder();
        json.append('[');
        boolean first = true;
        for (AnnotationPatchSuggestion suggestion : suggestions) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('{');
            field(json, "task_id", suggestion.taskId()).append(',');
            field(json, "task_type", suggestion.taskType()).append(',');
            json.append("\"access_target\":");
            accessTarget(json, suggestion.accessTarget()).append(',');
            numericField(json, "occurrences", suggestion.occurrences()).append(',');
            field(json, "suggested_fix", suggestion.suggestedFix());
            json.append('}');
        }
        json.append(']');
        return json.toString();
    }

    private static StringBuilder rwSet(StringBuilder json, RWSet rwSet) {
        json.append('{');
        json.append("\"blocks_read\":");
        stringArray(json, rwSet.readBlocks()).append(',');
        json.append("\"blocks_written\":");
        stringArray(json, rwSet.writtenBlocks()).append(',');
        json.append("\"block_entities_read\":");
        stringArray(json, rwSet.readBlockEntities()).append(',');
        json.append("\"block_entities_written\":");
        stringArray(json, rwSet.writtenBlockEntities()).append(',');
        json.append("\"entities_read\":");
        stringArray(json, rwSet.readEntityFields()).append(',');
        json.append("\"entities_written\":");
        stringArray(json, rwSet.writtenEntityFields()).append(',');
        json.append("\"poi_read\":");
        stringArray(json, rwSet.readPoiQueries()).append(',');
        json.append("\"global_read\":");
        stringArray(json, rwSet.readGlobalKeys().stream().map(Object::toString).toList()).append(',');
        json.append("\"global_written\":");
        stringArray(json, rwSet.writtenGlobalKeys().stream().map(Object::toString).toList()).append(',');
        json.append("\"random\":");
        json.append(rwSet.randomUsage().map(usage -> quote(usage.toString())).orElse("null")).append(',');
        json.append("\"events_written\":");
        stringArray(json, rwSet.writtenEvents());
        json.append('}');
        return json;
    }

    private static StringBuilder accessTarget(StringBuilder json, AccessTarget target) {
        json.append('{');
        field(json, "type", target.type().name()).append(',');
        // Emit type-specific STRUCTURED fields the read side can round-trip. Every non-BLOCK
        // type used to be splatted under a single "value" holding the record toString()
        // (e.g. "EntityField[entityId=12345, fieldPath=FieldPath[value=health]]"), which the
        // read side could not recover: it reads GLOBAL from "key", RANDOM from "instance", and
        // called EntityField/BlockEntityField.parse() expecting their delimited serialization
        // forms — not the record toString. So NONE of the four non-BLOCK types round-tripped.
        // Structured fields (matching the read side's existing expectations) fix that and avoid
        // fragile toString parsing.
        switch (target.type()) {
            case BLOCK -> {
                // AccessTarget.block(pos) stores WorldPos.toString(), i.e. the record form
                // "WorldPos[dimensionId=D, x=X, y=Y, z=Z]". Pull the four signed ints out of
                // it (also tolerates the legacy bare "dim,x,y,z" form) so we emit clean
                // integer fields instead of splatting the raw toString into "dimension".
                int[] coords = parseBlockCoords(target.value());
                if (coords != null) {
                    json.append("\"dimension\":").append(coords[0]).append(',');
                    json.append("\"x\":").append(coords[1]).append(',');
                    json.append("\"y\":").append(coords[2]).append(',');
                    json.append("\"z\":").append(coords[3]);
                } else {
                    field(json, "value", target.value());
                }
            }
            case ENTITY_FIELD -> {
                // value == EntityField.toString() "EntityField[entityId=E, fieldPath=FieldPath[value=P]]"
                long[] entityId = new long[1];
                String path = parseEntityField(target.value(), entityId);
                if (path != null) {
                    json.append("\"entity_id\":").append(entityId[0]).append(',');
                    field(json, "field_path", path);
                } else {
                    field(json, "value", target.value());
                }
            }
            case BLOCK_ENTITY_FIELD -> {
                // value == BlockEntityField.toString()
                // "BlockEntityField[pos=WorldPos[dimensionId=D, x=X, y=Y, z=Z], fieldPath=FieldPath[value=P]]"
                int[] coords = parseBlockCoords(target.value());
                String path = parseTrailingFieldPath(target.value());
                if (coords != null && path != null) {
                    json.append("\"dimension\":").append(coords[0]).append(',');
                    json.append("\"x\":").append(coords[1]).append(',');
                    json.append("\"y\":").append(coords[2]).append(',');
                    json.append("\"z\":").append(coords[3]).append(',');
                    field(json, "field_path", path);
                } else {
                    field(json, "value", target.value());
                }
            }
            case GLOBAL, GLOBAL_KEY ->
                // value == GlobalKey.value() (bare key string, e.g. "game_time")
                field(json, "key", target.value());
            case RANDOM ->
                // value == RandomInstance.name() (e.g. "WORLD_RANDOM")
                field(json, "instance", target.value());
        }
        json.append('}');
        return json;
    }

    private static final Pattern SIGNED_INT = Pattern.compile("-?\\d+");

    /**
     * Extracts dimension/x/y/z from a BLOCK AccessTarget value. Handles both the current
     * {@code WorldPos.toString()} record form ("WorldPos[dimensionId=0, x=1, y=-59, z=0]")
     * and the legacy bare "dim,x,y,z" form by reading the first four signed integers in order.
     * Returns null if fewer than four integers are present.
     */
    private static int[] parseBlockCoords(String value) {
        Matcher matcher = SIGNED_INT.matcher(value);
        int[] coords = new int[4];
        int found = 0;
        while (found < 4 && matcher.find()) {
            coords[found++] = Integer.parseInt(matcher.group());
        }
        return found == 4 ? coords : null;
    }

    // FieldPath values may themselves contain ']' (e.g. "inventory.slots[0]"). Since an
    // ENTITY_FIELD / BLOCK_ENTITY_FIELD toString always wraps the FieldPath and closes with
    // "]]" (FieldPath's own ']' plus the outer record's), match greedily up to the trailing "]]".
    private static final Pattern FIELD_PATH_VALUE = Pattern.compile("FieldPath\\[value=(.*)\\]\\]");
    private static final Pattern ENTITY_ID = Pattern.compile("entityId=(-?\\d+)");

    /**
     * Extracts the entity id (into {@code out[0]}) and field-path string from an
     * {@link org.nebula.core.state.EntityField#toString()} record form
     * ("EntityField[entityId=E, fieldPath=FieldPath[value=P]]"). Returns the field-path string,
     * or null if the value is not in that form.
     */
    private static String parseEntityField(String value, long[] out) {
        Matcher id = ENTITY_ID.matcher(value);
        Matcher path = FIELD_PATH_VALUE.matcher(value);
        if (id.find() && path.find()) {
            out[0] = Long.parseLong(id.group(1));
            return path.group(1);
        }
        return null;
    }

    /**
     * Extracts the inner FieldPath value from any record toString that ends in a
     * {@code FieldPath[value=P]} fragment (used by BLOCK_ENTITY_FIELD). Returns null if absent.
     */
    private static String parseTrailingFieldPath(String value) {
        Matcher path = FIELD_PATH_VALUE.matcher(value);
        return path.find() ? path.group(1) : null;
    }

    private static StringBuilder field(StringBuilder json, String name, String value) {
        json.append(quote(name)).append(':').append(quote(value));
        return json;
    }

    private static StringBuilder numericField(StringBuilder json, String name, long value) {
        json.append(quote(name)).append(':').append(value);
        return json;
    }

    private static StringBuilder stringArray(StringBuilder json, Collection<?> values) {
        json.append('[');
        json.append(values.stream()
            .map(Object::toString)
            .map(RWSetViolationJson::quote)
            .collect(Collectors.joining(",")));
        json.append(']');
        return json;
    }

    private static String quote(String value) {
        return "\"" + escape(value) + "\"";
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static final Pattern STRING_FIELD = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern NUMERIC_FIELD = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\\d+)");

    private static String extractStringField(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private static long extractNumericField(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*(\\d+)");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        return 0;
    }

    private static int extractIntField(String json, String fieldName) {
        // Coordinates can be negative (e.g. y=-59), so match a signed integer rather than
        // reusing the unsigned tick_number extractor.
        Pattern pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*(-?\\d+)");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 0;
    }

    private static long extractLongField(String json, String fieldName) {
        // Entity ids are 64-bit and can be negative; match a signed long.
        Pattern pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*(-?\\d+)");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        return 0L;
    }

    private static String extractObject(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*\\{");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            int start = matcher.end() - 1;
            int depth = 0;
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return json.substring(start, i + 1);
                    }
                }
            }
        }
        return "{}";
    }
}
