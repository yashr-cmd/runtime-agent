package runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

import org.objectweb.asm.Type;

public final class PreatorSpoof {

    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("transfinity.preator.spoof", "true"));

    private static final String REAL_NS = "transfinity_improved";
    private static final String FAKE_NS = "minecraft";

    // Only the Praetor suit.
    private static final String[] SUIT_NAMES = {
            "preator_helmet", "preator_chestplate", "preator_leggings", "preator_boots"
    };

    // fake id ("minecraft:preator_helmet") -> real id ("transfinity_improved:preator_helmet")
    private static final Map<String, String> FAKE_TO_REAL = new HashMap<>();

    static {
        for (String n : SUIT_NAMES) {
            FAKE_TO_REAL.put(FAKE_NS + ":" + n, REAL_NS + ":" + n);
        }
    }

    private static final Object LOCK = new Object();
    private static volatile boolean reflectReady = false;
    private static volatile boolean reflectFailed = false;
    private static volatile Object ITEM_REGISTRY;              // BuiltInRegistries.ITEM
    private static volatile Method M_KEY_LOCATION;             // ResourceKey.location()
    private static volatile Method M_RK_CREATE;                // ResourceKey.create(ResourceKey, ResourceLocation)
    private static volatile Method M_REG_GET_VALUE_RL;         // Registry.getValue(ResourceLocation)
    private static volatile Method M_REG_GET_RL;               // Registry.get(ResourceLocation)
    private static volatile Map<String, Object> FAKE_RL;       // fake id -> ResourceLocation
    private static volatile Map<String, Object> REAL_RL;       // real id -> ResourceLocation
    private static volatile Map<String, Object> FAKE_RK_OPT;   // fake id -> Optional<ResourceKey>
    private static volatile boolean itemsLoaded = false;
    private static final Map<Object, Object> ITEM_TO_FAKE_RL =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<String, Object> FAKE_TO_ITEM =
            Collections.synchronizedMap(new HashMap<>());

    private PreatorSpoof() {}

    public static Object hijackGetKey(Object registry, Object item) {
        if (!ENABLED || registry == null || item == null) return null;
        if (!isItemRegistry(registry)) return null;
        ensureItems(registry);
        return ITEM_TO_FAKE_RL.get(item);
    }

    /** Registry.getResourceKey(T): real Praetor item -> Optional[ResourceKey(minecraft:preator_*)]. */
    public static Object hijackGetResourceKey(Object registry, Object item) {
        if (!ENABLED || registry == null || item == null) return null;
        if (!isItemRegistry(registry)) return null;
        ensureItems(registry);
        Object fakeRl = ITEM_TO_FAKE_RL.get(item);
        if (fakeRl == null) return null;
        return FAKE_RK_OPT.get(fakeRl.toString());
    }

    /** Registry.getValue(ResourceLocation): minecraft:preator_* -> the real item. */
    public static Object hijackGetValue(Object registry, Object key) {
        if (!ENABLED || registry == null || key == null) return null;
        if (!isItemRegistry(registry)) return null;
        String fake = key.toString();
        if (!FAKE_TO_REAL.containsKey(fake)) return null;
        return realItemFor(registry, fake);
    }

    /** Registry.getValue(ResourceKey): ResourceKey(minecraft:preator_*) -> the real item. */
    public static Object hijackGetValueKey(Object registry, Object resourceKey) {
        if (!ENABLED || registry == null || resourceKey == null) return null;
        if (!isItemRegistry(registry)) return null;
        String ks = keyLocationString(resourceKey);
        if (ks == null || !FAKE_TO_REAL.containsKey(ks)) return null;
        return realItemFor(registry, ks);
    }

    /** Registry.get(ResourceLocation): minecraft:preator_* -> the real item's holder Optional. */
    public static Object hijackGet(Object registry, Object key) {
        if (!ENABLED || registry == null || key == null) return null;
        if (!isItemRegistry(registry)) return null;
        String real = FAKE_TO_REAL.get(key.toString());
        if (real == null) return null;
        try {
            return M_REG_GET_RL.invoke(registry, REAL_RL.get(real));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Registry.containsKey(ResourceLocation): minecraft:preator_* -> true. */
    public static Boolean hijackContainsKey(Object registry, Object key) {
        if (!ENABLED || registry == null || key == null) return null;
        if (!isItemRegistry(registry)) return null;
        if (FAKE_TO_REAL.containsKey(key.toString())) return Boolean.TRUE;
        return null;
    }

    private static boolean isItemRegistry(Object registry) {
        if (!reflectReady && !reflectFailed) {
            synchronized (LOCK) {
                if (!reflectReady && !reflectFailed) {
                    if (bootstrapPending) {
                        Boolean ready = bootstrapReady(registry);
                        if (ready == null || Boolean.TRUE.equals(ready)) bootstrapPending = false;
                    }
                    if (!bootstrapPending) initReflection(registry);
                }
            }
        }
        return registry == ITEM_REGISTRY;
    }

    private static void initReflection(Object registry) {
        if (reflectReady || reflectFailed) return;
        try {
            Class<?> rl = resolveClass("net.minecraft.resources.ResourceLocation", registry);
            Class<?> rk = resolveClass("net.minecraft.resources.ResourceKey", registry);
            Class<?> builtIn = resolveClass("net.minecraft.core.registries.BuiltInRegistries", registry);
            Class<?> registries = resolveClass("net.minecraft.core.registries.Registries", registry);
            if (rl == null || rk == null || builtIn == null || registries == null) {
                reflectFailed = true;
                return;
            }

            Boolean bootstrapped = bootstrapReady(registry);
            if (Boolean.FALSE.equals(bootstrapped)) {
                deferTransient("[PreatorSpoof] game not bootstrapped yet — deferring item-registry reflection");
                return;
            }

            Method mParse = resolveMethod(rl,
                    "(Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;",
                    "parse", "m_135827_");
            Method mRkCreate = resolveMethod(rk,
                    "(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/resources/ResourceKey;",
                    "create", "m_135785_");
            Method mKeyLocation = resolveMethod(rk,
                    "()Lnet/minecraft/resources/ResourceLocation;",
                    "location", "m_135804_");
            if (mParse == null || mRkCreate == null || mKeyLocation == null) {
                reflectFailed = true;
                AgentLog.log("[PreatorSpoof] could not resolve ResourceLocation/ResourceKey methods"
                        + " — item-id spoof disabled");
                return;
            }

            Class<?> registryIface = resolveClass("net.minecraft.core.Registry", registry);
            Field itemField = namedOrTypedField(builtIn, "ITEM", registryIface, "Item");
            if (itemField == null) {
                reflectFailed = true;
                AgentLog.log("[PreatorSpoof] no ITEM registry field found on BuiltInRegistries"
                        + " — item-id spoof disabled");
                return;
            }
            Object itemRegistry;
            try {
                itemRegistry = itemField.get(null);
            } catch (Throwable t) {
                deferTransient("[PreatorSpoof] BuiltInRegistries unavailable for now ("
                        + t.getClass().getSimpleName() + ") — deferring");
                return;
            }
            if (itemRegistry == null) {
                deferTransient("[PreatorSpoof] ITEM registry not published yet (mid-init) — deferring");
                return;
            }

            Method mGetValue = resolveMethod(registry.getClass(),
                    "(Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/Object;",
                    "getValue", "m_194117_");
            Method mGet = resolveMethod(registry.getClass(),
                    "(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;",
                    "get", "m_194052_");
            if (mGetValue == null || mGet == null) {
                reflectFailed = true;
                AgentLog.log("[PreatorSpoof] could not resolve MappedRegistry.getValue/get"
                        + " — item-id spoof disabled");
                return;
            }

            Field registriesKeyField = namedOrTypedField(registries, "ITEM", rk, "Item");
            Object registriesItemKey = null;
            if (registriesKeyField != null) {
                try {
                    registriesItemKey = registriesKeyField.get(null);
                } catch (Throwable ignored) {}
            }
            if (registriesItemKey == null) {
                deferTransient("[PreatorSpoof] Registries.ITEM key not available yet — deferring");
                return;
            }

            Map<String, Object> fakeRl = new HashMap<>();
            Map<String, Object> realRl = new HashMap<>();
            Map<String, Object> fakeRkOpt = new HashMap<>();
            for (String n : SUIT_NAMES) {
                String fake = FAKE_NS + ":" + n;
                String real = REAL_NS + ":" + n;
                Object fRl = mParse.invoke(null, fake);
                fakeRl.put(fake, fRl);
                realRl.put(real, mParse.invoke(null, real));
                fakeRkOpt.put(fake, Optional.of(mRkCreate.invoke(null, registriesItemKey, fRl)));
            }

            M_KEY_LOCATION = mKeyLocation;
            M_RK_CREATE = mRkCreate;
            M_REG_GET_VALUE_RL = mGetValue;
            M_REG_GET_RL = mGet;
            FAKE_RL = fakeRl;
            REAL_RL = realRl;
            FAKE_RK_OPT = fakeRkOpt;
            ITEM_REGISTRY = itemRegistry;
            bootstrapPending = false;
            reflectReady = true;
        } catch (Throwable t) {
            reflectFailed = true;
            AgentLog.logThrowable("[PreatorSpoof] reflection init failed — item-id spoof disabled", t);
        }
    }

    private static volatile boolean bootstrapPending = false;
    private static volatile long lastTransientLog = 0L;

    private static void deferTransient(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastTransientLog > 5000L) {
            lastTransientLog = now;
            AgentLog.log(msg);
        }
        bootstrapPending = true;
    }

    private static volatile Class<?> BOOTSTRAP_CLASS;
    private static volatile Method BOOTSTRAP_METHOD;
    private static volatile Field BOOTSTRAP_FLAG;

    private static Boolean bootstrapReady(Object context) {
        try {
            if (BOOTSTRAP_CLASS == null) {
                BOOTSTRAP_CLASS = resolveClass("net.minecraft.server.Bootstrap", context);
                if (BOOTSTRAP_CLASS == null) BOOTSTRAP_CLASS = resolveClass("net.minecraft.Bootstrap", context);
                if (BOOTSTRAP_CLASS == null) return null;
            }
            if (BOOTSTRAP_METHOD == null && BOOTSTRAP_FLAG == null) {
                try {
                    BOOTSTRAP_METHOD = BOOTSTRAP_CLASS.getMethod("isBootstrapped");
                } catch (NoSuchMethodException nsme) {
                    try {
                        BOOTSTRAP_FLAG = BOOTSTRAP_CLASS.getField("bootstrapped");
                    } catch (NoSuchFieldException nsfe) {
                        return null; // version with neither signal — proceed
                    }
                }
            }
            if (BOOTSTRAP_METHOD != null) return (Boolean) BOOTSTRAP_METHOD.invoke(null);
            if (BOOTSTRAP_FLAG != null) return (Boolean) BOOTSTRAP_FLAG.get(null);
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method resolveMethod(Class<?> cls, String desc, String... names) {
        Method fallback = null;
        for (Method m : cls.getMethods()) {
            if (!Type.getMethodDescriptor(m).equals(desc)) continue;
            for (String n : names) {
                if (m.getName().equals(n)) return m;
            }
            if (fallback == null) fallback = m;
        }
        return fallback;
    }

    private static Field namedOrTypedField(Class<?> cls, String name, Class<?> type, String genericMarker) {
        try {
            return cls.getField(name);
        } catch (Throwable ignored) {}
        if (type != null) {
            for (Field f : cls.getFields()) {
                if (!type.isAssignableFrom(f.getType())) continue;
                if (genericMarker != null && !f.getGenericType().getTypeName().contains(genericMarker)) continue;
                return f;
            }
        }
        return null;
    }

    private static void ensureItems(Object registry) {
        if (itemsLoaded) return;
        synchronized (LOCK) {
            if (itemsLoaded) return;
            try {
                boolean all = true;
                for (String n : SUIT_NAMES) {
                    String fake = FAKE_NS + ":" + n;
                    if (FAKE_TO_ITEM.containsKey(fake)) continue;
                    // real-key lookup falls straight through our own guard, so
                    // this returns the genuinely registered item — never a loop.
                    Object item = M_REG_GET_VALUE_RL.invoke(registry, REAL_RL.get(REAL_NS + ":" + n));
                    if (item == null) {
                        all = false;
                        continue;
                    }
                    FAKE_TO_ITEM.put(fake, item);
                    ITEM_TO_FAKE_RL.put(item, FAKE_RL.get(fake));
                }
                if (all && FAKE_TO_ITEM.size() == SUIT_NAMES.length) itemsLoaded = true;
            } catch (Throwable t) {
                AgentLog.logThrowable("[PreatorSpoof] item resolution failed", t);
            }
        }
    }

    private static Object realItemFor(Object registry, String fakeKey) {
        ensureItems(registry);
        return FAKE_TO_ITEM.get(fakeKey);
    }

    private static String keyLocationString(Object resourceKey) {
        try {
            return String.valueOf(M_KEY_LOCATION.invoke(resourceKey));
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> resolveClass(String name, Object context) {
        ClassLoader[] candidates = {
                context != null ? context.getClass().getClassLoader() : null,
                Thread.currentThread().getContextClassLoader(),
                PreatorSpoof.class.getClassLoader()
        };
        for (ClassLoader cl : candidates) {
            if (cl == null) continue;
            try {
                return Class.forName(name, false, cl);
            } catch (Throwable ignored) {}
        }
        try {
            return Class.forName(name, false, null);
        } catch (Throwable ignored) {
            return null;
        }
    }
}