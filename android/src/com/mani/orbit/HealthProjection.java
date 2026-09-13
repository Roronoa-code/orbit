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
        ZoneId zone = ZoneId.systemDefault();
        long from = date.minusDays(366).atStartOfDay(zone).toInstant().toEpochMilli();
        long until = date.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli();
        JSONArray rows = new JSONArray(); Map<String, JSONObject> heart = new TreeMap<>();
        try (Cursor cursor = store.window(from, until)) {
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject(cursor.getString(0)); String type = row.getString("type");
                if ("steps".equals(type) || "distance".equals(type) || "floors".equals(type) || "energy".equals(type) || "totalEnergy".equals(type) || "exercise".equals(type)) continue;
                if ("heart".equals(type)) {
                    JSONArray samples = row.getJSONArray("samples");
                    for (int i = 0; i < samples.length(); i++) {
                        JSONArray sample = samples.getJSONArray(i); long at = sample.getLong(0); double bpm = sample.getDouble(1);
                        if (at < from || at >= until || !Double.isFinite(bpm) || bpm <= 0) continue;
                        ZonedDateTime time = Instant.ofEpochMilli(at).atZone(zone);
                        String key = time.toLocalDate() + ":" + time.getHour(); JSONObject bucket = heart.get(key);
                        if (bucket == null) { bucket = new JSONObject().put("type", "heartHour").put("source", HealthRecordCodec.SOURCE).put("date", time.toLocalDate().toString()).put("hour", time.getHour()).put("count", 0).put("sum", 0).put("low", bpm).put("high", bpm); heart.put(key, bucket); }
                        bucket.put("count", bucket.getLong("count") + 1).put("sum", bucket.getDouble("sum") + bpm).put("low", Math.min(bucket.getDouble("low"), bpm)).put("high", Math.max(bucket.getDouble("high"), bpm));
                        if (at >= bucket.optLong("latestTime", 0)) bucket.put("latestTime", at).put("latest", bpm);
                    }
                } else rows.put(row);
            }
        }
        for (JSONObject bucket : heart.values()) rows.put(bucket);
        JSONArray workouts = new JSONArray();
        try (Cursor cursor = store.workouts()) { while (cursor.moveToNext()) workouts.put(new JSONObject(cursor.getString(0))); }
        return new JSONObject().put("schema", 1).put("date", date.toString()).put("rows", rows).put("workouts", workouts).put("meta", store.metadata());
    }
}
