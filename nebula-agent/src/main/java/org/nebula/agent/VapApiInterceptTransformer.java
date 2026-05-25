package org.nebula.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM transformer that rewrites Bukkit/Paper API method calls in plugin classes
 * to route through VapApiInterceptor for Level 0 virtual thread suspension.
 *
 * <p>Detects calls to org.bukkit.* methods and wraps them into a
 * VapInterceptHooks.interceptCall(...) invocation that suspends the virtual
 * thread until the plugin phase executes the operation.
 */
public final class VapApiInterceptTransformer {

    static final String BUKKIT_PREFIX = "org/bukkit/";
    static final String PAPER_PREFIX = "io/papermc/paper/";
    static final String HOOKS_CLASS = "org/nebula/agent/VapInterceptHooks";

    private VapApiInterceptTransformer() {}

    public static boolean shouldTransform(String className) {
        if (className == null) return false;
        if (className.startsWith("org/nebula/")) return false;
        if (className.startsWith("java/")) return false;
        if (className.startsWith("javax/")) return false;
        if (className.startsWith("jdk/")) return false;
        if (className.startsWith("sun/")) return false;
        if (className.startsWith("net/minecraft/")) return false;
        if (className.startsWith(BUKKIT_PREFIX)) return false;
        if (className.startsWith(PAPER_PREFIX)) return false;
        return true;
    }

    public static byte[] instrument(String className, byte[] bytecode) {
        ClassReader reader = new ClassReader(bytecode);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = new VapApiClassVisitor(writer, className);
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    private static final class VapApiClassVisitor extends ClassVisitor {
        private final String ownerClass;

        VapApiClassVisitor(ClassVisitor delegate, String ownerClass) {
            super(Opcodes.ASM9, delegate);
            this.ownerClass = ownerClass;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                          String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new VapApiMethodVisitor(delegate, ownerClass);
        }
    }

    private static final class VapApiMethodVisitor extends MethodVisitor {
        private final String ownerClass;

        VapApiMethodVisitor(MethodVisitor delegate, String ownerClass) {
            super(Opcodes.ASM9, delegate);
            this.ownerClass = ownerClass;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                     String descriptor, boolean isInterface) {
            if (isBukkitApiCall(owner) && isWriteOperation(name)) {
                super.visitLdcInsn(ownerClass.replace('/', '.'));
                super.visitLdcInsn(owner + "." + name);
                super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    HOOKS_CLASS,
                    "beforeApiCall",
                    "(Ljava/lang/String;Ljava/lang/String;)V",
                    false
                );

                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);

                super.visitLdcInsn(ownerClass.replace('/', '.'));
                super.visitLdcInsn(owner + "." + name);
                super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    HOOKS_CLASS,
                    "afterApiCall",
                    "(Ljava/lang/String;Ljava/lang/String;)V",
                    false
                );
            } else {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
        }

        private boolean isBukkitApiCall(String owner) {
            return owner.startsWith(BUKKIT_PREFIX) || owner.startsWith(PAPER_PREFIX);
        }

        private boolean isWriteOperation(String name) {
            return name.startsWith("set")
                || name.startsWith("teleport")
                || name.startsWith("remove")
                || name.startsWith("add")
                || name.startsWith("send")
                || name.startsWith("kick")
                || name.startsWith("ban")
                || name.startsWith("spawn")
                || name.startsWith("create")
                || name.startsWith("delete")
                || name.startsWith("place")
                || name.startsWith("break")
                || name.startsWith("damage")
                || name.startsWith("heal")
                || name.startsWith("kill")
                || name.startsWith("give")
                || name.startsWith("take")
                || name.startsWith("update")
                || name.startsWith("save");
        }
    }
}
