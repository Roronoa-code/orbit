package com.mani.orbit;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.Collection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Imported originals stay private. An incomplete scan never replaces the last completed import. */
final class HealthRecordStore implements AutoCloseable {
    private final SQLiteDatabase db;
    private static final int PART_CHARS = 65536;
    private static final Set<String> ARRAYS = new HashSet<>(Arrays.asList("samples", "stages", "route", "logs", "laps", "segments"));

    HealthRecordStore(File path) {
        File parent = path.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("Cannot create health storage");
        db = SQLiteDatabase.openOrCreateDatabase(path, null);
        db.execSQL("CREATE TABLE IF NOT EXISTS records (kind TEXT NOT NULL,id TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(kind,id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS health_time ON records(start,end)");
        db.execSQL("CREATE TABLE IF NOT EXISTS staging (kind TEXT NOT NULL,id TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(kind,id))");
        db.execSQL("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY,value TEXT NOT NULL)");
        for (String table : Arrays.asList("record_parts", "staging_parts"))
            db.execSQL("CREATE TABLE IF NOT EXISTS " + table + " (kind TEXT NOT NULL,id TEXT NOT NULL,field TEXT NOT NULL,part INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(kind,id,field,part))");
        if (db.getVersion() == 0) {
            try { migrate(); }
            catch (Exception error) { db.close(); throw new IllegalStateException("Health storage upgrade did not finish", error); }
        }
        if (db.getVersion() != 1) { db.close(); throw new IllegalStateException("Unsupported health storage version"); }
    }

