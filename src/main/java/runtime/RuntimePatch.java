package runtime;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;

public class RuntimePatch {

    private static final String HELPER = "net/mcreator/transfinityimproved/coremod/PreatorGodHelper";

    public static byte[] patchPig2Class(byte[] bytes, String className) {
        ClassNode cn = read(bytes);

        String name = cn.name.toLowerCase();
        if (name.contains("kakiku") || name.contains("pig2mod") || name.contains("myxformer") ||
                name.contains("myscheduled") || name.contains("myxform")) {

            System.out.println("[NUCLEAR] defended against pig2 class: " + cn.name);

            for (MethodNode m : new ArrayList<>(cn.methods)) {
                if (!m.name.equals("<init>") && !m.name.equals("<clinit>")) {
                    makeMethodNoOp(m);
                }
            }

            cn.fields.clear();
            cn.methods.removeIf(m -> m.name.equals("<clinit>"));
            return write(cn);
        }

        return patchClass(bytes, RuntimePatch::patchPig2Methods);
    }

    private static void patchPig2Methods(ClassNode cn) {
        boolean patched = false;
        for (MethodNode m : cn.methods) {
            if (isPig2HostileMethod(m.name)) {
                makeMethodNoOp(m);
                patched = true;
                System.out.println("[RuntimePatch] Neutered pig2 method: "
                        + cn.name + "." + m.name + m.desc);
            }
        }
        if (!patched) {
            for (MethodNode m : cn.methods) {
                if (!m.name.equals("<init>") && !m.name.equals("<clinit>")) {
                    makeMethodNoOp(m);
                }
            }
            if (!cn.methods.isEmpty())
                System.out.println("[RuntimePatch] Neutered all methods in pig2 class: " + cn.name);
        }
    }

    //Returns true for pig2 methods that do active damage to our agent/launcher.
    private static boolean isPig2HostileMethod(String name) {
        return switch (name) {
            case "stopBadThreads",
                 "killOtherXform",
                 "killOtherXformFromMainThread",
                 "killOtherXformFromMyThread",
                 "keepKillingBeforeMod",
                 "keepKillingAfterMod",
                 "invalidOtherXformService",
                 // --- event bus attack methods ---
                 "unregisterForgeEventBus",
                 "unregisterEventSubscriptionByOtherBadMOD",
                 "resetEventSubscriptionByOtherMOD",
                 "registerForgeEventBus",
                 "preventForgeEventAttack",
                 "preventForgeEventUnregisterAttack",
                 "lambda$preventForgeEventAttack$0",
                 // --- anti-tamper / integrity ---
                 "checkIntegrity",
                 "verifyIntegrity",
                 "run", "call", "execute"
                    -> true;
            default -> false;
        };
    }

