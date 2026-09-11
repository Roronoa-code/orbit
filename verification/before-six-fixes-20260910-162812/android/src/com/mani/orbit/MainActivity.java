package com.mani.orbit;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Offline, separately installed host for the Orbit HTML design. */
public final class MainActivity extends Activity {
    private static final String PAGE = "https://orbit.invalid/index.html";
    private WebView web;
    private WorkoutSession workouts;
    private MusicSession music;
    private boolean pageReady;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(10, 10, 12));
        getWindow().setDecorFitsSystemWindows(false);
        WindowManager.LayoutParams layout = getWindow().getAttributes();
        layout.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setAttributes(layout);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
            view.setPadding(0, 0, 0, keyboard.bottom);
            return insets;
        });
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(8, 15, 21));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        // Only the bundled offline page can load; no remote page or frame receives this bridge.
        workouts = new WorkoutSession(this);
        web.addJavascriptInterface(workouts, "OrbitWorkouts");
        music = new MusicSession(this);
        web.addJavascriptInterface(music, "OrbitMusic");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) { pageReady = true; openWorkoutIntent(); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !PAGE.equals(request.getUrl().toString());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (PAGE.equals(request.getUrl().toString()) && "GET".equals(request.getMethod())) {
                    try {
                        return new WebResourceResponse("text/html", "UTF-8", getAssets().open("index.html"));
                    } catch (IOException error) {
                        return textResponse(500, "App file unavailable", "Orbit could not open its bundled page. Please reinstall the Orbit APK.");
                    }
                }
                return textResponse(404, "Not found", "");
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        enterImmersive();
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::goBack);
        }
        web.loadUrl(PAGE);
    }

    private static WebResourceResponse textResponse(int status, String reason, String text) {
        return new WebResourceResponse("text/plain", "UTF-8", status, reason, null,
            new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private void openWorkoutIntent() {
        if (pageReady && "com.mani.orbit.OPEN_WORKOUT".equals(getIntent().getAction())) {
            web.evaluateJavascript("Health.refresh();Health.open('workouts');", null);
            getIntent().setAction(null);
        }
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); openWorkoutIntent(); }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        workouts.updateNotification();
        if (pageReady) web.evaluateJavascript("Health.render();", null);
    }

    private void goBack() {
        web.evaluateJavascript("(() => {if(closeInline())return true;const b=document.querySelector('#back');if(b&&!b.disabled){b.click();return true;}return false;})()",
            handled -> { if (!"true".equals(handled)) finish(); });
    }

    @Override public void onBackPressed() { goBack(); }
    private void enterImmersive() {
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsets.Type.systemBars());
        }
    }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (focused) enterImmersive(); }
    @Override protected void onPause() { music.suspend(); web.onPause(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (music != null) music.resume(); if (web != null) { web.onResume(); if (pageReady) web.evaluateJavascript("Health.refresh();MusicPlayer.refresh();",null); } if (workouts != null) workouts.updateNotification(); enterImmersive(); }
    @Override protected void onDestroy() { music.suspend(); web.destroy(); super.onDestroy(); }
}
