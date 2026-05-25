package org.nebula.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM transformer for InstantNeighborUpdater.neighborChanged().
 * This updater is used when a block is directly changed (e.g. /setblock, pistons),
 * bypassing CollectingNeighborUpdater's queue.
 */
public final class InstantNeighborUpdateTransformer {

    public static final String TARGET_CLASS =
        "net/minecraft/world/level/redstone/InstantNeighborUpdater";

    // neighborChanged(BlockPos, Block, Orientation)V
    public static final String TARGET_METHOD_NAME = "neighborChanged";
    public static final String TARGET_METHOD_DESC =
        "(Lnet/minecraft/core/BlockPos;" +
        "Lnet/minecraft/world/level/block/Block;" +
        "Lnet/minecraft/world/level/redstone/Orientation;)V";

    public static final String WORLD_FIELD      = "level";
    public static final String WORLD_FIELD_DESC = "Lnet/minecraft/world/level/Level;";

    private InstantNeighborUpdateTransformer() {}

    public static byte[] maybeInstrument(String className, byte[] bytecode) {
        if (!TARGET_CLASS.equals(className)) return bytecode;
        try {
            ClassReader reader = new ClassReader(bytecode);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new CV(writer), 0);
            return writer.toByteArray();
        } catch (Exception e) {
            System.err.println("[Nebula/InstantNeighborUpdateTransformer] Failed: " + e);
            return bytecode;
        }
    }

    private static final class CV extends ClassVisitor {
        CV(ClassVisitor cv) { super(Opcodes.ASM9, cv); }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                          String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (TARGET_METHOD_NAME.equals(name) && TARGET_METHOD_DESC.equals(descriptor)) {
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
            // Load this.level (World)
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, WORLD_FIELD, WORLD_FIELD_DESC);
            // Load blockPos (arg 1)
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
}
