package org.nebula.maintenance.asm;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Infers @NebulaRW annotations from decompiled MC source code using pattern database.
 * 
 * Run: java NebulaRWSourceInferrer <decompiled-src-path> [--output <file>]
 */
public class NebulaRWSourceInferrer {

    private static Map<String, List<PatternEntry>> patternDB = new HashMap<>();
    private static final String MC_VERSION = "1.21.4";
    
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: NebulaRWSourceInferrer <decompiled-src-path> [--output <file>]");
            System.exit(1);
        }
        
        // Load pattern database
        Path patternDbPath = Paths.get("nebula-maintenance/src/main/resources/rw-patterns.json");
        if (Files.exists(patternDbPath)) {
            loadPatternDatabase(patternDbPath);
            System.err.println("Loaded " + patternDB.values().stream().mapToInt(List::size).sum() + " patterns");
        }
        
        Path basePath = Paths.get(args[0]);
        if (!Files.exists(basePath)) {
            System.err.println("Path not found: " + basePath);
            System.exit(1);
        }
        
        // Find all Java files
        List<Path> javaFiles = new ArrayList<>();
        Files.walk(basePath)
            .filter(p -> p.toString().endsWith(".java"))
            .filter(p -> !p.toString().contains("/test/"))
            .forEach(javaFiles::add);
        
        System.err.println("Scanning " + javaFiles.size() + " files...");
        
        // Analyze
        Map<String, MethodAnalysis> allMethods = new HashMap<>();
        for (Path file : javaFiles) {
            try {
                String content = Files.readString(file);
                String relativePath = basePath.relativize(file).toString();
                extractMethods(content, relativePath, allMethods);
            } catch (Exception e) {
                System.err.println("Error: " + file + ": " + e.getMessage());
            }
        }
        
        System.err.println("Found " + allMethods.size() + " methods");
        
        // Infer annotations
        List<InferredAnnotation> inferred = inferAnnotations(allMethods);
        System.err.println("Generated " + inferred.size() + " annotations");
        
        // Output
        if (args.length > 1 && args[1].equals("--output")) {
            writeOutput(inferred, Paths.get(args[2]));
        }
    }
    
    private static void loadPatternDatabase(Path path) throws IOException {
        String content = Files.readString(path);
        Pattern entryPattern = Pattern.compile(
            "\\{\\s*\"method\"\\s*:\\s*\"([^\"]+)\"[^}]+\\}",
            Pattern.DOTALL
        );
        
        List<PatternEntry> allPatterns = new ArrayList<>();
        Matcher m = entryPattern.matcher(content);
        while (m.find()) {
            PatternEntry entry = parsePatternEntry(m.group(0));
            if (entry != null && entry.method != null) {
                allPatterns.add(entry);
            }
        }
        
        for (PatternEntry entry : allPatterns) {
            String category = categorizeMethod(entry.method);
            patternDB.computeIfAbsent(category, k -> new ArrayList<>()).add(entry);
            patternDB.computeIfAbsent(entry.className, k -> new ArrayList<>()).add(entry);
        }
    }
    
    private static PatternEntry parsePatternEntry(String entryStr) {
        PatternEntry entry = new PatternEntry();
        
        Pattern methodP = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = methodP.matcher(entryStr);
        if (m.find()) entry.method = m.group(1);
        
        Pattern classP = Pattern.compile("\"class\"\\s*:\\s*\"([^\"]+)\"");
        m = classP.matcher(entryStr);
        if (m.find()) entry.className = m.group(1);
        
        entry.readEntities = extractJsonArray(entryStr, "readEntities");
        entry.writeEntities = extractJsonArray(entryStr, "writeEntities");
        entry.readBlocks = extractJsonArray(entryStr, "readBlocks");
        entry.triggeredEvents = extractJsonArray(entryStr, "events");
        
        Pattern maxP = Pattern.compile("\"maxRandom\"\\s*:\\s*(-?\\d+)");
        m = maxP.matcher(entryStr);
        if (m.find()) entry.maxRandomCalls = Integer.parseInt(m.group(1));
        
        Pattern randP = Pattern.compile("\"random\"\\s*:\\s*\"([^\"]*)\"");
        m = randP.matcher(entryStr);
        if (m.find()) entry.randomInstance = m.group(1);
        
        return entry;
    }
    
    private static String[] extractJsonArray(String str, String field) {
        Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*\\[([^\\]]*)\\]");
        Matcher m = p.matcher(str);
        if (m.find()) {
            String inner = m.group(1);
            if (inner.trim().isEmpty()) return new String[0];
            return Arrays.stream(inner.split(","))
                .map(s -> s.trim().replace("\"", ""))
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        }
        return new String[0];
    }
    
    private static void extractMethods(String content, String relativePath, Map<String, MethodAnalysis> methods) {
        Pattern methodPattern = Pattern.compile(
            "(?:public|protected|private)\\s+(?:static\\s+)?(?:final\\s+)?([\\w<>,\\s\\[\\]]+)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws[^{]*)?\\{([\\s\\S]*?)(?=\\n\\s*(?:public|protected|private|\\}|@|$))"
        );
        
        Matcher m = methodPattern.matcher(content);
        while (m.find()) {
            String returnType = m.group(1).trim();
            String methodName = m.group(2);
            String params = m.group(3);
            String body = m.group(4);
            
            if (isAccessor(methodName) || body.length() < 30) continue;
            
            String key = relativePath + "#" + methodName;
            
            MethodAnalysis analysis = new MethodAnalysis();
            analysis.methodName = methodName;
            analysis.returnType = returnType;
            analysis.parameters = params;
            analysis.sourceFile = relativePath;
            analysis.body = body;
            
            // Analyze body
            analysis.fieldReads = extractFieldReads(body);
            analysis.fieldWrites = extractFieldWrites(body);
            analysis.blockAccesses = detectBlockAccesses(body);
            analysis.rngUsage = detectRng(body);
            analysis.eventTriggers = detectEvents(body);
            
            methods.put(key, analysis);
        }
    }
    
    private static boolean isAccessor(String name) {
        return name.startsWith("get") || name.startsWith("set") || 
               name.startsWith("is") || name.startsWith("has") ||
               name.startsWith("can") || name.startsWith("add") ||
               name.startsWith("remove") || name.startsWith("clear");
    }
    
    private static Set<String> extractFieldReads(String body) {
        Set<String> reads = new HashSet<>();
        
        // Pattern: this.field (but not this.field( or this.field =)
        Pattern p = Pattern.compile("this\\.([a-z][a-zA-Z0-9]*)(?!\\(|\\s*[=:;])");
        Matcher m = p.matcher(body);
        while (m.find()) {
            reads.add(m.group(1));
        }
        
        // Pattern: setField() calls indicate reads
        p = Pattern.compile("this\\.get([A-Z][a-zA-Z0-9]*)\\(");
        m = p.matcher(body);
        while (m.find()) {
            String field = Character.toLowerCase(m.group(1).charAt(0)) + m.group(1).substring(1);
            reads.add(field);
        }
        
        return reads;
    }
    
    private static Set<String> extractFieldWrites(String body) {
        Set<String> writes = new HashSet<>();
        
        // Pattern: this.field = or this.field:
        Pattern p = Pattern.compile("this\\.([a-z][a-zA-Z0-9]*)\\s*[=:]");
        Matcher m = p.matcher(body);
        while (m.find()) {
            writes.add(m.group(1));
        }
        
        // Pattern: setField() calls
        p = Pattern.compile("this\\.set([A-Z][a-zA-Z0-9]*)\\(");
        m = p.matcher(body);
        while (m.find()) {
            String field = Character.toLowerCase(m.group(1).charAt(0)) + m.group(1).substring(1);
            writes.add(field);
        }
        
        return writes;
    }
    
    private static Set<String> detectBlockAccesses(String body) {
        Set<String> accesses = new HashSet<>();
        
        if (body.contains("getBlock(") || body.contains("setBlock(")) {
            accesses.add("position");
        }
        if (body.contains("getBlockState(") || body.contains("setBlockState(")) {
            accesses.add("position");
        }
        if (body.contains("getChunk(")) {
            accesses.add("chunk");
        }
        if (body.contains("pos.relative") || body.contains("pos.above") || body.contains("pos.below")) {
            accesses.add("neighbors");
        }
        if (body.contains("level.getBlock(") || body.contains("this.level.setBlock(")) {
            accesses.add("level");
        }
        
        return accesses;
    }
    
    private static boolean detectRng(String body) {
        return body.contains("random") || body.contains("Random") || 
               body.contains("nextInt(") || body.contains("nextDouble(") ||
               body.contains("nextFloat(") || body.contains("nextLong(");
    }
    
    private static Set<String> detectEvents(String body) {
        Set<String> events = new HashSet<>();
        
        if (body.contains("broadcastChange") || body.contains("sendMessage") || body.contains("setChanged()")) {
            events.add("BLOCK_UPDATE");
        }
        if (body.contains("addFreshEntity") || body.contains("spawn") && body.contains("Entity")) {
            events.add("ENTITY_SPAWNED");
        }
        if (body.contains("damage(") || body.contains("hurt(")) {
            events.add("ENTITY_DAMAGED");
        }
        if (body.contains("deltaMovement") && body.contains("setDeltaMovement")) {
            events.add("ENTITY_MOVED");
        }
        if (body.contains("playSound")) {
            events.add("SOUND_PLAY");
        }
        
        return events;
    }
    
    private static String categorizeMethod(String name) {
        if (name.equals("tick")) return "tick";
        if (name.startsWith("tick")) return "tick";
        if (name.startsWith("ai")) return "ai";
        if (name.startsWith("move")) return "move";
        if (name.startsWith("hurt")) return "hurt";
        if (name.contains("Path")) return "pathfinding";
        if (name.contains("random") || name.contains("Random")) return "random";
        if (name.contains("spawn") || name.contains("add")) return "spawn";
        if (name.contains("damage")) return "damage";
        if (name.contains("jump")) return "jump";
        if (name.contains("look")) return "look";
        if (name.contains("navigation")) return "navigation";
        return "other";
    }
    
    private static List<InferredAnnotation> inferAnnotations(Map<String, MethodAnalysis> methods) {
        List<InferredAnnotation> inferred = new ArrayList<>();
        
        for (Map.Entry<String, MethodAnalysis> entry : methods.entrySet()) {
            MethodAnalysis analysis = entry.getValue();
            
            if (analysis.methodName.length() < 4) continue;
            if (analysis.fieldReads.isEmpty() && analysis.fieldWrites.isEmpty() && 
                analysis.blockAccesses.isEmpty() && !analysis.rngUsage) continue;
            
            List<MatchResult> matches = findMatchingPatterns(analysis);
            
            InferredAnnotation inf = new InferredAnnotation();
            inf.methodKey = entry.getKey();
            inf.analysis = analysis;
            
            if (!matches.isEmpty()) {
                inf.confidence = matches.get(0).confidence;
                inf.suggested = templateFromPattern(matches.get(0).pattern);
                inf.patternMatches = matches;
            } else {
                inf.confidence = inferFromBodyOnlyConfidence(analysis);
                inf.suggested = inferFromBodyOnly(analysis);
            }
            
            if (inf.suggested != null && inf.confidence >= 0.4) {
                inferred.add(inf);
            }
        }
        
        inferred.sort((a, b) -> Double.compare(b.confidence, a.confidence));
        return inferred;
    }
    
    private static List<MatchResult> findMatchingPatterns(MethodAnalysis analysis) {
        List<MatchResult> results = new ArrayList<>();
        
        String category = categorizeMethod(analysis.methodName);
        List<PatternEntry> categoryPatterns = patternDB.getOrDefault(category, Collections.emptyList());
        for (PatternEntry pattern : categoryPatterns) {
            double conf = calculateMatchConfidence(analysis, pattern);
            if (conf > 0.5) {
                results.add(new MatchResult(pattern, conf));
            }
        }
        
        String classKey = analysis.sourceFile.replace("/", ".");
        classKey = classKey.replace(".java", "");
        if (classKey.contains(".")) {
            classKey = classKey.substring(classKey.lastIndexOf(".") + 1);
        }
        List<PatternEntry> classPatterns = patternDB.getOrDefault(classKey, Collections.emptyList());
        for (PatternEntry pattern : classPatterns) {
            double conf = calculateMatchConfidence(analysis, pattern);
            if (conf > 0.5) {
                results.add(new MatchResult(pattern, conf));
            }
        }
        
        results.sort((a, b) -> Double.compare(b.confidence, a.confidence));
        return results;
    }
    
    private static double calculateMatchConfidence(MethodAnalysis analysis, PatternEntry pattern) {
        double conf = 0.4;
        
        if (pattern.method.equals(analysis.methodName)) conf += 0.3;
        if (!analysis.fieldReads.isEmpty() && pattern.readEntities.length > 0) conf += 0.1;
        if (!analysis.fieldWrites.isEmpty() && pattern.writeEntities.length > 0) conf += 0.1;
        if (!analysis.blockAccesses.isEmpty() && pattern.readBlocks.length > 0) conf += 0.1;
        if (analysis.rngUsage && pattern.randomInstance != null && !pattern.randomInstance.isEmpty()) conf += 0.05;
        if (pattern.readEntities.length > 0 && pattern.readEntities[0].equals("this.*")) conf += 0.05;
        
        return Math.min(conf, 0.95);
    }
    
    private static double inferFromBodyOnlyConfidence(MethodAnalysis analysis) {
        double conf = 0.3;
        if (!analysis.fieldReads.isEmpty() || !analysis.fieldWrites.isEmpty()) conf += 0.15;
        if (!analysis.blockAccesses.isEmpty()) conf += 0.1;
        if (!analysis.eventTriggers.isEmpty()) conf += 0.1;
        if (analysis.rngUsage) conf += 0.05;
        return Math.min(conf, 0.7);
    }
    
    private static AnnotationTemplate inferFromBodyOnly(MethodAnalysis analysis) {
        AnnotationTemplate t = new AnnotationTemplate();
        
        // Use conservative this.* patterns for broad reads/writes
        if (!analysis.fieldReads.isEmpty()) {
            if (analysis.fieldReads.size() > 5) {
                t.readEntities = new String[]{"this.*"};
            } else {
                t.readEntities = analysis.fieldReads.stream()
                    .map(f -> "this." + f)
                    .toArray(String[]::new);
            }
        }
        
        if (!analysis.fieldWrites.isEmpty()) {
            if (analysis.fieldWrites.size() > 5) {
                t.writeEntities = new String[]{"this.*"};
            } else {
                t.writeEntities = analysis.fieldWrites.stream()
                    .map(f -> "this." + f)
                    .toArray(String[]::new);
            }
        }
        
        if (!analysis.blockAccesses.isEmpty()) {
            t.readBlocks = analysis.blockAccesses.stream()
                .map(b -> "{" + b + "}")
                .toArray(String[]::new);
        }
        
        if (!analysis.eventTriggers.isEmpty()) {
            t.triggeredEvents = analysis.eventTriggers.toArray(new String[0]);
        }
        
        if (analysis.rngUsage) {
            t.maxRandomCalls = 8;
            t.randomInstance = "WORLD_RANDOM";
        }
        
        return t;
    }
    
    private static AnnotationTemplate templateFromPattern(PatternEntry pattern) {
        AnnotationTemplate t = new AnnotationTemplate();
        t.readEntities = pattern.readEntities.clone();
        t.writeEntities = pattern.writeEntities.clone();
        t.readBlocks = pattern.readBlocks.clone();
        t.triggeredEvents = pattern.triggeredEvents.clone();
        t.maxRandomCalls = pattern.maxRandomCalls;
        t.randomInstance = pattern.randomInstance;
        return t;
    }
    
    private static void writeOutput(List<InferredAnnotation> inferred, Path output) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("// Auto-generated @NebulaRW annotations\n");
        sb.append("// Generated: ").append(new java.util.Date()).append("\n");
        sb.append("// Total: ").append(inferred.size()).append(" annotations\n\n");
        
        for (InferredAnnotation inf : inferred) {
            sb.append("// Confidence: ").append(String.format("%.2f", inf.confidence)).append("\n");
            sb.append("// Method: ").append(inf.methodKey.replace("/", ".")).append("\n");
            sb.append(inf.suggested.toAnnotationString(MC_VERSION)).append("\n\n");
        }
        
        Files.writeString(output, sb.toString());
        System.err.println("Written to: " + output);
    }
    
    // Data structures
    
    private static class MethodAnalysis {
        String methodName;
        String returnType;
        String parameters;
        String sourceFile;
        String body;
        Set<String> fieldReads = new HashSet<>();
        Set<String> fieldWrites = new HashSet<>();
        Set<String> blockAccesses = new HashSet<>();
        Set<String> eventTriggers = new HashSet<>();
        boolean rngUsage;
    }
    
    private static class AnnotationTemplate {
        String[] readEntities = new String[0];
        String[] writeEntities = new String[0];
        String[] readBlocks = new String[0];
        String[] triggeredEvents = new String[0];
        int maxRandomCalls = -1;
        String randomInstance;
        
        String toAnnotationString(String version) {
            StringBuilder sb = new StringBuilder();
            sb.append("@org.nebula.annotations.NebulaRW(\n");
            
            if (readEntities.length > 0) {
                sb.append("    readEntities = {");
                sb.append(String.join(", ", Arrays.stream(readEntities).map(s -> "\"" + s + "\"").toList()));
                sb.append("},\n");
            }
            
            if (writeEntities.length > 0) {
                sb.append("    writeEntities = {");
                sb.append(String.join(", ", Arrays.stream(writeEntities).map(s -> "\"" + s + "\"").toList()));
                sb.append("},\n");
            }
            
            if (readBlocks.length > 0) {
                sb.append("    readBlocks = {");
                sb.append(String.join(", ", Arrays.stream(readBlocks).map(s -> "\"" + s + "\"").toList()));
                sb.append("},\n");
            }
            
            if (triggeredEvents.length > 0) {
                sb.append("    triggeredEvents = {");
                sb.append(String.join(", ", Arrays.stream(triggeredEvents).map(s -> "\"" + s + "\"").toList()));
                sb.append("},\n");
            }
            
            if (maxRandomCalls >= 0) {
                sb.append("    maxRandomCalls = ").append(maxRandomCalls).append(",\n");
            }
            
            if (randomInstance != null && !randomInstance.isEmpty()) {
                sb.append("    randomInstance = \"").append(randomInstance).append("\",\n");
            }
            
            sb.append("    verifiedAt = \"").append(version).append("\"\n");
            sb.append(")");
            
            return sb.toString();
        }
    }
    
    private static class PatternEntry {
        String method;
        String className;
        String[] readEntities = new String[0];
        String[] writeEntities = new String[0];
        String[] readBlocks = new String[0];
        String[] triggeredEvents = new String[0];
        int maxRandomCalls;
        String randomInstance;
    }
    
    private static class InferredAnnotation {
        String methodKey;
        MethodAnalysis analysis;
        double confidence;
        AnnotationTemplate suggested;
        List<MatchResult> patternMatches = new ArrayList<>();
    }
    
    private static class MatchResult {
        PatternEntry pattern;
        double confidence;
        MatchResult(PatternEntry pattern, double confidence) {
            this.pattern = pattern;
            this.confidence = confidence;
        }
    }
}
