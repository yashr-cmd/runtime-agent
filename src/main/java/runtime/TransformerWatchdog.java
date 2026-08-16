package runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;

public class TransformerWatchdog {

    private static final String[] RETRANSFORM_TARGETS = {
            "net.minecraft.world.entity.LivingEntity",
            "net.minecraft.world.entity.Entity",
            "net.minecraft.world.entity.player.Player",
            "net.minecraft.network.syncher.SynchedEntityData",
            "net.minecraft.server.level.ServerPlayer",
            "net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback",
            "net.minecraft.server.network.ServerGamePacketListenerImpl",
            "net.minecraft.core.MappedRegistry"
    };

    private static volatile MethodHandle retransformHandle;

    public static Thread startAndReturn(Instrumentation inst, ClassFileTransformer transformer) {
        Thread t = new HardenedThread(() -> watchdogLoop(inst, transformer), "Netty-Server-IO-1");
        t.setDaemon(true);
        t.start();
        AgentLog.log("[Transfinity Watchdog] Started");
        return t;
    }

    public static void start(Instrumentation inst, ClassFileTransformer transformer) {
        startAndReturn(inst, transformer);
    }

    private static void watchdogLoop(Instrumentation inst, ClassFileTransformer transformer) {
        int resurrections = 0;

        while (true) {
            try {
                Thread.sleep(500);

                if (!isRegistered(inst, transformer)) {
                    resurrections++;
                    AgentLog.log("[Transfinity Watchdog] GodTransformer was removed! Resurrecting #" + resurrections);
                    inst.addTransformer(transformer, true);
                    RuntimeAgent.retransformPig2Classes(inst, true);
                    reapply(inst);
                    AgentLog.log("[Transfinity Watchdog] Resurrected");
                }

                LauncherStateGuard.restore();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                AgentLog.log("[Transfinity Watchdog] Loop error: " + e.getMessage());
            }
        }
    }

    private static boolean isRegistered(Instrumentation inst, ClassFileTransformer target) {
        try {
            Class<?> instrImpl = Class.forName("sun.instrument.InstrumentationImpl");

            Field tmField = null;
            for (String name : new String[]{
                    "mRetransfomableTransformerManager",  // JDK 8-17 (typo intentional in JDK src)
                    "mRetransformableTransformerManager", // JDK 21+ (typo fixed upstream)
                    "mTransformerManager"                 // ultimate fallback
            }) {
                try { tmField = instrImpl.getDeclaredField(name); break; }
                catch (NoSuchFieldException ignored) {}
            }
            if (tmField == null) return true;
            tmField.setAccessible(true);
            Object tm = tmField.get(inst);
            if (tm == null) return false;

            Field listField = null;
            for (String name : new String[]{"mTransformerList", "transformerList"}) {
                try { listField = tm.getClass().getDeclaredField(name); break; }
                catch (NoSuchFieldException ignored) {}
            }
            if (listField == null) return true;
            listField.setAccessible(true);
            Object[] list = (Object[]) listField.get(tm);
            if (list == null) return false;

            for (Object entry : list) {
                if (entry == null) continue;
                for (Field f : entry.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    try {
                        Object val = f.get(entry);
                        if (val == target ||
                                (val != null && val.getClass().getName().contains("GodTransformer")))
                            return true;
                    } catch (Throwable ignored) {}
                }
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    static void reapply(Instrumentation inst) {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String name = c.getName();
            for (String target : RETRANSFORM_TARGETS) {
                if (name.equals(target) && inst.isModifiableClass(c)) {
                    try { retransformStealth(inst, c); }
                    catch (Throwable t) {
                        AgentLog.logThrowable("[Transfinity Watchdog] reapply failed for " + name, t);
                    }
                    break;
                }
            }
        }
    }

    static void retransformStealth(Instrumentation inst, Class<?> c) throws Throwable {
        ClassLoader cl = c.getClassLoader();
        if (cl != null) {
            Thread.currentThread().setContextClassLoader(cl);
        }
        MethodHandle mh = getRetransformHandle();
        mh.invoke(inst, new Class[]{c});
    }

    private static MethodHandle getRetransformHandle() throws Throwable {
        if (retransformHandle == null) {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            retransformHandle = lookup.findVirtual(
                    Instrumentation.class,
                    "retransformClasses",
                    MethodType.methodType(void.class, Class[].class)
            );
        }
        return retransformHandle;
    }
}