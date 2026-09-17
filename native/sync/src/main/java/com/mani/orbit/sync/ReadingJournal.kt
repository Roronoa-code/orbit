package com.mani.orbit.sync

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class PendingBatch(val id: String, val hash: String, val bytes: ByteArray, val path: String = ReadingWire.PATH)
data class ReadingReceipt(val batch: String, val hash: String)

/** The same transactional journal runs on watch and phone. Samsung import staging never touches it. */
class ReadingJournal(path: File) : AutoCloseable {
    internal val db: SQLiteDatabase
    init {
        check(path.parentFile!!.isDirectory || path.parentFile!!.mkdirs())
        db = SQLiteDatabase.openOrCreateDatabase(path, null)
        try {
        db.beginTransaction()
        try {
        db.execSQL("CREATE TABLE IF NOT EXISTS outbox(id TEXT PRIMARY KEY,hash TEXT NOT NULL,bytes BLOB NOT NULL,created INTEGER NOT NULL)")
        val hasPath = db.rawQuery("PRAGMA table_info(outbox)", null).use { c ->
            var found = false; while (c.moveToNext()) if (c.getString(1) == "path") found = true; found
        }
        if (!hasPath) db.execSQL("ALTER TABLE outbox ADD COLUMN path TEXT NOT NULL DEFAULT '${ReadingWire.PATH}'")
        db.execSQL("CREATE INDEX IF NOT EXISTS outbox_family ON outbox(path,created)")
        db.execSQL("CREATE TABLE IF NOT EXISTS receipts(installation TEXT NOT NULL,batch TEXT NOT NULL,hash TEXT NOT NULL,received INTEGER NOT NULL,PRIMARY KEY(installation,batch))")
        db.execSQL("CREATE TABLE IF NOT EXISTS readings(installation TEXT NOT NULL,id TEXT NOT NULL,revision INTEGER NOT NULL,metric TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,hash TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(installation,id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS reading_time ON readings(metric,start,end)")
        db.execSQL("CREATE TABLE IF NOT EXISTS measurements(installation TEXT NOT NULL,id TEXT NOT NULL,at INTEGER NOT NULL,payload BLOB NOT NULL,PRIMARY KEY(installation,id))")
        EcgJournal.createTables(db)
        HeartJournal.createTables(db)
        RawSensorJournal.createTables(db)
        SweatJournal.createTables(db)
        migrateReadingOrder()
        migrateMeasurementOrder()
        EcgJournal(this).migrateOrder()
        db.execSQL("CREATE TABLE IF NOT EXISTS workouts(installation TEXT NOT NULL,id TEXT NOT NULL,revision INTEGER NOT NULL,start INTEGER NOT NULL,phase TEXT NOT NULL,hash TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(installation,id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS workout_history ON workouts(installation,start DESC,id DESC) WHERE phase IN ('ended','interrupted')")
        db.execSQL("CREATE TABLE IF NOT EXISTS workout_points(installation TEXT NOT NULL,workout TEXT NOT NULL,elapsed INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(installation,workout,elapsed))")
        db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        } catch (failure: Exception) { db.close(); throw failure }
    }

    fun enqueue(batch: ReadingBatch): PendingBatch {
        val bytes = ReadingWire.encode(batch)
        return enqueueBytes(batch.id, bytes, ReadingWire.PATH)
    }

    internal fun enqueueBytes(id: String, bytes: ByteArray, path: String): PendingBatch {
        val hash = ReadingWire.digest(bytes)
        db.beginTransaction()
        try {
            db.rawQuery("SELECT hash,path FROM outbox WHERE id=?", arrayOf(id)).use { cursor ->
                if (cursor.moveToFirst()) require(cursor.getString(0) == hash && cursor.getString(1) == path) { "Batch identity collision" }
                else db.insertOrThrow("outbox", null, ContentValues().apply {
                    put("id", id); put("hash", hash); put("bytes", bytes); put("created", System.currentTimeMillis()); put("path", path)
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return PendingBatch(id, hash, bytes, path)
    }

    /** Capture and queue are one transaction. A repeated sensor callback creates no new batch. */
    fun capture(installation: String, readings: List<WatchReading>): PendingBatch? {
        uuid(installation)
        require(readings.size in 1..ReadingWire.MAX_READINGS && readings.map { it.id }.distinct().size == readings.size)
        db.beginTransaction()
        try {
            val changed = readings.mapNotNull { sample ->
                val previous = db.rawQuery("SELECT payload FROM readings WHERE installation=? AND id=?", arrayOf(installation, sample.id)).use {
                    if (it.moveToFirst()) JSONObject(it.getString(0)) else null
                }
                if (previous == null) sample else {
                    require(previous.getString("boot") == sample.boot && previous.getLong("elapsedMs") == sample.elapsedMs &&
                        previous.getString("metric") == sample.metric && previous.getString("source") == sample.source) { "Sample identity collision" }
                    val sameValue = if (sample.value == null) previous.isNull("value") else !previous.isNull("value") && previous.getDouble("value") == sample.value
                    // The passive and foreground APIs can deliver the same sample with less accuracy metadata.
                    val quality = if (sameValue && previous.getString("quality") == "valid" && sample.quality == "unknown") "valid" else sample.quality
                    val bootCount = sample.bootCount ?: readingBootCount(previous)
                    val timeUncertain = previous.getBoolean("timeUncertain") || sample.timeUncertain
                    if (sameValue && readingBootCount(previous) == bootCount && previous.getString("quality") == quality &&
                        previous.getBoolean("timeUncertain") == timeUncertain) null
                    else sample.copy(revision = Math.addExact(previous.getLong("revision"), 1), start = previous.getLong("start"),
                        end = previous.getLong("end"), offsetSeconds = previous.getInt("offset"), timeUncertain = timeUncertain,
                        quality = quality, bootCount = bootCount)
                }
            }
            val pending = if (changed.isEmpty()) null else {
                val batch = ReadingBatch(UUID.randomUUID().toString(), installation, changed)
                changed.forEach { store(installation, it) }
                enqueue(batch)
            }
            db.setTransactionSuccessful()
            return pending
        } finally { db.endTransaction() }
    }

    fun pending(limit: Int = 16, path: String? = null): List<PendingBatch> {
        require(limit in 1..100)
        val where = if (path == null) "" else "WHERE path=?"
        val args = if (path == null) arrayOf(limit.toString()) else arrayOf(path, limit.toString())
        return db.rawQuery("SELECT id,hash,bytes,path FROM outbox $where ORDER BY created,rowid LIMIT ?", args).use { cursor ->
            buildList { while (cursor.moveToNext()) add(PendingBatch(cursor.getString(0), cursor.getString(1), cursor.getBlob(2), cursor.getString(3))) }
        }
    }

    fun pendingPaths(): Set<String> = db.rawQuery("SELECT DISTINCT path FROM outbox", null).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    /** Caller validates the paired role before accepting the receipt. Wrong hashes never delete data. */
    fun acknowledge(receipt: ReadingReceipt): Boolean = db.delete("outbox", "id=? AND hash=?", arrayOf(receipt.batch, receipt.hash)) == 1

    /** Return an ACK only after both originals and receipt commit. Retries are idempotent. */
    fun receive(bytes: ByteArray, receivedAt: Long): ReadingReceipt {
        require(receivedAt >= 0)
        val batch = ReadingWire.decode(bytes)
        val digest = ReadingWire.digest(bytes)
        db.beginTransaction()
        try {
            val duplicate = db.rawQuery("SELECT hash FROM receipts WHERE installation=? AND batch=?", arrayOf(batch.installation, batch.id)).use { cursor ->
                if (cursor.moveToFirst()) { require(cursor.getString(0) == digest) { "Batch identity collision" }; true } else false
            }
            if (!duplicate) {
                for (reading in batch.readings) {
                    val payload = ReadingWire.json(reading).toString()
                    val hash = ReadingWire.digest(payload.toByteArray(Charsets.UTF_8))
                    val replace = db.rawQuery("SELECT revision,hash FROM readings WHERE installation=? AND id=?", arrayOf(batch.installation, reading.id)).use { cursor ->
                        if (!cursor.moveToFirst()) true else {
                            if (cursor.getLong(0) == reading.revision) require(cursor.getString(1) == hash) { "Conflicting reading revision" }
                            reading.revision > cursor.getLong(0)
                        }
                    }
                    if (replace) store(batch.installation, reading)
                }
                db.insertOrThrow("receipts", null, ContentValues().apply {
                    put("installation", batch.installation); put("batch", batch.id); put("hash", digest); put("received", receivedAt)
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return ReadingReceipt(batch.id, digest)
    }

    private fun store(installation: String, reading: WatchReading) {
        registerBoot(installation, reading.boot, reading.bootCount)
        val payload = ReadingWire.json(reading).toString()
        db.insertWithOnConflict("readings", null, ContentValues().apply {
            put("installation", installation); put("id", reading.id); put("revision", reading.revision)
            put("metric", reading.metric); put("start", reading.start); put("end", reading.end)
            put("boot", reading.boot); put("sensor_elapsed", reading.elapsedMs)
            put("hash", ReadingWire.digest(payload.toByteArray(Charsets.UTF_8))); put("payload", payload)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it >= 0) { "Reading could not be saved" } }
    }

    /** Indexed sensor order within a boot, native boot order across restarts. Originals stay intact. */
    fun latest(metric: String, installation: String? = null, boot: String? = null): JSONObject? {
        require(metric in WatchReading.UNITS)
        installation?.let(::uuid)
        boot?.let { uuid(it); require(installation != null) }
        val clauses = listOfNotNull(installation?.let { "b.installation=?" }, boot?.let { "b.boot=?" })
        val where = if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")
        val args = listOfNotNull(metric, installation, boot).toTypedArray()
        // One indexed seek per boot, rather than parsing/sorting the entire sensor archive.
        val candidates = db.rawQuery("""SELECT r.installation,r.payload,b.boot_count FROM reading_boots b
            JOIN readings r ON r.installation=b.installation AND r.id=(SELECT id FROM readings
            WHERE installation=b.installation AND boot=b.boot AND metric=? ORDER BY sensor_elapsed DESC,id DESC LIMIT 1)
            $where""", args).use { c ->
            buildList { while (c.moveToNext()) add(JSONObject(c.getString(1)).put("installation", c.getString(0)) to
                if (c.isNull(2)) null else c.getInt(2)) }
        }
        val wallOrder = compareBy<JSONObject> { it.getLong("end") }.thenBy { it.getString("id") }
        val sources = candidates.groupBy { it.first.getString("installation") }.values.map { readings ->
            orderSensorEntries(readings, wallOrder).first()
                .put("orderingUncertain", readings.size > 1 && readings.any { it.second == null })
        }
        return sources.maxWithOrNull(wallOrder)?.also {
            if (sources.size > 1) it.put("orderingUncertain", true) // Sensor clocks cannot order different watches.
        }
    }

    /** The schema and indexes migrate in the caller's transaction, including original legacy payloads. */
    private fun migrateReadingOrder() {
        db.execSQL("CREATE TABLE IF NOT EXISTS reading_boots(installation TEXT NOT NULL,boot TEXT NOT NULL,boot_count INTEGER,PRIMARY KEY(installation,boot),UNIQUE(installation,boot_count))")
        val ready = db.rawQuery("PRAGMA table_info(readings)", null).use { c ->
            var found = false; while (c.moveToNext()) if (c.getString(1) == "sensor_elapsed") found = true; found
        }
        if (!ready) {
            db.execSQL("ALTER TABLE readings ADD COLUMN boot TEXT")
            db.execSQL("ALTER TABLE readings ADD COLUMN sensor_elapsed INTEGER")
            db.rawQuery("SELECT installation,id,payload FROM readings", null).use { c -> while (c.moveToNext()) {
                val row = JSONObject(c.getString(2))
                val boot = row.getString("boot").also(::uuid)
                val elapsed = row.getLong("elapsedMs").also { require(it >= 0) }
                registerBoot(c.getString(0), boot, readingBootCount(row))
                check(db.update("readings", ContentValues().apply { put("boot", boot); put("sensor_elapsed", elapsed) },
                    "installation=? AND id=?", arrayOf(c.getString(0), c.getString(1))) == 1)
            } }
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS reading_sensor_order ON readings(installation,boot,metric,sensor_elapsed DESC,id DESC)")
    }

    internal fun registerBoot(installation: String, boot: String, count: Int?) {
        db.execSQL("INSERT OR IGNORE INTO reading_boots(installation,boot,boot_count) VALUES(?,?,?)", arrayOf<Any?>(installation, boot, count))
        if (count != null) {
            db.execSQL("UPDATE reading_boots SET boot_count=? WHERE installation=? AND boot=? AND boot_count IS NULL", arrayOf<Any?>(count, installation, boot))
            val saved = db.rawQuery("SELECT boot_count FROM reading_boots WHERE installation=? AND boot=?", arrayOf(installation, boot)).use {
                if (it.moveToFirst() && !it.isNull(0)) it.getInt(0) else null
            }
            require(saved == count) { "Conflicting sensor boot identity" }
        }
    }

    fun installations(): List<String> = db.rawQuery("SELECT installation FROM (SELECT installation,end AS at FROM readings UNION ALL SELECT installation,start AS at FROM ecg_records UNION ALL SELECT installation,at FROM heart_frames WHERE payload IS NOT NULL UNION ALL SELECT installation,at FROM raw_frames WHERE payload IS NOT NULL) GROUP BY installation ORDER BY MAX(at) DESC", null).use {
        buildList { while (it.moveToNext()) add(it.getString(0)) }
    }

    fun pendingCount(): Long = db.rawQuery("SELECT COUNT(*) FROM outbox", null).use { it.moveToFirst(); it.getLong(0) }

    fun isPending(id: String, hash: String): Boolean = db.rawQuery("SELECT 1 FROM outbox WHERE id=? AND hash=?", arrayOf(id, hash)).use { it.moveToFirst() }

    fun readings(metric: String, start: Long, end: Long): List<JSONObject> {
        require(metric in WatchReading.UNITS && start >= 0 && end >= start)
        return db.rawQuery("SELECT installation,payload FROM readings WHERE metric=? AND end>=? AND start<? ORDER BY start,id",
            arrayOf(metric, start.toString(), end.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(JSONObject(cursor.getString(1)).put("installation", cursor.getString(0))) }
        }
    }

    /** Session, incremental route, and pending transfer either all commit or none do. */
    fun interruptOtherBootWorkouts(installation: String, boot: String, at: Long): Int {
        uuid(installation); uuid(boot); require(at >= 0)
        db.beginTransaction()
        try {
            val stale = db.rawQuery("SELECT payload FROM workouts WHERE installation=? AND phase NOT IN ('ended','interrupted')", arrayOf(installation)).use { c ->
                buildList { while (c.moveToNext()) { val w = WorkoutWire.read(JSONObject(c.getString(0))); if (w.boot != boot) add(w) } }
            }
            for (w in stale) captureWorkoutUpdate(installation, w.copy(revision = Math.addExact(w.revision, 1),
                phase = "interrupted", updatedAt = at, timeUncertain = true), emptyList())
            db.setTransactionSuccessful()
            return stale.size
        } finally { db.endTransaction() }
    }

    fun captureWorkoutUpdate(installation: String, workout: WatchWorkout, points: List<WatchRoutePoint>) {
        require(points.zipWithNext().all { (a, b) -> a.elapsed < b.elapsed })
        db.beginTransaction()
        try {
            val chunks = if (points.isEmpty()) listOf(emptyList()) else points.chunked(WorkoutWire.MAX_POINTS)
            chunks.forEach { captureWorkout(WorkoutPacket(UUID.randomUUID().toString(), installation, workout, it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun captureWorkout(packet: WorkoutPacket): PendingBatch {
        val bytes = WorkoutWire.encode(packet)
        db.beginTransaction()
        try {
            storeWorkout(packet)
            val pending = enqueueBytes(packet.id, bytes, WorkoutWire.PATH)
            db.setTransactionSuccessful()
            return pending
        } finally { db.endTransaction() }
    }

    fun receiveWorkout(bytes: ByteArray, receivedAt: Long): ReadingReceipt {
        require(receivedAt >= 0)
        val packet = WorkoutWire.decode(bytes)
        val digest = ReadingWire.digest(bytes)
        db.beginTransaction()
        try {
            val duplicate = db.rawQuery("SELECT hash FROM receipts WHERE installation=? AND batch=?", arrayOf(packet.installation, packet.id)).use {
                if (it.moveToFirst()) { require(it.getString(0) == digest) { "Batch identity collision" }; true } else false
            }
            if (!duplicate) {
                storeWorkout(packet)
                db.insertOrThrow("receipts", null, ContentValues().apply {
                    put("installation", packet.installation); put("batch", packet.id); put("hash", digest); put("received", receivedAt)
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return ReadingReceipt(packet.id, digest)
    }

    private fun storeWorkout(packet: WorkoutPacket) {
        val w = packet.workout
        SweatJournal(this).forWorkout(packet.installation, w) // Also validate when an attachment arrives first.
        val payload = WorkoutWire.json(w).toString()
        val hash = ReadingWire.digest(payload.toByteArray(Charsets.UTF_8))
        val prior = workout(packet.installation, w.id)
        if (prior != null) {
            require(prior.boot == w.boot && prior.kind == w.kind && prior.start == w.start && prior.startElapsed == w.startElapsed && prior.gps == w.gps) { "Workout identity collision" }
            if (w.revision == prior.revision) require(WorkoutWire.json(prior).toString() == payload) { "Conflicting workout revision" }
            if (w.revision > prior.revision) {
                require(!prior.terminal || w.phase == prior.phase) { "Finished workout cannot restart" }
                require(w.updatedElapsed >= prior.updatedElapsed && w.activeMs >= prior.activeMs) { "Workout clock regressed" }
                listOf(prior.distance to w.distance, prior.energy to w.energy, prior.elevation to w.elevation).forEach { (a, b) ->
                    require(a == null || b != null && b >= a) { "Workout total regressed" }
                }
                require(prior.steps == null || w.steps != null && w.steps >= prior.steps)
            }
        }
        if (prior == null || w.revision > prior.revision) db.insertWithOnConflict("workouts", null, ContentValues().apply {
            put("installation", packet.installation); put("id", w.id); put("revision", w.revision); put("start", w.start)
            put("phase", w.phase); put("hash", hash); put("payload", payload)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it >= 0) { "Workout could not be saved" } }
        var addedPoints = false
        for (point in packet.points) {
            val original = WorkoutWire.pointJson(point).toString()
            db.rawQuery("SELECT payload FROM workout_points WHERE installation=? AND workout=? AND elapsed=?",
                arrayOf(packet.installation, w.id, point.elapsed.toString())).use {
                if (it.moveToFirst()) require(it.getString(0) == original) { "Conflicting route point" }
                else {
                    db.insertOrThrow("workout_points", null, ContentValues().apply {
                        put("installation", packet.installation); put("workout", w.id); put("elapsed", point.elapsed); put("payload", original)
                    })
                    addedPoints = true
                }
            }
        }
        // A late route chunk changes the view even when its session revision is already received.
        if (addedPoints) db.execSQL("UPDATE workouts SET rowid=(SELECT COALESCE(MAX(rowid),0)+1 FROM workouts) WHERE installation=? AND id=?",
            arrayOf(packet.installation, w.id))
    }

    fun workout(installation: String, id: String): WatchWorkout? {
        uuid(installation); uuid(id)
        return db.rawQuery("SELECT payload FROM workouts WHERE installation=? AND id=?", arrayOf(installation, id)).use {
            if (it.moveToFirst()) WorkoutWire.read(JSONObject(it.getString(0))) else null
        }
    }

    fun hasOpenWorkout(installation: String, boot: String): Boolean {
        uuid(installation); uuid(boot)
        return db.rawQuery("SELECT payload FROM workouts WHERE installation=? AND phase NOT IN ('ended','interrupted')",
            arrayOf(installation)).use { cursor ->
            var found = false
            while (cursor.moveToNext() && !found) found = WorkoutWire.read(JSONObject(cursor.getString(0))).boot == boot
            found
        }
    }

    fun workouts(installation: String? = null, limit: Int = 100): List<Pair<String, WatchWorkout>> {
        installation?.let(::uuid); require(limit in 1..1000)
        val where = if (installation == null) "" else "WHERE installation=?"
        val args = if (installation == null) arrayOf(limit.toString()) else arrayOf(installation, limit.toString())
        return db.rawQuery("SELECT installation,payload FROM workouts $where ORDER BY start DESC,id DESC LIMIT ?", args).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to WorkoutWire.read(JSONObject(c.getString(1)))) }
        }
    }

    /** Only completed local sessions participate. A saved id restores the same session after inserts. */
    fun workoutHistoryPosition(installation: String, selectedId: String? = null): Pair<Int, Int> {
        uuid(installation); selectedId?.let(::uuid)
        val selected = selectedId?.let { workout(installation, it) }?.takeIf { it.terminal }
        val count = db.rawQuery("SELECT COUNT(*) FROM workouts WHERE installation=? AND phase IN ('ended','interrupted')",
            arrayOf(installation)).use { it.moveToFirst(); it.getInt(0) }
        val index = if (selected == null) 0 else db.rawQuery(
            "SELECT COUNT(*) FROM workouts WHERE installation=? AND phase IN ('ended','interrupted') AND (start>? OR (start=? AND id>?))",
            arrayOf(installation, selected.start.toString(), selected.start.toString(), selected.id)).use { it.moveToFirst(); it.getInt(0) }
        return count to index
    }

    fun workoutHistoryAt(installation: String, index: Int): WatchWorkout? {
        uuid(installation); require(index >= 0)
        // ponytail: indexed OFFSET loads one visible session; use keyset windows if very large archives make seeks slow.
        return db.rawQuery("SELECT payload FROM workouts WHERE installation=? AND phase IN ('ended','interrupted') ORDER BY start DESC,id DESC LIMIT 1 OFFSET ?",
            arrayOf(installation, index.toString())).use { if (it.moveToFirst()) WorkoutWire.read(JSONObject(it.getString(0))) else null }
    }

    /** Replacements allocate a new SQLite rowid. Native observers parse only changed sessions. */
    fun workoutChanges(after: Long): List<Triple<Long, String, WatchWorkout>> {
        require(after >= 0)
        return db.rawQuery("SELECT rowid,installation,payload FROM workouts WHERE rowid>? ORDER BY rowid", arrayOf(after.toString())).use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getString(1), WorkoutWire.read(JSONObject(c.getString(2))))) }
        }
    }

    fun workoutPoints(installation: String, id: String, after: Long = -1): List<WatchRoutePoint> {
        uuid(installation); uuid(id)
        require(after >= -1)
        return db.rawQuery("SELECT payload FROM workout_points WHERE installation=? AND workout=? AND elapsed>? ORDER BY elapsed", arrayOf(installation, id, after.toString())).use { c ->
            buildList { while (c.moveToNext()) add(WorkoutWire.point(JSONObject(c.getString(0)))) }
        }
    }
    override fun close() = db.close()
}
