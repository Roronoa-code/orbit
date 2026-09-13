package com.mani.orbit;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/** One authoritative workout state for WebView, service, and notification actions. */
public final class WorkoutSession {
    static final Object LOCK = new Object();
    static final String CHANNEL = "orbit-workout";
    static final int NOTIFICATION = 41;
    static final int MAX_ROUTE_POINTS = 4096;
    private static final int MAX_STORE_CHARS = 4 * 1024 * 1024;
    private static final int COMPACT_STORE_CHARS = 3_500_000;
    private static final int RESUME_TOKEN_BYTES = 16;
    private static final String[] KINDS = {"Walking", "Running", "Cycling", "Strength"};
    private static final String[] METRIC_STATES = {"off", "permission", "searching", "tracking", "paused", "unavailable", "error", "finished"};
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Context context;
    private final MainActivity activity;
    private final SharedPreferences preferences;
    private final WorkoutNotification notification;
    private String sampledRaw;
    private JSONObject sampledActive;
    private long sampleRevision;

    WorkoutSession(Context context) {
        this.context = context;
        activity = context instanceof MainActivity ? (MainActivity) context : null;
        preferences = context.getSharedPreferences("orbit-workouts", Context.MODE_PRIVATE);
        notification = new WorkoutNotification(context, this);
    }

    private static boolean number(JSONObject row, String key) throws Exception {
        Object value = row.get(key);
        return value instanceof Number && Double.isFinite(((Number) value).doubleValue())
            && ((Number) value).doubleValue() >= 0;
    }

    private static boolean optionalNumber(JSONObject row, String key) throws Exception {
        return !row.has(key) || row.isNull(key) || number(row, key);
    }

    private static boolean validResumeToken(String token) {
        if (token == null || token.length() != RESUME_TOKEN_BYTES * 2) return false;
        for (int i = 0; i < token.length(); i++) {
            char value = token.charAt(i);
            if (!(value >= '0' && value <= '9' || value >= 'a' && value <= 'f')) return false;
        }
        return true;
    }

    private static boolean optionalResumeToken(JSONObject row) throws Exception {
        return !row.has("resumeToken") || row.isNull("resumeToken")
            || row.get("resumeToken") instanceof String && validResumeToken(row.getString("resumeToken"));
    }

    private static String resumeToken() {
        byte[] bytes = new byte[RESUME_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        StringBuilder token = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) token.append(String.format("%02x", value & 0xff));
        return token.toString();
    }