    void beginImport() {
        db.beginTransaction();
        try { db.delete("staging_parts", null, null); db.delete("staging", null, null); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
    // Hold one SQLite snapshot across rows, workouts and metadata, even during an import commit.
    void beginRead() { db.beginTransactionNonExclusive(); }
    void endRead() { db.endTransaction(); }

    void stage(JSONArray rows) throws Exception {
        db.beginTransaction();
        try {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                if (!"com.sec.android.app.shealth".equals(row.getString("source"))) throw new IllegalArgumentException("Unexpected source");
                if (row.has("_parts") || row.has("_counts")) throw new IllegalArgumentException("Load complete health details before staging a summary");
                String kind = row.getString("type"), id = row.getString("id");
                long start = row.getLong("start"), end = row.getLong("end");
                if (kind.isEmpty() || id.isEmpty() || start < 0 || end < start) throw new IllegalArgumentException("Invalid record");
                write("staging", "staging_parts", row);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** Keep each cursor row small; array values remain exact and in original order. */
    private void write(String table, String partsTable, JSONObject row) throws Exception {
        String kind = row.getString("type"), id = row.getString("id");
        db.delete(partsTable, "kind=? AND id=?", new String[]{kind, id});
        JSONObject header = new JSONObject(), parts = new JSONObject(), counts = new JSONObject();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        java.util.Iterator<String> keys = row.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.equals("_parts") || key.equals("_counts") || key.equals("_revision")) continue;
            Object value = row.get(key);
            if (ARRAYS.contains(key) && value instanceof JSONArray) {
                JSONArray array = (JSONArray) value; int part = 0;
                StringBuilder block = new StringBuilder("[");
                digest.update(key.getBytes(StandardCharsets.UTF_8));
                for (int i = 0; i < array.length(); i++) {
                    Object element = array.get(i);
                    String item = element instanceof String ? JSONObject.quote((String) element)
                            : element instanceof Number ? JSONObject.numberToString((Number) element) : element.toString();
                    if (item.length() > PART_CHARS) throw new IllegalArgumentException("Health array item exceeds storage limit");
                    if (block.length() > 1 && block.length() + item.length() + 2 > PART_CHARS) {
                        savePart(partsTable, kind, id, key, part++, block.append(']').toString(), digest);
                        block = new StringBuilder("[");
                    }
                    if (block.length() > 1) block.append(',');
                    block.append(item);
                }
                if (block.length() > 1) savePart(partsTable, kind, id, key, part++, block.append(']').toString(), digest);
                header.put(key, new JSONArray()); parts.put(key, part); counts.put(key, array.length());
            } else header.put(key, value);
        }
        header.put("_parts", parts).put("_counts", counts);
        digest.update(header.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hash = new StringBuilder();
        for (byte b : digest.digest()) hash.append(Character.forDigit((b & 255) >> 4, 16)).append(Character.forDigit(b & 15, 16));
        header.put("_revision", hash.toString());
        String payload = header.toString();
        if (payload.length() > PART_CHARS) throw new IllegalArgumentException("Health header exceeds storage limit");
        ContentValues stored = new ContentValues();
        stored.put("kind", kind); stored.put("id", id); stored.put("start", row.getLong("start")); stored.put("end", row.getLong("end")); stored.put("payload", payload);
        if (db.insertWithOnConflict(table, null, stored, SQLiteDatabase.CONFLICT_REPLACE) < 0) throw new IllegalStateException("Record not saved");
    }

    private void savePart(String table, String kind, String id, String field, int part, String text, MessageDigest digest) {
        digest.update(text.getBytes(StandardCharsets.UTF_8));
        ContentValues value = new ContentValues();
        value.put("kind", kind); value.put("id", id); value.put("field", field); value.put("part", part); value.put("payload", text);
        if (db.insert(table, null, value) < 0) throw new IllegalStateException("Health detail not saved");
    }

    /** Upgrade old inline records transactionally, without ever asking CursorWindow for a huge value. */
    private void migrate() throws Exception {
        db.beginTransaction();
        try {
            String lastKind = "", lastId = "";
            while (true) {
                String kind, id;
                try (Cursor next = db.rawQuery("SELECT kind,id FROM records WHERE (kind='exercise' OR length(payload)>?) AND (kind>? OR (kind=? AND id>?)) ORDER BY kind,id LIMIT 1",
                        new String[]{Integer.toString(PART_CHARS), lastKind, lastKind, lastId})) {
                    if (!next.moveToFirst()) break;
                    kind = next.getString(0); id = next.getString(1);
                }
                StringBuilder original = new StringBuilder();
                for (int offset = 1; ; offset += PART_CHARS) {
                    try (Cursor piece = db.rawQuery("SELECT substr(payload,?,?) FROM records WHERE kind=? AND id=?",
                            new String[]{Integer.toString(offset), Integer.toString(PART_CHARS), kind, id})) {
                        if (!piece.moveToFirst()) throw new IllegalStateException("Health record disappeared during upgrade");
                        String text = piece.getString(0); if (text.isEmpty()) break; original.append(text);
                    }
                }
                write("records", "record_parts", new JSONObject(original.toString()));
                lastKind = kind; lastId = id;
            }
            db.setVersion(1); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    Cursor arrayParts(JSONObject header, String field) throws Exception { return arrayParts(header, field, false); }
    private Cursor arrayParts(JSONObject header, String field, boolean pending) throws Exception {
        JSONObject index = header.optJSONObject("_parts");
        if (index == null || !index.has(field)) {
            MatrixCursor inline = new MatrixCursor(new String[]{"payload"});
            JSONArray value = header.optJSONArray(field);
            if (value != null) inline.addRow(new Object[]{value.toString()});
            return inline;
        }
        Cursor parts = db.rawQuery("SELECT payload FROM " + (pending ? "staging_parts" : "record_parts") + " WHERE kind=? AND id=? AND field=? ORDER BY part",
                new String[]{header.getString("type"), header.getString("id"), field});
        if (parts.getCount() != index.getInt(field)) { parts.close(); throw new IllegalStateException("Health detail is incomplete"); }
        return parts;
    }

    JSONObject hydrate(JSONObject header) throws Exception { return hydrate(header, false); }
    JSONObject hydratePending(JSONObject header) throws Exception { return hydrate(header, true); }
    private JSONObject hydrate(JSONObject header, boolean pending) throws Exception {
        JSONObject index = header.optJSONObject("_parts");
        if (index == null) return header;
        java.util.Iterator<String> fields = index.keys();
        while (fields.hasNext()) {
            String field = fields.next(); JSONArray full = new JSONArray();
            try (Cursor parts = arrayParts(header, field, pending)) {
                while (parts.moveToNext()) {
                    JSONArray block = new JSONArray(parts.getString(0));
                    for (int i = 0; i < block.length(); i++) full.put(block.get(i));
                }
            }
            if (full.length() != header.getJSONObject("_counts").getInt(field)) throw new IllegalStateException("Health array is incomplete");
            header.put(field, full);
        }
        header.remove("_parts"); header.remove("_counts");
        return header;
    }

    JSONObject record(String kind, String id) throws Exception {
        beginRead();
        try (Cursor row = db.rawQuery("SELECT payload FROM records WHERE kind=? AND id=?", new String[]{kind, id})) {
            return row.moveToFirst() ? hydrate(new JSONObject(row.getString(0))) : null;
        } finally { endRead(); }
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
        finishImport(kinds, start, end, history, "health_connect");
    }

    void finishImport(Collection<String> kinds, long start, long end, boolean history, String transport) throws Exception {
        if (!"health_connect".equals(transport) && !"samsung_sdk".equals(transport)) throw new IllegalArgumentException("Unknown import transport");
        db.beginTransaction();
        try {
            JSONObject previous = metadata();
            for (String kind : kinds) db.delete("records", "kind=? AND start>=? AND start<?", new String[]{kind, Long.toString(start), Long.toString(end)});
            db.execSQL("DELETE FROM record_parts WHERE EXISTS (SELECT 1 FROM staging s WHERE s.kind=record_parts.kind AND s.id=record_parts.id)");
            db.execSQL("INSERT OR REPLACE INTO records SELECT * FROM staging");
            db.execSQL("DELETE FROM record_parts WHERE NOT EXISTS (SELECT 1 FROM records r WHERE r.kind=record_parts.kind AND r.id=record_parts.id)");
            db.execSQL("INSERT INTO record_parts SELECT * FROM staging_parts");
            JSONObject meta = new JSONObject().put("lastSync", System.currentTimeMillis()).put("historyAllowed", history).put("readStart", start).put("readEnd", end).put("types", new JSONArray(kinds)).put("transport", transport);
            if ("samsung_sdk".equals(transport)) {
                java.util.Set<String> complete = new java.util.HashSet<>();
                if (transport.equals(previous.optString("transport"))) {
                    JSONArray old = previous.optJSONArray("completeTypes");
                    if (old != null) for (int i = 0; i < old.length(); i++) complete.add(old.getString(i));
                }
                if (start == 0) complete.addAll(kinds);
                meta.put("completeTypes", new JSONArray(complete));
                meta.put("lastFullSync", start == 0 ? System.currentTimeMillis() : previous.optLong("lastFullSync", 0));
            }
            ContentValues value = new ContentValues(); value.put("key", "import"); value.put("value", meta.toString());
            if (db.insertWithOnConflict("metadata", null, value, SQLiteDatabase.CONFLICT_REPLACE) < 0) throw new IllegalStateException("Import not saved");
            db.delete("staging", null, null);
            db.delete("staging_parts", null, null);
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
