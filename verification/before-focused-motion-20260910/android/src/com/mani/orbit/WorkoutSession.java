package com.mani.orbit;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.Locale;

/** One authoritative workout state for WebView and notification actions. */
public final class WorkoutSession {
    private static final Object LOCK = new Object();
    private static final String CHANNEL = "orbit-workout";
    private static final int NOTIFICATION = 41;
    private final Context context;
    private final MainActivity activity;
    private final SharedPreferences preferences;

    WorkoutSession(Context context) {
        this.context = context;
        activity = context instanceof MainActivity ? (MainActivity) context : null;
        preferences = context.getSharedPreferences("orbit-workouts", Context.MODE_PRIVATE);
    }

    private static boolean number(JSONObject row, String key) throws Exception {
        Object value = row.get(key);
        return value instanceof Number && Double.isFinite(((Number)value).doubleValue()) && ((Number)value).doubleValue() >= 0;
    }

    private static boolean target(double value) {
        return value == 0 || Double.isFinite(value) && value == Math.floor(value) && value >= 60000 && value <= 86400000;
    }

    private static void validateSession(JSONObject row, boolean active) throws Exception {
        if (!Arrays.asList("Walking", "Running", "Cycling", "Strength").contains(row.getString("kind")) ||
                !number(row,"startedAt") || !number(row,"elapsed") || !target(row.optDouble("targetMs",0))) throw new Exception("Invalid workout");
        if (active ? !row.has("resumedAt") || !row.isNull("resumedAt") && !number(row,"resumedAt") : !number(row,"endedAt")) throw new Exception("Invalid time");
    }

    private static JSONObject decode(String raw) throws Exception {
        // ponytail: one JSON history up to 4 MB; use the native Health database for larger histories.
        if (raw == null || raw.length() > 4 * 1024 * 1024) throw new Exception("History unavailable");
        JSONObject value = new JSONObject(raw);
        if (!value.has("active")) throw new Exception("Missing state");
        if (!value.isNull("active")) validateSession(value.getJSONObject("active"), true);
        JSONArray history = value.getJSONArray("history");
        for (int i=0;i<history.length();i++) validateSession(history.getJSONObject(i), false);
        return value;
    }

    @JavascriptInterface public String read() { synchronized (LOCK) { return preferences.getString("sessions", null); } }

    /** Only the first migration may replace a whole store. Actions then own all updates. */
    @JavascriptInterface public boolean write(String raw) {
        synchronized (LOCK) {
            try {
                decode(raw);
                String existing = read();
                if (existing != null) return existing.equals(raw);
                return preferences.edit().putString("sessions", raw).commit();
            } catch (Exception error) { return false; }
        }
    }

    @JavascriptInterface public String action(String name, String kind, double targetMs) {
        String result = change(name, kind, targetMs, -1);
        if (result != null && "start".equals(name) && activity != null) activity.runOnUiThread(this::askPermission);
        return result;
    }

    private int bootCount() { return Settings.Global.getInt(context.getContentResolver(),Settings.Global.BOOT_COUNT,-1); }

    private long elapsed(JSONObject row, long now) throws Exception {
        if (row.isNull("resumedAt")) return row.getLong("elapsed");
        boolean sameBoot = row.has("resumedRealtime") && row.optInt("bootCount",-2)==bootCount();
        long delta = sameBoot ? SystemClock.elapsedRealtime()-row.getLong("resumedRealtime") : now-row.getLong("resumedAt");
        return Math.max(0,row.getLong("elapsed")+Math.max(0,delta));
    }

    private JSONObject resume(JSONObject row, long now) throws Exception {
        return row.put("resumedAt",now).put("resumedRealtime",SystemClock.elapsedRealtime()).put("bootCount",bootCount());
    }

    @JavascriptInterface public double elapsedMs() {
        synchronized (LOCK) {
            try { JSONObject active=decode(read()).optJSONObject("active"); return active==null ? 0 : elapsed(active,System.currentTimeMillis()); }
            catch (Exception error) { return -1; }
        }
    }

