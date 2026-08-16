package runtime;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RuntimePatch {
    private static final String HELPER = "runtime/GodHelper";

    public static byte[] patchHostileClass(byte[] bytes, String className) {
        ClassNode cn = read(bytes);

        String name = cn.name.toLowerCase();
        if (name.contains("kakiku") || name.contains("pig2mod") || name.contains("myxformer") ||
                name.contains("myscheduled") || name.contains("myxform")) {

            AgentLog.log("[NUCLEAR] defended against hostile class: " + cn.name);

            for (MethodNode m : new ArrayList<>(cn.methods)) {
                if (!m.name.equals("<init>") && !m.name.equals("<clinit>")) {
                    makeMethodNoOp(m);
                }
            }

            cn.fields.clear();
            cn.methods.removeIf(m -> m.name.equals("<clinit>"));
            return write(cn);
        }

        return patchClass(bytes, RuntimePatch::patchHostileMethods);
    }

    public static byte[] patchPig2Class(byte[] bytes, String className) {
        return patchHostileClass(bytes, className);
    }

    private static void patchHostileMethods(ClassNode cn) {
        int neutered = 0;
        for (MethodNode m : cn.methods) {
            if (m.name.equals("<init>")) continue;
            makeMethodNoOp(m);
            neutered++;
        }
        if (neutered > 0)
            AgentLog.log("[RuntimePatch] Neutered " + neutered
                    + " method(s) in hostile class: " + cn.name);
    }

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

    public static byte[] patchPlayer(byte[] bytes) {
        return patchClass(bytes, RuntimePatch::patchPlayerMethods);
    }

    private static void patchPlayerMethods(ClassNode cn) {
        String getItemBySlotName = findMethodByDesc(cn,
                "(Lnet/minecraft/world/entity/EquipmentSlot;)Lnet/minecraft/world/item/ItemStack;",
                "getItemBySlot", "m_6844_");

        for (MethodNode m : cn.methods) {
            // Match setItemSlot() by descriptor so both name eras (m_8061_ / setItemSlot) are covered.
            if (m.desc.equals("(Lnet/minecraft/world/entity/EquipmentSlot;Lnet/minecraft/world/item/ItemStack;)V")
                    && hasAnyName(m, "setItemSlot", "m_8061_")) {
                injectArmorLockGuard(m, cn.name, getItemBySlotName);
            }
        }
    }

    public static byte[] patchInventory(byte[] bytes) {
        return patchClass(bytes, RuntimePatch::patchInventoryMethods);
    }

    private static void patchInventoryMethods(ClassNode cn) {
        String getItemName = findMethodByDesc(cn, "(I)Lnet/minecraft/world/item/ItemStack;",
                "getItem", "m_8020_");

        for (MethodNode m : cn.methods) {
            if (m.desc.equals("(ILnet/minecraft/world/item/ItemStack;)V")
                    && (m.name.equals("setItem") || m.name.equals("m_6836_"))) {
                injectInventorySetItemGuard(m, cn.name, getItemName);
                AgentLog.log("[RuntimePatch] Patched Inventory." + m.name + m.desc
                        + " with armor-lock guard (covers /clear, shift-click, and click-drag writes)");
            } else if (m.desc.equals("(I)Lnet/minecraft/world/item/ItemStack;")
                    && (m.name.equals("removeItemNoUpdate") || m.name.equals("m_8016_"))) {
                injectInventoryRemoveNoUpdateGuard(m, cn.name, getItemName, findStaticItemStackField(cn));
                AgentLog.log("[RuntimePatch] Patched Inventory." + m.name + m.desc
                        + " with armor-lock guard (removeItemNoUpdate)");
            } else if (m.desc.equals("(Lnet/minecraft/world/item/ItemStack;)V")
                    && (m.name.equals("removeItem") || m.name.equals("m_36057_"))) {
                injectInventoryRemoveStackGuard(m, cn.name);
                AgentLog.log("[RuntimePatch] Patched Inventory." + m.name + m.desc
                        + " with armor-lock guard (removeItem-by-identity)");
            }
        }
    }

    private static String findMethodByDesc(ClassNode cn, String desc, String... preferredNames) {
        MethodNode fallback = null;
        for (MethodNode m : cn.methods) {
            if (!m.desc.equals(desc)) continue;
            for (String pn : preferredNames) {
                if (m.name.equals(pn)) return m.name;
            }
            if (fallback == null) fallback = m;
        }
        return fallback != null ? fallback.name : null;
    }

    private static void injectInventorySetItemGuard(MethodNode m, String ownerClass, String getItemName) {
        if (isNotPatchable(m)) return;
        if (getItemName == null) {
            AgentLog.log("[RuntimePatch] Could not resolve Inventory.getItem(int) by descriptor — "
                    + "skipping setItem armor-lock guard (armor removal via /clear/shift-click/drag will NOT be blocked)");
            return;
        }
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ownerClass, getItemName,
                "(I)Lnet/minecraft/world/item/ItemStack;", false));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "runtime/ArmorLockGuard", "shouldBlockSetItem",
                "(Ljava/lang/Object;ILjava/lang/Object;Ljava/lang/Object;)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new InsnNode(Opcodes.RETURN));
        il.add(skip);
        m.instructions.insert(il);
    }

    private static void injectInventoryRemoveNoUpdateGuard(MethodNode m, String ownerClass, String getItemName, String emptyStackField) {
        if (isNotPatchable(m)) return;
        if (getItemName == null) {
            AgentLog.log("[RuntimePatch] Could not resolve Inventory.getItem(int) by descriptor — "
                    + "skipping removeItemNoUpdate armor-lock guard");
            return;
        }
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ownerClass, getItemName,
                "(I)Lnet/minecraft/world/item/ItemStack;", false));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "runtime/ArmorLockGuard", "shouldBlockInventoryRemoveNoUpdate",
                "(Ljava/lang/Object;ILjava/lang/Object;)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, "net/minecraft/world/item/ItemStack", emptyStackField,
                "Lnet/minecraft/world/item/ItemStack;"));
        il.add(new InsnNode(Opcodes.ARETURN));
        il.add(skip);
        m.instructions.insert(il);
    }

    private static void injectInventoryRemoveStackGuard(MethodNode m, String ownerClass) {
        if (isNotPatchable(m)) return;
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "runtime/ArmorLockGuard", "shouldBlockInventoryRemoveStack",
                "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new InsnNode(Opcodes.RETURN));
        il.add(skip);
        m.instructions.insert(il);
    }

    public static byte[] patchContainerHelper(byte[] bytes) {
        return patchClass(bytes, RuntimePatch::patchContainerHelperMethods);
    }

    private static void patchContainerHelperMethods(ClassNode cn) {
        String emptyStackField = findStaticItemStackField(cn);
        for (MethodNode m : cn.methods) {
            if ((m.access & Opcodes.ACC_STATIC) == 0 || isNotPatchable(m)) continue;
            if (m.desc.equals("(Ljava/util/List;II)Lnet/minecraft/world/item/ItemStack;")
                    && (m.name.equals("removeItem") || m.name.equals("m_18969_"))) {
                injectContainerHelperRemoveGuard(m, emptyStackField);
                AgentLog.log("[RuntimePatch] Patched ContainerHelper." + m.name + m.desc
                        + " with armor-lock guard (removeItem split path)");
            } else if (m.desc.equals("(Ljava/util/List;I)Lnet/minecraft/world/item/ItemStack;")
                    && (m.name.equals("removeItemNoUpdate") || m.name.equals("m_18966_"))) {
                injectContainerHelperRemoveNoUpdateGuard(m, emptyStackField);
                AgentLog.log("[RuntimePatch] Patched ContainerHelper." + m.name + m.desc
                        + " with armor-lock guard (removeItemNoUpdate)");
            } else if (m.desc.equals("(Lnet/minecraft/world/Container;Ljava/util/function/Predicate;IZ)I")
                    && (m.name.equals("clearOrCountMatchingItems") || m.name.equals("m_18956_"))) {
                injectContainerHelperClearGuard(m);
                AgentLog.log("[RuntimePatch] Patched ContainerHelper." + m.name + m.desc
                        + " with armor-lock guard (clearOrCountMatchingItems container)");
            } else if (m.desc.equals("(Lnet/minecraft/world/item/ItemStack;Ljava/util/function/Predicate;IZ)I")
                    && (m.name.equals("clearOrCountMatchingItems") || m.name.equals("m_18961_"))) {
                injectContainerHelperStackClearGuard(m);
                AgentLog.log("[RuntimePatch] Patched ContainerHelper." + m.name + m.desc
                        + " with armor-lock guard (clearOrCountMatchingItems stack)");
            }
        }
    }

    private static void injectContainerHelperRemoveGuard(MethodNode m, String emptyStackField) {
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new VarInsnNode(Opcodes.ILOAD, 2));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "runtime/ArmorLockGuard", "shouldBlockContainerHelperRemove",
                "(Ljava/lang/Object;II)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, "net/minecraft/world/item/ItemStack", emptyStackField,
                "Lnet/minecraft/world/item/ItemStack;"));
        il.add(new InsnNode(Opcodes.ARETURN));
        il.add(skip);
        m.instructions.insert(il);
    }

    private static void injectContainerHelperRemoveNoUpdateGuard(MethodNode m, String emptyStackField) {
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "runtime/ArmorLockGuard", "shouldBlockContainerHelperRemoveNoUpdate",
                "(Ljava/lang/Object;I)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, "net/minecraft/world/item/ItemStack", emptyStackField,
                "Lnet/minecraft/world/item/ItemStack;"));
        il.add(new InsnNode(Opcodes.ARETURN));
        il.add(skip);
        m.instructions.insert(il);
    }

    private static void injectContainerHelperClearGuard(MethodNode m) {
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "runtime/ArmorLockGuard", "shouldBlockContainerClear",
                "(Ljava/lang/Object;)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new InsnNode(Opcodes.IRETURN));
        il.add(skip);
        m.instructions.insert(il);
    }

    private static void injectContainerHelperStackClearGuard(MethodNode m) {
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "runtime/ArmorLockGuard", "shouldBlockStackClear",
                "(Ljava/lang/Object;)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new InsnNode(Opcodes.IRETURN));
        il.add(skip);
        m.instructions.insert(il);
    }

    public static byte[] patchItemStack(byte[] bytes) {
        return patchClass(bytes, RuntimePatch::patchItemStackMethods);
    }

    private static void patchItemStackMethods(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if (m.desc.equals("(I)V")
                    && hasAnyName(m, "setCount", "m_41764_")) {
                injectItemStackSetCountGuard(m);
                AgentLog.log("[RuntimePatch] Patched ItemStack." + m.name + m.desc
                        + " with armor-lock guard (setCount/shrink)");
            }
        }
    }

    private static void injectItemStackSetCountGuard(MethodNode m) {
        if (isNotPatchable(m)) return;
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "runtime/ArmorLockGuard", "shouldBlockSetCount",
                "(Ljava/lang/Object;I)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new InsnNode(Opcodes.RETURN));
        il.add(skip);
        m.instructions.insert(il);
    }

    public static byte[] patchNonNullList(byte[] bytes) {
        return patchClass(bytes, RuntimePatch::patchNonNullListMethods);
    }

    private static void patchNonNullListMethods(ClassNode cn) {
        String getItemName = findMethodByDesc(cn, "(I)Ljava/lang/Object;", "get", "m_122199_");
        for (MethodNode m : cn.methods) {
            if (m.desc.equals("(ILjava/lang/Object;)Ljava/lang/Object;")
                    && (m.name.equals("set") || m.name.equals("m_122212_"))) {
                injectNonNullListSetGuard(m, cn.name, getItemName);
                AgentLog.log("[RuntimePatch] Patched NonNullList." + m.name + m.desc
                        + " with raw armor-list write guard (avatar strip)");
            } else if (m.desc.equals("()V") && m.name.equals("clear")) {
                injectNonNullListClearGuard(m);
                AgentLog.log("[RuntimePatch] Patched NonNullList." + m.name + m.desc
                        + " with raw armor-list clear guard");
            } else if (m.desc.equals("(I)Ljava/lang/Object;") && m.name.equals("remove")) {
                injectNonNullListRemoveGuard(m, cn.name, getItemName);
                AgentLog.log("[RuntimePatch] Patched NonNullList." + m.name + m.desc
                        + " with raw armor-list remove guard");
            }
        }
    }

    private static void injectNonNullListClearGuard(MethodNode m) {
        if (isNotPatchable(m)) return;
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "runtime/ArmorLockGuard", "shouldBlockRawArmorListClear",
                "(Ljava/lang/Object;)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new InsnNode(Opcodes.RETURN));
        il.add(skip);
        m.instructions.insert(il);
    }

    private static void injectNonNullListRemoveGuard(MethodNode m, String ownerClass, String getItemName) {
        if (isNotPatchable(m)) return;
        if (getItemName == null) {
            AgentLog.log("[RuntimePatch] Could not resolve NonNullList.get(int) by descriptor — "
                    + "skipping NonNullList.remove armor-lock guard");
            return;
        }
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "runtime/ArmorLockGuard", "shouldBlockRawArmorListRemove",
                "(Ljava/lang/Object;I)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ownerClass, getItemName,
                "(I)Ljava/lang/Object;", false));
        il.add(new InsnNode(Opcodes.ARETURN));
        il.add(skip);
        m.instructions.insert(il);
    }

    private static void injectNonNullListSetGuard(MethodNode m, String ownerClass, String getItemName) {
        if (isNotPatchable(m)) return;
        if (getItemName == null) {
            AgentLog.log("[RuntimePatch] Could not resolve NonNullList.get(int) by descriptor — "
                    + "skipping NonNullList.set armor-lock guard (raw avatar armor-strip will NOT be blocked)");
            return;
        }
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "runtime/ArmorLockGuard", "shouldBlockRawArmorListWrite",
                "(Ljava/lang/Object;ILjava/lang/Object;)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ownerClass, getItemName,
                "(I)Ljava/lang/Object;", false));
        il.add(new InsnNode(Opcodes.ARETURN));
        il.add(skip);
        m.instructions.insert(il);
    }

    public static byte[] patchMappedRegistry(byte[] bytes) {
        return patchClass(bytes, RuntimePatch::patchMappedRegistryMethods);
    }

    private static void patchMappedRegistryMethods(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if (isNotPatchable(m)) continue;

            // Registry.getKey(T) — erasure (Ljava/lang/Object;)Lnet/minecraft/resources/ResourceLocation;
            if (m.desc.equals("(Ljava/lang/Object;)Lnet/minecraft/resources/ResourceLocation;")) {
                injectRegistrySpoof(m, "hijackGetKey",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                        "net/minecraft/resources/ResourceLocation");
                AgentLog.log("[RuntimePatch] Patched MappedRegistry." + m.name + m.desc
                        + " with item-id spoof (hijackGetKey)");
            }
            // Registry.getValue(ResourceLocation) — erasure returns Object (T)
            else if (m.desc.equals("(Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/Object;")) {
                injectRegistrySpoof(m, "hijackGetValue",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", null);
                AgentLog.log("[RuntimePatch] Patched MappedRegistry." + m.name + m.desc
                        + " with item-id spoof (hijackGetValue)");
            }
            // Registry.getValue(ResourceKey<T>) — erasure returns Object (T)
            else if (m.desc.equals("(Lnet/minecraft/resources/ResourceKey;)Ljava/lang/Object;")) {
                injectRegistrySpoof(m, "hijackGetValueKey",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", null);
                AgentLog.log("[RuntimePatch] Patched MappedRegistry." + m.name + m.desc
                        + " with item-id spoof (hijackGetValueKey)");
            }
            // Registry.containsKey(ResourceLocation) — returns boolean
            else if (m.desc.equals("(Lnet/minecraft/resources/ResourceLocation;)Z")) {
                injectRegistrySpoof(m, "hijackContainsKey",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Boolean;", null, true);
                AgentLog.log("[RuntimePatch] Patched MappedRegistry." + m.name + m.desc
                        + " with item-id spoof (hijackContainsKey)");
            }
            // Registry.get(ResourceLocation) — Optional<Holder.Reference<T>>
            else if (m.desc.equals("(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;")) {
                injectRegistrySpoof(m, "hijackGet",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", "java/util/Optional");
                AgentLog.log("[RuntimePatch] Patched MappedRegistry." + m.name + m.desc
                        + " with item-id spoof (hijackGet)");
            }
            // Registry.getResourceKey(T) — Optional<ResourceKey<T>>
            else if (m.desc.equals("(Ljava/lang/Object;)Ljava/util/Optional;")) {
                injectRegistrySpoof(m, "hijackGetResourceKey",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", "java/util/Optional");
                AgentLog.log("[RuntimePatch] Patched MappedRegistry." + m.name + m.desc
                        + " with item-id spoof (hijackGetResourceKey)");
            }
        }
    }

    private static void injectRegistrySpoof(MethodNode m, String hijackMethod, String hijackDesc,
                                            String checkcastType) {
        injectRegistrySpoof(m, hijackMethod, hijackDesc, checkcastType, false);
    }

    private static void injectRegistrySpoof(MethodNode m, String hijackMethod, String hijackDesc,
                                            String checkcastType, boolean unboxBoolean) {
        if (isNotPatchable(m)) return;
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "runtime/PreatorSpoof", hijackMethod, hijackDesc, false));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new JumpInsnNode(Opcodes.IFNULL, skip));
        if (unboxBoolean) {
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
            il.add(new InsnNode(Opcodes.IRETURN));
        } else {
            if (checkcastType != null) il.add(new TypeInsnNode(Opcodes.CHECKCAST, checkcastType));
            il.add(new InsnNode(Opcodes.ARETURN));
        }
        il.add(skip);
        m.instructions.insert(il);
    }

    private static boolean hasAnyName(MethodNode m, String... names) {
        for (String n : names) if (m.name.equals(n)) return true;
        return false;
    }

    private static boolean isDamageBooleanEntry(MethodNode m) {
        if (isNotPatchable(m)) return false;
        String d = m.desc;
        if (!d.endsWith("Lnet/minecraft/world/damagesource/DamageSource;F)Z")) return false;
        return d.startsWith("(Lnet/minecraft/world/damagesource/DamageSource;")
                || d.startsWith("(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;");
    }

    private static boolean isDamageVoidEntry(MethodNode m) {
        if (isNotPatchable(m)) return false;
        String d = m.desc;
        if (!d.endsWith("Lnet/minecraft/world/damagesource/DamageSource;F)V")) return false;
        return d.startsWith("(Lnet/minecraft/world/damagesource/DamageSource;")
                || d.startsWith("(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;");
    }

    private static boolean isFallDamageVoidEntry(MethodNode m) {
        if (isNotPatchable(m)) return false;
        return m.desc.startsWith("(DZ") && m.desc.endsWith(")V")
                && m.desc.contains("Lnet/minecraft/world/level/block/state/BlockState;")
                && m.desc.contains("Lnet/minecraft/core/BlockPos;");
    }

    private static boolean isFallDamageBooleanEntry(MethodNode m) {
        if (isNotPatchable(m)) return false;
        return m.desc.startsWith("(DD") && m.desc.endsWith(")Z")
                && m.desc.contains("Lnet/minecraft/world/level/block/state/BlockState;")
                && m.desc.contains("Lnet/minecraft/core/BlockPos;");
    }

    private static boolean isEntityRemovalMethod(MethodNode m) {
        if (isNotPatchable(m)) return false;
        return m.desc.equals("(Lnet/minecraft/world/entity/Entity$RemovalReason;)V");
    }

    private static boolean isEntityDiscardMethod(MethodNode m) {
        if (isNotPatchable(m)) return false;
        return m.desc.equals("()V") && hasAnyName(m, "discard", "m_142682_");
    }

    private static String findStaticItemStackField(ClassNode cn) {
        for (FieldNode f : cn.fields) {
            if ((f.access & Opcodes.ACC_STATIC) != 0
                    && f.desc.equals("Lnet/minecraft/world/item/ItemStack;")) return f.name;
        }
        return "f_41583_"; // last-resort SRG name
    }

    private static void patchLivingEntityMethods(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if (isDamageBooleanEntry(m)) {
                injectCancelBoolean(m, "isGod", "(Ljava/lang/Object;)Z");

            } else if (isDamageVoidEntry(m)) {
                injectCancelVoid(m, "isGod", "(Ljava/lang/Object;)Z");

            } else if (isFallDamageVoidEntry(m)) {
                injectCancelVoid(m, "isGod", "(Ljava/lang/Object;)Z");

            } else if (isFallDamageBooleanEntry(m)) {
                injectCancelBoolean(m, "isGod", "(Ljava/lang/Object;)Z");

            } else if (hasAnyName(m, "setHealth", "m_21153_") && m.desc.equals("(F)V")) {
                injectSetHealth(m);

            } else if (hasAnyName(m, "die", "m_6667_")
                    && m.desc.startsWith("(Lnet/minecraft/world/damagesource/DamageSource;)")) {
                injectDie(m);

            } else if (hasAnyName(m, "kill", "m_6675_") && m.desc.equals("()V")) {
                injectCancelVoid(m, "isGod", "(Ljava/lang/Object;)Z");

            } else if (hasAnyName(m, "tick", "m_8119_") && m.desc.equals("()V")) {
                injectTick(m);

            } else if (hasAnyName(m, "isDeadOrDying", "m_21224_")) {
                injectIsDeadOrDying(m);

            } else if (hasAnyName(m, "getHealth", "m_21223_") && m.desc.equals("()F")) {
                injectHealthGetterOverride(m, Opcodes.FRETURN, "getEnforcedHealth", "F");

            } else if (hasAnyName(m, "isAlive", "m_6084_") && m.desc.equals("()Z")) {
                injectHealthGetterOverride(m, Opcodes.IRETURN, "getEnforcedAlive", "Z");

            } else if (hasAnyName(m, "getMaxHealth", "m_21233_") && m.desc.equals("()F")) {
                injectMaxHealthObserver(m);

            } else if (hasAnyName(m, "outOfWorld", "m_8077_") && m.desc.equals("()V")) {
                injectCancelVoid(m, "isGod", "(Ljava/lang/Object;)Z");

            } else if (hasAnyName(m, "spawnAtLocation", "m_20208_")
                    && m.desc.startsWith("(Lnet/minecraft/world/item/ItemStack;)")) {
                injectDropGuard(m);
            }
        }
    }

    private static void patchEntityMethods(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if (isEntityRemovalMethod(m) || isEntityDiscardMethod(m)) {
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
            if (isDamageBooleanEntry(m)) {
                injectCancelBoolean(m, "isGod", "(Ljava/lang/Object;)Z");

            } else if (hasAnyName(m, "die", "m_6667_")
                    && m.desc.startsWith("(Lnet/minecraft/world/damagesource/DamageSource;)")) {
                injectPlayerDie(m);

            } else if (hasAnyName(m, "setGameMode", "m_7284_")
                    && m.desc.startsWith("(Lnet/minecraft/world/level/GameType;)")) {
                injectBlockGameMode(m);
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
                "(Ljava/lang/Object;F)F", false));
        il.add(new VarInsnNode(Opcodes.FSTORE, 1));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.FLOAD, 1));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "runtime/HealthGuard", "recordHealth",
                "(Ljava/lang/Object;F)V", false));
        m.instructions.insert(il);
    }

    private static void injectEntityDataSet(MethodNode m) {
        InsnList il = new InsnList();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "interceptEntityDataSet",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 2));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "runtime/HealthGuard", "filterEntityDataWrite",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 2));
        m.instructions.insert(il);
    }

    private static void injectDie(MethodNode m) {
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "isGod",
                "(Ljava/lang/Object;)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "restoreHealth",
                "(Ljava/lang/Object;)V", false));
        emitGuardReturn(il, Type.getReturnType(m.desc));
        il.add(skip);
        m.instructions.insert(il);
    }

    private static void injectEntityRemove(MethodNode m) {
        if (isNotPatchable(m)) return;
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "isGodEntity",
                "(Ljava/lang/Object;)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new InsnNode(Opcodes.RETURN));
        il.add(skip);
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "runtime/ArmorLockGuard", "untrackEntity",
                "(Ljava/lang/Object;)V", false));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "runtime/HealthGuard", "forget",
                "(Ljava/lang/Object;)V", false));
        m.instructions.insert(il);
    }

    private static void injectTick(MethodNode m) {
        if (isNotPatchable(m)) return;
        InsnList il = new InsnList();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "godTick",
                "(Ljava/lang/Object;)V", false));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "runtime/ArmorLockGuard", "trackEntityGodStatus",
                "(Ljava/lang/Object;)V", false));
        m.instructions.insert(il);
    }

    private static void injectIsDeadOrDying(MethodNode m) {
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "isGod",
                "(Ljava/lang/Object;)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new InsnNode(Opcodes.IRETURN));
        il.add(skip);
        m.instructions.insert(il);
    }

    private static void injectHealthGetterOverride(MethodNode m, int returnOpcode, String enforcerMethod, String returnTypeDesc) {
        if (isNotPatchable(m)) return;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn.getOpcode() != returnOpcode) continue;

            LabelNode noOverride = new LabelNode();
            InsnList il = new InsnList();
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "runtime/HealthGuard", "hasOverride",
                    "(Ljava/lang/Object;)Z", false));
            il.add(new JumpInsnNode(Opcodes.IFEQ, noOverride));
            il.add(new InsnNode(Opcodes.POP));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "runtime/HealthGuard", enforcerMethod,
                    "(Ljava/lang/Object;)" + returnTypeDesc, false));
            il.add(new InsnNode(returnOpcode));
            il.add(noOverride);
            m.instructions.insertBefore(insn, il);
        }
    }

    private static void injectMaxHealthObserver(MethodNode m) {
        if (isNotPatchable(m)) return;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn.getOpcode() != Opcodes.FRETURN) continue;

            InsnList il = new InsnList();
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new InsnNode(Opcodes.SWAP));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "runtime/HealthGuard", "observeMaxHealth",
                    "(Ljava/lang/Object;F)F", false));
            m.instructions.insertBefore(insn, il);
        }
    }

    private static void injectPlayerDie(MethodNode m) {
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "isGod",
                "(Ljava/lang/Object;)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "restoreHealth",
                "(Ljava/lang/Object;)V", false));
        emitGuardReturn(il, Type.getReturnType(m.desc));
        il.add(skip);
        m.instructions.insert(il);
    }

    private static void injectBlockGameMode(MethodNode m) {
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "shouldBlockGameMode",
                "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        emitGuardReturn(il, Type.getReturnType(m.desc));
        il.add(skip);
        m.instructions.insert(il);
    }

    private static void emitGuardReturn(InsnList il, Type returnType) {
        switch (returnType.getSort()) {
            case Type.VOID ->
                il.add(new InsnNode(Opcodes.RETURN));
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                il.add(new InsnNode(Opcodes.ICONST_0));
                il.add(new InsnNode(Opcodes.IRETURN));
            }
            case Type.LONG -> {
                il.add(new InsnNode(Opcodes.LCONST_0));
                il.add(new InsnNode(Opcodes.LRETURN));
            }
            case Type.FLOAT -> {
                il.add(new InsnNode(Opcodes.FCONST_0));
                il.add(new InsnNode(Opcodes.FRETURN));
            }
            case Type.DOUBLE -> {
                il.add(new InsnNode(Opcodes.DCONST_0));
                il.add(new InsnNode(Opcodes.DRETURN));
            }
            default -> {
                il.add(new InsnNode(Opcodes.ACONST_NULL));
                il.add(new InsnNode(Opcodes.ARETURN));
            }
        }
    }

    private static void injectCallbackGuard(MethodNode m, String entityField, String ownerClass) {
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new FieldInsnNode(Opcodes.GETFIELD, ownerClass, entityField,
                "Lnet/minecraft/world/entity/Entity;"));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "isGodEntity",
                "(Ljava/lang/Object;)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        emitGuardReturn(il, Type.getReturnType(m.desc));
        il.add(skip);
        m.instructions.insert(il);
    }

    private static void injectArmorLockGuard(MethodNode m, String ownerClass, String getItemBySlotName) {
        if (isNotPatchable(m)) return;
        if (getItemBySlotName == null) {
            AgentLog.log("[RuntimePatch] Could not resolve Player.getItemBySlot by descriptor — "
                    + "skipping setItemSlot armor-lock guard");
            return;
        }
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ownerClass, getItemBySlotName,
                "(Lnet/minecraft/world/entity/EquipmentSlot;)Lnet/minecraft/world/item/ItemStack;", false));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "runtime/ArmorLockGuard", "shouldBlockSetItemSlot",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new InsnNode(Opcodes.RETURN));
        il.add(skip);
        m.instructions.insert(il);
    }

    private static void injectDropGuard(MethodNode m) {
        if (isNotPatchable(m)) return;
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "runtime/ArmorLockGuard", "shouldBlockDrop",
                "(Ljava/lang/Object;)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new InsnNode(Opcodes.ACONST_NULL));
        il.add(new InsnNode(Opcodes.ARETURN));
        il.add(skip);
        m.instructions.insert(il);
    }

    private static boolean isNotPatchable(MethodNode m) {
        return (m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0;
    }

    public static byte[] patchServerGamePacketListener(byte[] bytes) {
        return patchClass(bytes, RuntimePatch::patchServerPacketListenerMethods);
    }

    public static byte[] patchServerCommonPacketListener(byte[] bytes) {
        return patchClass(bytes, RuntimePatch::patchServerPacketListenerMethods);
    }

    private static void patchServerPacketListenerMethods(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if (m.desc.equals("(Lnet/minecraft/network/chat/Component;)V")
                    && hasAnyName(m, "disconnect", "m_143402_")) {
                injectDisconnectGuard(m);
                AgentLog.log("[RuntimePatch] Patched " + cn.name + "." + m.name
                        + "() against hostile kick");
            }
            if (m.desc.startsWith("(Lnet/minecraft/network/protocol/Packet;")
                    && Type.getReturnType(m.desc).getSort() == Type.VOID
                    && (m.access & Opcodes.ACC_STATIC) == 0) {
                injectPacketNukeGuard(m);
                AgentLog.log("[RuntimePatch] Patched " + cn.name + "." + m.name
                        + m.desc + " against client-sync-nuke packets");
            }
        }
    }

    private static void injectPacketNukeGuard(MethodNode m) {
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "runtime/ArmorLockGuard", "shouldBlockPacket",
                "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new InsnNode(Opcodes.RETURN));
        il.add(skip);
        m.instructions.insert(il);
    }

    private static void injectDisconnectGuard(MethodNode m) {
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "runtime/HostileRegistry", "isCallerHostile", "()Z", false));
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
        } catch (Throwable t) {
            AgentLog.logThrowable("[RuntimePatch] Failed patching " + cn.name, t);
            return bytes;
        }
    }

    private static final String[] MARKER_OWNERS = {
            HELPER,
            "runtime/ArmorLockGuard",
            "runtime/ThreadSanitizer",
            "runtime/HostileRegistry",
            "runtime/HealthGuard",
            "runtime/PreatorSpoof"
    };

    public static boolean hasGodMarker(byte[] bytes) {
        try {
            ClassNode cn = read(bytes);
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode insn : m.instructions) {
                    if (insn instanceof MethodInsnNode min && insn.getOpcode() == Opcodes.INVOKESTATIC) {
                        for (String owner : MARKER_OWNERS) {
                            if (min.owner.equals(owner)) return true;
                        }
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            // If we can't even parse it, don't force a retransform loop over it.
            return true;
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return cn;
    }

    private static final ThreadLocal<Map<String, ClassNode>> IN_FLIGHT =
            ThreadLocal.withInitial(HashMap::new);

    private static byte[] write(ClassNode cn) {
        Map<String, ClassNode> inFlight = IN_FLIGHT.get();
        boolean registeredHere = !inFlight.containsKey(cn.name);
        if (registeredHere) inFlight.put(cn.name, cn);

        try {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected String getCommonSuperClass(String type1, String type2) {
                    if (type1.equals(type2)) return type1;

                    List<String> chain1 = ancestorChain(type1);
                    if (chain1.contains(type2)) return type2;

                    List<String> chain2 = ancestorChain(type2);
                    if (chain2.contains(type1)) return type1;

                    for (String t : chain1) {
                        if (chain2.contains(t)) return t;
                    }
                    return "java/lang/Object";
                }

                private List<String> ancestorChain(String internalName) {
                    List<String> chain = new ArrayList<>();
                    String cur = internalName;
                    while (cur != null && !chain.contains(cur)) {
                        chain.add(cur);
                        if (cur.equals("java/lang/Object")) break;
                        ClassNode live = IN_FLIGHT.get().get(cur);
                        cur = (live != null) ? live.superName : superNameOfReflective(cur);
                    }
                    return chain;
                }

                private String superNameOfReflective(String internalName) {
                    for (ClassLoader cl : candidateLoaders()) {
                        try {
                            Class<?> c = Class.forName(internalName.replace('/', '.'), false, cl);
                            Class<?> sup = c.getSuperclass();
                            return sup != null ? sup.getName().replace('.', '/') : null;
                        } catch (Throwable ignored) {}
                    }
                    return null;
                }

                private ClassLoader[] candidateLoaders() {
                    return new ClassLoader[]{
                            Thread.currentThread().getContextClassLoader(),
                            RuntimePatch.class.getClassLoader()
                    };
                }
            };

            cn.accept(cw);
            return cw.toByteArray();
        } finally {
            if (registeredHere) inFlight.remove(cn.name);
        }
    }
}