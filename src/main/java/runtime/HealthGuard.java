package runtime;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public class HealthGuard {

    private static final Map<Object, Float> CANONICAL_HEALTH =
            Collections.synchronizedMap(new WeakHashMap<>());

    public static void recordHealth(Object entity, float value) {
        if (entity == null) return;
        if (!TrustChecker.calledFromTrustedCode()) {
            System.err.println("[HealthGuard] Blocked untrusted attempt to record health");
            return;
        }
        CANONICAL_HEALTH.put(entity, value);
    }

    public static void forget(Object entity) {
        if (entity == null) return;
        CANONICAL_HEALTH.remove(entity);
        LAST_GOOD_MAX_HEALTH.remove(entity);
    }

    public static boolean hasOverride(Object entity) {
        return entity != null && CANONICAL_HEALTH.containsKey(entity);
    }

    public static float getEnforcedHealth(Object entity) {
        Float v = CANONICAL_HEALTH.get(entity);
        return v != null ? v : 0f;
    }

    public static boolean getEnforcedAlive(Object entity) {
        return getEnforcedHealth(entity) > 0f;
    }

    public static boolean getEnforcedDeadOrDying(Object entity) {
        return getEnforcedHealth(entity) <= 0f;
    }

    private static final Map<Object, Float> LAST_GOOD_MAX_HEALTH =
            Collections.synchronizedMap(new WeakHashMap<>());

    public static float observeMaxHealth(Object entity, float computed) {
        if (entity == null) return computed;

        if (Float.isNaN(computed) || Float.isInfinite(computed) || computed <= 0f) {
            Float lastGood = LAST_GOOD_MAX_HEALTH.get(entity);
            if (lastGood != null && lastGood > 0f) {
                System.err.println("[HealthGuard] getMaxHealth() returned an implausible value ("
                        + computed + ") — substituting last-known-good " + lastGood);
                return lastGood;
            }
            return computed;
        }

        LAST_GOOD_MAX_HEALTH.put(entity, computed);
        return computed;
    }

    private static volatile Object healthAccessorRef;
    private static volatile boolean healthAccessorResolveFailed = false;

    public static Object filterEntityDataWrite(Object synchedData, Object accessor, Object value) {
        try {
            if (!(value instanceof Float newHealth)) return value; // not a health-shaped write at all

            Object healthAccessor = resolveHealthAccessor(accessor);
            if (healthAccessor == null || accessor != healthAccessor) return value; // not the health slot

            if (TrustChecker.calledFromTrustedCode()) {
                return value; // legitimate vanilla/forge/our-own write — never interfered with
            }

            if (Float.isFinite(newHealth) && newHealth > 0f) {
                return value;
            }

            Object entity = getBackingEntity(synchedData);
            Float lastGood = entity != null ? CANONICAL_HEALTH.get(entity) : null;
            if (lastGood != null && lastGood > 0f) {
                System.err.println("[HealthGuard] Blocked untrusted direct health-data write ("
                        + newHealth + ") — substituting last-known-good " + lastGood);
                return lastGood;
            }
            return value; // nothing trustworthy to fall back to — let it through rather than guess
        } catch (Throwable t) {
            return value;
        }
    }

    private static Object resolveHealthAccessor(Object contextObj) {
        Object cached = healthAccessorRef;
        if (cached != null) return cached;
        if (healthAccessorResolveFailed) return null;
        try {
            Class<?> livingEntityCls = Class.forName("net.minecraft.world.entity.LivingEntity",
                    false, contextObj.getClass().getClassLoader());
            java.lang.reflect.Field f = livingEntityCls.getDeclaredField("f_20961_"); // DATA_HEALTH_ID
            f.setAccessible(true);
            Object val = f.get(null);
            healthAccessorRef = val;
            return val;
        } catch (Throwable t) {
            healthAccessorResolveFailed = true;
            return null;
        }
    }

    private static Object getBackingEntity(Object synchedData) {
        try {
            java.lang.reflect.Field f = synchedData.getClass().getDeclaredField("f_135344_");
            f.setAccessible(true);
            return f.get(synchedData);
        } catch (Throwable t) {
            return null;
        }
    }
}