package org.nebula.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM transformer for Folia's RedstoneWireTurbo.
 *
 * <p>Folia 26.1.2 uses RedstoneWireTurbo to optimize redstone wire updates,
 * bypassing the standard NeighborUpdater path. This transformer instruments
 * the key entry points to call NeighborUpdateHooks.
 */
public final class RedstoneWireTurboTransformer {

    public static final String TARGET_CLASS =
        "io/papermc/paper/redstone/RedstoneWireTurbo";

    // updateSurroundingRedstone(Level, BlockPos, BlockState, BlockPos) → BlockState
    public static final String METHOD_UPDATE_SURROUNDING = "updateSurroundingRedstone";
    public static final String DESC_UPDATE_SURROUNDING =
        "(Lnet/minecraft/world/level/Level;" +
        "Lnet/minecraft/core/BlockPos;" +
        "Lnet/minecraft/world/level/block/state/BlockState;" +
        "Lnet/minecraft/core/BlockPos;)" +
        "Lnet/minecraft/world/level/block/state/BlockState;";

    // updateNeighborShapes(Level, BlockPos, BlockState)
    public static final String METHOD_NEIGHBOR_SHAPES = "updateNeighborShapes";
    public static final String DESC_NEIGHBOR_SHAPES =
        "(Lnet/minecraft/world/level/Level;" +
        "Lnet/minecraft/core/BlockPos;" +
        "Lnet/minecraft/world/level/block/state/BlockState;)V";

    private RedstoneWireTurboTransformer() {}

    public static byte[] maybeInstrument(String className, byte[] bytecode) {
        if (!TARGET_CLASS.equals(className)) return bytecode;
        try {
            ClassReader reader = new ClassReader(bytecode);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new CV(writer), 0);
            return writer.toByteArray();
        } catch (Exception e) {
            System.err.println("[Nebula/RedstoneWireTurboTransformer] Failed: " + e);
            return bytecode;
        }
    }

    private static final class CV extends ClassVisitor {
        CV(ClassVisitor cv) { super(Opcodes.ASM9, cv); }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                          String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (METHOD_UPDATE_SURROUNDING.equals(name) && DESC_UPDATE_SURROUNDING.equals(descriptor)) {
                return new UpdateSurroundingMV(mv);
            }
            if (METHOD_NEIGHBOR_SHAPES.equals(name) && DESC_NEIGHBOR_SHAPES.equals(descriptor)) {
                return new NeighborShapesMV(mv);
            }
            return mv;
        }
    }

    /** Instruments updateSurroundingRedstone — called when wire power changes. */
    private static final class UpdateSurroundingMV extends MethodVisitor {
        UpdateSurroundingMV(MethodVisitor mv) { super(Opcodes.ASM9, mv); }

        @Override
        public void visitCode() {
            super.visitCode();

            // Set sentinel: hooksActive = true
            super.visitInsn(Opcodes.ICONST_1);
            super.visitFieldInsn(
                Opcodes.PUTSTATIC,
                "org/nebula/agent/NeighborUpdateHooks",
                "hooksActive",
                "Z"
            );

            // Load Level (arg 0)
            super.visitVarInsn(Opcodes.ALOAD, 0);
            // Load BlockPos (arg 1)
            super.visitVarInsn(Opcodes.ALOAD, 1);
            // Call NeighborUpdateHooks.onNeighborUpdate(Object, Object)
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "org/nebula/agent/NeighborUpdateHooks",
                "onNeighborUpdate",
                "(Ljava/lang/Object;Ljava/lang/Object;)V",
                false
            );
        }
    }

    /** Instruments updateNeighborShapes — called for shape updates. */
    private static final class NeighborShapesMV extends MethodVisitor {
        NeighborShapesMV(MethodVisitor mv) { super(Opcodes.ASM9, mv); }

        @Override
        public void visitCode() {
            super.visitCode();

            // Set sentinel: hooksActive = true
            super.visitInsn(Opcodes.ICONST_1);
            super.visitFieldInsn(
                Opcodes.PUTSTATIC,
                "org/nebula/agent/NeighborUpdateHooks",
                "hooksActive",
                "Z"
            );

            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitVarInsn(Opcodes.ALOAD, 1);
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