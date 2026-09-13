package com.mani.orbit;

import android.content.Context;
import android.content.pm.PackageManager;
import android.health.connect.*;
import android.health.connect.datatypes.*;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.units.Energy;
import android.health.connect.datatypes.units.Length;
import android.os.OutcomeReceiver;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Foreground, read-only Samsung import. Pagination and aggregation happen off the UI thread. */
final class HealthConnectReader {
    static final String HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY";
    private final Context context;
    private final HealthConnectManager manager;
    private final DataOrigin origin = new DataOrigin.Builder().setPackageName(HealthRecordCodec.SOURCE).build();
    volatile boolean visible;

    HealthConnectReader(Context context) {
        this.context = context.getApplicationContext();
        manager = context.getSystemService(HealthConnectManager.class);
    }
    boolean available() { return manager != null; }
    boolean allowed(String permission) { return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    List<String> grantedTypes() {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, String> entry : HealthRecordCodec.PERMISSIONS.entrySet()) if (allowed(entry.getValue())) keys.add(entry.getKey());
        return keys;
    }
    String[] permissions() {
        List<String> permissions = new ArrayList<>(HealthRecordCodec.PERMISSIONS.values());
        try { context.getPackageManager().getPermissionInfo(HISTORY, 0); permissions.add(HISTORY); }
        catch (PackageManager.NameNotFoundException unavailable) { /* Older Health Connect exposes only its default history window. */ }
        return permissions.toArray(new String[0]);
    }
    private void checkVisible() { if (!visible) throw new CancellationException("Keep Orbit open while importing"); }
    private static <T> OutcomeReceiver<T, HealthConnectException> receiver(CompletableFuture<T> result) {
        return new OutcomeReceiver<T, HealthConnectException>() {
            @Override public void onResult(T value) { result.complete(value); }
            @Override public void onError(HealthConnectException error) { result.completeExceptionally(error); }
        };
    }
    private static <T> T await(CompletableFuture<T> result) throws Exception { return result.get(45, TimeUnit.SECONDS); }

