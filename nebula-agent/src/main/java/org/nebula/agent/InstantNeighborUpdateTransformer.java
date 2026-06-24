package org.nebula.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM transformer for InstantNeighborUpdater.neighborChanged().
 * This updater is used when a block is directly changed (e.g. /setblock, pistons),
 * bypassing CollectingNeighborUpdater's queue.
 *
 * <p>The World/Level field name is detected at transform time by scanning
 * declared fields for the expected type descriptor, so this transformer
 * works under both Mojang-mapped and obfuscated (Spigot/CraftBukkit) jars.</p>
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

    public static final String WORLD_FIELD_DESC = "Lnet/minecraft/world/level/Level;";

    private InstantNeighborUpdateTransformer() {}

    public static byte[] maybeInstrument(String className, byte[] bytecode) {
        if (!TARGET_CLASS.equals(className)) return bytecode;
        try {
            String worldFieldName = findLevelField(bytecode);
            if (worldFieldName == null) {
                System.err.println("[Nebula/InstantNeighborUpdateTransformer] Could not find Level field in " + TARGET_CLASS);
                return bytecode;
            }

            ClassReader reader = new ClassReader(bytecode);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new CV(writer, worldFieldName), 0);
            return writer.toByteArray();
        } catch (Exception e) {
            System.err.println("[Nebula/InstantNeighborUpdateTransformer] Failed: " + e);
            return bytecode;
        }
    }

    private static String findLevelField(byte[] bytecode) {
        ClassReader reader = new ClassReader(bytecode);
        String[] found = {null};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                if (WORLD_FIELD_DESC.equals(descriptor)) {
                    found[0] = name;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        return found[0];
    }

    private static final class CV extends ClassVisitor {
        private final String worldFieldName;

        CV(ClassVisitor cv, String worldFieldName) {
            super(Opcodes.ASM9, cv);
            this.worldFieldName = worldFieldName;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                          String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (TARGET_METHOD_NAME.equals(name) && TARGET_METHOD_DESC.equals(descriptor)) {
                return new MV(mv, worldFieldName);
            }
            return mv;
        }
    }

    private static final class MV extends MethodVisitor {
        private final String worldFieldName;

        MV(MethodVisitor mv, String worldFieldName) {
            super(Opcodes.ASM9, mv);
            this.worldFieldName = worldFieldName;
        }

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

            // Load this.{worldFieldName} (World)
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, worldFieldName, WORLD_FIELD_DESC);
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
