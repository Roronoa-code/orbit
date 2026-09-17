package com.mani.orbit;

import android.database.Cursor;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.*;
import java.util.Map;
import java.util.TreeMap;

/** Bounded chart window; original records remain in SQLite, not in the WebView's frame loop. */
final class HealthProjection {
    static JSONObject read(HealthRecordStore store, LocalDate date) throws Exception {
        return read(store, date, false);
    }

    static JSONObject read(HealthRecordStore store, LocalDate date, boolean workoutSummaries) throws Exception {
        store.beginRead();
        try { return readSnapshot(store, date, workoutSummaries); }
        finally { store.endRead(); }
    }

    private static JSONObject readSnapshot(HealthRecordStore store, LocalDate date, boolean workoutSummaries) throws Exception {
        ZoneId zone = ZoneId.systemDefault();
        long from = date.minusDays(366).atStartOfDay(zone).toInstant().toEpochMilli();
        long until = date.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli();
        JSONArray rows = new JSONArray(); Map<String, JSONObject> heart = new TreeMap<>();
        try (Cursor cursor = store.window(from, until)) {
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject(cursor.getString(0)); String type = row.getString("type");
                if ("steps".equals(type) || "distance".equals(type) || "floors".equals(type) || "energy".equals(type) || "totalEnergy".equals(type) || "exercise".equals(type)) continue;
                if ("heart".equals(type)) {
                    int count = 0;
                    try (Cursor blocks = store.arrayParts(row, "samples")) { while (blocks.moveToNext()) {
                    JSONArray samples = new JSONArray(blocks.getString(0));
                    count = Math.addExact(count, samples.length());
                    for (int i = 0; i < samples.length(); i++) {
                        JSONArray sample = samples.getJSONArray(i); long at = sample.getLong(0); double bpm = sample.getDouble(1);
                        if (at < from || at >= until || !Double.isFinite(bpm) || bpm <= 0) continue;
                        double low = sample.length() > 2 ? sample.getDouble(2) : bpm, high = sample.length() > 3 ? sample.getDouble(3) : bpm;
                        if (!Double.isFinite(low) || !Double.isFinite(high) || low <= 0 || bpm < low || bpm > high) throw new IllegalArgumentException("Invalid heart range");
                        ZonedDateTime time = Instant.ofEpochMilli(at).atZone(zone);
                        String key = time.toLocalDate() + ":" + time.getHour(); JSONObject bucket = heart.get(key);
                        if (bucket == null) { bucket = new JSONObject().put("type", "heartHour").put("source", "com.sec.android.app.shealth").put("date", time.toLocalDate().toString()).put("hour", time.getHour()).put("count", 0).put("sum", 0).put("low", low).put("high", high); heart.put(key, bucket); }
                        bucket.put("count", bucket.getLong("count") + 1).put("sum", bucket.getDouble("sum") + bpm).put("low", Math.min(bucket.getDouble("low"), low)).put("high", Math.max(bucket.getDouble("high"), high));
                        if (at >= bucket.optLong("latestTime", 0)) bucket.put("latestTime", at).put("latest", bpm);
                    }
                    } }
                    if (row.has("_counts") && count != row.getJSONObject("_counts").getInt("samples")) throw new IllegalStateException("Heart samples are incomplete");
                } else rows.put(store.hydrate(row));
            }
        }
        for (JSONObject bucket : heart.values()) rows.put(bucket);
        JSONArray workouts = new JSONArray();
        try (Cursor cursor = store.workouts()) { while (cursor.moveToNext()) {
            JSONObject row = new JSONObject(cursor.getString(0));
            workouts.put(workoutSummaries ? row : store.hydrate(row));
        } }
        return new JSONObject().put("schema", 1).put("date", date.toString()).put("rows", rows).put("workouts", workouts).put("meta", store.metadata());
    }
}