    void sync(HealthRecordStore store, BiConsumer<String, Integer> progress) throws Exception {
        sync(store, progress, false);
    }
    void sync(HealthRecordStore store, BiConsumer<String, Integer> progress, boolean recent) throws Exception {
        checkVisible();
        List<String> types = grantedTypes();
        if (types.isEmpty()) throw new SecurityException("Allow health-data access to import");
        boolean history = allowed(HISTORY);
        Instant end = Instant.now(), start = recent ? LocalDate.now().minusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
            : history ? Instant.EPOCH : LocalDate.now().minusDays(29).atStartOfDay(ZoneId.systemDefault()).toInstant();
        store.beginImport(); int count = 0;
        List<String> replace = new ArrayList<>(types);
        for (String type : types) {
            checkVisible(); progress.accept(type, count);
            count += readType(store, type, HealthRecordCodec.TYPES.get(type), start, end);
        }
        // Health Connect's aggregation handles overlapping step records; raw step sums can double count.
        for (String type : Arrays.asList("steps", "distance", "floors", "energy", "totalEnergy")) if (types.contains(type)) {
            checkVisible(); progress.accept(type, count);
            long first = Math.max(start.toEpochMilli(), store.firstStaged(type, end.toEpochMilli()));
            if (first < end.toEpochMilli()) {
                LocalDate from = Instant.ofEpochMilli(first).atZone(ZoneId.systemDefault()).toLocalDate();
                LocalDate until = end.atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1);
                for (LocalDate date = from; date.isBefore(until); date = date.plusDays(180)) {
                    LocalDate to = date.plusDays(180).isBefore(until) ? date.plusDays(180) : until;
                    switch (type) {
                        case "steps": aggregateDays(store, type, StepsRecord.STEPS_COUNT_TOTAL, date, to); break;
                        case "distance": aggregateDays(store, type, DistanceRecord.DISTANCE_TOTAL, date, to); break;
                        case "floors": aggregateDays(store, type, FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL, date, to); break;
                        case "energy": aggregateDays(store, type, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, date, to); break;
                        case "totalEnergy": aggregateDays(store, type, TotalCaloriesBurnedRecord.ENERGY_TOTAL, date, to); break;
                    }
                }
            }
            replace.add(type + "Day");
        }
        if (types.contains("exercise")) {
            // One request per recorded session, with all permitted metrics in that time window.
            // These are labelled interval totals; Health Connect does not link separate sensors by workout ID.
            try (android.database.Cursor rows = store.pendingWorkouts()) {
                while (rows.moveToNext()) {
                    checkVisible(); JSONObject row = new JSONObject(rows.getString(0));
                    row.put("summary", workoutTotals(row, types)); store.stage(new JSONArray().put(row));
                }
            }
        }
        checkVisible();
        for (String type : types) if (!allowed(HealthRecordCodec.PERMISSIONS.get(type))) throw new SecurityException("Health access changed during import");
        store.finishImport(replace, start.toEpochMilli(), end.toEpochMilli(), history);
        progress.accept("complete", count);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private JSONObject workoutTotals(JSONObject row, List<String> granted) throws Exception {
        Map<String, AggregationType> metrics = new LinkedHashMap<>();
        if (granted.contains("distance")) metrics.put("distance", DistanceRecord.DISTANCE_TOTAL);
        if (granted.contains("energy")) metrics.put("energy", ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL);
        if (granted.contains("steps")) metrics.put("steps", StepsRecord.STEPS_COUNT_TOTAL);
        if (granted.contains("heart")) {
            metrics.put("heartAverage", HeartRateRecord.BPM_AVG);
            metrics.put("heartLow", HeartRateRecord.BPM_MIN);
            metrics.put("heartHigh", HeartRateRecord.BPM_MAX);
        }
        JSONObject totals = new JSONObject(); if (metrics.isEmpty()) return totals;
        AggregateRecordsRequest.Builder<Object> request = new AggregateRecordsRequest.Builder<>(new TimeInstantRangeFilter.Builder()
            .setStartTime(Instant.ofEpochMilli(row.getLong("start"))).setEndTime(Instant.ofEpochMilli(row.getLong("end"))).build());
        request.addDataOriginsFilter(origin);
        for (AggregationType type : metrics.values()) request.addAggregationType(type);
        CompletableFuture<AggregateRecordsResponse<Object>> future = new CompletableFuture<>();
        manager.aggregate(request.build(), Runnable::run, receiver(future));
        AggregateRecordsResponse<Object> result = await(future);
        for (Map.Entry<String, AggregationType> entry : metrics.entrySet()) {
            Object value = result.get(entry.getValue()); if (value == null) continue;
            for (Object item : result.getDataOrigins(entry.getValue())) if (!HealthRecordCodec.SOURCE.equals(((DataOrigin)item).getPackageName())) throw new IllegalStateException("Unexpected workout source");
            totals.put(entry.getKey(), number(value));
        }
        return totals;
    }

    private <T extends Record> int readType(HealthRecordStore store, String name, Class<T> type, Instant start, Instant end) throws Exception {
        int count = 0; long token = -1; Set<Long> seen = new HashSet<>();
        do {
            checkVisible();
            ReadRecordsRequestUsingFilters.Builder<T> request = new ReadRecordsRequestUsingFilters.Builder<>(type)
                .addDataOrigins(origin).setTimeRangeFilter(new TimeInstantRangeFilter.Builder().setStartTime(start).setEndTime(end).build()).setPageSize(500);
            if (token >= 0) request.setPageToken(token); // Do not set sort order with a continuation token.
            CompletableFuture<ReadRecordsResponse<T>> result = new CompletableFuture<>();
            manager.readRecords(request.build(), Runnable::run, receiver(result));
            ReadRecordsResponse<T> page = await(result); JSONArray records = new JSONArray();
            for (T record : page.getRecords()) records.put(HealthRecordCodec.encode(name, record));
            checkVisible(); store.stage(records); count += records.length();
            token = page.getNextPageToken();
            if (token < -1 || token >= 0 && !seen.add(token)) throw new IllegalStateException("Health import stopped making progress");
        } while (token != -1);
        return count;
    }

