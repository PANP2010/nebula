package org.nebula.maintenance.asm;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Generates patch-ready @NebulaRW annotation files from inferred annotations.
 *
 * <p>Pinned behaviour: see {@link NebulaRWPatchGeneratorTest} for regressions we
 * explicitly protect against (method names without {@code (params)},
 * multi-line annotation body prefixing).
 */
public class NebulaRWPatchGenerator {

    /**
     * Visible-for-testing render entry point. Splits the given inferred-annotation
     * text into per-class patch payloads and returns a single concatenated string
     * for assertions. Each per-class patch is the same format as the on-disk
     * files {@link #main(String[])} writes.
     */
    public static String renderPatchText(String content, String outputDirName, int seedPrefix) {
        Map<String, List<AnnotationEntry>> byClass = new LinkedHashMap<>();
        parseAnnotations(content, byClass);

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<AnnotationEntry>> entry : byClass.entrySet()) {
            String className = entry.getKey();
            List<AnnotationEntry> annotations = entry.getValue();
            if (annotations.isEmpty()) continue;
            annotations.sort((a, b) -> Double.compare(b.confidence, a.confidence));
            sb.append(generatePatch(className, annotations));
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        Path inputFile = Paths.get("/tmp/full-nebularw-v2.txt");
        Path outputDir = Paths.get("nebula-server-build/nebula-server/minecraft-patches/features");
        
        if (!Files.exists(inputFile)) {
            System.err.println("Input file not found: " + inputFile);
            System.exit(1);
        }
        
        String content = Files.readString(inputFile);
        
        // Parse annotations grouped by class
        Map<String, List<AnnotationEntry>> byClass = new LinkedHashMap<>();
        parseAnnotations(content, byClass);
        
        System.err.println("Parsed " + byClass.size() + " classes, " + 
            byClass.values().stream().mapToInt(List::size).sum() + " annotations");
        
        // Generate patch files
        Files.createDirectories(outputDir);
        int totalAnnotations = 0;
        
        for (Map.Entry<String, List<AnnotationEntry>> entry : byClass.entrySet()) {
            String className = entry.getKey();
            List<AnnotationEntry> annotations = entry.getValue();
            
            if (annotations.isEmpty()) continue;
            
            // Sort by confidence descending
            annotations.sort((a, b) -> Double.compare(b.confidence, a.confidence));
            
            String fileName = generateFileName(className, annotations.size());
            Path patchFile = outputDir.resolve(fileName);
            
            String patch = generatePatch(className, annotations);
            Files.writeString(patchFile, patch);
            
            totalAnnotations += annotations.size();
        }
        
        System.err.println("Generated " + totalAnnotations + " annotations in " + byClass.size() + " files");
    }
    
    private static void parseAnnotations(String content, Map<String, List<AnnotationEntry>> byClass) {
        // Split by "Confidence:" markers
        String[] blocks = content.split("// Confidence:");
        System.err.println("Split into " + blocks.length + " blocks");

        int parsed = 0;
        int debugged = 0;
        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];

            // Extract confidence
            double confidence = 0.4;
            int confEnd = block.indexOf('\n');
            if (confEnd > 0) {
                try {
                    confidence = Double.parseDouble(block.substring(0, confEnd).trim());
                } catch (NumberFormatException e) {}
            }

            // Extract method line - look for "// Method:"
            int methodStart = block.indexOf("// Method:");
            if (methodStart < 0) {
                if (debugged < 3) {
                    System.err.println("DEBUG[no Method]: block=" + block.substring(0, Math.min(150, block.length())));
                    debugged++;
                }
                continue;
            }

            // Find end of method line (newline)
            int methodLineEnd = block.indexOf('\n', methodStart);
            if (methodLineEnd < 0) {
                if (debugged < 3) {
                    System.err.println("DEBUG[no newline after Method]: " + block.substring(methodStart, Math.min(methodStart + 150, block.length())));
                    debugged++;
                }
                continue;
            }
            String methodLine = block.substring(methodStart + "// Method:".length(), methodLineEnd).trim();

