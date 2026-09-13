package com.mani.orbit;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;

/** Foreground GPS collector for an explicitly started outdoor workout. */
public final class WorkoutTrackingService extends Service {
    static final String ACTION_START = "com.mani.orbit.WORKOUT_TRACKING_START";
    static final String ACTION_RESUME = "com.mani.orbit.WORKOUT_TRACKING_RESUME";
    static final String ACTION_PAUSE = "com.mani.orbit.WORKOUT_TRACKING_PAUSE";
    static final String ACTION_STOP = "com.mani.orbit.WORKOUT_TRACKING_STOP";
    static final String EXTRA_STARTED_AT = "startedAt";
    private static final long LOCATION_INTERVAL_MS = 1000;
    private static final long MAX_SEGMENT_GAP_NS = 15_000_000_000L;
    private static volatile WorkoutTrackingService running;

    private WorkoutSession session;
    private LocationManager locationManager;
    private Handler handler;
    private android.os.HandlerThread worker;
    private long startedAt = -1;
    private String kind;
    private LocationListener listener;
    private boolean listening;
    private boolean foreground;
    private boolean stopping;
    private boolean searching = true;
    private final WorkoutLocationMath filter = new WorkoutLocationMath();
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (stopping) return;
            if (!session.activeLocationRequested(startedAt)) {
                shutdown();
                return;
            }
            if (listening && filter.lastGood() > 0 && SystemClock.elapsedRealtimeNanos() - filter.lastGood() > MAX_SEGMENT_GAP_NS) markSearching();
            session.updateNotification();
            handler.postDelayed(this, 5000);
        }
    };

    static void startForSession(Context context, long expectedStart) {
        if (expectedStart < 0) return;
        Intent intent = new Intent(context, WorkoutTrackingService.class).setAction(ACTION_START)
            .putExtra(EXTRA_STARTED_AT, expectedStart);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Exception error) {
            new WorkoutSession(context).locationError(expectedStart);
            android.util.Log.e("OrbitWorkout", "Unable to start location service", error);
        }
    }

    static void pauseForSession(Context context, long expectedStart) {
        WorkoutTrackingService service = running;
        if (service != null) service.handler.post(() -> service.pause(expectedStart));
    }

    static void stopForSession(Context context, long expectedStart) {
        WorkoutTrackingService service = running;
        if (service != null) service.handler.post(() -> service.stop(expectedStart));
    }

    @Override public void onCreate() {
        super.onCreate();
        session = new WorkoutSession(this);
        locationManager = getSystemService(LocationManager.class);
        worker = new android.os.HandlerThread("Orbit GPS"); worker.start();
        handler = new Handler(worker.getLooper());
        running = this;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        handler.post(() -> handleStart(intent));
        return START_STICKY;
    }

    private int handleStart(Intent intent) {
        String action = intent == null ? ACTION_START : intent.getAction();
        long expected = intent == null ? session.activeLocationStart() : intent.getLongExtra(EXTRA_STARTED_AT, -1);
        if (ACTION_STOP.equals(action)) {
            if (expected < 0 || expected == startedAt) shutdown();
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            pause(expected);
            return START_STICKY;
        }
        if (!ACTION_START.equals(action) && !ACTION_RESUME.equals(action) || expected < 0) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (!session.activeLocationRequested(expected)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (stopping || startedAt >= 0 && startedAt != expected) {
            stopLocation();
            handler.removeCallbacks(refresh);
            breakAnchor();
            searching = true;
            if (foreground) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                foreground = false;
            }
            stopping = false;
        }
        startedAt = expected;
        kind = session.activeKind(expected);
        if (kind == null || !session.activeForLocation(expected)) {
            // A queued start can arrive after the user pauses; preserve the paused state.
            shutdown();
            return START_NOT_STICKY;
        }
        startTracking();
        return START_STICKY;
    }

    private void startTracking() {
        if (stopping) return;
        if (foreground && listening && session.activeForLocation(startedAt)) return;
        stopLocation();
        handler.removeCallbacks(refresh);
        breakAnchor();
        searching = true;
        if (!session.hasLocationPermission()) {
            session.locationPermissionDenied(startedAt);
            shutdown();
            return;
        }
        Notification notification = session.buildNotification();
        if (notification == null) {
            session.locationError(startedAt);
            shutdown();
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(WorkoutSession.NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(WorkoutSession.NOTIFICATION, notification);
            foreground = true;
        } catch (Exception error) {
            session.locationError(startedAt);
            android.util.Log.e("OrbitWorkout", "Unable to promote location service", error);
            shutdown();
            return;
        }
        if (locationManager == null || !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            session.locationUnavailable(startedAt);
            shutdown();
            return;
        }
        listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) { accept(location); }
            @Override public void onProviderDisabled(String provider) {
                if (LocationManager.GPS_PROVIDER.equals(provider)) {
                    stopLocation();
                    session.locationUnavailable(startedAt);
                    shutdown();
                }
            }
            @Override public void onProviderEnabled(String provider) { }
        };
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, LOCATION_INTERVAL_MS, 0, listener, handler.getLooper());
            listening = true;
            searching = true;
            session.startLocation(startedAt);
            handler.removeCallbacks(refresh);
            handler.postDelayed(refresh, 5000);
            session.updateNotification();
        } catch (SecurityException | IllegalArgumentException error) {
            session.locationError(startedAt);
            android.util.Log.e("OrbitWorkout", "Unable to request GPS updates", error);
            shutdown();
        }
    }

    private void accept(Location location) {
        if (stopping || location == null || !session.activeForLocation(startedAt)) {
            if (!stopping) shutdown();
            return;
        }
        if (System.currentTimeMillis() < startedAt) return;
        WorkoutLocationMath.Fix fix = filter.filter(kind, SystemClock.elapsedRealtimeNanos(), location.getElapsedRealtimeNanos(),
            location.getLatitude(), location.getLongitude(), location.hasAccuracy() ? location.getAccuracy() : Double.NaN,
            location.hasSpeed() ? (double) location.getSpeed() : null,
            location.hasSpeedAccuracy() ? (double) location.getSpeedAccuracyMetersPerSecond() : null);
        if (fix == null) return;
        long elapsedMs = session.activeElapsed(startedAt);
        if (elapsedMs < 0) { shutdown(); return; }
        Double altitude = location.hasAltitude() && location.hasVerticalAccuracy() && location.getVerticalAccuracyMeters() > 0
            && location.getVerticalAccuracyMeters() <= 8 && Double.isFinite(location.getAltitude()) ? location.getAltitude() : null;
        int result = session.recordLocation(startedAt, location.getLatitude(), location.getLongitude(), elapsedMs,
            altitude, fix.speed, location.getAccuracy(), fix.distance, fix.breakBefore);
        if (result == 1) searching = false;
        else if (result == 3) shutdown();
        else if (result == 2) { session.locationError(startedAt); shutdown(); }
    }

    private void markSearching() {
        breakAnchor();
        if (searching || stopping) return;
        searching = true;
        session.startLocation(startedAt);
    }

    private void pause(long expected) {
        if (startedAt < 0 || expected != startedAt) return;
        stopLocation();
        breakAnchor();
        searching = true;
        session.updateNotification();
    }

    private void breakAnchor() { filter.reset(); }

    private void stop(long expected) {
        if (startedAt < 0 || expected != startedAt) return;
        shutdown();
    }

    private void stopLocation() {
        if (!listening || listener == null || locationManager == null) return;
        try { locationManager.removeUpdates(listener); }
        catch (SecurityException error) { android.util.Log.e("OrbitWorkout", "Unable to stop GPS updates", error); }
        listening = false;
    }

    private void shutdown() {
        if (stopping) return;
        stopping = true;
        stopLocation();
        handler.removeCallbacks(refresh);
        synchronized (WorkoutSession.LOCK) {
            boolean keepNotification = session.activeStart() >= 0;
            if (foreground) {
                stopForeground(keepNotification ? STOP_FOREGROUND_DETACH : STOP_FOREGROUND_REMOVE);
                foreground = false;
            }
            if (keepNotification) session.updateNotification();
        }
        stopSelf();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        if (running == this) running = null;
        handler.post(() -> { stopping = true; stopLocation(); handler.removeCallbacksAndMessages(null); worker.quitSafely(); });
        super.onDestroy();
    }
}
