package io.github.osamudazai666.skipintro;

import android.content.Context;

final class App {
    private static Context context;

    private App() {
    }

    /** The application context, found through ActivityThread without needing a hook in Application. */
    static synchronized Context context() {
        if (context != null) return context;
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object app = activityThread.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) context = ((Context) app).getApplicationContext();
        } catch (Throwable ignored) {
        }
        return context;
    }
}
