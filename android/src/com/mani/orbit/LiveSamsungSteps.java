package com.mani.orbit;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import com.samsung.android.sdk.health.data.HealthDataService;
import com.samsung.android.sdk.health.data.HealthDataStore;
import com.samsung.android.sdk.health.data.data.AggregatedData;
import com.samsung.android.sdk.health.data.permission.AccessType;
import com.samsung.android.sdk.health.data.permission.Permission;
import com.samsung.android.sdk.health.data.request.DataType;
import com.samsung.android.sdk.health.data.request.LocalTimeFilter;
import com.samsung.android.sdk.health.data.request.LocalTimeGroup;
import com.samsung.android.sdk.health.data.request.LocalTimeGroupUnit;
import com.samsung.android.sdk.health.data.response.AsyncSingleFuture;
import com.samsung.android.sdk.health.data.error.HealthDataException;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** Read Samsung's merged phone/watch count. Never add a second sensor count to it. */
final class LiveSamsungSteps {
    private final Activity activity;
    private final Runnable changed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Permission> permissions = Collections.singleton(Permission.of(new DataType.StepsType(), AccessType.READ));
    private HealthDataStore store;
    private AsyncSingleFuture<?> pending;
    private boolean visible, closed, consent, granted;
    private int generation;
    private JSONObject reading;
    private volatile String snapshot = "{\"status\":\"Connect live steps\",\"connected\":false}";
    private final Runnable poll = this::check;
    private final Runnable timeout = () -> { if (pending != null && !consent) { generation++; pending.cancel(false); pending = null; fail(new IllegalStateException("Timed out")); } };

    LiveSamsungSteps(Activity activity, Runnable changed) { this.activity = activity; this.changed = changed; }
    String snapshot() { return snapshot; }

    void foreground(boolean value) {
        visible = value; handler.removeCallbacks(poll);
        if (value && !closed && !consent) check();
        else if (!value && !consent) { generation++; if (pending != null) pending.cancel(false); pending = null; handler.removeCallbacks(timeout); }
    }
    void close() { closed = true; foreground(false); generation++; if (pending != null) pending.cancel(false); handler.removeCallbacksAndMessages(null); }
    private HealthDataStore store() { if (store == null) store = HealthDataService.getStore(activity); return store; }
    void connect() {
        if (!visible || closed || consent) return;
        generation++; if (pending != null) pending.cancel(false); pending = null; handler.removeCallbacks(timeout); handler.removeCallbacks(poll);
        consent = true; emit("Allow live steps in Samsung Health");
        try {
            pending = store().requestPermissionsAsync(permissions, activity);
            @SuppressWarnings("unchecked") AsyncSingleFuture<Set<Permission>> request = (AsyncSingleFuture<Set<Permission>>) pending;
            request.setCallback(Looper.getMainLooper(), result -> { pending = null; consent = false; if (closed) return; granted = result.containsAll(permissions); emit(granted ? "Live steps connected" : "Live steps access not allowed"); if (visible) check(); }, error -> { pending = null; consent = false; if (!closed) fail(error); });
        } catch (Throwable error) { consent = false; fail(error); }
    }
    private <T> void await(AsyncSingleFuture<T> request, Consumer<T> success) {
        final int run = generation; pending = request;
        handler.removeCallbacks(timeout); handler.postDelayed(timeout, 12000);
        request.setCallback(Looper.getMainLooper(), result -> {
            if (run != generation || closed) return;
            pending = null; handler.removeCallbacks(timeout);
            try { success.accept(result); } catch (Exception error) { fail(error); }
        }, error -> { if (run != generation || closed) return; pending = null; handler.removeCallbacks(timeout); fail(error); });
    }
    private void check() {
        if (!visible || closed || pending != null || consent) return;
        try { await(store().getGrantedPermissionsAsync(permissions), result -> {
            granted = result.containsAll(permissions);
            if (!granted) { emit("Connect live steps"); return; }
            LocalDateTime end = LocalDateTime.now();
            read(end.toLocalDate(), end, null, new JSONArray(), new HashSet<>());
        }); } catch (Throwable error) { fail(error); }
    }
    private void read(LocalDate day, LocalDateTime end, String token, JSONArray hours, Set<String> seen) {
        if (!visible || closed) return;
        com.samsung.android.sdk.health.data.request.AggregateRequest.LocalTimeBuilder<Long> builder = DataType.StepsType.TOTAL.getRequestBuilder();
        builder.setLocalTimeFilterWithGroup(LocalTimeFilter.of(day.atStartOfDay(), end), LocalTimeGroup.of(LocalTimeGroupUnit.HOURLY, 1));
        builder.setPageSize(100); if (token != null) builder.setPageToken(token);
        await(store().aggregateDataAsync(builder.build()), result -> {
            try {
                for (AggregatedData<Long> row : result.getDataList()) {
                    Long value = row.getValue(); if (value == null) continue;
                    if (value < 0 || value > 9007199254740991L) throw new IllegalStateException("Invalid step count");
                    long start = row.getStartTime() != null ? row.getStartTime().toEpochMilli() : row.getStartLocalDateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                    long finish = row.getEndTime() != null ? row.getEndTime().toEpochMilli() : row.getEndLocalDateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                    hours.put(new JSONObject().put("start", start).put("end", finish).put("value", value));
                }
                String next = result.getPageToken();
                if (next != null && !next.isEmpty()) {
                    if (seen.size() >= 64 || !seen.add(next)) throw new IllegalStateException("Repeated page");
                    read(day, end, next, hours, seen); return;
                }
                if (!day.equals(LocalDate.now())) { later(0); return; }
                long total = 0; for (int i = 0; i < hours.length(); i++) total = Math.addExact(total, hours.getJSONObject(i).getLong("value"));
                reading = new JSONObject().put("source", "com.sec.android.app.shealth").put("date", day.toString()).put("steps", total).put("hours", hours).put("at", System.currentTimeMillis());
                emit("Live from Samsung Health"); later(2000);
            } catch (Exception error) { fail(error); }
        });
    }
    private void later(long delay) { handler.removeCallbacks(poll); if (visible && !closed) handler.postDelayed(poll, delay); }
    private void fail(Throwable error) {
        pending = null; handler.removeCallbacks(timeout);
        int code = error instanceof HealthDataException && ((HealthDataException) error).getErrorCode() != null ? ((HealthDataException) error).getErrorCode() : -1;
        String status;
        if (code == 2000) { granted = false; reading = null; status = "Allow live steps in Samsung Health"; }
        else if (code >= 1000 && code <= 2005) { granted = false; status = "Samsung direct access needs developer setup"; }
        else if (code >= 3000 && code <= 3003) status = "Install, update or open Samsung Health first";
        else status = "Live steps delayed · retrying";
        emit(status); later(30000);
    }
    private void emit(String status) {
        try { snapshot = new JSONObject().put("status", status).put("connected", granted).put("reading", reading == null ? JSONObject.NULL : reading).toString(); changed.run(); }
        catch (Exception error) { android.util.Log.e("OrbitHealth", "Unable to publish live steps", error); }
    }
}
