package org.nebula.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM transformer for Folia's NeighborUpdater.executeUpdate static method.
 *
 * <p>Folia 26.1.2 routes neighbor updates through the static method
 * {@code NeighborUpdater.executeUpdate(Level, BlockState, BlockPos, Block, Orientation, boolean)}
 * rather than through {@code CollectingNeighborUpdater.addAndRun}.
 * This transformer instruments executeUpdate to call
 * {@link NeighborUpdateHooks#onNeighborUpdate(Object, Object)} before
 * the existing logic.
 */
public final class ExecuteUpdateTransformer {

    public static final String TARGET_CLASS =
        "net/minecraft/world/level/redstone/NeighborUpdater";

    // executeUpdate(Level, BlockState, BlockPos, Block, Orientation, boolean)
    public static final String TARGET_METHOD_NAME = "executeUpdate";
    // executeUpdate(Level, BlockState, BlockPos, Block, Orientation, boolean)
    public static final String TARGET_METHOD_DESC =
        "(Lnet/minecraft/world/level/Level;" +
        "Lnet/minecraft/world/level/block/state/BlockState;" +
        "Lnet/minecraft/core/BlockPos;" +
        "Lnet/minecraft/world/level/block/Block;" +
        "Lnet/minecraft/world/level/redstone/Orientation;" +
        "Z)V";
    // executeUpdate(Level, BlockState, BlockPos, Block, Orientation, boolean, BlockPos)
    public static final String TARGET_METHOD_DESC_2 =
        "(Lnet/minecraft/world/level/Level;" +
        "Lnet/minecraft/world/level/block/state/BlockState;" +
        "Lnet/minecraft/core/BlockPos;" +
        "Lnet/minecraft/world/level/block/Block;" +
        "Lnet/minecraft/world/level/redstone/Orientation;" +
        "ZLnet/minecraft/core/BlockPos;)V";

    private ExecuteUpdateTransformer() {}

    public static byte[] maybeInstrument(String className, byte[] bytecode) {
        if (!TARGET_CLASS.equals(className)) return bytecode;
        try {
            ClassReader reader = new ClassReader(bytecode);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new CV(writer), 0);
            return writer.toByteArray();
        } catch (Exception e) {
            System.err.println("[Nebula/ExecuteUpdateTransformer] Failed: " + e);
            return bytecode;
        }
    }

    private static final class CV extends ClassVisitor {
        CV(ClassVisitor cv) { super(Opcodes.ASM9, cv); }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                          String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (TARGET_METHOD_NAME.equals(name) &&
                (TARGET_METHOD_DESC.equals(descriptor) || TARGET_METHOD_DESC_2.equals(descriptor))) {
                return new MV(mv);
            }
            return mv;
        }
    }

    private static final class MV extends MethodVisitor {
        MV(MethodVisitor mv) { super(Opcodes.ASM9, mv); }

        @Override
        public void visitCode() {
            super.visitCode();


            // Load Level (arg 0)
            super.visitVarInsn(Opcodes.ALOAD, 0);
            // Load BlockPos (arg 2)
            super.visitVarInsn(Opcodes.ALOAD, 2);
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
}