    /**
     * Replaces a method body with a minimal return instruction appropriate for
     * its return type. Clears all try-catch blocks too so no exception handlers
     * can intercept our return.
     */
    private static void makeMethodNoOp(MethodNode m) {
        m.instructions.clear();
        m.tryCatchBlocks.clear();
        m.localVariables = null;

        Type returnType = Type.getReturnType(m.desc);
        switch (returnType.getSort()) {
            case Type.VOID ->
                m.instructions.add(new InsnNode(Opcodes.RETURN));
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                m.instructions.add(new InsnNode(Opcodes.ICONST_0));
                m.instructions.add(new InsnNode(Opcodes.IRETURN));
            }
            case Type.LONG -> {
                m.instructions.add(new InsnNode(Opcodes.LCONST_0));
                m.instructions.add(new InsnNode(Opcodes.LRETURN));
            }
            case Type.FLOAT -> {
                m.instructions.add(new InsnNode(Opcodes.FCONST_0));
                m.instructions.add(new InsnNode(Opcodes.FRETURN));
            }
            case Type.DOUBLE -> {
                m.instructions.add(new InsnNode(Opcodes.DCONST_0));
                m.instructions.add(new InsnNode(Opcodes.DRETURN));
            }
            default -> {
                m.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
                m.instructions.add(new InsnNode(Opcodes.ARETURN));
            }
        }
        m.maxStack = 1;
        m.maxLocals = Math.max(1, m.maxLocals);
    }

    //MC patching

    public static byte[] patchLivingEntity(byte[] bytes) {
        return patchClass(bytes, RuntimePatch::patchLivingEntityMethods);
    }

    public static byte[] patchEntity(byte[] bytes) {
        return patchClass(bytes, RuntimePatch::patchEntityMethods);
    }

    public static byte[] patchSynchedEntityData(byte[] bytes) {
        return patchClass(bytes, RuntimePatch::patchSynchedEntityDataMethods);
    }

    public static byte[] patchServerPlayer(byte[] bytes) {
        return patchClass(bytes, RuntimePatch::patchServerPlayerMethods);
    }

    public static byte[] patchEntityCallback(byte[] bytes) {
        return patchClass(bytes, RuntimePatch::patchEntityCallbackMethods);
    }

    private static void patchLivingEntityMethods(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            switch (m.name) {
                case "hurt", "m_6469_" ->
                        injectCancelBoolean(m, "isGod", "(Lnet/minecraft/world/entity/LivingEntity;)Z");

                case "actuallyHurt", "m_6478_" ->
                        injectCancelVoid(m, "isGod", "(Lnet/minecraft/world/entity/LivingEntity;)Z");

                case "setHealth", "m_21153_" ->
                        injectSetHealth(m);

                case "die", "m_6667_" ->
                        injectDie(m);

                case "kill", "m_6675_" ->
                        injectCancelVoid(m, "isGod", "(Lnet/minecraft/world/entity/LivingEntity;)Z");

                case "tick", "m_8119_" ->
                        injectTick(m);

                case "isDeadOrDying", "m_6060_" ->
                        injectIsDeadOrDying(m);

                case "outOfWorld", "m_8077_" ->
                        injectCancelVoid(m, "isGod", "(Lnet/minecraft/world/entity/LivingEntity;)Z");

                case "checkFallDamage", "m_20121_" ->
                        injectCancelVoid(m, "isGod", "(Lnet/minecraft/world/entity/LivingEntity;)Z");
            }
        }
    }

    private static void patchEntityMethods(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            switch (m.name) {
                case "remove", "m_142687_",
                     "setRemoved", "m_6925_",
                     "discard", "m_142682_" ->
                        injectEntityRemove(m);
            }
        }
    }

    private static void patchSynchedEntityDataMethods(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if ((m.name.equals("set") || m.name.equals("m_135381_"))
                    && m.desc.equals("(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;)V")) {
                injectEntityDataSet(m);
            }
        }
    }

    private static void patchServerPlayerMethods(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            switch (m.name) {
                case "die", "m_6667_" ->
                        injectPlayerDie(m);

                case "setGameMode", "m_7284_" ->
                        injectBlockGameMode(m);

                case "hurt", "m_6469_" ->
                        injectCancelBoolean(m, "isGod", "(Lnet/minecraft/world/entity/LivingEntity;)Z");
            }
        }
    }

    private static void patchEntityCallbackMethods(ClassNode cn) {
        String entityField = null;
        for (FieldNode f : cn.fields) {
            if (f.desc.equals("Lnet/minecraft/world/entity/Entity;")) {
                entityField = f.name;
                break;
            }
        }
        if (entityField == null) return;

        final String capturedField = entityField;

        for (MethodNode m : cn.methods) {
            if (m.desc.contains("RemovalReason")) {
                injectCallbackGuard(m, capturedField, cn.name);
            }
        }
    }

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
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
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


    // -----------------------------------------------------------------------
    // ServerGamePacketListenerImpl.disconnect() guard
    // pig2's MyXformData calls disconnect() directly to kick the player with
    // "You were killed by Pig2". We inject a check at the top: if the caller
    // stack contains any kakiku/pig2 frame, we swallow the call silently.
    // -----------------------------------------------------------------------
    public static byte[] patchServerGamePacketListener(byte[] bytes) {
        return patchClass(bytes, cn -> {
            for (MethodNode m : cn.methods) {
                if (m.name.equals("disconnect") || m.name.equals("m_143402_")) {
                    injectDisconnectGuard(m);
                    System.out.println("[RuntimePatch] Patched ServerGamePacketListenerImpl.disconnect() against pig2 kick");
                }
            }
        });
    }

    private static void injectDisconnectGuard(MethodNode m) {
        // Inject at top:
        //   if (Pig2ThreadKiller.isCallerPig2()) return;
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "runtime/ThreadSanitizer", "isCallerPig2", "()Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new InsnNode(Opcodes.RETURN));
        il.add(skip);
        m.instructions.insert(il);
    }

    private static byte[] patchClass(byte[] bytes, java.util.function.Consumer<ClassNode> patcher) {
        ClassNode cn = read(bytes);
        try {
            patcher.accept(cn);
            return write(cn);
        } catch (Exception e) {
            System.err.println("[RuntimePatch] Failed patching " + cn.name + ": " + e.getMessage());
            e.printStackTrace();
            return bytes;
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return cn;
    }

    private static byte[] write(ClassNode cn) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                ClassLoader primary = Thread.currentThread().getContextClassLoader();
                ClassLoader fallback = RuntimePatch.class.getClassLoader();
                for (ClassLoader cl : new ClassLoader[]{ primary, fallback }) {
                    if (cl == null) continue;
                    try {
                        Class<?> c1 = Class.forName(type1.replace('/', '.'), false, cl);
                        Class<?> c2 = Class.forName(type2.replace('/', '.'), false, cl);

                        if (c1.isAssignableFrom(c2)) return type1;
                        if (c2.isAssignableFrom(c1)) return type2;

                        Class<?> cur = c1;
                        while (cur != null && !cur.isAssignableFrom(c2)) {
                            cur = cur.getSuperclass();
                        }
                        return cur != null ? cur.getName().replace('.', '/') : "java/lang/Object";
                    } catch (Throwable ignored) {}
                }
                return "java/lang/Object";
            }
        };

        cn.accept(cw);
        return cw.toByteArray();
    }
}