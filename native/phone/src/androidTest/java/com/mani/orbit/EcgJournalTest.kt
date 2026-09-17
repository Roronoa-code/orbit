package com.mani.orbit

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.mani.health.core.model.measurement.*
import com.mani.health.core.protocol.*
import com.mani.orbit.sync.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class EcgJournalTest {
    private fun id() = UUID.randomUUID().toString()
    private val installation = id(); private val recording = id(); private val boot = id()
    @Before fun emulatorOnly() { check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) }
    private fun chunk(index: Int, contact: Int = 0): EcgPacket {
        val callback = EcgCallback(index.toLong(), Instant.ofEpochMilli(1000 + index * 10L), 500000000 + index * 10000000L,
            List(5) { sample -> EcgPoint(-20 + sample * 2L, sample / 10f, if (sample == 0) contact else null,
                if (sample == 0) index else null, if (sample == 0) 1f else null, if (sample == 0) -1f else null) })
        return EcgPacket(id(), installation, recording, boot, 1000, index, EcgChunk(recording, boot, listOf(callback)), startedElapsedMs = 100, bootCount = 7)
    }
    private fun finished(chunks: Int = 2, samples: Int = 10) = EcgPacket(id(), installation, recording, boot, 1000, -1,
        phase = "complete", expectedChunks = chunks, expectedSamples = samples, startedElapsedMs = 100, bootCount = 7)
    private fun temporary(block: (File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "ecg-${id()}").apply { check(mkdirs()) }
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun encryptedOfflinePacketsReplayInAnyOrderWithExactReceiptsAndOriginalMetadata() = temporary { dir ->
        val first = chunk(0); val second = chunk(1); val final = finished()
        ReadingJournal(File(dir, "watch.db")).use { db -> listOf(first, second, final).forEach { EcgJournal(db).capture(it) } }
        val packets = ReadingJournal(File(dir, "watch.db")).use { db -> db.pending(path = EcgWire.PATH).map {
            val plain = EcgJournal(db).outgoing(it)
            assertFalse(it.bytes.contentEquals(plain)); assertEquals(it.hash, ReadingWire.digest(plain))
            it to plain
        } }
        ReadingJournal(File(dir, "phone.db")).use { db ->
            val journal = EcgJournal(db)
            journal.receive(packets.last().second)
            assertFalse(journal.playback(journal.latest(installation)!!).complete)
            journal.receive(packets[1].second)
            assertTrue("Gap must remain visible", "CHUNK_GAP" in journal.playback(journal.latest(installation)!!).issues)
            packets.forEach { (pending, bytes) ->
                val receipt = journal.receive(bytes); assertEquals(receipt, journal.receive(bytes))
                ReadingJournal(File(dir, "watch.db")).use {
                    assertFalse(it.acknowledge(receipt.copy(hash = "0".repeat(64))))
                    assertTrue(it.isPending(pending.id, pending.hash)); assertTrue(it.acknowledge(receipt))
                }
            }
            val playback = journal.playback(journal.latest(installation)!!)
            assertTrue(playback.complete); assertEquals(10, playback.receivedSamples)
            assertEquals(-20L, playback.chunks.first().callbacks.first().points.first().rawTimestampMillis)
            assertEquals(first.chunk!!.callbacks.first().points, playback.chunks.first().callbacks.first().points)
            assertEquals(listOf(installation), db.installations())
            assertNull(journal.latest(id()))
        }
        ReadingJournal(File(dir, "watch.db")).use { assertEquals(0L, it.pendingCount()) }
    }

    @Test fun collisionsInvalidCompletionNewVersionsAndDamagedCipherNeverBecomeHealthyData() = temporary { dir ->
        val original = chunk(0, contact = 5); val bytes = EcgWire.encode(original)
        val future = JSONObject(bytes.toString(Charsets.UTF_8)).put("version", 2).toString().toByteArray()
        assertThrows(UnsupportedWire::class.java) { EcgWire.decode(future) }
        assertThrows(IllegalArgumentException::class.java) { EcgWire.decode(bytes + byteArrayOf(65)) }
        val path = File(dir, "journal.db")
        ReadingJournal(path).use { db ->
            val journal = EcgJournal(db); journal.capture(original)
            assertThrows(Exception::class.java) { journal.capture(chunk(0)) }
            assertThrows(IllegalArgumentException::class.java) { journal.capture(finished(0, 0)) }
            journal.capture(finished(1, 5))
            val playback = journal.playback(journal.latest(installation)!!)
            assertFalse(playback.complete); assertEquals("ECG_CONTACT_REQUIRED", playback.signalQuality!!.completionIssue())
            assertEquals(0.4f, playback.chunks.first().callbacks.first().points.last().millivolts, 0f)
        }
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE outbox SET bytes=zeroblob(length(bytes)) WHERE path=?", arrayOf(EcgWire.PATH))
            it.execSQL("UPDATE ecg_packets SET cipher=zeroblob(length(cipher)) WHERE id=?", arrayOf(original.id))
        }
        ReadingJournal(path).use { db ->
            val pending = db.pending(path = EcgWire.PATH).first()
            assertThrows(Exception::class.java) { EcgJournal(db).outgoing(pending) }
            assertTrue(db.isPending(pending.id, pending.hash))
            val journal = EcgJournal(db)
            assertThrows(Exception::class.java) { journal.receive(bytes) }
            assertTrue("INVALID_RAW_RECORDING" in journal.playback(journal.latest(installation)!!).issues)
        }
    }

    @Test fun interruptedPriorProcessKeepsItsSavedSamplesAndCurrentCaptureIsLeftAlone() = temporary { dir ->
        val path = File(dir, "journal.db")
        ReadingJournal(path).use { db ->
            val journal = EcgJournal(db); journal.capture(chunk(0)); journal.recoverInterrupted(installation)
            assertEquals("recording", journal.latest(installation)!!.phase)
        }
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE ecg_records SET process=?", arrayOf(id())) // Simulate a previous process, cache DB only.
        }
        ReadingJournal(path).use { db ->
            val journal = EcgJournal(db); journal.recoverInterrupted(installation)
            val row = journal.latest(installation)!!
            assertEquals("interrupted", row.phase); assertEquals(5, row.expectedSamples)
            assertEquals(100L, row.startedElapsedMs); assertEquals(7, row.bootCount)
            assertEquals(5, journal.playback(row).receivedSamples); assertFalse(journal.playback(row).complete)
            val count = db.pendingCount(); journal.recoverInterrupted(installation); assertEquals(count, db.pendingCount())
        }
    }
}
