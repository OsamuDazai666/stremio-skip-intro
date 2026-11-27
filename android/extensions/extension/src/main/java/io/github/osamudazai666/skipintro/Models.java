package io.github.osamudazai666.skipintro;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;

/**
 * Reflection access to Stremio's core models (com.stremio.core.*). Those classes are generated
 * from protobuf and are not obfuscated, so their getter names are stable across app versions.
 */
final class Models {
    private Models() {
    }

    static Object call(Object target, String method) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    static String string(Object target, String method) {
        Object v = call(target, method);
        return v instanceof String ? (String) v : null;
    }

    static long number(Object target, String method, long fallback) {
        Object v = call(target, method);
        return v instanceof Number ? ((Number) v).longValue() : fallback;
    }

    static Object getIntro(Object introOutro) {
        return call(introOutro, "getIntro");
    }

    /**
     * Builds {@code new Player.IntroOutro(new Player.IntroData(from, to, duration, {}), outro, {})}
     * with times in milliseconds. Classes are loaded from the same class loader as the original.
     */
    static Object newIntroOutro(Object original, Lookup.Times times) throws Exception {
        ClassLoader loader = original != null ? original.getClass().getClassLoader() : Models.class.getClassLoader();
        Class<?> introOutroClass = Class.forName("com.stremio.core.models.Player$IntroOutro", true, loader);
        Class<?> introDataClass = Class.forName("com.stremio.core.models.Player$IntroData", true, loader);

        Object intro = null;
        if (times.introStartMs >= 0 && times.introEndMs > times.introStartMs) {
            Constructor<?> c = introDataClass.getConstructor(long.class, long.class, Long.class, java.util.Map.class);
            Long duration = times.durationMs > 0 ? times.durationMs : null;
            intro = c.newInstance(times.introStartMs, times.introEndMs, duration, Collections.emptyMap());
        }
        Long outro = times.outroStartMs >= 0 ? times.outroStartMs : null;
        if (intro == null && outro == null) return null;

        Constructor<?> c = introOutroClass.getConstructor(introDataClass, Long.class, java.util.Map.class);
        return c.newInstance(intro, outro, Collections.emptyMap());
    }
}
