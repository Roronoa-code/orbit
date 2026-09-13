package com.mani.orbit;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.Collection;

/** Imported originals stay private. An incomplete scan never replaces the last completed import. */
final class HealthRecordStore implements AutoCloseable {
    private final SQLiteDatabase db;

    HealthRecordStore(File path) {
        File parent = path.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("Cannot create health storage");
        db = SQLiteDatabase.openOrCreateDatabase(path, null);
        db.execSQL("CREATE TABLE IF NOT EXISTS records (kind TEXT NOT NULL,id TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(kind,id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS health_time ON records(start,end)");
        db.execSQL("CREATE TABLE IF NOT EXISTS staging (kind TEXT NOT NULL,id TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(kind,id))");
        db.execSQL("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY,value TEXT NOT NULL)");
    }

    void beginImport() { db.delete("staging", null, null); }

    void stage(JSONArray rows) throws Exception {
        db.beginTransaction();
        try {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                if (!"com.sec.android.app.shealth".equals(row.getString("source"))) throw new IllegalArgumentException("Unexpected source");
                String kind = row.getString("type"), id = row.getString("id");
                long start = row.getLong("start"), end = row.getLong("end");
                if (kind.isEmpty() || id.isEmpty() || start < 0 || end < start) throw new IllegalArgumentException("Invalid record");
                ContentValues value = new ContentValues();
                value.put("kind", kind); value.put("id", id); value.put("start", start); value.put("end", end); value.put("payload", row.toString());
                if (db.insertWithOnConflict("staging", null, value, SQLiteDatabase.CONFLICT_REPLACE) < 0) throw new IllegalStateException("Record not saved");
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    long firstStaged(String kind, long fallback) {
        try (Cursor rows = db.rawQuery("SELECT MIN(start) FROM staging WHERE kind=?", new String[]{kind})) {
            return rows.moveToFirst() && !rows.isNull(0) ? rows.getLong(0) : fallback;
        }
    }

    long firstOverlappingStaged(String kind, long start, long end) {
        try (Cursor rows = db.rawQuery("SELECT MIN(start) FROM staging WHERE kind=? AND end>=? AND start<?", new String[]{kind, Long.toString(start), Long.toString(end)})) {
            return rows.moveToFirst() && !rows.isNull(0) ? Math.min(start, rows.getLong(0)) : start;
        }
    }

    void finishImport(Collection<String> kinds, long start, long end, boolean history) throws Exception {
        db.beginTransaction();
        try {
            for (String kind : kinds) db.delete("records", "kind=? AND start>=? AND start<?", new String[]{kind, Long.toString(start), Long.toString(end)});
            db.execSQL("INSERT OR REPLACE INTO records SELECT * FROM staging");
            JSONObject meta = new JSONObject().put("lastSync", System.currentTimeMillis()).put("historyAllowed", history).put("readStart", start).put("readEnd", end).put("types", new JSONArray(kinds));
            ContentValues value = new ContentValues(); value.put("key", "import"); value.put("value", meta.toString());
            if (db.insertWithOnConflict("metadata", null, value, SQLiteDatabase.CONFLICT_REPLACE) < 0) throw new IllegalStateException("Import not saved");
            db.delete("staging", null, null);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    JSONObject metadata() throws Exception {
        JSONObject info = new JSONObject();
        try (Cursor row = db.rawQuery("SELECT value FROM metadata WHERE key='import'", null)) { if (row.moveToFirst()) info = new JSONObject(row.getString(0)); }
        try (Cursor row = db.rawQuery("SELECT COUNT(*),MIN(start),MAX(end) FROM records WHERE kind NOT LIKE '%Day'", null)) {
            if (row.moveToFirst()) { info.put("recordCount", row.getLong(0)); if (!row.isNull(1)) info.put("firstRecord", row.getLong(1)); if (!row.isNull(2)) info.put("lastRecord", row.getLong(2)); }
        }
        return info;
    }

    Cursor window(long start, long end) {
        return db.rawQuery("SELECT payload FROM records WHERE end>=? AND start<? ORDER BY start,id", new String[]{Long.toString(start), Long.toString(end)});
    }

    Cursor workouts() { return db.rawQuery("SELECT payload FROM records WHERE kind='exercise' ORDER BY start DESC,id", null); }
    Cursor pendingWorkouts() { return db.rawQuery("SELECT payload FROM staging WHERE kind='exercise' ORDER BY start,id", null); }
    @Override public void close() { db.close(); }
}
