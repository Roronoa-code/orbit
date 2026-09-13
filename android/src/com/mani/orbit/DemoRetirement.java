package com.mani.orbit;

import android.content.Context;
import android.content.SharedPreferences;
import android.app.NotificationManager;
import android.content.Intent;

/** One-time transition requested by the user; the original test sessions remain recoverable. */
final class DemoRetirement {
    static boolean apply(Context context) {
        synchronized (WorkoutSession.LOCK) {
            SharedPreferences prefs = context.getSharedPreferences("orbit-workouts", Context.MODE_PRIVATE);
            if (prefs.getBoolean("real-data-v1", false)) return true;
            SharedPreferences.Editor edit = prefs.edit();
            String previous = prefs.getString("sessions", null);
            if (previous != null) edit.putString("test-sessions-backup-v1", previous);
            if (!edit.putString("sessions", "{\"active\":null,\"history\":[]}").putBoolean("real-data-v1", true).commit()) return false;
            context.stopService(new Intent(context, WorkoutTrackingService.class));
            context.getSystemService(NotificationManager.class).cancel(WorkoutSession.NOTIFICATION);
            return true;
        }
    }
}
