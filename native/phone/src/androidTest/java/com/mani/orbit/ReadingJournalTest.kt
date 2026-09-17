package com.mani.orbit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mani.orbit.sync.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import android.database.sqlite.SQLiteDatabase

@RunWith(AndroidJUnit4::class)
class ReadingJournalTest {
    private fun id() = UUID.randomUUID().toString()
    private fun reading() = WatchReading(id(), 1, 1000, 1000, 3600, "heart", 74.0, "bpm", "valid", "instant", id(), 500)

    @Test fun offlineReplayDuplicateDeliveryAndRevisionConflictsAreDurable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "journal-test-${id()}").apply { check(mkdirs()) }
        val watch = File(directory, "watch.db")
        val phone = File(directory, "phone.db")
        val installation = id()
        val original = reading()
        val batch = ReadingBatch(id(), installation, listOf(original))
        try {
            ReadingJournal(watch).use { it.enqueue(batch) }
            val sent = ReadingJournal(watch).use { it.pending().single() }
            val receipt = ReadingJournal(phone).use {
                val ack = it.receive(sent.bytes, 2000)
                assertEquals(ack, it.receive(sent.bytes, 2001))
                assertEquals(1, it.readings("heart", 0, 2000).size)
                ack
            }
            ReadingJournal(watch).use {
                assertFalse(it.acknowledge(ReadingReceipt(receipt.batch, "wrong")))
                assertEquals(1, it.pending().size)
                assertTrue(it.acknowledge(receipt))
                assertTrue(it.pending().isEmpty())
            }
            ReadingJournal(phone).use {
                val edited = original.copy(revision = 2, value = 80.0)
                it.receive(ReadingWire.encode(ReadingBatch(id(), installation, listOf(edited))), 2100)
                it.receive(ReadingWire.encode(ReadingBatch(id(), installation, listOf(original))), 2200)
                assertEquals(80.0, it.readings("heart", 0, 2000).single().getDouble("value"), 0.0)
                val conflict = edited.copy(value = 90.0)
                val innocent = original.copy(id = id())
                assertThrows(IllegalArgumentException::class.java) {
                    it.receive(ReadingWire.encode(ReadingBatch(id(), installation, listOf(innocent, conflict))), 2300)
                }
                // The earlier insert in the conflicting batch must roll back too.
                assertEquals(1, it.readings("heart", 0, 2000).size)
                assertThrows(IllegalArgumentException::class.java) {
                    it.receive(ReadingWire.encode(ReadingBatch(batch.id, installation, listOf(edited))), 2400)
                }
            }
            ReadingJournal(phone).use { assertEquals(80.0, it.readings("heart", 0, 2000).single().getDouble("value"), 0.0) }
        } finally {
            directory.listFiles()?.forEach { check(it.delete()) }
            check(directory.delete())
        }
    }

    @Test fun newerSensorTimeSurvivesBackwardClockAndLateDelivery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "reading-order-${id()}.db")
        val installation = id()
        val before = reading().copy(start = 10000, end = 10000, elapsedMs = 1000, anchorElapsedMs = 1000, capturedAt = 10000)
        val after = before.copy(id = id(), start = 5000, end = 5000, elapsedMs = 2000, anchorElapsedMs = 2000, capturedAt = 5000, value = 82.0)
        try {
            ReadingJournal(file).use {
                it.receive(ReadingWire.encode(ReadingBatch(id(), installation, listOf(after))), 11000)
                it.receive(ReadingWire.encode(ReadingBatch(id(), installation, listOf(before))), 12000)
                assertEquals("Sensor order survives a backward wall clock and late backlog", after.id, it.latest("heart", installation)!!.getString("id"))
                assertEquals(2, it.readings("heart", 0, 20000).size)
                // New additive metadata qualifies all original rows belonging to this same boot.
                it.receive(ReadingWire.encode(ReadingBatch(id(), installation, listOf(after.copy(revision = 2, bootCount = 7)))), 13000)
                val restart = after.copy(id = id(), boot = id(), bootCount = 8, revision = 1,
                    start = 3000, end = 3000, elapsedMs = 100, anchorElapsedMs = 100, capturedAt = 3000)
                it.receive(ReadingWire.encode(ReadingBatch(id(), installation, listOf(restart))), 14000)
                it.receive(ReadingWire.encode(ReadingBatch(id(), installation, listOf(before.copy(revision = 2, bootCount = 7)))), 15000)
                assertEquals(restart.id, it.latest("heart", installation)!!.getString("id"))
                assertFalse(it.latest("heart", installation)!!.getBoolean("orderingUncertain"))
                val legacyBoot = before.copy(id = id(), boot = id(), end = 11000)
                it.receive(ReadingWire.encode(ReadingBatch(id(), installation, listOf(legacyBoot))), 16000)
                assertEquals(legacyBoot.id, it.latest("heart", installation)!!.getString("id"))
                assertTrue("Unknown legacy boot order is explicit", it.latest("heart", installation)!!.getBoolean("orderingUncertain"))
                assertEquals(restart.id, it.latest("heart", installation, restart.boot)!!.getString("id"))
                assertNull(it.latest("heart", installation, id()))
                assertEquals(4, it.readings("heart", 0, 20000).size)
                val innocent = restart.copy(id = id(), metric = "battery", unit = "%", source = "android_battery")
                val collision = restart.copy(id = id(), boot = id())
                assertThrows(IllegalArgumentException::class.java) {
                    it.receive(ReadingWire.encode(ReadingBatch(id(), installation, listOf(innocent, collision))), 17000)
                }
                assertNull("A conflicting boot rolls back the whole batch", it.latest("battery", installation))
                val otherWatch = id()
                it.receive(ReadingWire.encode(ReadingBatch(id(), otherWatch, listOf(collision))), 18000)
                assertEquals(collision.id, it.latest("heart", otherWatch)!!.getString("id"))
            }
            ReadingJournal(file).use { assertEquals(after.id, it.latest("heart", installation, before.boot)!!.getString("id")) }
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }

    @Test fun legacySchemaMigrationPreservesOriginalsQueueAndReceiptsAndRollsBackOnCorruption() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "reading-migration-${id()}.db")
        val installation = id()
        val sample = reading()
        val batch = ReadingBatch(id(), installation, listOf(sample))
        val bytes = ReadingWire.encode(batch)
        val payload = ReadingWire.json(sample).toString()
        val hash = ReadingWire.digest(payload.toByteArray())
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE readings(installation TEXT NOT NULL,id TEXT NOT NULL,revision INTEGER NOT NULL,metric TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,hash TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(installation,id))")
                db.execSQL("INSERT INTO readings VALUES(?,?,?,?,?,?,?,?)", arrayOf<Any?>(installation, sample.id, 1, "heart", sample.start, sample.end, hash, "{}"))
                db.execSQL("CREATE TABLE outbox(id TEXT PRIMARY KEY,hash TEXT NOT NULL,bytes BLOB NOT NULL,created INTEGER NOT NULL)")
                db.execSQL("INSERT INTO outbox VALUES(?,?,?,?)", arrayOf<Any?>(batch.id, ReadingWire.digest(bytes), bytes, 2000))
                db.execSQL("CREATE TABLE receipts(installation TEXT NOT NULL,batch TEXT NOT NULL,hash TEXT NOT NULL,received INTEGER NOT NULL,PRIMARY KEY(installation,batch))")
                db.execSQL("INSERT INTO receipts VALUES(?,?,?,?)", arrayOf<Any?>(installation, batch.id, ReadingWire.digest(bytes), 2000))
            }
            assertThrows(org.json.JSONException::class.java) { ReadingJournal(file).close() }
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.rawQuery("PRAGMA table_info(readings)", null).use { c -> while (c.moveToNext()) assertNotEquals("sensor_elapsed", c.getString(1)) }
                db.execSQL("UPDATE readings SET payload=?", arrayOf<Any?>(payload))
            }
            ReadingJournal(file).use {
                assertEquals(sample.id, it.latest("heart", installation, sample.boot)!!.getString("id"))
                assertArrayEquals(bytes, it.pending().single().bytes)
                assertEquals(ReadingReceipt(batch.id, ReadingWire.digest(bytes)), it.receive(bytes, 3000))
            }
            ReadingJournal(file).use { assertEquals(sample.id, it.latest("heart", installation)!!.getString("id")) }
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.rawQuery("SELECT payload,hash FROM readings", null).use { c -> assertTrue(c.moveToFirst()); assertEquals(payload, c.getString(0)); assertEquals(hash, c.getString(1)) }
                db.rawQuery("SELECT received FROM receipts", null).use { c -> assertTrue(c.moveToFirst()); assertEquals(2000, c.getLong(0)) }
            }
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }

    @Test fun optionalBootCountPreservesLegacyBytesAndRejectsMalformedMetadata() {
        val batch = ReadingBatch(id(), id(), listOf(reading()))
        val legacy = ReadingWire.encode(batch)
        assertFalse(legacy.toString(Charsets.UTF_8).contains("bootCount"))
        assertArrayEquals(legacy, ReadingWire.encode(ReadingWire.decode(legacy)))
        val withCount = ReadingWire.encode(ReadingBatch(batch.id, batch.installation, listOf(batch.readings.single().copy(bootCount = 7))))
        assertEquals(7, ReadingWire.decode(withCount).readings.single().bootCount)
        for (bad in listOf(-1, 1.5, "7", 2147483648L, org.json.JSONObject.NULL)) {
            val root = org.json.JSONObject(withCount.toString(Charsets.UTF_8))
            root.getJSONArray("readings").getJSONObject(0).put("bootCount", bad)
            assertThrows(IllegalArgumentException::class.java) { ReadingWire.decode(root.toString().toByteArray()) }
        }
    }

    @Test fun wireRejectsInvalidUnitsValuesVersionsAndOversizedInput() {
        val good = reading()
        assertThrows(IllegalArgumentException::class.java) { good.copy(unit = "count") }
        assertThrows(IllegalArgumentException::class.java) { good.copy(value = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { good.copy(value = null) }
        assertThrows(IllegalArgumentException::class.java) { good.copy(end = 999) }
        assertThrows(IllegalArgumentException::class.java) { ReadingWire.decode(ByteArray(ReadingWire.MAX_BYTES + 1)) }
        val bytes = ReadingWire.encode(ReadingBatch(id(), id(), listOf(good)))
        assertEquals(good, ReadingWire.decode(bytes).readings.single())
        assertThrows(IllegalArgumentException::class.java) { ReadingWire.decode(bytes + "extra".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { ReadingWire.decode(bytes.toString(Charsets.UTF_8).replace("\"version\":1", "\"version\":2").toByteArray()) }
        val receipt = ReadingReceipt(id(), ReadingWire.digest(bytes))
        assertEquals(receipt, ReadingWire.decodeReceipt(ReadingWire.encodeReceipt(receipt)))
        assertThrows(IllegalArgumentException::class.java) { ReadingWire.decodeReceipt(ReadingWire.encodeReceipt(receipt) + "x".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { ReadingWire.decodeReceipt(ReadingWire.encodeReceipt(receipt.copy(hash = "bad"))) }
    }

    @Test fun captureIsAtomicAcrossLocalHistoryAndOutboxAndReplayedSensorCallbacks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val path = File(context.cacheDir, "capture-${id()}.db")
        val installation = id()
        val sample = reading()
        try {
            ReadingJournal(path).close()
            SQLiteDatabase.openOrCreateDatabase(path, null).use {
                it.execSQL("CREATE TRIGGER fail_outbox BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT,'disk write failed'); END")
            }
            ReadingJournal(path).use {
                assertThrows(android.database.sqlite.SQLiteException::class.java) { it.capture(installation, listOf(sample)) }
                assertNull(it.latest("heart"))
                assertEquals(0, it.pendingCount())
            }
            SQLiteDatabase.openOrCreateDatabase(path, null).use { it.execSQL("DROP TRIGGER fail_outbox") }
            ReadingJournal(path).use {
                assertNotNull(it.capture(installation, listOf(sample)))
                assertNull(it.capture(installation, listOf(sample.copy(capturedAt = 2000))))
                assertNull(it.capture(installation, listOf(sample.copy(quality = "unknown"))))
                assertEquals(1, it.pendingCount())
                assertEquals(sample.value!!, it.latest("heart", installation)!!.getDouble("value"), 0.0)
                assertNotNull(it.capture(installation, listOf(sample.copy(value = 81.0))))
                assertEquals(2, it.latest("heart")!!.getLong("revision"))
                assertEquals(2, it.pendingCount())
                assertNull(it.latest("heart", id()))
                val metadata = sample.copy(value = 81.0, quality = "unknown", bootCount = 7)
                assertNotNull(it.capture(installation, listOf(metadata)))
                assertEquals("valid", it.latest("heart", installation)!!.getString("quality"))
                assertEquals(7, it.latest("heart", installation)!!.getInt("bootCount"))
                assertNull(it.capture(installation, listOf(metadata.copy(bootCount = null))))
                assertNotNull(it.capture(installation, listOf(metadata.copy(timeUncertain = true))))
                assertTrue(it.latest("heart", installation)!!.getBoolean("timeUncertain"))
                assertNull(it.capture(installation, listOf(metadata)))
                assertThrows(IllegalArgumentException::class.java) { it.capture(installation, listOf(metadata.copy(bootCount = 8))) }
                assertEquals(4, it.pendingCount())
            }
            ReadingJournal(path).use { assertEquals(4, it.pendingCount()); assertEquals(81.0, it.latest("heart")!!.getDouble("value"), 0.0) }
        } finally { SQLiteDatabase.deleteDatabase(path) }
    }
}