            // Format: path.java#methodName or path.java#methodName(params)
            int hashIdx = methodLine.lastIndexOf(".java#");
            if (hashIdx < 0) {
                if (debugged < 3) {
                    System.err.println("DEBUG[no .java#]: '" + methodLine + "'");
                    debugged++;
                }
                continue;
            }

            String classPath = methodLine.substring(0, hashIdx); // without .java
            String methodSig = methodLine.substring(hashIdx + 6); // after .java#
            int sigEnd = methodSig.length();
            for (int j = 0; j < methodSig.length(); j++) {
                char c = methodSig.charAt(j);
                if (c == '(' || Character.isWhitespace(c)) {
                    sigEnd = j;
                    break;
                }
            }
            String methodName = methodSig.substring(0, sigEnd);
            if (methodName.isEmpty()) continue;

            // Extract annotation body — find @org.nebula.annotations.NebulaRW( and matching close paren
            int annStart = block.indexOf("@org.nebula.annotations.NebulaRW(");
            if (annStart < 0) {
                if (debugged < 3) {
                    System.err.println("DEBUG[no ann]: method=" + methodName);
                    debugged++;
                }
                continue;
            }
            int annOpenParen = annStart + "@org.nebula.annotations.NebulaRW(".length() - 1;
            int annCloseParen = findMatchingClose(block, annOpenParen);
            if (annCloseParen < 0) {
                if (debugged < 3) {
                    System.err.println("DEBUG[no close paren]: method=" + methodName);
                    debugged++;
                }
                continue;
            }
            String annotationBody = block.substring(annStart + "@org.nebula.annotations.NebulaRW(".length(), annCloseParen);
            // Strip leading 4-space indentation from each line so the body sits flush
            annotationBody = annotationBody.replaceAll("(?m)^    ", "");

            AnnotationEntry entry = new AnnotationEntry();
            entry.classPath = classPath + ".java";
            entry.methodName = methodName;
            entry.confidence = confidence;
            entry.annotationBody = annotationBody;

            String classKey = classPath.replace("/", ".");
            byClass.computeIfAbsent(classKey, k -> new ArrayList<>()).add(entry);
            parsed++;
        }

        System.err.println("Parsed " + parsed + " annotations (debugged=" + debugged + ")");
    }

    /** Find the index of the close paren matching the open paren at openIdx. */
    private static int findMatchingClose(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
    
    private static String generateFileName(String className, int count) {
        String shortName = className.replace("net.minecraft.", "").replace(".", "_");
        return String.format("9999-nebula-AUTO-NebulaRW-%03d-%s.patch", count, shortName);
    }
    
    private static String generatePatch(String classPath, List<AnnotationEntry> annotations) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("From: NebulaRW Auto-Generator\n");
        sb.append("Date: " + new java.util.Date() + "\n");
        sb.append("Subject: [PATCH] nebula: AUTO @NebulaRW annotations (" + annotations.size() + " methods)\n");
        sb.append("\n");
        sb.append("Auto-generated @NebulaRW annotations from NebulaRWSourceInferrer\n");
        sb.append("Pattern database: 168 patterns from existing annotations\n");
        sb.append("Confidence threshold: 0.4\n\n");
        
        for (AnnotationEntry entry : annotations) {
            sb.append("diff --git a/").append(entry.classPath).append(" b/").append(entry.classPath).append("\n");
            sb.append("--- a/").append(entry.classPath).append("\n");
            sb.append("+++ b/").append(entry.classPath).append("\n");
            sb.append("@@ // ").append(String.format("%.0f", entry.confidence * 100)).append("% confidence\n");
            sb.append("+    @org.nebula.annotations.NebulaRW(\n");
            // Each line of the annotation body must be prefixed with +
            for (String line : entry.annotationBody.split("\n", -1)) {
                sb.append("+").append(line).append("\n");
            }
            sb.append("+    )\n");
            sb.append("     public /* returnType */ ").append(entry.methodName).append("(/* params */);\n\n");
        }
        
        return sb.toString();
    }
    
    private static class AnnotationEntry {
        String classPath;
        String methodName;
        double confidence;
        String annotationBody;
    }
}