    private static boolean sameResumeToken(String stored, String supplied) {
        if (!validResumeToken(stored) || !validResumeToken(supplied)) return false;
        return MessageDigest.isEqual(stored.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean optionalAltitude(JSONObject row, String key) throws Exception {
        if (!row.has(key) || row.isNull(key)) return true;
        Object value = row.get(key);
        if (!(value instanceof Number)) return false;
        return validAltitude(((Number) value).doubleValue());
    }

    private static boolean validAltitude(double value) {
        return Double.isFinite(value) && value >= -12000 && value <= 100000;
    }

    private static boolean target(double value) {
        return value == 0 || Double.isFinite(value) && value == Math.floor(value)
            && value >= 60000 && value <= 86400000;
    }

    private static boolean trackKind(String kind) {
        return "Walking".equals(kind) || "Running".equals(kind) || "Cycling".equals(kind);
    }

    private static boolean kind(String value) {
        return Arrays.asList(KINDS).contains(value);
    }

    private static boolean metricState(String value) {
        return Arrays.asList(METRIC_STATES).contains(value);
    }

    private static boolean validTargetField(JSONObject row) throws Exception {
        return !row.has("targetMs") || row.get("targetMs") instanceof Number && target(row.getDouble("targetMs"));
    }

    private static boolean validWeight(JSONObject row) throws Exception {
        if (!row.has("weightKg")) return true;
        Object value = row.get("weightKg");
        if (!(value instanceof Number)) return false;
        double weight = ((Number) value).doubleValue();
        return Double.isFinite(weight) && (weight == 0 || weight >= 20 && weight <= 350);
    }

    private static boolean validPoint(JSONObject point, double previousElapsed) throws Exception {
        if (!(point.get("lat") instanceof Number) || !(point.get("lon") instanceof Number)
                || !Double.isFinite(point.getDouble("lat")) || !Double.isFinite(point.getDouble("lon"))
                || Math.abs(point.getDouble("lat")) > 90 || Math.abs(point.getDouble("lon")) > 180
                || !number(point, "elapsedMs") || point.getDouble("elapsedMs") < previousElapsed
                || !optionalAltitude(point, "altitudeM") || !optionalNumber(point, "speedMps")
                || !point.has("breakBefore") || !(point.get("breakBefore") instanceof Boolean)) return false;
        if (!point.isNull("speedMps") && point.getDouble("speedMps") > 100) return false;
        if (!point.isNull("altitudeM") && (point.getDouble("altitudeM") < -12000 || point.getDouble("altitudeM") > 100000)) return false;
        return true;
    }

    private static void validateMetrics(JSONObject row) throws Exception {
        if (!row.has("metrics") || row.isNull("metrics")) throw new Exception("Missing workout metrics");
        JSONObject metrics = row.getJSONObject("metrics");
        if (!(metrics.get("state") instanceof String) || !metricState(metrics.getString("state")) || !number(metrics, "distanceM")
                || !number(metrics, "maxSpeedMps") || metrics.getDouble("maxSpeedMps") > 100
                || !optionalNumber(metrics, "speedMps") || !optionalNumber(metrics, "accuracyM")
                || !optionalAltitude(metrics, "altitudeMinM") || !optionalAltitude(metrics, "altitudeMaxM")
                || !metrics.has("points") || metrics.getJSONArray("points").length() > MAX_ROUTE_POINTS) throw new Exception("Invalid workout metrics");
        if (!metrics.isNull("speedMps") && metrics.getDouble("speedMps") > 100
                || !metrics.isNull("accuracyM") && metrics.getDouble("accuracyM") > 1000) throw new Exception("Invalid workout metrics");
        if (!metrics.isNull("altitudeMinM") && (metrics.getDouble("altitudeMinM") < -12000 || metrics.getDouble("altitudeMinM") > 100000)
                || !metrics.isNull("altitudeMaxM") && (metrics.getDouble("altitudeMaxM") < -12000 || metrics.getDouble("altitudeMaxM") > 100000)) throw new Exception("Invalid workout metrics");
        JSONArray points = metrics.getJSONArray("points");
        double previousElapsed = 0;
        for (int i = 0; i < points.length(); i++) {
            JSONObject point = points.getJSONObject(i);
            if (!validPoint(point, previousElapsed)) throw new Exception("Invalid workout route");
            previousElapsed = point.getDouble("elapsedMs");
        }
    }

    private static void validateSession(JSONObject row, boolean active) throws Exception {
        String sessionKind = row.getString("kind");
        boolean tracking = row.has("trackLocation") && !row.isNull("trackLocation") && row.getBoolean("trackLocation");
        if (!kind(sessionKind) || !number(row, "startedAt") || !number(row, "elapsed")
                || !validTargetField(row) || !target(row.isNull("targetMs") ? 0 : row.optDouble("targetMs", 0)) || !validWeight(row)
                || row.has("trackLocation") && !(row.get("trackLocation") instanceof Boolean)
                || !optionalNumber(row, "totalMs") || !optionalNumber(row, "startedRealtime")
                || !optionalNumber(row, "bootCountTotal") || !optionalResumeToken(row)) throw new Exception("Invalid workout");
        if (tracking && !trackKind(sessionKind)) throw new Exception("Invalid workout tracking kind");
        if (tracking) validateMetrics(row);
        if (active ? !row.has("resumedAt") || !row.isNull("resumedAt") && !number(row, "resumedAt") : !number(row, "endedAt"))
            throw new Exception("Invalid time");
    }

    private static JSONObject decode(String raw) throws Exception {
        // ponytail: one JSON history up to 4 MB and 4,096 route points; use a native database for larger histories.
        if (raw == null || raw.length() > 4 * 1024 * 1024) throw new Exception("History unavailable");
        JSONObject value = new JSONObject(raw);
        if (!value.has("active") || !value.has("history")) throw new Exception("Missing state");
        if (!value.isNull("active")) validateSession(value.getJSONObject("active"), true);
        JSONArray history = value.getJSONArray("history");
        for (int i = 0; i < history.length(); i++) validateSession(history.getJSONObject(i), false);
        return value;
    }

    @JavascriptInterface public String read() {
        synchronized (LOCK) { return preferences.getString("sessions", null); }
    }

    /** Only the first migration may replace a whole store. Actions then own all updates. */
    @JavascriptInterface public boolean write(String raw) {
        synchronized (LOCK) {
            try {
                decode(raw);
                String existing = read();
                if (existing != null) return existing.equals(raw);
                return preferences.edit().putString("sessions", raw).commit();
            } catch (Exception error) {
                return false;
            }
        }
    }

    /** Compatibility action: existing callers get a time-only session by default. */
    @JavascriptInterface public String action(String name, String kind, double targetMs) {
        if ("start".equals(name)) return start(kind, targetMs, false, 0);
        return change(name, kind, targetMs, -1);
    }

    /** Starts a session and, when requested, records genuine phone location metrics. */
    @JavascriptInterface public String start(String kind, double targetMs, boolean trackLocation, double weightKg) {
        String result = startSession(kind, targetMs, trackLocation, weightKg);
        if (result == null || activity == null) return result;
        try {
            JSONObject state = new JSONObject(result);
            long startedAt = state.getJSONObject("active").getLong("startedAt");
            activity.runOnUiThread(() -> activity.onWorkoutStarted(startedAt, trackLocation));
        } catch (Exception error) {
            android.util.Log.e("OrbitWorkout", "Unable to schedule workout start", error);
        }
        return result;
    }

    private String startSession(String kind, double targetMs, boolean trackLocation, double weightKg) {
        synchronized (LOCK) {
            try {
                if (!kind(kind) || !target(targetMs) || trackLocation && !trackKind(kind)
                        || !Double.isFinite(weightKg) || weightKg < 0 || weightKg != 0 && (weightKg < 20 || weightKg > 350)) return null;
                String raw = read();
                JSONObject value = raw == null ? new JSONObject("{\"active\":null,\"history\":[]}") : decode(raw);
                if (value.optJSONObject("active") != null) return null;
                long now = System.currentTimeMillis();
                JSONObject session = new JSONObject().put("kind", kind).put("startedAt", now).put("elapsed", 0)
                    .put("resumedAt", now).put("targetMs", targetMs).put("trackLocation", trackLocation)
                    .put("startedRealtime", SystemClock.elapsedRealtime()).put("bootCountTotal", bootCount());
                if (weightKg > 0) session.put("weightKg", weightKg);
                resume(session, now);
                if (trackLocation) session.put("metrics", metrics(hasLocationPermission() ? "searching" : "permission"));
                validateSession(session, true);
                value.put("active", session);
                String next = saveLocked(value);
                if (next == null) return null;
                updateNotification();
                return next;
            } catch (Exception error) {
                android.util.Log.e("OrbitWorkout", "Unable to start workout", error);
                return null;
            }
        }
    }

    private int bootCount() {
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (RuntimeException error) {
            return -1;
        }
    }

    private long elapsed(JSONObject row, long now) throws Exception {
        if (row.isNull("resumedAt")) return row.getLong("elapsed");
        int currentBoot = bootCount();
        boolean sameBoot = currentBoot >= 0 && row.has("resumedRealtime") && row.optInt("bootCount", -2) == currentBoot;
        long delta = sameBoot ? SystemClock.elapsedRealtime() - row.getLong("resumedRealtime") : now - row.getLong("resumedAt");
        return Math.max(0, row.getLong("elapsed") + Math.max(0, delta));
    }

    long notificationElapsed(JSONObject row, long now) throws Exception {
        return elapsed(row, now);
    }

    boolean notificationSameBoot(JSONObject row) {
        int currentBoot = bootCount();
        return currentBoot >= 0 && row.has("resumedRealtime") && row.optInt("bootCount", -2) == currentBoot;
    }

    boolean resumeFromNotification(long expectedStart, String token) {
        if (expectedStart < 0 || !validResumeToken(token)) return false;
        return change("resume", "", 0, expectedStart, token) != null;
    }

    private long totalElapsed(JSONObject row, long now) throws Exception {
        int currentBoot = bootCount();
        boolean sameBoot = currentBoot >= 0 && row.has("startedRealtime") && row.optInt("bootCountTotal", -2) == currentBoot;
        long delta = sameBoot ? SystemClock.elapsedRealtime() - row.getLong("startedRealtime") : now - row.getLong("startedAt");
        return Math.max(0, delta);
    }

    private JSONObject resume(JSONObject row, long now) throws Exception {
        return row.put("resumedAt", now).put("resumedRealtime", SystemClock.elapsedRealtime()).put("bootCount", bootCount());
    }

    /** One clock sample per UI tick; unchanged history never crosses the bridge or gets decoded again. */
    @JavascriptInterface public String snapshot(String knownRevision) {
        synchronized (LOCK) {
            try {
                String raw = read();
                if (sampleRevision == 0 || !java.util.Objects.equals(raw, sampledRaw)) {
                    JSONObject value = raw == null ? null : decode(raw);
                    sampledActive = value == null ? null : value.optJSONObject("active");
                    sampledRaw = raw;
                    sampleRevision++;
                }
                long time = System.currentTimeMillis(), active = sampledActive == null ? 0 : elapsed(sampledActive, time);
                String revision = Long.toString(sampleRevision);
                return new JSONObject().put("realDataMode", preferences.getBoolean("real-data-v1", false)).put("revision", revision).put("empty", sampledRaw == null)
                    .put("store", revision.equals(knownRevision) ? JSONObject.NULL : sampledRaw == null ? "{\"active\":null,\"history\":[]}" : sampledRaw)
                    .put("startedAt", sampledActive == null ? JSONObject.NULL : sampledActive.getLong("startedAt"))
                    .put("elapsedMs", active).put("totalMs", sampledActive == null ? 0 : Math.max(active, totalElapsed(sampledActive, time))).toString();
            } catch (Exception error) {
                return "{\"error\":\"Workout state unavailable\"}";
            }
        }
    }

    private String change(String name, String kind, double targetMs, long expectedStart) {
        return change(name, kind, targetMs, expectedStart, null);
    }

    private String change(String name, String kind, double targetMs, long expectedStart, String expectedResumeToken) {
        boolean tracking = false;
        long startedAt = -1;
        String result = null;
        synchronized (LOCK) {
            try {
                String raw = read();
                JSONObject value = raw == null ? new JSONObject("{\"active\":null,\"history\":[]}") : decode(raw);
                JSONObject active = value.optJSONObject("active");
                long now = System.currentTimeMillis();
                if (expectedStart >= 0 && (active == null || active.getLong("startedAt") != expectedStart)) return null;
                if (active == null) return null;
                if (expectedResumeToken != null && (!"resume".equals(name) || !sameResumeToken(active.optString("resumeToken", null), expectedResumeToken))) return null;
                tracking = active.optBoolean("trackLocation", false);
                startedAt = active.getLong("startedAt");
                if ("pause".equals(name) && !active.isNull("resumedAt")) {
                    active.put("elapsed", elapsed(active, now)).put("resumedAt", JSONObject.NULL).put("resumeToken", resumeToken());
                    setMetricState(active, "paused");
                } else if ("resume".equals(name) && active.isNull("resumedAt")) {
                    resume(active, now);
                    active.remove("resumeToken");
                    if (tracking) setMetricState(active, hasLocationPermission() ? "searching" : "permission");
                } else if ("finish".equals(name)) {
                    long activeMs = elapsed(active, now);
                    JSONObject finished = new JSONObject(active.toString()).put("elapsed", activeMs)
                        .put("endedAt", now).put("totalMs", Math.max(activeMs, totalElapsed(active, now)));
                    finished.remove("resumedAt");
                    finished.remove("resumedRealtime");
                    finished.remove("bootCount");
                    finished.remove("startedRealtime");
                    finished.remove("bootCountTotal");
                    finished.remove("resumeToken");
                    if (tracking) setMetricState(finished, "finished");
                    JSONArray history = new JSONArray().put(finished), previous = value.getJSONArray("history");
                    for (int i = 0; i < previous.length(); i++) history.put(previous.get(i));
                    value.put("history", history).put("active", JSONObject.NULL);
                } else return null;
                String next = saveLocked(value);
                if (next == null) return null;
                result = next;
                updateNotification();
            } catch (Exception error) {
                android.util.Log.e("OrbitWorkout", "Unable to change workout", error);
                return null;
            }
        }
        if ("pause".equals(name) && tracking) WorkoutTrackingService.pauseForSession(context, startedAt);
        else if ("resume".equals(name) && tracking && activity != null) {
            final long resumedStart = startedAt;
            final MainActivity resumedActivity = activity;
            resumedActivity.runOnUiThread(() -> resumedActivity.resumeLocationTracking(resumedStart));
        }
        else if ("finish".equals(name) && tracking) WorkoutTrackingService.stopForSession(context, startedAt);
        return result;
    }

    private JSONObject activeLocked() throws Exception {
        String raw = read();
        return raw == null ? null : decode(raw).optJSONObject("active");
    }

    private static JSONObject metrics(String state) throws Exception {
        return new JSONObject().put("state", state).put("distanceM", 0).put("speedMps", JSONObject.NULL)
            .put("maxSpeedMps", 0).put("altitudeMinM", JSONObject.NULL).put("altitudeMaxM", JSONObject.NULL)
            .put("accuracyM", JSONObject.NULL).put("points", new JSONArray());
    }

    private static void setMetricState(JSONObject row, String state) throws Exception {
        if (!row.has("metrics") || row.isNull("metrics")) return;
        if (!metricState(state)) throw new Exception("Invalid metric state");
        JSONObject metrics = row.getJSONObject("metrics").put("state", state);
        if (!"tracking".equals(state)) metrics.put("speedMps", JSONObject.NULL);
    }

    private String saveLocked(JSONObject value) throws Exception {
        if (!compactForStorage(value)) {
            android.util.Log.e("OrbitWorkout", "Workout state exceeds the safe storage limit");
            return null;
        }
        String next = value.toString();
        if (next.length() > MAX_STORE_CHARS) {
            android.util.Log.e("OrbitWorkout", "Workout state exceeds the safe storage limit");
            return null;
        }
        decode(next);
        if (!preferences.edit().putString("sessions", next).commit()) {
            android.util.Log.e("OrbitWorkout", "Workout state write was not confirmed");
            return null;
        }
        return next;
    }

    private static boolean compactForStorage(JSONObject value) throws Exception {
        while (value.toString().length() > COMPACT_STORE_CHARS) {
            JSONObject candidate = value.optJSONObject("active");
            if (candidate == null || !candidate.has("metrics") || candidate.getJSONObject("metrics").getJSONArray("points").length() <= 2) candidate = null;
            if (candidate == null) {
                JSONArray history = value.getJSONArray("history");
                for (int i = 0; i < history.length(); i++) {
                    JSONObject row = history.getJSONObject(i);
                    if (row.has("metrics") && row.getJSONObject("metrics").getJSONArray("points").length() > 2) {
                        candidate = row;
                        break;
                    }
                }
            }
            if (candidate == null) return value.toString().length() <= MAX_STORE_CHARS;
            JSONObject metrics = candidate.getJSONObject("metrics");
            metrics.put("points", decimate(metrics.getJSONArray("points")));
        }
        return true;
    }

    boolean hasLocationPermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    boolean activeForLocation(long expectedStart) {
        synchronized (LOCK) {
            try {
                JSONObject active = activeLocked();
                return active != null && active.getLong("startedAt") == expectedStart
                    && active.optBoolean("trackLocation", false) && !active.isNull("resumedAt");
            } catch (Exception error) {
                return false;
            }
        }
    }

    boolean activeLocationRequested(long expectedStart) {
        synchronized (LOCK) {
            try {
                JSONObject active = activeLocked();
                return active != null && active.getLong("startedAt") == expectedStart && active.optBoolean("trackLocation", false);
            } catch (Exception error) {
                return false;
            }
        }
    }

    long activeLocationStart() {
        synchronized (LOCK) {
            try {
                JSONObject active = activeLocked();
                return active != null && active.optBoolean("trackLocation", false) && !active.isNull("resumedAt")
                    ? active.getLong("startedAt") : -1;
            } catch (Exception error) {
                return -1;
            }
        }
    }

    long activeStart() {
        synchronized (LOCK) {
            try {
                JSONObject active = activeLocked();
                return active == null ? -1 : active.getLong("startedAt");
            } catch (Exception error) {
                return -1;
            }
        }
    }

    String activeKind(long expectedStart) {
        synchronized (LOCK) {
            try {
                JSONObject active = activeLocked();
                return active != null && active.getLong("startedAt") == expectedStart ? active.getString("kind") : null;
            } catch (Exception error) {
                return null;
            }
        }
    }

    long activeElapsed(long expectedStart) {
        synchronized (LOCK) {
            try {
                JSONObject active = activeLocked();
                return active != null && active.getLong("startedAt") == expectedStart ? elapsed(active, System.currentTimeMillis()) : -1;
            } catch (Exception error) {
                return -1;
            }
        }
    }

    boolean startLocation(long expectedStart) {
        return setLocationState(expectedStart, "searching");
    }

    void locationPermissionDenied(long expectedStart) {
        setLocationState(expectedStart, "permission");
    }

    void locationUnavailable(long expectedStart) {
        setLocationState(expectedStart, "unavailable");
    }

    void locationError(long expectedStart) {
        setLocationState(expectedStart, "error");
    }

    private boolean setLocationState(long expectedStart, String state) {
        synchronized (LOCK) {
            try {
                JSONObject value = decode(read());
                JSONObject active = value.optJSONObject("active");
                if (active == null || active.getLong("startedAt") != expectedStart || !active.optBoolean("trackLocation", false)
                        || !"paused".equals(state) && active.isNull("resumedAt")) return false;
                setMetricState(active, state);
                if (saveLocked(value) == null) return false;
                updateNotification();
                return true;
            } catch (Exception error) {
                android.util.Log.e("OrbitWorkout", "Unable to save location state", error);
                return false;
            }
        }
    }

    /** Persists one validated fix; the service updates its in-memory anchor only after SAVED. */
    int recordLocation(long expectedStart, double latitude, double longitude, long elapsedMs,
                       Double altitudeM, Double speedMps, double accuracyM, double distanceDeltaM,
                       boolean breakBefore) {
        synchronized (LOCK) {
            try {
                if (!Double.isFinite(latitude) || Math.abs(latitude) > 90 || !Double.isFinite(longitude) || Math.abs(longitude) > 180
                        || elapsedMs < 0 || altitudeM != null && !validAltitude(altitudeM)
                        || speedMps != null && (!Double.isFinite(speedMps) || speedMps < 0 || speedMps > 100)
                        || !Double.isFinite(accuracyM) || accuracyM < 0 || accuracyM > 1000
                        || !Double.isFinite(distanceDeltaM) || distanceDeltaM < 0) return 0;
                JSONObject value = decode(read()), active = value.optJSONObject("active");
                if (active == null || active.getLong("startedAt") != expectedStart || !active.optBoolean("trackLocation", false)
                        || active.isNull("resumedAt")) return 3;
                JSONObject metrics = active.getJSONObject("metrics");
                JSONArray points = metrics.getJSONArray("points");
                long pointElapsed = Math.max(elapsedMs, points.length() == 0 ? 0 : points.getJSONObject(points.length() - 1).getLong("elapsedMs"));
                JSONObject point = new JSONObject().put("lat", latitude).put("lon", longitude).put("elapsedMs", pointElapsed)
                    .put("altitudeM", altitudeM == null ? JSONObject.NULL : altitudeM)
                    .put("speedMps", speedMps == null ? JSONObject.NULL : speedMps).put("breakBefore", breakBefore);
                if (points.length() >= MAX_ROUTE_POINTS) points = decimate(points);
                points.put(point);
                metrics.put("points", points).put("state", "tracking").put("distanceM", metrics.getDouble("distanceM") + distanceDeltaM)
                    .put("speedMps", speedMps == null ? JSONObject.NULL : speedMps)
                    .put("maxSpeedMps", Math.max(metrics.getDouble("maxSpeedMps"), speedMps == null ? 0 : speedMps))
                    .put("accuracyM", accuracyM);
                if (altitudeM != null) {
                    double min = metrics.isNull("altitudeMinM") ? altitudeM : Math.min(metrics.getDouble("altitudeMinM"), altitudeM);
                    double max = metrics.isNull("altitudeMaxM") ? altitudeM : Math.max(metrics.getDouble("altitudeMaxM"), altitudeM);
                    metrics.put("altitudeMinM", min).put("altitudeMaxM", max);
                }
                if (saveLocked(value) == null) return 2;
                updateNotification();
                return 1;
            } catch (Exception error) {
                android.util.Log.e("OrbitWorkout", "Unable to save location fix", error);
                return 2;
            }
        }
    }

    private static JSONArray decimate(JSONArray source) throws Exception {
        // ponytail: halve the route at the 4,096-point ceiling; switch to a streamed route store if detail needs to grow.
        JSONArray reduced = new JSONArray();
        boolean breakPending = false;
        for (int i = 0; i < source.length(); i++) {
            JSONObject point = source.getJSONObject(i);
            boolean keep = i == 0 || i == source.length() - 1 || i % 2 == 0;
            if (point.optBoolean("breakBefore", false)) breakPending = true;
            if (keep) {
                if (breakPending && i > 0) point = new JSONObject(point.toString()).put("breakBefore", true);
                reduced.put(point);
                breakPending = false;
            }
        }
        return reduced;
    }

    @JavascriptInterface public String enableTracking() {
        synchronized (LOCK) {
            try {
                JSONObject active = activeLocked();
                if (active == null || !active.optBoolean("trackLocation", false)) return read();
                long startedAt = active.getLong("startedAt");
                if (activity != null) activity.runOnUiThread(() -> activity.requestLocationForWorkout(startedAt));
                return read();
            } catch (Exception error) {
                return null;
            }
        }
    }

    @JavascriptInterface public String notificationStatus() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        if (!manager.areNotificationsEnabled() || channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return "disabled";
        return Build.VERSION.SDK_INT >= 36 && manager.canPostPromotedNotifications() ? "live" : "standard";
    }

    @JavascriptInterface public void enableNotifications() {
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            Intent settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
            activity.startActivity(settings);
        });
    }

