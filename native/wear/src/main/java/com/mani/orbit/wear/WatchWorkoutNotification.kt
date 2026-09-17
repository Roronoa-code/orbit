package com.mani.orbit.wear

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.mani.orbit.sync.WatchWorkout

/** One system-owned stopwatch and return surface, updated only when recording state changes. */
internal class WatchWorkoutNotification(private val context: Context, channel: String, private val id: Int) {
    private val returnIntent = PendingIntent.getActivity(context, 0,
        Intent(context, WatchWorkoutActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private val builder = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.orbit_workout)
        .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(returnIntent).setCategory(NotificationCompat.CATEGORY_WORKOUT)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

    @Synchronized fun build(w: WatchWorkout?): Notification {
        val status = status(w)
        builder.setContentTitle(w?.kind ?: "Orbit workout").setContentText(when (w?.phase) {
            "active" -> "Recording · tap to return"; "paused" -> "Paused · tap to return"
            "ended", "interrupted" -> "Workout saved · finishing sensor estimate"
            else -> "Connecting to Health Services"
        })
        // The service publishes this notification with startForeground. OngoingActivity.update would
        // post it a second time and require notification permission independently of recording.
        OngoingActivity.Builder(context, id, builder).setOngoingActivityId(id)
            .setStaticIcon(R.drawable.orbit_workout).setTouchIntent(returnIntent).setStatus(status).build().apply(context)
        return builder.build()
    }

    companion object {
        internal fun status(w: WatchWorkout?): Status {
            val builder = Status.Builder()
            if (w != null && w.phase in setOf("active", "paused")) {
                builder.addTemplate("#kind# · #time#${if (w.phase == "paused") " · Paused" else ""}")
                    .addPart("kind", Status.TextPart(w.kind))
                    .addPart("time", Status.StopwatchPart(w.updatedElapsed - w.activeMs,
                        if (w.phase == "paused") w.updatedElapsed else -1))
            } else builder.addTemplate("#state#").addPart("state", Status.TextPart(
                if (w?.terminal == true) "Workout saved" else "Starting workout"))
            return builder.build()
        }
    }
}
