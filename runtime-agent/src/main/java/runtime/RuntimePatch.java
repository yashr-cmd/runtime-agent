package runtime;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public class RuntimePatch {

    private static final String HELPER =
            "net/mcreator/transfinityimproved/coremod/PreatorGodHelper";

    // =========================================================================
    //  ENTRY POINTS
    // =========================================================================

    public static byte[] patchLivingEntity(byte[] bytes) {
        ClassNode cn = read(bytes);

        for (MethodNode m : cn.methods) {
            switch (m.name) {


                case "hurt",          "m_6469_"  -> injectCancelBoolean(m, "isGod",
                        "(Lnet/minecraft/world/entity/LivingEntity;)Z");

                case "actuallyHurt",  "m_6478_"  -> injectCancelVoid(m, "isGod",
                        "(Lnet/minecraft/world/entity/LivingEntity;)Z");


                case "setHealth",     "m_21153_" -> injectSetHealth(m);


                case "die",           "m_6667_"  -> injectDie(m);


                case "kill",          "m_6675_"  -> injectCancelVoid(m, "isGod",
                        "(Lnet/minecraft/world/entity/LivingEntity;)Z");


                case "tick",          "m_8119_"  -> injectTick(m);


                case "isDeadOrDying", "m_6060_"  -> injectIsDeadOrDying(m);

                case "outOfWorld",    "m_8077_"  -> injectCancelVoid(m, "isGod",
                        "(Lnet/minecraft/world/entity/LivingEntity;)Z");

                case "checkFallDamage","m_20121_" -> injectCancelVoid(m, "isGod",
                        "(Lnet/minecraft/world/entity/LivingEntity;)Z");
            }
        }
        return write(cn);
    }
    public static byte[] patchEntity(byte[] bytes) {
        ClassNode cn = read(bytes);
        for (MethodNode m : cn.methods) {
            switch (m.name) {

                case "remove",     "m_142687_" -> injectEntityRemove(m);

                case "setRemoved", "m_6925_"   -> injectEntityRemove(m);

                case "discard",    "m_142682_" -> injectEntityRemove(m);
            }
        }
        return write(cn);
    }
    public static byte[] patchSynchedEntityData(byte[] bytes) {
        ClassNode cn = read(bytes);
        for (MethodNode m : cn.methods) {
            if ((m.name.equals("set") || m.name.equals("m_135381_"))
                    && m.desc.equals(
                    "(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;)V")) {
                injectEntityDataSet(m);
            }
        }
        return write(cn);
    }
    public static byte[] patchServerPlayer(byte[] bytes) {
        ClassNode cn = read(bytes);
        for (MethodNode m : cn.methods) {
            switch (m.name) {

                case "die", "m_6667_" -> injectPlayerDie(m);

                case "setGameMode", "m_7284_" -> injectBlockGameMode(m);
            }
        }
        return write(cn);
    }
    public static byte[] patchEntityCallback(byte[] bytes) {
        ClassNode cn = read(bytes);

        // Locate the Entity field dynamically (works across mappings).
        String entityField = null;
        for (FieldNode f : cn.fields) {
            if (f.desc.equals("Lnet/minecraft/world/entity/Entity;")) {
                entityField = f.name;
                break;
            }
        }
        if (entityField == null) return bytes; // safe fail

        final String capturedField = entityField;
        for (MethodNode m : cn.methods) {
            if (m.desc.contains("RemovalReason")) {
                injectCallbackGuard(m, capturedField, cn.name);
            }
        }
        return write(cn);
    }

    // =========================================================================
    //  INJECTORS
    // =========================================================================

    private static void injectCancelBoolean(MethodNode m, String check, String desc) {
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, check, desc, false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new InsnNode(Opcodes.IRETURN));
        il.add(skip);
        m.instructions.insert(il);
    }
    private static void injectCancelVoid(MethodNode m, String check, String desc) {
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, check, desc, false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new InsnNode(Opcodes.RETURN));
        il.add(skip);
        m.instructions.insert(il);
    }
    private static void injectSetHealth(MethodNode m) {
        InsnList il = new InsnList();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.FLOAD, 1));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "safeSetHealth",
                "(Lnet/minecraft/world/entity/LivingEntity;F)F", false));
        il.add(new VarInsnNode(Opcodes.FSTORE, 1));
        m.instructions.insert(il);
    }
    private static void injectEntityDataSet(MethodNode m) {
        InsnList il = new InsnList();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "interceptEntityDataSet",
                "(Lnet/minecraft/network/syncher/SynchedEntityData;" +
                        "Lnet/minecraft/network/syncher/EntityDataAccessor;" +
                        "Ljava/lang/Object;)Ljava/lang/Object;", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 2));
        m.instructions.insert(il);
    }
    private static void injectDie(MethodNode m) {
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "isGod",
                "(Lnet/minecraft/world/entity/LivingEntity;)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "restoreHealth",
                "(Lnet/minecraft/world/entity/LivingEntity;)V", false));
        il.add(new InsnNode(Opcodes.RETURN));
        il.add(skip);
        m.instructions.insert(il);
    }
    private static void injectEntityRemove(MethodNode m) {
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "isGodEntity",
                "(Lnet/minecraft/world/entity/Entity;)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new InsnNode(Opcodes.RETURN));
        il.add(skip);
        m.instructions.insert(il);
    }
    private static void injectTick(MethodNode m) {
        InsnList il = new InsnList();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "godTick",
                "(Lnet/minecraft/world/entity/LivingEntity;)V", false));
        m.instructions.insert(il);
    }
    private static void injectIsDeadOrDying(MethodNode m) {
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "isGod",
                "(Lnet/minecraft/world/entity/LivingEntity;)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new InsnNode(Opcodes.IRETURN));
        il.add(skip);
        m.instructions.insert(il);
    }
    private static void injectPlayerDie(MethodNode m) {
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "isGod",
                "(Lnet/minecraft/world/entity/LivingEntity;)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "restoreHealth",
                "(Lnet/minecraft/world/entity/LivingEntity;)V", false));
        il.add(new InsnNode(Opcodes.RETURN));
        il.add(skip);
        m.instructions.insert(il);
    }
    private static void injectBlockGameMode(MethodNode m) {
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1)); // GameType — passed as Object
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "shouldBlockGameMode",
                "(Lnet/minecraft/world/entity/Entity;Ljava/lang/Object;)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new InsnNode(Opcodes.RETURN));
        il.add(skip);
        m.instructions.insert(il);
    }
    private static void injectCallbackGuard(MethodNode m, String entityField, String ownerClass) {
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new FieldInsnNode(Opcodes.GETFIELD, ownerClass, entityField,
                "Lnet/minecraft/world/entity/Entity;"));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "isGodEntity",
                "(Lnet/minecraft/world/entity/Entity;)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new InsnNode(Opcodes.RETURN));
        il.add(skip);
        m.instructions.insert(il);
    }

    // =========================================================================
    //  UTIL
    // =========================================================================

    private static ClassNode read(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        return cn;
    }

    private static byte[] write(ClassNode cn) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        return cw.toByteArray();
    }
}