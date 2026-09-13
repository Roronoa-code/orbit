package com.mani.orbit;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.samsung.android.sdk.health.data.HealthDataStore;
import com.samsung.android.sdk.health.data.data.AggregatedData;
import com.samsung.android.sdk.health.data.response.AsyncSingleFuture;
import com.samsung.android.sdk.health.data.response.DataResponse;
import org.json.JSONObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Local emulator only: no Samsung account, physical sensors or personal records. */
public final class WorkoutLiveCheck extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LiveSamsungSteps live, unavailable;
    private int calls, total = 5000;
    private String paused;
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private void later(int ms, Runnable action) { handler.postDelayed(() -> { try { action.run(); } catch (Throwable error) { Log.e("OrbitLocalCheck", "FAIL", error); } }, ms); }
    private JSONObject snapshot(LiveSamsungSteps source) { try { return new JSONObject(source.snapshot()); } catch (Exception error) { throw new AssertionError(error); } }

    private static final class Reply<T> implements AsyncSingleFuture<T> {
        final T value; final int delay;
        Reply(T value, int delay) { this.value = value; this.delay = delay; }
        public T get() { return value; }
        public T get(long timeout, TimeUnit unit) { return value; }
        public boolean cancel(boolean interrupt) { return true; }
        public boolean isCancelled() { return false; }
        public boolean isDone() { return false; }
        // Intentionally deliver after cancellation to verify the production generation guard.
        public void setCallback(Looper looper, Consumer<? super T> success, Consumer<? super Throwable> error) { new Handler(looper).postDelayed(() -> success.accept(value), delay); }
        public void setCallback(Executor executor, Consumer<? super T> success, Consumer<? super Throwable> error) { executor.execute(() -> success.accept(value)); }
    }

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        try {
            getSharedPreferences("orbit-workouts", MODE_PRIVATE).edit().clear().commit();
            WorkoutSession session = new WorkoutSession(getApplicationContext());
            check(session.startCountdown("Walking", 0, false, 85) != null, "Countdown not saved");
            check(session.cancelStart(), "Countdown cannot be cancelled");
            check(session.startCountdown("Walking", 0, true, 85) != null, "Second countdown not saved");
            WorkoutTrackingService.startForSession(this, session.activeStart());
            check(new JSONObject(session.snapshot("")).getLong("elapsedMs") == 0, "Countdown counted as exercise");
            live = new LiveSamsungSteps(this, () -> {});
            Field store = LiveSamsungSteps.class.getDeclaredField("store"); store.setAccessible(true);
            store.set(live, Proxy.newProxyInstance(getClassLoader(), new Class[]{HealthDataStore.class}, (proxy, method, args) -> {
                if (method.getName().contains("Permissions")) return new Reply<>(args[0], 20);
                if (!method.getName().equals("aggregateDataAsync")) throw new UnsupportedOperationException(method.getName());
                calls++; total += 10;
                ArrayList<AggregatedData<Long>> rows = new ArrayList<>();
                java.time.Instant start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
                Constructor<?> aggregate = AggregatedData.class.getDeclaredConstructor(Object.class, java.time.Instant.class, java.time.Instant.class); aggregate.setAccessible(true);
                rows.add((AggregatedData<Long>) aggregate.newInstance((long) total, start, start.plusSeconds(3600)));
                Constructor<?> constructor = DataResponse.class.getDeclaredConstructor(String.class, ArrayList.class); constructor.setAccessible(true);
                return new Reply<>(constructor.newInstance(null, rows), 250);
            }));
            live.foreground(true);
            later(60, () -> { live.foreground(false); paused = live.snapshot(); });
            later(500, () -> { check(live.snapshot().equals(paused), "Cancelled callback overwrote live data"); live.foreground(true); });
            later(1000, () -> { check(snapshot(live).optJSONObject("reading").optLong("steps") == 5020, "Direct SDK result missing"); });
            later(1200, () -> { try {
                Field running = WorkoutTrackingService.class.getDeclaredField("running"); running.setAccessible(true);
                Object service = running.get(null); check(service != null, "Background GPS service stopped during countdown");
                Field worker = WorkoutTrackingService.class.getDeclaredField("handler"); worker.setAccessible(true);
                check(((Handler) worker.get(service)).getLooper() != Looper.getMainLooper(), "GPS work blocks the UI thread");
                Field listening = WorkoutTrackingService.class.getDeclaredField("listening"); listening.setAccessible(true);
                check(listening.getBoolean(service), "Location listener did not start");
                Log.i("OrbitLocalCheck", "PASS: foreground GPS service and background processing thread");
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); } });
            later(3400, () -> {
                check(snapshot(live).optJSONObject("reading").optLong("steps") >= 5030, "Live total did not refresh automatically");
                live.close(); final int stopped = calls;
                later(2500, () -> { check(calls == stopped, "SDK polled after close"); Log.i("OrbitLocalCheck", "PASS: SDK polling, late callback rejection and shutdown"); });
            });
            unavailable = new LiveSamsungSteps(this, () -> {}); unavailable.foreground(true);
            later(5100, () -> {
                try {
                    JSONObject current = new JSONObject(new WorkoutSession(getApplicationContext()).snapshot(""));
                    check(current.getLong("elapsedMs") >= 1900 && current.getLong("elapsedMs") < 3500, "Background countdown clock failed: " + current);
                    check(!session.cancelStart(), "Running session cancelled as a countdown");
                    long id = session.activeStart(), elapsed = current.getLong("elapsedMs");
                    check(session.recordLocation(id, 51, 0, elapsed, 100.0, 1.4, 4, 0, true) == 1, "Initial route point not saved");
                    check(session.recordLocation(id, 51.00002, .00002, elapsed, 105.0, 0.0, 4, 0, false) == 1, "Stop not saved");
                    JSONObject metrics = new JSONObject(new JSONObject(session.snapshot("")).getString("store")).getJSONObject("active").getJSONObject("metrics");
                    org.json.JSONArray points = metrics.getJSONArray("points");
                    check(metrics.getDouble("speedMps") == 0 && points.getJSONObject(points.length()-1).getDouble("lat") == 51 && points.getJSONObject(points.length()-1).getDouble("lon") == 0, "Stopping changed position or retained speed");
                    String status = snapshot(unavailable).optString("status");
                    check(status.contains("Samsung Health first"), "SDK runtime unavailable boundary: " + status);
                    unavailable.close();
                    check(session.action("finish", "", 0) != null, "Background session not saved");
                    Log.i("OrbitLocalCheck", "PASS: persisted background countdown, cancellation and SDK runtime without Samsung installed");
                } catch (Exception error) { throw new AssertionError(error); }
            });
        } catch (Throwable error) { Log.e("OrbitLocalCheck", "FAIL", error); }
    }
    @Override protected void onDestroy() { if (live != null) live.close(); if (unavailable != null) unavailable.close(); handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