    private <T> void aggregateDays(HealthRecordStore store, String kind, AggregationType<T> type, LocalDate start, LocalDate end) throws Exception {
        checkVisible();
        // Extended-history reads can apply the local query start as a UTC access cutoff.
        // Include the beginnings of overlapping intervals and every legal zone offset;
        // discard only the extra daily buckets, never the underlying measurements.
        long earliest = store.firstOverlappingStaged(kind, start.atStartOfDay().toInstant(ZoneOffset.MAX).toEpochMilli(), end.atStartOfDay().toInstant(ZoneOffset.MIN).toEpochMilli());
        LocalDate queryStart = Instant.ofEpochMilli(earliest).atOffset(ZoneOffset.UTC).toLocalDate().minusDays(1);
        AggregateRecordsRequest<T> request = new AggregateRecordsRequest.Builder<T>(new LocalTimeRangeFilter.Builder().setStartTime(queryStart.atStartOfDay()).setEndTime(end.atStartOfDay()).build())
            .addDataOriginsFilter(origin).addAggregationType(type).build();
        CompletableFuture<List<AggregateRecordsGroupedByPeriodResponse<T>>> future = new CompletableFuture<>();
        manager.aggregateGroupByPeriod(request, Period.ofDays(1), Runnable::run, receiver(future));
        JSONArray rows = new JSONArray();
        for (AggregateRecordsGroupedByPeriodResponse<T> bucket : await(future)) {
            T value = bucket.get(type); if (value == null) continue;
            for (DataOrigin source : bucket.getDataOrigins(type)) if (!HealthRecordCodec.SOURCE.equals(source.getPackageName())) throw new IllegalStateException("Unexpected aggregate source");
            LocalDate date = bucket.getStartTime().toLocalDate();
            if (date.isBefore(start)) continue;
            long at = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            rows.put(new JSONObject().put("id", date.toString()).put("type", kind + "Day").put("source", HealthRecordCodec.SOURCE).put("start", at).put("end", at).put("date", date.toString()).put("value", number(value)));
        }
        checkVisible(); store.stage(rows);
    }

    JSONArray stepHours(LocalDate date) throws Exception {
        checkVisible();
        if (!allowed(HealthRecordCodec.PERMISSIONS.get("steps"))) return new JSONArray();
        ZoneId zone = ZoneId.systemDefault(); Instant start = date.atStartOfDay(zone).toInstant(), end = date.plusDays(1).atStartOfDay(zone).toInstant();
        if (start.isAfter(Instant.now())) return new JSONArray();
        AggregateRecordsRequest<Long> request = new AggregateRecordsRequest.Builder<Long>(new TimeInstantRangeFilter.Builder().setStartTime(start).setEndTime(end).build())
            .addDataOriginsFilter(origin).addAggregationType(StepsRecord.STEPS_COUNT_TOTAL).build();
        CompletableFuture<List<AggregateRecordsGroupedByDurationResponse<Long>>> future = new CompletableFuture<>();
        manager.aggregateGroupByDuration(request, Duration.ofHours(1), Runnable::run, receiver(future));
        JSONArray hours = new JSONArray();
        for (AggregateRecordsGroupedByDurationResponse<Long> bucket : await(future)) {
            Long value = bucket.get(StepsRecord.STEPS_COUNT_TOTAL);
            for (DataOrigin source : bucket.getDataOrigins(StepsRecord.STEPS_COUNT_TOTAL)) if (!HealthRecordCodec.SOURCE.equals(source.getPackageName())) throw new IllegalStateException("Unexpected aggregate source");
            if (value != null) hours.put(new JSONObject().put("start", bucket.getStartTime().toEpochMilli()).put("end", bucket.getEndTime().toEpochMilli()).put("value", value));
        }
        return hours;
    }

    private static double number(Object value) {
        double number;
        if (value instanceof Number) number = ((Number) value).doubleValue();
        else if (value instanceof Length) number = ((Length) value).getInMeters();
        else if (value instanceof Energy) number = ((Energy) value).getInCalories() / 1000;
        else throw new IllegalArgumentException("Unsupported aggregate unit");
        if (!Double.isFinite(number) || number < 0) throw new IllegalArgumentException("Invalid aggregate");
        return number;
    }
}
