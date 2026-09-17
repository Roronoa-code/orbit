package com.mani.orbit.sync

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

data class MeasurementPage(val result: WatchMeasurement, val olderId: String?, val newerId: String?, val orderingUncertain: Boolean = false)

/** Complete vendor result and standard projections commit together, using the existing receipts/outbox. */
fun ReadingJournal.captureMeasurement(measurement: WatchMeasurement): PendingBatch {
    val bytes = MeasurementWire.encode(measurement)
    db.beginTransaction()
    try {
        receiveMeasurement(bytes, System.currentTimeMillis())
        val pending = enqueueBytes(measurement.batch.id, bytes, MeasurementWire.PATH)
        db.setTransactionSuccessful()
        return pending
    } finally { db.endTransaction() }
}

fun ReadingJournal.receiveMeasurement(bytes: ByteArray, receivedAt: Long): ReadingReceipt {
    val measurement = MeasurementWire.decode(bytes)
    val sample = measurement.batch.readings.first()
    db.beginTransaction()
    try {
        val receipt = receive(bytes, receivedAt)
        db.insertWithOnConflict("measurements", null, ContentValues().apply {
            put("installation", measurement.batch.installation); put("id", measurement.batch.id)
            put("at", sample.start); put("payload", bytes); put("boot", sample.boot); put("sensor_elapsed", sample.elapsedMs)
        }, SQLiteDatabase.CONFLICT_IGNORE).also { result ->
            if (result == -1L) check(db.rawQuery("SELECT payload FROM measurements WHERE installation=? AND id=?",
                arrayOf(measurement.batch.installation, measurement.batch.id)).use { it.moveToFirst() && it.getBlob(0).contentEquals(bytes) })
        }
        db.setTransactionSuccessful()
        return receipt
    } finally { db.endTransaction() }
}

private data class MeasurementHead(val installation: String, val id: String, val boot: String, val at: Long)
private val wallOrder = compareBy<MeasurementHead> { it.at }.thenBy { it.id }

/** Indexed metadata only: one head per boot, without decoding stored vendor payloads. */
private fun ReadingJournal.measurementHeads(installation: String?): List<Pair<MeasurementHead, Int?>> {
    installation?.let(::uuid)
    return db.rawQuery("""SELECT m.installation,m.id,m.boot,m.at,b.boot_count FROM reading_boots b
        JOIN measurements m ON m.installation=b.installation AND m.id=(SELECT id FROM measurements
        WHERE installation=b.installation AND boot=b.boot ORDER BY sensor_elapsed DESC,id DESC LIMIT 1)
        ${if (installation == null) "" else "WHERE b.installation=?"}""", installation?.let { arrayOf(it) }).use { c ->
        buildList { while (c.moveToNext()) add(MeasurementHead(c.getString(0), c.getString(1), c.getString(2), c.getLong(3)) to
            if (c.isNull(4)) null else c.getInt(4)) }
    }
}

private fun ReadingJournal.measurement(installation: String, id: String): WatchMeasurement? =
    db.rawQuery("SELECT payload FROM measurements WHERE installation=? AND id=?", arrayOf(installation, id)).use {
        if (it.moveToFirst()) MeasurementWire.decode(it.getBlob(0)) else null
    }

fun ReadingJournal.latestMeasurement(installation: String? = null): WatchMeasurement? {
    val head = measurementHeads(installation).groupBy { it.first.installation }.values
        .map { orderSensorEntries(it, wallOrder).first() }.maxWithOrNull(wallOrder) ?: return null
    return measurement(head.installation, head.id)
}

/** Stable selection and reciprocal neighbors. Decode only the visible entry, never the archive. */
fun ReadingJournal.measurementPage(installation: String, selectedId: String? = null): MeasurementPage? {
    uuid(installation); selectedId?.let(::uuid)
    db.beginTransactionNonExclusive()
    try {
        val candidates = measurementHeads(installation)
        val boots = orderSensorEntries(candidates, wallOrder)
        val head = boots.firstOrNull() ?: return null
        val selected = selectedId?.let { measurement(installation, it) } ?: measurement(installation, head.id) ?: return null
        val sample = selected.batch.readings.first()
        val index = boots.indexOfFirst { it.boot == sample.boot }.also { check(it >= 0) }
        fun neighbor(older: Boolean): String? {
            val op = if (older) "<" else ">"
            val order = if (older) "DESC" else "ASC"
            val elapsed = sample.elapsedMs.toString()
            val sameBoot = db.rawQuery("""SELECT id FROM measurements WHERE installation=? AND boot=? AND
                (sensor_elapsed,id) $op (?,?) ORDER BY sensor_elapsed $order,id $order LIMIT 1""",
                arrayOf(installation, sample.boot, elapsed, selected.batch.id)).use { if (it.moveToFirst()) it.getString(0) else null }
            if (sameBoot != null) return sameBoot
            val nextBoot = boots.getOrNull(index + if (older) 1 else -1) ?: return null
            if (older) return nextBoot.id
            return db.rawQuery("SELECT id FROM measurements WHERE installation=? AND boot=? ORDER BY sensor_elapsed ASC,id ASC LIMIT 1",
                arrayOf(installation, nextBoot.boot)).use { if (it.moveToFirst()) it.getString(0) else null }
        }
        val page = MeasurementPage(selected, neighbor(true), neighbor(false), candidates.size > 1 && candidates.any { it.second == null })
        db.setTransactionSuccessful()
        return page
    } finally { db.endTransaction() }
}

/** Caller owns the schema transaction. Index metadata is derived; originals and receipt bytes never change. */
internal fun ReadingJournal.migrateMeasurementOrder() {
    val ready = db.rawQuery("PRAGMA table_info(measurements)", null).use { c ->
        var found = false; while (c.moveToNext()) if (c.getString(1) == "sensor_elapsed") found = true; found
    }
    if (!ready) {
        db.execSQL("ALTER TABLE measurements ADD COLUMN boot TEXT")
        db.execSQL("ALTER TABLE measurements ADD COLUMN sensor_elapsed INTEGER")
    }
    // Also catches old-binary inserts after a downgrade, without scanning the archive on every open.
    db.execSQL("CREATE INDEX IF NOT EXISTS measurement_unordered ON measurements(installation,id) WHERE boot IS NULL OR sensor_elapsed IS NULL")
    db.rawQuery("SELECT installation,id,at,payload FROM measurements WHERE boot IS NULL OR sensor_elapsed IS NULL", null).use { c ->
        while (c.moveToNext()) {
            val bytes = c.getBlob(3)
            val value = MeasurementWire.decode(bytes)
            val batch = value.batch; val sample = batch.readings.first()
            require(batch.installation == c.getString(0) && batch.id == c.getString(1) && sample.start == c.getLong(2)) { "Measurement identity mismatch" }
            check(db.rawQuery("SELECT hash FROM receipts WHERE installation=? AND batch=?", arrayOf(batch.installation, batch.id)).use {
                it.moveToFirst() && it.getString(0) == ReadingWire.digest(bytes)
            }) { "Measurement receipt mismatch" }
            batch.readings.forEach { registerBoot(batch.installation, it.boot, it.bootCount) }
            check(db.update("measurements", ContentValues().apply { put("boot", sample.boot); put("sensor_elapsed", sample.elapsedMs) },
                "installation=? AND id=?", arrayOf(batch.installation, batch.id)) == 1)
        }
    }
    db.execSQL("CREATE INDEX IF NOT EXISTS measurement_sensor_order ON measurements(installation,boot,sensor_elapsed DESC,id DESC)")
    db.execSQL("DROP INDEX IF EXISTS measurement_history")
}
