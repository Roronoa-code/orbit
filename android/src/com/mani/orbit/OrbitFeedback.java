package com.mani.orbit;

import android.app.Activity;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

/** Semantic, permission-free feedback, always respecting Android's touch feedback setting. */
public final class OrbitFeedback {
    private final Activity activity;
    private final WebView view;
    private long last;

    OrbitFeedback(Activity activity, WebView view) { this.activity = activity; this.view = view; }

    @JavascriptInterface public void pulse(String kind) {
        final int effect;
        if ("tick".equals(kind)) effect = HapticFeedbackConstants.CLOCK_TICK;
        else if ("select".equals(kind)) effect = HapticFeedbackConstants.VIRTUAL_KEY;
        else if ("confirm".equals(kind)) effect = HapticFeedbackConstants.CONFIRM;
        else return;
        activity.runOnUiThread(() -> {
            long now = SystemClock.uptimeMillis();
            if (!view.hasWindowFocus() || !view.isShown() || now - last < 50) return;
            last = now;
            view.performHapticFeedback(effect);
        });
    }
}
