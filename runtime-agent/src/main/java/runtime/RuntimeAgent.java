package runtime;

import java.io.*;
import java.lang.instrument.*;
import java.nio.file.*;
import java.security.ProtectionDomain;
import java.util.*;

public class RuntimeAgent {

    private static volatile boolean nativeLoaded = false;

    private static void loadNullifierDll() {
        if (nativeLoaded) return;
        synchronized (RuntimeAgent.class) {
            if (nativeLoaded) return;
            try (InputStream in = RuntimeAgent.class.getResourceAsStream("/nullifier/nullifier.dll")) {
                if (in == null) {
                    System.err.println("[Transfinity Runtime] nullifier.dll not found in JAR resources");
                    return;
                }
                Path tmp = Files.createTempFile("nullifier-", ".dll");
                tmp.toFile().deleteOnExit();
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                System.load(tmp.toAbsolutePath().toString());
                nativeLoaded = true;
                System.out.println("[Transfinity Runtime] nullifier.dll loaded from " + tmp);
            } catch (Exception e) {
                System.err.println("[Transfinity Runtime] Failed to load nullifier.dll: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public static void premain(String args, Instrumentation inst) {
        System.out.println("[Transfinity Runtime] premain");
        loadNullifierDll();
        inst.addTransformer(new GodTransformer(), true);
    }

    public static void agentmain(String args, Instrumentation inst) {
        System.out.println("[Transfinity Runtime] agentmain");
        loadNullifierDll();
        inst.addTransformer(new GodTransformer(), true);

        String[] targetNames = {
                "net.minecraft.world.entity.LivingEntity",
                "net.minecraft.world.entity.Entity",
                "net.minecraft.network.syncher.SynchedEntityData",
                "net.minecraft.server.level.ServerPlayer",
                "net.minecraft.world.level.entity.EntityInLevelCallback"  // ← added
        };
        Set<String> targets = new HashSet<>(Arrays.asList(targetNames));

        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (targets.contains(c.getName()) && inst.isModifiableClass(c)) {
                try {
                    System.out.println("[Transfinity Runtime] Retransforming " + c.getName());
                    inst.retransformClasses(c);
                } catch (Throwable t) {
                    System.err.println("[Transfinity Runtime] FAILED to retransform " + c.getName() +
                            " → " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    if (t.getCause() != null) t.getCause().printStackTrace();
                    else t.printStackTrace();
                }
            }
        }

        System.out.println("[Transfinity Runtime] Hot-attach completed (some early classes may have been skipped)");
    }

    public static class GodTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(Module module, ClassLoader loader, String className,
                                Class<?> classBeingRedefined, ProtectionDomain domain,
                                byte[] classfileBuffer) {
            if (className == null) return null;
            return switch (className) {
                case "net/minecraft/world/entity/LivingEntity"
                        -> RuntimePatch.patchLivingEntity(classfileBuffer);
                case "net/minecraft/world/entity/Entity"
                        -> RuntimePatch.patchEntity(classfileBuffer);
                case "net/minecraft/network/syncher/SynchedEntityData"
                        -> RuntimePatch.patchSynchedEntityData(classfileBuffer);
                case "net/minecraft/server/level/ServerPlayer"
                        -> RuntimePatch.patchServerPlayer(classfileBuffer);
                case "net/minecraft/world/level/entity/EntityInLevelCallback"  // ← added
                        -> RuntimePatch.patchEntityCallback(classfileBuffer);
                default -> null;
            };
        }
    }
}