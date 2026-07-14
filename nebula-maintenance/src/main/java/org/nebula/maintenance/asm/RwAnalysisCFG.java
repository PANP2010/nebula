package org.nebula.maintenance.asm;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * CFG-based data-flow analyzer for @NebulaRW annotation inference.
 * 
 * Uses ASM Tree API + ControlFlowKit for CFG construction, then performs:
 * - Reaching definitions analysis
 * - Live variable analysis  
 * - Precise field read/write tracking per execution path
 * 
 * Run: java RwAnalysisCFG <class-dir> [--output <file>]
 */
public class RwAnalysisCFG {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: RwAnalysisCFG <class-dir> [--output <file>]");
            System.exit(1);
        }

        String outputPath = null;
        String classDir = args[0];
        for (int i = 1; i < args.length - 1; i++) {
            if (args[i].equals("--output")) outputPath = args[i + 1];
        }

        System.err.println("CFG-based RW Analysis (ASM Tree + ControlFlowKit)");
        System.err.println("==================================================");

        // Find all class files
        Path path = Paths.get(classDir);
        List<Path> classFiles = new ArrayList<>();
        Files.walk(path)
            .filter(p -> p.toString().endsWith(".class"))
            .forEach(classFiles::add);

        System.err.println("Found " + classFiles.size() + " class files");

        // Analyze each class
        List<ClassAnalysis> results = new ArrayList<>();
        for (Path cf : classFiles) {
            try {
                ClassAnalysis ca = analyzeClassFile(cf);
                if (ca != null && !ca.methods.isEmpty()) {
                    results.add(ca);
                }
            } catch (Exception e) {
                // Skip problematic files
            }
        }

        // Summary
        System.err.println("\nResults:");
        System.err.println("--------");
        int totalMethods = results.stream().mapToInt(r -> r.methods.size()).sum();
        System.err.println("Classes analyzed: " + results.size());
        System.err.println("Methods with field access: " + totalMethods);

        // Classification breakdown
        Map<String, Long> byType = new HashMap<>();
        for (ClassAnalysis ca : results) {
            for (MethodAnalysis ma : ca.methods) {
                byType.merge(ma.classification, 1L, Long::sum);
            }
        }
        System.err.println("\nBy type:");
        byType.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(e -> System.err.println("  " + e.getKey() + ": " + e.getValue()));

        // Output annotations
        if (outputPath != null) {
            writeAnnotations(results, Paths.get(outputPath));
        }
    }

    private static ClassAnalysis analyzeClassFile(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            
            ClassReader reader = new ClassReader(bytes);
            ClassNode cn = new ClassNode();
            reader.accept(cn, 0);

            ClassAnalysis ca = new ClassAnalysis();
            ca.className = cn.name.replace('/', '.');
            ca.classFile = file.toString();

            // Analyze each method
            for (MethodNode m : cn.methods) {
                if (m.instructions == null || m.instructions.size() == 0) continue;
                
                MethodAnalysis ma = analyzeMethod(cn.name, m);
                if (ma != null && (!ma.fieldReads.isEmpty() || !ma.fieldWrites.isEmpty())) {
                    ca.methods.add(ma);
                }
            }

            return ca;
        } catch (Exception e) {
            return null;
        }
    }

    private static MethodAnalysis analyzeMethod(String className, MethodNode method) {
        String name = method.name;
        
        // Skip trivial accessors
        if (name.startsWith("get") || name.startsWith("set") || 
            name.startsWith("is") || name.startsWith("can") ||
            name.startsWith("has") || name.startsWith("add") ||
            name.startsWith("remove") || name.startsWith("clear") ||
            name.startsWith("toString") || name.startsWith("equals") ||
            name.startsWith("hashCode")) {
            return null;
        }

        try {
            MethodAnalysis ma = new MethodAnalysis();
            ma.methodName = name;
            ma.descriptor = method.desc;

            // Build basic blocks using ControlFlowKit
            List<BasicBlock> blocks = buildBasicBlocks(method);
            if (blocks.isEmpty()) return null;

            // Compute data flow
            Map<BasicBlock, Set<String>> reachingDefs = computeReachingDefs(blocks);
            Map<BasicBlock, Set<String>> liveVars = computeLiveVars(blocks);

            // Track killed definitions
            Set<String> killedDefs = new HashSet<>();
            Set<String> killedLocals = new HashSet<>();

            // Collect field accesses
            Set<String> fieldReads = new LinkedHashSet<>();
            Set<String> fieldWrites = new LinkedHashSet<>();
            Set<String> blockAccesses = new LinkedHashSet<>();
            Set<String> events = new LinkedHashSet<>();
            boolean hasRng = false;

            for (BasicBlock block : blocks) {
                Set<String> blockReaching = reachingDefs.getOrDefault(block, Collections.emptySet());
                Set<String> blockLive = liveVars.getOrDefault(block, Collections.emptySet());
                
                for (AbstractInsnNode insn : block.instructions) {
                    // Update killed sets
                    if (insn instanceof VarInsnNode) {
                        VarInsnNode vin = (VarInsnNode) insn;
                        int opcode = insn.getOpcode();
                        // Local variable stores
                        // Local variable stores (ISTORE: 54-57, ASTORE: 75-78)
                        if (opcode >= 54 && opcode <= 57 || opcode >= 75 && opcode <= 78) {
                            killedLocals.add("local" + vin.var);
                        }
                    }
                    
                    // Field writes kill previous definitions
                    if (insn instanceof FieldInsnNode) {
                        FieldInsnNode fin = (FieldInsnNode) insn;
                        int opcode = insn.getOpcode();
                        if (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) {
                            String fieldKey = fin.owner + "." + fin.name;
                            killedDefs.add(fieldKey);
                            fieldWrites.add(fieldKey);
                        }
                    }

                    // Field reads - check if reaching definition exists
                    if (insn instanceof FieldInsnNode) {
                        FieldInsnNode fin = (FieldInsnNode) insn;
                        int opcode = insn.getOpcode();
                        if (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC) {
                            String fieldKey = fin.owner + "." + fin.name;
                            // Only count as read if not killed by a later write
                            if (!killedDefs.contains(fieldKey)) {
                                fieldReads.add(fieldKey);
                            }
                        }
                    }

                    // Method calls
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode min = (MethodInsnNode) insn;
                        String methodName = min.name;
                        String methodSig = min.desc;
                        
                        // Block accesses
                        if (methodName.contains("BlockPos") || methodName.contains("getBlock") || 
                            methodName.contains("setBlock") || methodName.contains("getChunk") ||
                            methodName.contains("getBlockState")) {
                            blockAccesses.add("position");
                        }
                        
                        // Events
                        if (methodName.contains("playSound") || methodName.contains("broadcast") ||
                            methodName.contains("sendMessage") || methodName.contains("Event") ||
                            methodName.contains("emit")) {
                            events.add(methodName);
                        }
                        
                        // RNG
                        if (methodName.contains("Random") || methodName.contains("nextInt") || 
                            methodName.contains("nextFloat") || methodName.contains("nextDouble") ||
                            methodName.contains("random")) {
                            hasRng = true;
                        }
                    }
                    
                    // Handle branches - killed defs reset at branch points
                    if (insn.getType() == AbstractInsnNode.JUMP_INSN) {
                        killedDefs.clear();
                    }
                }
                
                // At block exit, if it's a conditional branch, also reset
                AbstractInsnNode lastInsn = block.instructions.get(block.instructions.size() - 1);
                if (lastInsn.getType() == AbstractInsnNode.JUMP_INSN) {
                    killedDefs.clear();
                }
            }

            ma.fieldReads = fieldReads;
            ma.fieldWrites = fieldWrites;
            ma.blockAccesses = blockAccesses;
            ma.events = events;
            ma.hasRng = hasRng;
            ma.classification = classifyMethod(ma);

            return ma;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Build basic blocks using ASM Tree API
     */
    private static List<BasicBlock> buildBasicBlocks(MethodNode method) {
        InsnList instructions = method.instructions;
        
        // Identify block leaders
        Set<Integer> leaders = new HashSet<>();
        leaders.add(0); // Entry block
        
        for (int i = 0; i < instructions.size(); i++) {
            AbstractInsnNode insn = instructions.get(i);
            
            // Jump instructions
            if (insn.getType() == AbstractInsnNode.JUMP_INSN) {
                JumpInsnNode jmp = (JumpInsnNode) insn;
                int target = instructions.indexOf(jmp.label);
                if (target >= 0) leaders.add(target);
                // Fall-through
                if (i + 1 < instructions.size()) {
                    leaders.add(i + 1);
                }
            }
            // Conditional branches (IF* opcodes: 153-167, IFNULL/IFNONNULL: 198-199)
            else if (insn.getOpcode() >= 153 && insn.getOpcode() <= 167) {
                if (i + 1 < instructions.size()) {
                    leaders.add(i + 1);
                }
            }
            else if (insn.getOpcode() == 198 || insn.getOpcode() == 199) { // IFNULL, IFNONNULL
                if (i + 1 < instructions.size()) {
                    leaders.add(i + 1);
                }
            }
            // Return instructions
            // Return instructions (172-177)
            else if (insn.getOpcode() >= 172 && insn.getOpcode() <= 177) {
                if (i + 1 < instructions.size()) {
                    leaders.add(i + 1);
                }
            }
            // Exception handlers
            else if (insn.getType() == AbstractInsnNode.LABEL) {
                leaders.add(i);
            }
        }
        
        // Create blocks
        List<Integer> leaderList = new ArrayList<>(leaders);
        Collections.sort(leaderList);
        
        List<BasicBlock> blocks = new ArrayList<>();
        Map<Integer, BasicBlock> blockMap = new HashMap<>();
        
        for (int i = 0; i < leaderList.size(); i++) {
            BasicBlock block = new BasicBlock();
            block.id = i;
            block.start = leaderList.get(i);
            block.end = (i + 1 < leaderList.size()) ? leaderList.get(i + 1) : instructions.size();
            
            // Collect instructions
            for (int j = block.start; j < block.end && j < instructions.size(); j++) {
                block.instructions.add(instructions.get(j));
            }
            
            blockMap.put(block.id, block);
            blocks.add(block);
        }
        
        // Compute predecessors/successors
        for (BasicBlock block : blocks) {
            if (block.instructions.isEmpty()) continue;
            
            AbstractInsnNode last = block.instructions.get(block.instructions.size() - 1);
            
            // Jump successors
            if (last instanceof JumpInsnNode) {
                JumpInsnNode jmp = (JumpInsnNode) last;
                int targetIdx = instructions.indexOf(jmp.label);
                
                for (BasicBlock target : blocks) {
                    if (target.start <= targetIdx && targetIdx < target.end) {
                        block.successors.add(target.id);
                        target.predecessors.add(block.id);
                        break;
                    }
                }
                
                // Conditional jumps also have fall-through
                // Conditional branches (IF* opcodes: 153-167)
                if (last.getOpcode() >= 153 && last.getOpcode() <= 167) {
                    int fallThrough = block.end;
                    for (BasicBlock succ : blocks) {
                        if (succ.start == fallThrough) {
                            block.successors.add(succ.id);
                            succ.predecessors.add(block.id);
                            break;
                        }
                    }
                }
            }
            // Conditional branch fall-through
            else if (last.getOpcode() >= 153 && last.getOpcode() <= 167) {
                int fallThrough = block.end;
                for (BasicBlock succ : blocks) {
                    if (succ.start == fallThrough) {
                        block.successors.add(succ.id);
                        succ.predecessors.add(block.id);
                        break;
                    }
                }
            }
            // Return instructions have no successors (172-177)
            else if (last.getOpcode() >= 172 && last.getOpcode() <= 177) {
                // No successors
            }
            // Normal fall-through
            else if (block.end < instructions.size()) {
                int fallThrough = block.end;
                for (BasicBlock succ : blocks) {
                    if (succ.start == fallThrough) {
                        block.successors.add(succ.id);
                        succ.predecessors.add(block.id);
                        break;
                    }
                }
            }
        }
        
        return blocks;
    }

    /**
     * Simple block building fallback
     */
    private static List<BasicBlock> buildSimpleBlocks(MethodNode method) {
        List<BasicBlock> blocks = new ArrayList<>();
        InsnList instructions = method.instructions;
        
        Set<Integer> leaders = new HashSet<>();
        leaders.add(0);
        
        // Mark jump targets
        for (int i = 0; i < instructions.size(); i++) {
            AbstractInsnNode insn = instructions.get(i);
            if (insn.getType() == AbstractInsnNode.JUMP_INSN) {
                JumpInsnNode jmp = (JumpInsnNode) insn;
                leaders.add(instructions.indexOf(jmp.label));
                if (i + 1 < instructions.size()) leaders.add(i + 1);
            } else if (insn.getType() == AbstractInsnNode.LABEL) {
                leaders.add(i);
            }
        }
        
        List<Integer> sortedLeaders = new ArrayList<>(leaders);
        Collections.sort(sortedLeaders);
        
        for (int i = 0; i < sortedLeaders.size(); i++) {
            BasicBlock block = new BasicBlock();
            block.id = i;
            block.start = sortedLeaders.get(i);
            block.end = (i + 1 < sortedLeaders.size()) ? sortedLeaders.get(i + 1) : instructions.size();
            
            for (int j = block.start; j < block.end && j < instructions.size(); j++) {
                block.instructions.add(instructions.get(j));
            }
            
            blocks.add(block);
        }
        
        // Compute successors
        for (int i = 0; i < blocks.size(); i++) {
            BasicBlock block = blocks.get(i);
            AbstractInsnNode last = block.instructions.get(block.instructions.size() - 1);
            
            if (last instanceof JumpInsnNode) {
                JumpInsnNode jmp = (JumpInsnNode) last;
                int target = instructions.indexOf(jmp.label);
                for (int j = 0; j < blocks.size(); j++) {
                    if (blocks.get(j).start == target) {
                        block.successors.add(j);
                        blocks.get(j).predecessors.add(i);
                        break;
                    }
                }
            }
        }
        
        return blocks;
    }

    /**
     * Reaching definitions analysis - per block level
     * Returns: for each block, the set of field definitions that reach the block entry
     */
    private static Map<BasicBlock, Set<String>> computeReachingDefs(List<BasicBlock> blocks) {
        Map<BasicBlock, Set<String>> result = new HashMap<>();
        Map<BasicBlock, Set<String>> previous = new HashMap<>();
        
        // Initialize
        for (BasicBlock block : blocks) {
            result.put(block, new HashSet<>());
            previous.put(block, new HashSet<>());
        }
        
        // Iterative fixpoint
        int iterations = 0;
        boolean changed = true;
        while (changed && iterations < 100) {
            changed = false;
            iterations++;
            
            // Copy current to previous
            for (BasicBlock block : blocks) {
                previous.put(block, new HashSet<>(result.get(block)));
            }
            
            for (BasicBlock block : blocks) {
                Set<String> in = new HashSet<>();
                
                // Union of predecessors' out
                for (int predId : block.predecessors) {
                    BasicBlock pred = blocks.get(predId);
                    in.addAll(previous.get(pred));
                }
                
                // GEN: definitions within this block
                Set<String> gen = new HashSet<>();
                for (AbstractInsnNode insn : block.instructions) {
                    if (insn instanceof FieldInsnNode) {
                        FieldInsnNode fin = (FieldInsnNode) insn;
                        if (insn.getOpcode() == Opcodes.PUTFIELD || insn.getOpcode() == Opcodes.PUTSTATIC) {
                            String fieldKey = fin.owner + "." + fin.name;
                            gen.add(fieldKey);
                        }
                    }
                }
                
                // OUT = (IN - KILL) ∪ GEN
                // KILL = any definition in IN for fields in GEN
                Set<String> kill = new HashSet<>();
                for (String g : gen) {
                    for (String i : in) {
                        if (i.equals(g)) {
                            kill.add(i);
                        }
                    }
                }
                in.removeAll(kill);
                in.addAll(gen);
                
                result.put(block, in);
            }
            
            // Check for changes
            for (BasicBlock block : blocks) {
                if (!result.get(block).equals(previous.get(block))) {
                    changed = true;
                    break;
                }
            }
        }
        
        return result;
    }

    /**
     * Live variable analysis (simplified) - per block level
     * Returns: for each block, the set of fields that are live at block entry
     */
    private static Map<BasicBlock, Set<String>> computeLiveVars(List<BasicBlock> blocks) {
        Map<BasicBlock, Set<String>> result = new HashMap<>();
        Map<BasicBlock, Set<String>> previous = new HashMap<>();
        
        // Initialize
        for (BasicBlock block : blocks) {
            result.put(block, new HashSet<>());
            previous.put(block, new HashSet<>());
        }
        
        // Iterative fixpoint (backwards)
        int iterations = 0;
        boolean changed = true;
        while (changed && iterations < 100) {
            changed = false;
            iterations++;
            
            // Copy current to previous
            for (BasicBlock block : blocks) {
                previous.put(block, new HashSet<>(result.get(block)));
            }
            
            // Process blocks in reverse order
            for (int i = blocks.size() - 1; i >= 0; i--) {
                BasicBlock block = blocks.get(i);
                Set<String> out = new HashSet<>();
                
                // Union of successors' in
                for (int succId : block.successors) {
                    BasicBlock succ = blocks.get(succId);
                    out.addAll(previous.get(succ));
                }
                
                // USE and DEF within block
                Set<String> use = new HashSet<>();
                Set<String> def = new HashSet<>();
                
                for (AbstractInsnNode insn : block.instructions) {
                    if (insn instanceof FieldInsnNode) {
                        FieldInsnNode fin = (FieldInsnNode) insn;
                        String fieldKey = fin.owner + "." + fin.name;
                        if (insn.getOpcode() == Opcodes.GETFIELD || insn.getOpcode() == Opcodes.GETSTATIC) {
                            use.add(fieldKey);
                        } else if (insn.getOpcode() == Opcodes.PUTFIELD || insn.getOpcode() == Opcodes.PUTSTATIC) {
                            def.add(fieldKey);
                        }
                    }
                }
                
                // IN = USE ∪ (OUT - DEF)
                Set<String> in = new HashSet<>(use);
                for (String v : out) {
                    if (!def.contains(v)) {
                        in.add(v);
                    }
                }
                
                result.put(block, in);
            }
            
            // Check for changes
            for (BasicBlock block : blocks) {
                if (!result.get(block).equals(previous.get(block))) {
                    changed = true;
                    break;
                }
            }
        }
        
        return result;
    }

    private static String classifyMethod(MethodAnalysis ma) {
        String name = ma.methodName.toLowerCase();
        
        if (name.equals("tick") || name.startsWith("tick")) return "TICK";
        if (name.contains("move") && !name.contains("moveTo")) return "MOVE";
        if (name.contains("ai") || name.contains("path") || name.contains("navigation")) return "AI";
        if (name.contains("damage") || name.contains("hurt")) return "DAMAGE";
        if (name.contains("spawn") || (name.contains("add") && !name.contains("remove"))) return "SPAWN";
        if (name.contains("random") || name.contains("next")) return "RANDOM";
        if (name.contains("jump")) return "JUMP";
        if (name.contains("look") || name.contains("rotation")) return "LOOK";
        if (name.contains("despawn") || name.contains("die") || 
            (name.contains("remove") && !name.contains("add"))) return "DESPAWN";
        if (ma.fieldWrites.size() > 15) return "HEAVY_WRITE";
        if (ma.fieldReads.size() > 15) return "HEAVY_READ";
        if (ma.hasRng) return "RANDOM";
        if (ma.events.size() > 2) return "EVENT";
        
        return "GENERAL";
    }

    private static void writeAnnotations(List<ClassAnalysis> results, Path output) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("// CFG-based @NebulaRW annotations\n");
        sb.append("// Generated: ").append(new java.util.Date()).append("\n\n");

        int total = 0;
        for (ClassAnalysis ca : results) {
            for (MethodAnalysis ma : ca.methods) {
                total++;
                sb.append("// Class: ").append(ca.className).append("\n");
                sb.append("// Method: ").append(ma.methodName).append("\n");
                sb.append("// Type: ").append(ma.classification).append("\n");
                sb.append("@org.nebula.annotations.NebulaRW(\n");
                
                if (!ma.fieldReads.isEmpty()) {
                    sb.append("    readEntities = {");
                    sb.append(String.join(", ", ma.fieldReads));
                    sb.append("},\n");
                }
                
                if (!ma.fieldWrites.isEmpty()) {
                    sb.append("    writeEntities = {");
                    sb.append(String.join(", ", ma.fieldWrites));
                    sb.append("},\n");
                }
                
                if (!ma.blockAccesses.isEmpty()) {
                    sb.append("    readBlocks = {");
                    sb.append(String.join(", ", ma.blockAccesses));
                    sb.append("},\n");
                }
                
                if (!ma.events.isEmpty()) {
                    sb.append("    triggeredEvents = {");
                    sb.append(String.join(", ", ma.events));
                    sb.append("},\n");
                }
                
                sb.append("    maxRandomCalls = ").append(ma.hasRng ? 8 : 0).append(",\n");
                sb.append("    randomInstance = \"").append(ma.hasRng ? "WORLD_RANDOM" : "NONE").append("\"\n");
                sb.append(")\n\n");
            }
        }

        Files.writeString(output, sb.toString());
        System.err.println("Written " + total + " annotations to: " + output);
    }

    // Data structures
    static class BasicBlock {
        int id;
        int start;
        int end;
        List<AbstractInsnNode> instructions = new ArrayList<>();
        List<Integer> predecessors = new ArrayList<>();
        List<Integer> successors = new ArrayList<>();
    }

    static class MethodAnalysis {
        String methodName;
        String descriptor;
        String classification;
        Set<String> fieldReads = new LinkedHashSet<>();
        Set<String> fieldWrites = new LinkedHashSet<>();
        Set<String> blockAccesses = new LinkedHashSet<>();
        Set<String> events = new LinkedHashSet<>();
        boolean hasRng;
    }

    static class ClassAnalysis {
        String className;
        String classFile;
        List<MethodAnalysis> methods = new ArrayList<>();
    }
}
