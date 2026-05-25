package org.nebula.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * ASM transformer for Folia CollectingNeighborUpdater.
 * Instruments the neighbor-update dispatch entry point to call
 * NeighborUpdateHooks.onNeighborUpdate(world, blockPos) before
 * any existing logic, enabling OBSERVE/INTERCEPT mode in Nebula.
 * Target: net/minecraft/world/level/redstone/CollectingNeighborUpdater
 * Method: a(BlockPosition, Block, Orientation) - obfuscated NMS
 */
public final class NeighborUpdateTransformer {

    /** Internal class name of the target (obfuscated NMS name in the remapped jar). */
    public static final String TARGET_CLASS =
        "net/minecraft/world/level/redstone/CollectingNeighborUpdater";

    /**
     * Descriptor of the target method:
     * {@code a(BlockPosition, Block, Orientation)} has descriptor
     * {@code (Lnet/minecraft/core/BlockPosition;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;)V}
     */
    // addAndRun is the single funnel for ALL neighbor update types
    public static final String TARGET_METHOD_NAME = "addAndRun";
    // addAndRun(BlockPos, NeighborUpdates)V
    public static final String TARGET_METHOD_DESC =
        "(Lnet/minecraft/core/BlockPos;" +
        "Lnet/minecraft/world/level/redstone/CollectingNeighborUpdater$NeighborUpdates;)V";

    // Mojang-mapped field name and descriptor
    public static final String WORLD_FIELD = "level";
    public static final String WORLD_FIELD_DESC =
        "Lnet/minecraft/world/level/Level;";

    private NeighborUpdateTransformer() {}

    /**
     * Instruments the given bytecode if it belongs to the target class.
     *
     * @param className internal class name (slashes)
     * @param bytecode  original class bytes
     * @return instrumented bytes, or the original if this class is not targeted
     */
    public static byte[] maybeInstrument(String className, byte[] bytecode) {
        if (!TARGET_CLASS.equals(className)) {
            return bytecode;
        }
        try {
            ClassReader reader = new ClassReader(bytecode);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new NeighborUpdaterClassVisitor(writer), 0);
            return writer.toByteArray();
        } catch (Exception e) {
            // Never break the server — log and return original bytes
            System.err.println("[Nebula/NeighborUpdateTransformer] Instrumentation failed: " + e);
            return bytecode;
        }
    }

    // ── ASM visitors ─────────────────────────────────────────────────────────

    private static final class NeighborUpdaterClassVisitor extends ClassVisitor {
        NeighborUpdaterClassVisitor(ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (TARGET_METHOD_NAME.equals(name) && TARGET_METHOD_DESC.equals(descriptor)) {
                return new NeighborUpdateMethodVisitor(mv);
            }
            return mv;
        }
    }

    /**
     * Inserts NeighborUpdateHooks.onNeighborUpdate(world, blockPos) at method start.
     * Loads this.c (World field) and arg1 (BlockPosition) before existing bytecode.
     */
    private static final class NeighborUpdateMethodVisitor extends MethodVisitor {
        NeighborUpdateMethodVisitor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitCode() {
            super.visitCode();

            // Load this.c (the World field) → arg 0 = this
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, WORLD_FIELD, WORLD_FIELD_DESC);

            // Load blockPos argument (arg 1)
            super.visitVarInsn(Opcodes.ALOAD, 1);

            // Call NeighborUpdateHooks.onNeighborUpdate(World, BlockPosition)
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "org/nebula/agent/NeighborUpdateHooks",
                "onNeighborUpdate",
                "(Ljava/lang/Object;Ljava/lang/Object;)V",
                false
            );
        }
    }
}
