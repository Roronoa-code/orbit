package com.mani.orbit.sync

import android.content.ContentValues
import com.mani.health.core.database.RawEncryptionKey
import com.mani.health.core.model.measurement.*
import com.mani.health.core.model.privacy.AuthenticatedEnvelope
import java.io.File
import java.util.UUID

data class EcgRecord(val installation: String, val id: String, val boot: String, val start: Long,
    val phase: String, val expectedChunks: Int?, val expectedSamples: Int?, val startedElapsedMs: Long? = null, val bootCount: Int? = null) {
    override fun toString() = "EcgRecord(phase=$phase)"
}

/** Same SQLite transaction/receipt queue as other Watch data; raw packet bytes only persist encrypted. */
class EcgJournal(internal val journal: ReadingJournal) {
    internal val db = journal.db
    companion object {
      private val process = UUID.randomUUID().toString()
      internal fun createTables(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS ecg_records(installation TEXT NOT NULL,id TEXT NOT NULL,boot TEXT NOT NULL,start INTEGER NOT NULL,phase TEXT NOT NULL,chunks INTEGER,samples INTEGER,process TEXT,PRIMARY KEY(installation,id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS ecg_packets(installation TEXT NOT NULL,id TEXT NOT NULL,recording TEXT NOT NULL,position INTEGER NOT NULL,samples INTEGER NOT NULL,hash TEXT NOT NULL,cipher BLOB NOT NULL,PRIMARY KEY(installation,id),UNIQUE(installation,recording,position))")
      }
    }

    fun capture(packet: EcgPacket): ReadingReceipt = store(EcgWire.encode(packet), true)
    fun receive(bytes: ByteArray): ReadingReceipt = store(bytes, false)

    private fun store(bytes: ByteArray, outgoing: Boolean): ReadingReceipt {
        val packet = EcgWire.decode(bytes); val hash = ReadingWire.digest(bytes)
        db.beginTransaction()
        try {
            journal.registerBoot(packet.installation, packet.boot, packet.bootCount)
            val existing = db.rawQuery("SELECT hash,cipher FROM ecg_packets WHERE installation=? AND id=?",
                arrayOf(packet.installation, packet.id)).use { if (it.moveToFirst()) it.getString(0) to it.getBlob(1) else null }
            require(existing == null || existing.first == hash) { "Conflicting ECG packet identity" }
            // A matching hash alone cannot receipt a damaged encrypted copy.
            if (existing != null) plaintext(packet.installation, packet.id, hash, existing.second)
            val cipher = existing?.second ?: run {
                check(File(db.path).parentFile!!.usableSpace >= 16L * 1024 * 1024) { "Storage is nearly full" }
                val prior = record(packet.installation, packet.recording)
                require(prior == null || prior.boot == packet.boot && prior.start == packet.start) { "Conflicting ECG recording identity" }
                require(prior?.startedElapsedMs == null || packet.startedElapsedMs == null || prior.startedElapsedMs == packet.startedElapsedMs) { "Conflicting ECG capture clock" }
                if (prior == null) db.insertOrThrow("ecg_records", null, ContentValues().apply {
                    put("installation", packet.installation); put("id", packet.recording); put("boot", packet.boot)
                    put("start", packet.start); put("phase", "recording")
                    put("sensor_elapsed", packet.startedElapsedMs); put("order_ready", 1)
                })
                else if (prior.startedElapsedMs == null && packet.startedElapsedMs != null) db.update("ecg_records",
                    ContentValues().apply { put("sensor_elapsed", packet.startedElapsedMs) }, "installation=? AND id=?",
                    arrayOf(packet.installation, packet.recording)).also { check(it == 1) }
                if (packet.chunk == null) {
                    require(prior == null || prior.phase == "recording" || prior.phase == packet.phase &&
                        prior.expectedChunks == packet.expectedChunks && prior.expectedSamples == packet.expectedSamples) { "Conflicting ECG completion" }
                    db.update("ecg_records", ContentValues().apply { put("phase", packet.phase); put("chunks", packet.expectedChunks); put("samples", packet.expectedSamples) },
                        "installation=? AND id=?", arrayOf(packet.installation, packet.recording)).also { check(it == 1) }
                } else require(prior?.expectedChunks == null || packet.index < prior.expectedChunks) { "ECG chunk outside completed recording" }
                val encrypted = AuthenticatedEnvelope.encrypt(bytes, RawEncryptionKey.get(canCreateKey()), binding(packet.installation, packet.id))
                db.insertOrThrow("ecg_packets", null, ContentValues().apply {
                    put("installation", packet.installation); put("id", packet.id); put("recording", packet.recording); put("position", packet.index)
                    put("samples", packet.chunk?.callbacks?.sumOf { it.points.size } ?: 0); put("hash", hash); put("cipher", encrypted)
                })
                val current = requireNotNull(record(packet.installation, packet.recording))
                if (current.expectedChunks != null) db.rawQuery(
                    "SELECT COUNT(*),COALESCE(SUM(samples),0),COALESCE(MAX(position),-1) FROM ecg_packets WHERE installation=? AND recording=? AND position>=0",
                    arrayOf(packet.installation, packet.recording)).use {
                    it.moveToFirst(); require(it.getInt(0) <= current.expectedChunks && it.getInt(1) <= current.expectedSamples!! &&
                        it.getInt(2) < current.expectedChunks) { "ECG completion does not match received data" }
                }
                encrypted
            }
            if (outgoing) {
                db.execSQL("UPDATE ecg_records SET process=? WHERE installation=? AND id=?", arrayOf(process, packet.installation, packet.recording))
                val queuedHash = db.rawQuery("SELECT hash FROM outbox WHERE id=?", arrayOf(packet.id)).use { if (it.moveToFirst()) it.getString(0) else null }
                require(queuedHash == null || queuedHash == hash) { "Conflicting ECG queue identity" }
                if (queuedHash == null) db.insertOrThrow("outbox", null, ContentValues().apply {
                    put("id", packet.id); put("hash", hash); put("bytes", cipher); put("created", System.currentTimeMillis()); put("path", EcgWire.PATH)
                })
            }
            db.setTransactionSuccessful()
            return ReadingReceipt(packet.id, hash)
        } finally { db.endTransaction() }
    }

    fun outgoing(pending: PendingBatch): ByteArray {
        require(pending.path == EcgWire.PATH)
        val installation = db.rawQuery("SELECT installation FROM ecg_packets WHERE id=? AND hash=?", arrayOf(pending.id, pending.hash)).use {
            check(it.count == 1 && it.moveToFirst()) { "ECG packet unavailable" }; it.getString(0)
        }
        return plaintext(installation, pending.id, pending.hash, pending.bytes)
    }

    /** A new Watch process closes only its own interrupted local captures, never received phone copies. */
    fun recoverInterrupted(installation: String) {
        uuid(installation)
        val ids = db.rawQuery("SELECT id FROM ecg_records WHERE installation=? AND phase='recording' AND process IS NOT NULL AND process!=?",
            arrayOf(installation, process)).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        for (id in ids) {
            val old = requireNotNull(record(installation, id))
            val totals = db.rawQuery("SELECT COALESCE(MAX(position)+1,0),COALESCE(SUM(samples),0) FROM ecg_packets WHERE installation=? AND recording=? AND position>=0",
                arrayOf(installation, id)).use { it.moveToFirst(); it.getInt(0) to it.getInt(1) }
            capture(EcgPacket(UUID.nameUUIDFromBytes("$installation:$id:finished".toByteArray()).toString(), installation, id, old.boot,
                old.start, -1, phase = "interrupted", expectedChunks = totals.first, expectedSamples = totals.second,
                startedElapsedMs = old.startedElapsedMs, bootCount = old.bootCount))
        }
    }

    fun record(installation: String, id: String): EcgRecord? {
        uuid(installation); uuid(id)
        return db.rawQuery("""SELECT r.boot,r.start,r.phase,r.chunks,r.samples,r.sensor_elapsed,b.boot_count FROM ecg_records r
            JOIN reading_boots b ON b.installation=r.installation AND b.boot=r.boot WHERE r.installation=? AND r.id=?""", arrayOf(installation, id)).use {
            if (!it.moveToFirst()) null else EcgRecord(installation, id, it.getString(0), it.getLong(1), it.getString(2),
                if (it.isNull(3)) null else it.getInt(3), if (it.isNull(4)) null else it.getInt(4),
                if (it.isNull(5)) null else it.getLong(5), if (it.isNull(6)) null else it.getInt(6))
        }
    }

    /** Cheap invalidation token; decrypt waveform chunks only after a visible recording changes. */
    fun revision(record: EcgRecord): Long = db.rawQuery("SELECT COALESCE(MAX(rowid),0) FROM ecg_packets WHERE installation=? AND recording=?",
        arrayOf(record.installation, record.id)).use { it.moveToFirst(); it.getLong(0) }

    /** Adapted from the existing EcgPlaybackReader. Partial data stays partial, with visible gaps. */
    fun playback(record: EcgRecord): EcgPlayback {
        val issues = mutableSetOf<String>()
        val chunks = mutableListOf<EcgPlaybackChunk>()
        db.rawQuery("SELECT id,hash,cipher,position FROM ecg_packets WHERE installation=? AND recording=? AND position>=0 ORDER BY position LIMIT 33",
            arrayOf(record.installation, record.id)).use { cursor ->
            while (cursor.moveToNext()) {
                try {
                    val packet = EcgWire.decode(plaintext(record.installation, cursor.getString(0), cursor.getString(1), cursor.getBlob(2)))
                    require(packet.recording == record.id && packet.boot == record.boot && packet.index == cursor.getInt(3))
                    chunks += EcgPlaybackChunk(packet.index.toLong(), requireNotNull(packet.chunk).callbacks)
                } catch (_: Exception) { issues += "INVALID_RAW_RECORDING" }
            }
        }
        val callbacks = chunks.flatMap { it.callbacks }
        val count = callbacks.sumOf { it.points.size }
        if (chunks.isNotEmpty() && (chunks.first().sequence != 0L || chunks.zipWithNext().any { (a,b) -> b.sequence != a.sequence + 1 })) issues += "CHUNK_GAP"
        if (callbacks.isNotEmpty() && (callbacks.first().callbackSequence != 0L || callbacks.zipWithNext().any { (a,b) -> b.callbackSequence != a.callbackSequence + 1 })) issues += "CALLBACK_GAP"
        if (chunks.size != record.expectedChunks || count != record.expectedSamples) issues += "SAMPLES_PENDING"
        if (callbacks.any { callback -> callback.points.any { it.metadataReadFailures != 0 } }) issues += "INCOMPLETE_SENSOR_METADATA"
        val quality = summarizeEcgSignal(callbacks)
        quality.completionIssue()?.let { issues += it }
        return EcgPlayback(record.phase, chunks, record.expectedSamples, count,
            record.phase == "complete" && issues.isEmpty(), issues, quality)
    }

    private fun plaintext(installation: String, id: String, hash: String, cipher: ByteArray): ByteArray =
        AuthenticatedEnvelope.decrypt(cipher, RawEncryptionKey.get(false), binding(installation, id)).also {
            check(ReadingWire.digest(it) == hash) { "ECG integrity failure" }
            val packet = EcgWire.decode(it); require(packet.installation == installation && packet.id == id)
        }
    private fun canCreateKey(): Boolean = db.rawQuery("SELECT 1 FROM ecg_packets LIMIT 1", null).use { !it.moveToFirst() }
    private fun binding(installation: String, id: String) = "orbit-ecg-v1:$installation:$id"
}
