package com.mani.orbit;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
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
import android.webkit.JavascriptInterface;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.json.JSONObject;

/** Local controls for the phone's active media session. No notification contents or history are read. */
public final class MusicSession {
    private final Activity activity;
    private final MediaSessionManager manager;
    private final ComponentName listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService artworkWorker = Executors.newSingleThreadExecutor();
    private Future<?> artworkTask;
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
    public synchronized void close() { suspend(); artworkWorker.shutdownNow(); }

    private void clear() {
        if (artworkTask != null) artworkTask.cancel(true);
        artworkTask = null;
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
        if (artworkTask != null) artworkTask.cancel(true);
        artworkTask = null;
        MediaMetadata data = controller.getMetadata();
        trackKey = trackKey(data);
        title = ""; artist = ""; duration = 0;
        if (data == null) return;
        title = text(data.getDescription().getTitle());
        artist = text(data.getString(MediaMetadata.METADATA_KEY_ARTIST));
        if (artist.isEmpty()) artist = text(data.getDescription().getSubtitle());
        duration = Math.max(0, data.getLong(MediaMetadata.METADATA_KEY_DURATION));
        final long version = artVersion;
        // Never decode/compress inside the synchronous JavaScript read: it stalls page animation.
        artworkTask = artworkWorker.submit(() -> {
            String encoded = artwork(data);
            synchronized (MusicSession.this) {
                if (foreground && artVersion == version) { art = encoded; artworkTask = null; }
            }
        });
    }

    static int[] artworkSize(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid artwork size");
        double scale = Math.min(1.0, 2048.0 / Math.max(width, height));
        return new int[]{Math.max(1, (int)Math.round(width * scale)), Math.max(1, (int)Math.round(height * scale))};
    }

    private String artwork(MediaMetadata data) {
        Bitmap best = null;
        boolean owned = false;
        try {
            for (String key : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
                Bitmap candidate = data.getBitmap(key);
                if (candidate != null && !candidate.isRecycled() && (best == null || (long)candidate.getWidth() * candidate.getHeight() > (long)best.getWidth() * best.getHeight())) best = candidate;
            }
            // Android may downsample embedded bitmaps; an accessible content URI can retain the original.
            for (String key : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) {
                if (Thread.currentThread().isInterrupted()) return "";
                if (best != null && Math.max(best.getWidth(), best.getHeight()) >= 2048) break;
                String value = data.getString(key);
                if (value == null) continue;
                Uri uri = Uri.parse(value);
                if (!"content".equals(uri.getScheme())) continue;
                try {
                    Bitmap candidate = ImageDecoder.decodeBitmap(ImageDecoder.createSource(activity.getContentResolver(), uri), (decoder, info, source) -> {
                        int[] size = artworkSize(info.getSize().getWidth(), info.getSize().getHeight());
                        decoder.setTargetSize(size[0], size[1]);
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
                    if (best == null || (long)candidate.getWidth() * candidate.getHeight() > (long)best.getWidth() * best.getHeight()) {
                        if (owned) best.recycle();
                        best = candidate; owned = true;
                    } else candidate.recycle();
                } catch (java.io.IOException | RuntimeException unavailable) { /* Retain the best shared bitmap. */ }
            }
            return best == null || Thread.currentThread().isInterrupted() ? "" : encodeArtwork(best);
        } catch (RuntimeException unavailable) { return ""; }
        finally { if (owned && best != null) best.recycle(); }
    }

    static String encodeArtwork(Bitmap original) {
        int[] size = artworkSize(original.getWidth(), original.getHeight());
        Bitmap image = Bitmap.createScaledBitmap(original, size[0], size[1], true);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (!Thread.currentThread().isInterrupted()) {
                bytes.reset();
                if (!image.compress(Bitmap.CompressFormat.JPEG, 94, bytes)) return "";
                // Keep the bridge payload below its 1.5MB text limit without magnifying small sources.
                if (bytes.size() <= 1000000) return "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
                Bitmap smaller = Bitmap.createScaledBitmap(image, Math.max(1, image.getWidth() * 3 / 4), Math.max(1, image.getHeight() * 3 / 4), true);
                if (image != original) image.recycle();
                image = smaller;
            }
            return "";
        } finally { if (image != original) image.recycle(); }
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
            result.put("artLoading", artworkTask != null);
            if (artworkTask == null && !Long.toString(artVersion).equals(knownArt)) result.put("art", art);
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
