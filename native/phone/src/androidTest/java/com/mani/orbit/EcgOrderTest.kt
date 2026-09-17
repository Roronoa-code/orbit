package com.mani.orbit

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.mani.health.core.model.measurement.EcgCallback
import com.mani.health.core.model.measurement.EcgPoint
import com.mani.health.core.protocol.EcgChunk
import com.mani.orbit.sync.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class EcgOrderTest {
    private fun id() = UUID.randomUUID().toString()
    @Before fun emulatorOnly() { check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) }
    private fun packet(source: String, boot: String, count: Int?, elapsed: Long?, wall: Long) =
        EcgPacket(id(), source, id(), boot, wall, -1, phase = "failed", expectedChunks = 0, expectedSamples = 0,
            startedElapsedMs = elapsed, bootCount = count)
    private fun temporary(block: (File) -> Unit) {
        val path = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "ecg-order-${id()}.db")
        try { block(path) } finally { SQLiteDatabase.deleteDatabase(path) }
    }
    private fun EcgJournal.assertOrder(source: String, expected: List<EcgPacket>, uncertain: Boolean = false) {
        var current = page(source)!!
        expected.forEachIndexed { index, packet ->
            assertEquals(packet.recording, current.record.id)
            assertEquals(expected.getOrNull(index - 1)?.recording, current.newerId)
            assertEquals(expected.getOrNull(index + 1)?.recording, current.olderId)
            assertEquals(uncertain, current.orderingUncertain)
            current.olderId?.let { current = page(source, it)!! }
        }
        expected.reversed().forEach { packet ->
            assertEquals(packet.recording, current.record.id)
            current.newerId?.let { current = page(source, it)!! }
        }
        assertEquals(expected.first().recording, latest(source)!!.id)
    }

    @Test fun indexedModernOrderAndQualifiedLegacyMergesKeepReciprocalPages() = temporary { path ->
        val source = id(); val boot = id(); val nextBoot = id()
        val before = packet(source, boot, 7, 1000, 10000)
        val after = packet(source, boot, 7, 2000, 5000)
        val reboot = packet(source, nextBoot, 8, 100, 3000)
        val rebootAfter = packet(source, nextBoot, 8, 200, 1000)
        ReadingJournal(path).use { db ->
            val journal = EcgJournal(db)
            listOf(rebootAfter, after, reboot, before).forEach { journal.capture(it) }
            journal.assertOrder(source, listOf(rebootAfter, reboot, after, before))
            val other = packet(id(), id(), 9, 300, 30000); journal.capture(other)
            assertNull(journal.page(id()))
            assertEquals(other.recording, journal.latest(other.installation)!!.id)
            val loneLegacyBoot = packet(other.installation, id(), 8, null, 40000)
            journal.capture(loneLegacyBoot)
            journal.assertOrder(other.installation, listOf(other, loneLegacyBoot)) // Known boot counts fully order these single-record boots.
            val legacySameBoot = packet(source, nextBoot, null, null, 2000)
            journal.capture(legacySameBoot)
            journal.assertOrder(source, listOf(legacySameBoot, rebootAfter, reboot, after, before), true)
            val unknownBoot = packet(source, id(), null, 100, 15000)
            journal.capture(unknownBoot)
            journal.assertOrder(source, listOf(unknownBoot, legacySameBoot, rebootAfter, reboot, after, before), true)
            // A reading from the same actual boot supplies an ordinal; no original recording is rewritten.
            val reading = WatchReading(id(), 1, 1000, 1000, 0, "heart", 80.0, "bpm", "valid", "instant", unknownBoot.boot, 200, bootCount = 6)
            db.receive(ReadingWire.encode(ReadingBatch(id(), source, listOf(reading))), 50000)
            journal.assertOrder(source, listOf(legacySameBoot, rebootAfter, reboot, after, before, unknownBoot), true)
            assertEquals(after.recording, journal.page(source, after.recording)!!.record.id)
            val count = db.pendingCount()
            assertThrows(IllegalArgumentException::class.java) { journal.capture(packet(source, boot, 9, 3000, 500)) }
            assertEquals(count, db.pendingCount())
        }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.rawQuery("EXPLAIN QUERY PLAN SELECT id FROM ecg_records WHERE installation=? AND boot=? AND (sensor_elapsed,id)<(?,?) ORDER BY sensor_elapsed DESC,id DESC LIMIT 1",
                arrayOf(source, boot, "2000", after.recording)).use {
                assertTrue(it.moveToFirst()); val plan = it.getString(3)
                assertTrue(plan, plan.contains("ecg_sensor_order") && (plan.contains("(sensor_elapsed,id)<(?,?)") || plan.contains("sensor_elapsed<?")))
            }
        }
    }

    @Test fun optionalClockMetadataIsStrictAndCompletionBeforeChunksKeepsExactBytes() = temporary { path ->
        val source = id(); val boot = id(); val recording = id()
        val oldCompletion = EcgPacket(id(), source, recording, boot, 1000, -1, phase = "complete", expectedChunks = 1, expectedSamples = 5)
        val bytes = EcgWire.encode(oldCompletion)
        assertFalse(String(bytes).contains("startedElapsedMs")); assertFalse(String(bytes).contains("bootCount"))
        assertArrayEquals(bytes, EcgWire.encode(EcgWire.decode(bytes)))
        for (name in listOf("startedElapsedMs", "bootCount")) for (bad in listOf(JSONObject.NULL, "10", 1.5, -1)) {
            val malformed = JSONObject(bytes.toString(Charsets.UTF_8)).put(name, bad).toString().toByteArray()
            assertThrows(IllegalArgumentException::class.java) { EcgWire.decode(malformed) }
        }
        val overflow = JSONObject(bytes.toString(Charsets.UTF_8)).put("bootCount", Int.MAX_VALUE.toLong() + 1).toString().toByteArray()
        assertThrows(IllegalArgumentException::class.java) { EcgWire.decode(overflow) }
        val callback = EcgCallback(0, Instant.ofEpochMilli(1010), 500000000,
            List(5) { EcgPoint(it.toLong(), it / 10f, if (it == 0) 0 else null, if (it == 0) 0 else null,
                if (it == 0) 1f else null, if (it == 0) -1f else null) })
        val chunk = EcgPacket(id(), source, recording, boot, 1000, 0, EcgChunk(recording, boot, listOf(callback)), startedElapsedMs = 100, bootCount = 7)
        assertEquals(100L, EcgWire.decode(EcgWire.encode(chunk)).startedElapsedMs)
        assertThrows(IllegalArgumentException::class.java) {
            EcgWire.decode(JSONObject(EcgWire.encode(chunk).toString(Charsets.UTF_8)).put("startedElapsedMs", 501).toString().toByteArray())
        }
        ReadingJournal(path).use { db ->
            val journal = EcgJournal(db)
            journal.capture(oldCompletion); journal.capture(chunk)
            val row = journal.record(source, recording)!!
            assertEquals(100L, row.startedElapsedMs); assertEquals(7, row.bootCount)
            assertTrue(journal.playback(row).complete)
            assertArrayEquals(bytes, journal.outgoing(db.pending(path = EcgWire.PATH).first { it.id == oldCompletion.id }))
            val conflicting = JSONObject(EcgWire.encode(chunk).toString(Charsets.UTF_8)).put("id", id()).put("startedElapsedMs", 200).toString().toByteArray()
            assertThrows(IllegalArgumentException::class.java) { journal.receive(conflicting) }
            assertEquals(100L, journal.record(source, recording)!!.startedElapsedMs)
            assertEquals(ReadingReceipt(oldCompletion.id, ReadingWire.digest(bytes)), journal.receive(bytes))
        }
    }

    @Test fun legacyMigrationIsAtomicWithoutDecryptingOrChangingEncryptedOriginals() = temporary { path ->
        val first = packet(id(), id(), null, null, 1000)
        lateinit var cipher: ByteArray
        ReadingJournal(path).use { EcgJournal(it).capture(first) }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.rawQuery("SELECT cipher FROM ecg_packets", null).use { it.moveToFirst(); cipher = it.getBlob(0) }
            db.execSQL("ALTER TABLE ecg_records RENAME TO prior_ecg")
            db.execSQL("CREATE TABLE ecg_records(installation TEXT NOT NULL,id TEXT NOT NULL,boot TEXT NOT NULL,start INTEGER NOT NULL,phase TEXT NOT NULL,chunks INTEGER,samples INTEGER,process TEXT,PRIMARY KEY(installation,id))")
            db.execSQL("INSERT INTO ecg_records SELECT installation,id,boot,start,phase,chunks,samples,process FROM prior_ecg")
            db.execSQL("DROP TABLE prior_ecg")
            db.execSQL("DELETE FROM reading_boots")
            db.execSQL("UPDATE ecg_records SET boot='invalid'")
        }
        assertThrows(IllegalArgumentException::class.java) { ReadingJournal(path).close() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.rawQuery("PRAGMA table_info(ecg_records)", null).use { c -> while (c.moveToNext()) assertNotEquals("order_ready", c.getString(1)) }
            db.execSQL("UPDATE ecg_records SET boot=?", arrayOf(first.boot))
            // Even unavailable raw data must not prevent the metadata-only history migration.
            db.execSQL("UPDATE ecg_packets SET cipher=zeroblob(length(cipher))")
        }
        ReadingJournal(path).use { db ->
            val row = EcgJournal(db).page(first.installation)!!.record
            assertNull(row.startedElapsedMs); assertEquals(first.recording, row.id)
        }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db -> db.execSQL("UPDATE ecg_packets SET cipher=?", arrayOf(cipher)) }
        ReadingJournal(path).use { db ->
            val journal = EcgJournal(db); val pending = db.pending(path = EcgWire.PATH).single()
            assertArrayEquals(cipher, pending.bytes)
            assertArrayEquals(EcgWire.encode(first), journal.outgoing(pending))
        }
    }
}
