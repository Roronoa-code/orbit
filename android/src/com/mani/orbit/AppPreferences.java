package com.mani.orbit;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;

/** Small app settings, acknowledged only after Android finishes writing them to disk. */
public final class AppPreferences {
    private final SharedPreferences preferences;

    AppPreferences(Context context) {
        preferences = context.getSharedPreferences("orbit-settings", Context.MODE_PRIVATE);
    }

    static boolean allowed(String key) {
        return "orbit-profile-v1".equals(key) || "orbit-steps-goal-v1".equals(key)
            || "orbit-reduce-motion-v1".equals(key);
    }

    @JavascriptInterface public synchronized String read(String key) {
        if (!allowed(key)) throw new IllegalArgumentException("Unknown setting");
        return preferences.getString(key, null);
    }

    @JavascriptInterface public synchronized boolean write(String key, String value) {
        if (!allowed(key) || value == null || value.length() > 4096) return false;
        try { return preferences.edit().putString(key, value).commit(); }
        catch (RuntimeException unavailable) { return false; }
    }
}
