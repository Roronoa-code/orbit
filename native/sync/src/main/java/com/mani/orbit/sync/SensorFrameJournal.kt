package com.mani.orbit.sync

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayOutputStream
import java.io.File

/** Uses Orbit's journal/receipts/outbox, not a second delivery system. App-private, backup excluded. */
internal data class SensorFramePage(val frame: SensorFrame, val older: String?, val newer: String?)

internal class SensorFrameJournal(private val journal: ReadingJournal, private val family: WireFamily) {
    private val prefix = when (family) { WireFamily.HEART -> "heart"; WireFamily.RAW -> "raw"; else -> error("Not a callback family") }
    private fun decodeFrame(bytes: ByteArray): SensorFrame = if (family == WireFamily.HEART) HeartWire.decodeFrame(bytes) else RawSensorWire.decodeFrame(bytes)
    private val db get() = journal.db
    companion object {
        internal fun createTables(db: SQLiteDatabase, prefix: String) {
            require(prefix in setOf("heart", "raw"))
            db.execSQL("CREATE TABLE IF NOT EXISTS ${prefix}_frames(installation TEXT NOT NULL,id TEXT NOT NULL,parts INTEGER NOT NULL,digest TEXT NOT NULL,boot TEXT,boot_count INTEGER,elapsed INTEGER,at INTEGER,payload BLOB,PRIMARY KEY(installation,id))")
            db.execSQL("CREATE INDEX IF NOT EXISTS ${prefix}_frame_order ON ${prefix}_frames(installation,boot_count DESC,elapsed DESC,id DESC) WHERE payload IS NOT NULL")
            db.execSQL("CREATE TABLE IF NOT EXISTS ${prefix}_parts(installation TEXT NOT NULL,frame TEXT NOT NULL,position INTEGER NOT NULL,hash TEXT NOT NULL,bytes BLOB NOT NULL,PRIMARY KEY(installation,frame,position))")
        }
    }

    fun capture(frame: SensorFrame) {
        val encoded = if (family == WireFamily.HEART) HeartWire.encodeFrame(frame as HeartFrame) else RawSensorWire.encodeFrame(frame as RawSensorFrame)
        val packets = SensorPackets.split(frame.installation, frame.id, encoded)
        db.beginTransaction()
        try {
            for (packet in packets) {
                val bytes = SensorPackets.encode(packet)
                receive(bytes)
                journal.enqueueBytes(packet.id, bytes, family.path)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun receive(bytes: ByteArray): ReadingReceipt {
        val packet = SensorPackets.decode(bytes); val hash = ReadingWire.digest(bytes)
        val keys = arrayOf(packet.installation, packet.frame)
        db.beginTransaction()
        try {
            var completed = false
            val exists = db.rawQuery("SELECT parts,digest,payload IS NOT NULL FROM ${prefix}_frames WHERE installation=? AND id=?", keys).use {
                if (it.moveToFirst()) { require(it.getInt(0) == packet.parts && it.getString(1) == packet.digest); completed = it.getInt(2) != 0; true } else false
            }
            if (!exists) {
                check(File(db.path).parentFile!!.usableSpace >= 16L * 1024 * 1024) { "Storage is nearly full" }
                db.insertOrThrow("${prefix}_frames", null, ContentValues().apply {
                    put("installation", packet.installation); put("id", packet.frame); put("parts", packet.parts); put("digest", packet.digest)
                })
            }
            val partKeys = arrayOf(packet.installation, packet.frame, packet.index.toString())
            val duplicate = db.rawQuery("SELECT hash,bytes FROM ${prefix}_parts WHERE installation=? AND frame=? AND position=?", partKeys).use {
                if (it.moveToFirst()) { require(it.getString(0) == hash && it.getBlob(1).contentEquals(bytes)); true } else false
            }
            if (!duplicate) {
                check(File(db.path).parentFile!!.usableSpace >= 16L * 1024 * 1024) { "Storage is nearly full" }
                db.insertOrThrow("${prefix}_parts", null, ContentValues().apply {
                    put("installation", packet.installation); put("frame", packet.frame); put("position", packet.index)
                    put("hash", hash); put("bytes", bytes)
                })
            }
            val count = db.rawQuery("SELECT COUNT(*) FROM ${prefix}_parts WHERE installation=? AND frame=?", keys).use { it.moveToFirst(); it.getInt(0) }
            if (count == packet.parts && !completed) {
                val original = ByteArrayOutputStream()
                db.rawQuery("SELECT bytes FROM ${prefix}_parts WHERE installation=? AND frame=? ORDER BY position", keys).use {
                    while (it.moveToNext()) original.write(SensorPackets.decode(it.getBlob(0)).data)
                }
                val raw = original.toByteArray()
                require(ReadingWire.digest(raw) == packet.digest) { "Sensor callback integrity failure" }
                val frame = decodeFrame(raw)
                require(frame.id == packet.frame && frame.installation == packet.installation)
                journal.registerBoot(frame.installation, frame.boot, frame.bootCount)
                frame.readings().chunked(ReadingWire.MAX_READINGS).forEachIndexed { i, readings ->
                    journal.receive(ReadingWire.encode(ReadingBatch(heartId("${frame.id}:projections:$i"), frame.installation, readings)),
                        System.currentTimeMillis())
                }
                db.update("${prefix}_frames", ContentValues().apply {
                    put("boot", frame.boot); put("boot_count", frame.bootCount); put("elapsed", frame.receivedElapsed)
                    put("at", frame.at); put("payload", raw)
                }, "installation=? AND id=?", keys).also { check(it == 1) }
            }
            db.setTransactionSuccessful()
            return ReadingReceipt(packet.id, hash)
        } finally { db.endTransaction() }
    }

    fun latestId(installation: String): String? {
        uuid(installation)
        return db.rawQuery("SELECT id FROM ${prefix}_frames WHERE installation=? AND payload IS NOT NULL ORDER BY boot_count DESC,elapsed DESC,id DESC LIMIT 1",
            arrayOf(installation)).use { if (it.moveToFirst()) it.getString(0) else null }
    }

    /** Native boot/receipt order; screen-off batches keep their internal SDK ordering intact. */
    fun page(installation: String, selected: String? = null): SensorFramePage? {
        uuid(installation); selected?.let(::uuid)
        db.beginTransactionNonExclusive()
        try {
            val id = selected ?: latestId(installation) ?: return null
            val frame = db.rawQuery("SELECT payload FROM ${prefix}_frames WHERE installation=? AND id=? AND payload IS NOT NULL",
                arrayOf(installation, id)).use { if (it.moveToFirst()) decodeFrame(it.getBlob(0)) else null } ?: return null
            fun neighbor(older: Boolean): String? {
                val op = if (older) "<" else ">"; val order = if (older) "DESC" else "ASC"
                return db.rawQuery("""SELECT id FROM ${prefix}_frames WHERE installation=? AND payload IS NOT NULL AND
                    (boot_count,elapsed,id) $op (?,?,?) ORDER BY boot_count $order,elapsed $order,id $order LIMIT 1""",
                    arrayOf(installation, frame.bootCount.toString(), frame.receivedElapsed.toString(), id)).use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }
            val result = SensorFramePage(frame, neighbor(true), neighbor(false))
            db.setTransactionSuccessful(); return result
        } finally { db.endTransaction() }
    }
}