    Notification buildNotification() {
        synchronized (LOCK) {
            try {
                String raw = read();
                JSONObject active = raw == null ? null : decode(raw).optJSONObject("active");
                return active == null ? null : notification.build(active);
            } catch (Exception error) {
                android.util.Log.e("OrbitWorkout", "Unable to build workout notification", error);
                return null;
            }
        }
    }

    public void updateNotification() {
        synchronized (LOCK) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            try {
                String raw = read();
                JSONObject value = raw == null ? null : decode(raw);
                JSONObject active = value == null ? null : value.optJSONObject("active");
                if (active == null) {
                    manager.cancel(NOTIFICATION);
                    return;
                }
                if (active.isNull("resumedAt") && active.isNull("resumeToken")) {
                    active.put("resumeToken", resumeToken());
                    String next = saveLocked(value);
                    if (next == null) return;
                    active = decode(next).optJSONObject("active");
                }
                manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Live workouts", NotificationManager.IMPORTANCE_LOW));
                manager.notify(NOTIFICATION, notification.build(active));
            } catch (Exception error) {
                // Notification failure cannot roll back a successfully saved workout.
                android.util.Log.e("OrbitWorkout", "Unable to refresh workout notification", error);
            }
        }
    }

    public static final class Actions extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            WorkoutSession session = new WorkoutSession(context);
            long expected = intent == null ? -2 : intent.getLongExtra("startedAt", -2);
            String action = intent == null ? null : intent.getAction();
            if ("resume".equals(action)) {
                Toast.makeText(context, "Workout was not changed. Open Orbit and try again.", Toast.LENGTH_LONG).show();
                return;
            }
            String result = expected < 0 ? null : session.change(action, "", 0, expected);
            if (result == null)
                Toast.makeText(context, "Workout was not changed. Open Orbit and try again.", Toast.LENGTH_LONG).show();
        }
    }
}
