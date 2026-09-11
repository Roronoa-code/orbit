package com.mani.orbit;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.util.Base64;
import android.util.Size;
import android.webkit.JavascriptInterface;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.json.JSONObject;

/** Local controls for the phone's active media session. No notification contents or history are read. */
public final class MusicSession {
    private final Activity activity;
    private final MediaSessionManager manager;
    private final ComponentName listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaController controller;
    private MediaSession.Token token;
    private long sessionId, artVersion;
    private boolean foreground, metadataDirty = true;
    private String title = "", artist = "", source = "", art = "", trackKey = "";
    private long duration;
    private MediaController.Callback callback;

    public MusicSession(Activity host) {
        activity = host;
        manager = host.getSystemService(MediaSessionManager.class);
        listener = new ComponentName(host, Access.class);
    }

    // Android requires an enabled listener to expose other apps' media sessions. Ignore all notifications.
    public static final class Access extends NotificationListenerService { }

    private boolean allowed() {
        return activity.getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(listener);
    }

    public synchronized void resume() { foreground = true; }
    public synchronized void suspend() { foreground = false; clear(); }

    private void clear() {
        if (controller != null) controller.unregisterCallback(callback);
        controller = null; token = null; art = ""; metadataDirty = true;
        title = ""; artist = ""; source = ""; duration = 0; artVersion++;
    }