    private String change(String name, String kind, double targetMs, long expectedStart) {
        synchronized (LOCK) {
            try {
                String raw = read();
                JSONObject value = raw == null ? new JSONObject("{\"active\":null,\"history\":[]}") : decode(raw);
                JSONObject active = value.optJSONObject("active");
                long now = System.currentTimeMillis();
                if (expectedStart >= 0 && (active == null || active.getLong("startedAt") != expectedStart)) return null;
                if ("start".equals(name)) {
                    if (active != null || !target(targetMs)) return null;
                    JSONObject session = new JSONObject().put("kind",kind).put("startedAt",now).put("elapsed",0).put("resumedAt",now).put("targetMs",targetMs);
                    resume(session,now);
                    validateSession(session,true);
                    value.put("active",session);
                } else {
                    if (active == null) return null;
                    if ("pause".equals(name) && !active.isNull("resumedAt")) active.put("elapsed",elapsed(active,now)).put("resumedAt",JSONObject.NULL);
                    else if ("resume".equals(name) && active.isNull("resumedAt")) resume(active,now);
                    else if ("finish".equals(name)) {
                        JSONObject finished = new JSONObject(active.toString()).put("elapsed",elapsed(active,now)).put("endedAt",now);
                        finished.remove("resumedAt");
                        finished.remove("resumedRealtime");
                        finished.remove("bootCount");
                        JSONArray history = new JSONArray().put(finished), previous = value.getJSONArray("history");
                        for (int i=0;i<previous.length();i++) history.put(previous.get(i));
                        value.put("history",history).put("active",JSONObject.NULL);
                    } else return null;
                }
                String next = value.toString();
                decode(next);
                if (!preferences.edit().putString("sessions",next).commit()) return null;
                updateNotification();
                return next;
            } catch (Exception error) { return null; }
        }
    }

    @JavascriptInterface public String notificationStatus() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        if (!manager.areNotificationsEnabled() || channel != null && channel.getImportance()==NotificationManager.IMPORTANCE_NONE) return "disabled";
        return Build.VERSION.SDK_INT >= 36 && manager.canPostPromotedNotifications() ? "live" : "standard";
    }

    private void askPermission() {
        if (activity == null) return;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},41);
        else updateNotification();
    }

    @JavascriptInterface public void enableNotifications() {
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            Intent settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
            activity.startActivity(settings);
        });
    }

    private PendingIntent actionIntent(String action, long startedAt) {
        Intent intent = new Intent(context,Actions.class).setAction(action).putExtra("startedAt",startedAt);
        return PendingIntent.getBroadcast(context,action.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }

    public void updateNotification() {
        synchronized (LOCK) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            try {
                String raw = read();
                JSONObject active = raw == null ? null : decode(raw).optJSONObject("active");
                if (active == null) { manager.cancel(NOTIFICATION); return; }
                manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Live workouts",NotificationManager.IMPORTANCE_DEFAULT));
                if ("disabled".equals(notificationStatus())) return;
                long duration = elapsed(active,System.currentTimeMillis()),started = active.getLong("startedAt");
                boolean paused = active.isNull("resumedAt");
                String title = active.getString("kind"),description = paused ? "Paused · "+clock(duration) : "Workout in progress";
                long targetMs = active.optLong("targetMs",0);
                if (targetMs>0) description += " · "+targetMs/60000+" min target";
                Intent open = new Intent(context,MainActivity.class).setAction("com.mani.orbit.OPEN_WORKOUT").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent content = PendingIntent.getActivity(context,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                Notification.Builder builder = new Notification.Builder(context,CHANNEL)
                    .setSmallIcon(context.getResources().getIdentifier("workout_notification","drawable",context.getPackageName()))
                    .setContentTitle(title).setContentText(description).setStyle(new Notification.BigTextStyle().bigText(description))
                    .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_WORKOUT)
                    .setWhen(System.currentTimeMillis()-duration).setShowWhen(!paused).setUsesChronometer(!paused)
                    .addAction(new Notification.Action.Builder(null,paused?"Resume":"Pause",actionIntent(paused?"resume":"pause",started)).build())
                    .addAction(new Notification.Action.Builder(null,"Finish",actionIntent("finish",started)).build());
                if (Build.VERSION.SDK_INT >= 36) {
                    Bundle extras = new Bundle();
                    extras.putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING,true);
                    builder.addExtras(extras);
                }
                manager.notify(NOTIFICATION,builder.build());
            } catch (Exception error) {
                // Notification failure cannot roll back a successfully saved workout.
                android.util.Log.e("OrbitWorkout","Unable to refresh workout notification",error);
            }
        }
    }

    private static String clock(long ms) {
        long seconds=ms/1000;
        return String.format(Locale.UK,"%02d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60);
    }

    public static final class Actions extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            WorkoutSession session = new WorkoutSession(context);
            long expected = intent.getLongExtra("startedAt",-2);
            if (expected < 0 || session.change(intent.getAction(),"",0,expected) == null)
                Toast.makeText(context,"Workout was not changed. Open Orbit and try again.",Toast.LENGTH_LONG).show();
        }
    }
}
