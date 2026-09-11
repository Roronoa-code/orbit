package com.mani.orbit;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.net.Uri;
import org.json.JSONObject;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/** Renders the sparse workout notification without owning workout state. */
final class WorkoutNotification {
    private final Context context;
    private final WorkoutSession session;

    WorkoutNotification(Context context, WorkoutSession session) {
        this.context = context;
        this.session = session;
    }

    Notification build(JSONObject active) throws Exception {
        long now = System.currentTimeMillis();
        long duration = session.notificationElapsed(active, now);
        long started = active.getLong("startedAt");
        boolean paused = active.isNull("resumedAt");
        String title = active.getString("kind");
        String description = (paused ? "Paused" : "Recording") + " · " + clock(duration);
        long targetMs = active.optLong("targetMs", 0);
        if (targetMs > 0) description += " · " + (duration >= targetMs ? "Target reached" : clock(targetMs - duration) + " remaining");
        JSONObject metrics = active.optJSONObject("metrics");
        if (metrics != null) {
            double distance = metrics.optDouble("distanceM", 0);
            description += " · " + String.format(Locale.UK, "%.2f km", distance / 1000);
            String state = metrics.optString("state", "searching");
            if (!"tracking".equals(state)) description += " · GPS " + state;
        }
        String resumeToken = active.isNull("resumeToken") ? null : active.getString("resumeToken");
        Intent open = new Intent(context, MainActivity.class).setAction("com.mani.orbit.OPEN_WORKOUT")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(context, WorkoutSession.CHANNEL)
            .setSmallIcon(context.getResources().getIdentifier("workout_notification", "drawable", context.getPackageName()))
            .setLargeIcon(workoutIcon(title)).setContentTitle(title).setContentText(description)
            .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true).setAutoCancel(false)
            .setCategory(Notification.CATEGORY_WORKOUT).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setColor(0xFF777B82).setColorized(false).setWhen(now - duration)
            .setShowWhen(!paused).setUsesChronometer(!paused).setChronometerCountDown(false)
            .addAction(new Notification.Action.Builder(null, paused ? "Resume" : "Pause", actionIntent(paused ? "resume" : "pause", started, resumeToken)).build())
            .addAction(new Notification.Action.Builder(null, "Finish", actionIntent("finish", started, null)).build());
        if (Build.VERSION.SDK_INT >= 37) builder.setStyle(metricStyle(duration, targetMs, paused, session.notificationSameBoot(active), metrics));
        else builder.setStyle(new Notification.BigTextStyle().bigText(description));
        if (Build.VERSION.SDK_INT >= 36) {
            Bundle extras = new Bundle();
            extras.putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true);
            builder.addExtras(extras);
        }
        return builder.build();
    }

    private PendingIntent actionIntent(String action, long startedAt, String resumeToken) {
        if ("resume".equals(action)) {
            Intent open = new Intent(context, MainActivity.class).setAction("com.mani.orbit.OPEN_WORKOUT")
                .putExtra("resumeStartedAt", startedAt)
                .putExtra("resumeToken", resumeToken)
                .setData(Uri.parse("orbit://workout/resume/" + startedAt + "/" + resumeToken))
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            return PendingIntent.getActivity(context, action.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        Intent intent = new Intent(context, WorkoutSession.Actions.class).setAction(action)
            .setData(Uri.parse("orbit://workout/" + action + "/" + startedAt)).putExtra("startedAt", startedAt);
        return PendingIntent.getBroadcast(context, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Notification.MetricStyle metricStyle(long duration, long targetMs, boolean paused, boolean sameBoot, JSONObject metrics) throws Exception {
        Notification.MetricStyle style = new Notification.MetricStyle();
        int format = Notification.Metric.TimeDifference.FORMAT_CHRONOMETER;
        Notification.Metric.TimeDifference active = paused
            ? Notification.Metric.TimeDifference.forPausedStopwatch(Duration.ofMillis(duration), format)
            : sameBoot
                ? Notification.Metric.TimeDifference.forStopwatch(SystemClock.elapsedRealtime() - duration, format)
                : Notification.Metric.TimeDifference.forStopwatch(Instant.ofEpochMilli(System.currentTimeMillis() - duration), format);
        style.addMetric(new Notification.Metric(active, "Duration"));
        style.setCriticalMetric(0);
        if (targetMs > 0) style.addMetric(new Notification.Metric(new Notification.Metric.FixedText(clock(targetMs), ""), "Target"));
        if (metrics != null) {
            double distance = metrics.optDouble("distanceM", 0);
            style.addMetric(new Notification.Metric(new Notification.Metric.FixedText(String.format(Locale.UK, "%.2f", distance / 1000), "km"), "Distance"));
        }
        return style;
    }

    private Bitmap workoutIcon(String kind) {
        int id = context.getResources().getIdentifier("workout_" + kind.toLowerCase(Locale.ROOT), "drawable", context.getPackageName());
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inScaled = false;
        Bitmap source = BitmapFactory.decodeResource(context.getResources(), id, decode);
        if (source == null) return null;
        Bitmap icon = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColorFilter(new PorterDuffColorFilter(0xFFD7D9DE, PorterDuff.Mode.SRC_IN));
        new Canvas(icon).drawBitmap(source, 0, 0, paint);
        return icon;
    }

    private static String clock(long ms) {
        long seconds = ms / 1000;
        return String.format(Locale.UK, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
}