    private void select() {
        List<MediaController> sessions = manager.getActiveSessions(listener);
        MediaController chosen = sessions.isEmpty() ? null : sessions.get(0);
        for (MediaController item : sessions) {
            PlaybackState state = item.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) { chosen = item; break; }
        }
        if (chosen == null) { clear(); return; }
        if (!chosen.getSessionToken().equals(token)) {
            clear(); controller = chosen; token = chosen.getSessionToken(); sessionId++;
            final MediaSession.Token selectedToken = token;
            callback = new MediaController.Callback() {
                @Override public void onMetadataChanged(MediaMetadata metadata) { synchronized (MusicSession.this) { if (selectedToken.equals(token)) metadataDirty = true; } }
                @Override public void onSessionDestroyed() { synchronized (MusicSession.this) { if (selectedToken.equals(token)) clear(); } }
            };
            controller.registerCallback(callback, main);
            source = chosen.getPackageName();
            try { source = activity.getPackageManager().getApplicationLabel(activity.getPackageManager().getApplicationInfo(source, 0)).toString(); }
            catch (android.content.pm.PackageManager.NameNotFoundException ignored) { /* Package name remains usable. */ }
        }
    }

    private static String text(CharSequence value) { return value == null ? "" : value.toString(); }
    private static String trackKey(MediaMetadata data) {
        return data == null ? "" : text(data.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)) + "\n" + text(data.getDescription().getTitle()) + "\n" + text(data.getDescription().getSubtitle()) + "\n" + data.getLong(MediaMetadata.METADATA_KEY_DURATION);
    }

    private void metadata() {
        if (!metadataDirty) return;
        metadataDirty = false; art = ""; artVersion++;
        MediaMetadata data = controller.getMetadata();
        trackKey = trackKey(data);
        title = ""; artist = ""; duration = 0;
        if (data == null) return;
        title = text(data.getDescription().getTitle());
        artist = text(data.getString(MediaMetadata.METADATA_KEY_ARTIST));
        if (artist.isEmpty()) artist = text(data.getDescription().getSubtitle());
        duration = Math.max(0, data.getLong(MediaMetadata.METADATA_KEY_DURATION));
        Bitmap bitmap = data.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bitmap == null) bitmap = data.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (bitmap == null) bitmap = data.getDescription().getIconBitmap();
        boolean owned = false;
        try {
            if (bitmap == null) {
                Uri uri = data.getDescription().getIconUri();
                if (uri != null && "content".equals(uri.getScheme())) {
                    bitmap = activity.getContentResolver().loadThumbnail(uri, new Size(512, 512), null); owned = true;
                }
            }
            if (bitmap == null || bitmap.isRecycled()) return;
            float scale = Math.min(1f, 512f / Math.max(bitmap.getWidth(), bitmap.getHeight()));
            Bitmap small = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)), Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (small.compress(Bitmap.CompressFormat.JPEG, 85, bytes)) art = "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
            if (small != bitmap) small.recycle();
        } catch (java.io.IOException | RuntimeException unavailable) {
            art = ""; // Missing or unshared artwork is represented explicitly in the player.
        } finally { if (owned && bitmap != null) bitmap.recycle(); }
    }

    @JavascriptInterface public synchronized String read(String knownArt) {
        try {
            if (!foreground) return "{\"status\":\"inactive\"}";
            if (!allowed()) { clear(); return "{\"status\":\"permission\"}"; }
            select();
            if (controller == null) return "{\"status\":\"idle\"}";
            metadata();
            PlaybackState state = controller.getPlaybackState();
            long actions = state == null ? 0 : state.getActions();
            boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
            boolean buffering = state != null && (state.getState() == PlaybackState.STATE_BUFFERING || state.getState() == PlaybackState.STATE_CONNECTING);
            long position = state == null ? 0 : Math.max(0, state.getPosition());
            if (playing && state.getLastPositionUpdateTime() > 0) position += (long)(Math.max(0, SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
            position = Math.max(0, duration > 0 ? Math.min(duration, position) : position);
            JSONObject result = new JSONObject().put("status", "ready").put("id", sessionId + ":" + artVersion)
                .put("title", title).put("artist", artist).put("source", source).put("duration", duration).put("position", position)
                .put("playing", playing).put("buffering", buffering).put("canOpen", controller.getSessionActivity() != null)
                .put("canToggle", (actions & (PlaybackState.ACTION_PLAY_PAUSE | (playing ? PlaybackState.ACTION_PAUSE : PlaybackState.ACTION_PLAY))) != 0)
                .put("canPrevious", (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0)
                .put("canNext", (actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0)
                .put("canSeek", duration > 0 && (actions & PlaybackState.ACTION_SEEK_TO) != 0)
                .put("artKey", Long.toString(artVersion));
            if (!Long.toString(artVersion).equals(knownArt)) result.put("art", art);
            return result.toString();
        } catch (Exception unavailable) { clear(); return "{\"status\":\"error\"}"; }
    }

    @JavascriptInterface public synchronized boolean command(String id, String action, double value) {
        try {
            if (!foreground || !allowed()) return false;
            select();
            if (controller == null) return false;
            if (!trackKey.equals(trackKey(controller.getMetadata()))) metadataDirty = true;
            metadata();
            if (!(sessionId + ":" + artVersion).equals(id)) return false;
            PlaybackState state = controller.getPlaybackState();
            long actions = state == null ? 0 : state.getActions();
            MediaController.TransportControls controls = controller.getTransportControls();
            if ("open".equals(action) && controller.getSessionActivity() != null) { controller.getSessionActivity().send(); return true; }
            if ("play".equals(action) && (actions & (PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE)) != 0) controls.play();
            else if ("pause".equals(action) && (actions & (PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)) != 0) controls.pause();
            else if ("previous".equals(action) && (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0) controls.skipToPrevious();
            else if ("next".equals(action) && (actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0) controls.skipToNext();
            else if ("seek".equals(action) && (actions & PlaybackState.ACTION_SEEK_TO) != 0) {
                metadata(); if (!Double.isFinite(value) || value < 0 || duration <= 0 || value > duration) return false;
                controls.seekTo((long)value);
            } else return false;
            return true;
        } catch (Exception unavailable) { return false; }
    }

    @JavascriptInterface public void connect() {
        activity.runOnUiThread(() -> {
            try {
                Intent settings = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString());
                activity.startActivity(settings);
            } catch (RuntimeException unavailable) {
                try { activity.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
                catch (RuntimeException failed) { Toast.makeText(activity, "Open Settings > Notification access > Orbit", Toast.LENGTH_LONG).show(); }
            }
        });
    }
}
