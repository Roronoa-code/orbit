package com.mani.orbit;

import android.content.Intent;
import android.os.Build;
import android.webkit.JavascriptInterface;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Only the bundled Orbit page receives this read-only health bridge. */
final class SamsungHealth {
    static final int PERMISSION_REQUEST = 43;
    private final MainActivity activity;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final HealthConnectReader reader;
    private final AtomicBoolean syncing = new AtomicBoolean();
    private volatile String data = "null", status = "Connect Samsung Health", revision = "0";
    private volatile boolean visible, closed;
    private volatile LocalDate selected = LocalDate.now();
    private volatile int imported;
    private long revisionNumber;
    private volatile boolean importAfterConsent;

    SamsungHealth(MainActivity activity) {
        this.activity = activity;
        reader = Build.VERSION.SDK_INT >= 34 ? new HealthConnectReader(activity) : null;
        if (reader == null || !reader.available()) status = "Health Connect needs Android 14 or later";
        load(selected.toString());
    }
    private HealthRecordStore openStore() { return new HealthRecordStore(activity.getDatabasePath("samsung-health.db")); }

    @JavascriptInterface public synchronized String snapshot(String knownRevision) {
        try {
            String info = new JSONObject().put("revision", revision).put("status", status).put("syncing", syncing.get()).put("scanned", imported)
                .put("available", reader != null && reader.available()).put("permitted", reader != null && !reader.grantedTypes().isEmpty()).toString();
            return info.substring(0, info.length() - 1) + ",\"data\":" + (revision.equals(knownRevision) ? "null" : data) + "}";
        } catch (Exception error) { return "{\"error\":\"Health data could not be read\"}"; }
    }

    @JavascriptInterface public void connect() {
        activity.runOnUiThread(() -> {
            if (!visible || closed || reader == null || !reader.available()) return;
            try { activity.requestPermissions(reader.permissions(), PERMISSION_REQUEST); }
            catch (RuntimeException failure) { status = "Could not open health permissions"; notifyPage(); }
        });
    }
    @JavascriptInterface public void permissions() {
        activity.runOnUiThread(() -> {
            if (!visible || closed || reader == null) return;
            try { activity.startActivity(new Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS").putExtra(Intent.EXTRA_PACKAGE_NAME, activity.getPackageName())); }
            catch (RuntimeException missing) { status = "Open Health Connect in Android Settings"; notifyPage(); }
        });
    }
    void permissionResult() { importAfterConsent = true; if (visible) { importAfterConsent = false; sync(); } }

    @JavascriptInterface public void sync() {
        if (!visible || closed || reader == null || !reader.available() || !syncing.compareAndSet(false, true)) return;
        imported = 0; status = "Importing Samsung Health…"; notifyPage();
        worker.execute(() -> {
            try (HealthRecordStore store = openStore()) {
                reader.sync(store, (type, count) -> { imported = count; status = "Importing " + type + "…"; notifyPage(); });
                status = store.metadata().optLong("recordCount") == 0 ? "No Samsung Health records shared yet" : "Samsung Health imported";
                publish(store, selected, true);
            } catch (Exception error) { status = message(error); }
            finally { syncing.set(false); notifyPage(); }
        });
    }

    @JavascriptInterface public void load(String value) {
        final LocalDate date;
        try { date = LocalDate.parse(value); if (date.isBefore(LocalDate.of(1970, 1, 1)) || date.isAfter(LocalDate.now())) return; }
        catch (RuntimeException invalid) { return; }
        if (closed) return; selected = date;
        worker.execute(() -> {
            if (!selected.equals(date) || closed) return;
            try (HealthRecordStore store = openStore()) {
                if (!syncing.get() && store.metadata().has("lastSync")) status = "Samsung Health saved on this phone";
                publish(store, date, visible && reader != null && !reader.grantedTypes().isEmpty());
            }
            catch (Exception error) { status = message(error); notifyPage(); }
        });
    }
    private void publish(HealthRecordStore store, LocalDate date, boolean readHours) throws Exception {
        JSONObject next = HealthProjection.read(store, date);
        if (readHours) {
            try { next.put("stepHours", reader.stepHours(date)); }
            catch (Exception error) { next.put("stepHours", new JSONArray()).put("hourlyStatus", "Hourly steps could not be refreshed"); }
        }
        if (!selected.equals(date) || closed) return;
        synchronized (this) { data = next.toString(); revision = Long.toString(++revisionNumber); }
        notifyPage();
    }
    private void notifyPage() { if (!closed) activity.runOnUiThread(() -> activity.healthChanged()); }
    void foreground(boolean value) {
        visible = value; if (reader != null) reader.visible = value;
        if (value && importAfterConsent) { importAfterConsent = false; sync(); }
    }
    void close() { closed = true; visible = false; if (reader != null) reader.visible = false; worker.shutdown(); }
    private static String message(Exception error) {
        Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof SecurityException) return "Allow access in Health Connect, then import again";
        if (cause instanceof java.util.concurrent.CancellationException) return "Import paused. Keep Orbit open and retry; saved data is safe";
        return "Import did not finish. Saved data is unchanged; try again";
    }
}
