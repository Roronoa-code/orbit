package com.mani.orbit

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.mani.orbit.sync.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class MeasurementOrderTest {
    private fun id() = UUID.randomUUID().toString()
    private fun result(source: String, boot: String, count: Int?, elapsed: Long, wall: Long, batch: String = id()): WatchMeasurement {
        val reading = WatchReading(id(), 1, wall, wall, 0, "oxygen", 98.0, "%", "valid", "instant", boot, elapsed,
            source = "samsung_sensor", bootCount = count)
        return WatchMeasurement(ReadingBatch(batch, source, listOf(reading)), "SPO2", listOf(MeasurementValue("SPO2", 98.0, "%")))
    }
    private fun file() = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "measurement-order-${id()}.db")
    private fun ReadingJournal.assertTraversal(source: String, expected: List<String>, uncertain: Boolean = false) {
        var page = measurementPage(source)!!
        expected.forEachIndexed { i, expectedId ->
            assertEquals(expectedId, page.result.batch.id)
            assertEquals(expected.getOrNull(i - 1), page.newerId)
            assertEquals(expected.getOrNull(i + 1), page.olderId)
            assertEquals(uncertain, page.orderingUncertain)
            page.olderId?.let { page = measurementPage(source, it)!! }
        }
        expected.asReversed().forEach { expectedId ->
            assertEquals(expectedId, page.result.batch.id)
            page.newerId?.let { page = measurementPage(source, it)!! }
        }
    }

    @Test fun captureOrderSurvivesRollbackRebootsBacklogAndLearnedLegacyMetadata() {
        val path = file(); val source = id(); val firstBoot = id(); val secondBoot = id()
        val before = result(source, firstBoot, 7, 1000, 10000)
        val after = result(source, firstBoot, 7, 2000, 5000)
        val reboot = result(source, secondBoot, 8, 100, 3000)
        val rebootAfter = result(source, secondBoot, 8, 200, 2000)
        try { ReadingJournal(path).use { journal ->
            listOf(rebootAfter, after, reboot, before).forEach { journal.captureMeasurement(it) }
            journal.assertTraversal(source, listOf(rebootAfter, reboot, after, before).map { it.batch.id })
            assertEquals(rebootAfter.batch.id, journal.latestMeasurement(source)!!.batch.id)
            assertEquals(rebootAfter.batch.readings.single().id, journal.latest("oxygen", source)!!.getString("id"))
            val selected = journal.measurementPage(source, after.batch.id)!!
            val newest = result(source, secondBoot, 8, 300, 1000)
            journal.captureMeasurement(newest)
            assertEquals(selected.result.batch.id, journal.measurementPage(source, selected.result.batch.id)!!.result.batch.id)
            val other = result(id(), id(), 9, 400, 20000)
            journal.captureMeasurement(other)
            assertEquals(newest.batch.id, journal.latestMeasurement(source)!!.batch.id)
            assertEquals(other.batch.id, journal.latestMeasurement()!!.batch.id)
            assertNull(journal.measurementPage(id()))
            assertThrows(IllegalArgumentException::class.java) { journal.latestMeasurement("bad-source") }

            val legacy = result(source, id(), null, 100, 6000)
            val bytes = journal.captureMeasurement(legacy).bytes
            val knownOrder = listOf(newest, rebootAfter, reboot, after, before)
            journal.assertTraversal(source, (listOf(legacy) + knownOrder).map { it.batch.id }, true)
            // Learning the boot ordinal qualifies the unchanged original measurement through its ordinary projection.
            val qualified = legacy.batch.readings.single().copy(revision = 2, bootCount = 6)
            journal.receive(ReadingWire.encode(ReadingBatch(id(), source, listOf(qualified))), 30000)
            journal.assertTraversal(source, (knownOrder + legacy).map { it.batch.id })
            assertArrayEquals(bytes, MeasurementWire.encode(journal.measurementPage(source, legacy.batch.id)!!.result))
            val collision = result(source, firstBoot, 9, 3000, 500)
            assertThrows(IllegalArgumentException::class.java) { journal.captureMeasurement(collision) }
            journal.assertTraversal(source, (knownOrder + legacy).map { it.batch.id })
            assertFalse(journal.pending().any { it.id == collision.batch.id })
        }
        ReadingJournal(path).use { assertEquals(secondBoot, it.latestMeasurement(source)!!.batch.readings.first().boot) }
        } finally { SQLiteDatabase.deleteDatabase(path) }
    }

    @Test fun equalElapsedTiesAndMixedBootStreamsHaveReciprocalNeighbors() {
        val path = file(); val source = id(); val boot = id()
        val a = result(source, boot, 1, 10, 900, "00000000-0000-4000-8000-000000000001")
        val b = result(source, boot, 1, 10, 100, "00000000-0000-4000-8000-000000000002")
        val nextBoot = result(source, id(), 2, 2, 200)
        val legacy = result(source, id(), null, 1, 150)
        try { ReadingJournal(path).use { journal ->
            listOf(a, legacy, b, nextBoot).forEach { journal.captureMeasurement(it) }
            // Known boot order is fixed even when legacy wall time lies between their heads.
            journal.assertTraversal(source, listOf(nextBoot, legacy, b, a).map { it.batch.id }, true)
        } } finally { SQLiteDatabase.deleteDatabase(path) }
    }

    @Test fun legacyMigrationIsAtomicPreservesBytesAndRepairsOldBinaryInserts() {
        val path = file(); val source = id(); val boot = id()
        val first = result(source, boot, 3, 100, 500, "00000000-0000-4000-8000-000000000001")
        val second = result(source, boot, 3, 200, 100, "00000000-0000-4000-8000-000000000002")
        val originals = listOf(first, second).associate { it.batch.id to MeasurementWire.encode(it) }
        try {
            ReadingJournal(path).use { journal -> listOf(first, second).forEach { journal.captureMeasurement(it) } }
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.execSQL("ALTER TABLE measurements RENAME TO prior_measurements")
                db.execSQL("CREATE TABLE measurements(installation TEXT NOT NULL,id TEXT NOT NULL,at INTEGER NOT NULL,payload BLOB NOT NULL,PRIMARY KEY(installation,id))")
                db.execSQL("INSERT INTO measurements SELECT installation,id,at,payload FROM prior_measurements")
                db.execSQL("DROP TABLE prior_measurements")
                db.execSQL("DELETE FROM reading_boots")
                db.execSQL("UPDATE measurements SET payload=? WHERE id=?", arrayOf("{}".toByteArray(), second.batch.id))
            }
            assertThrows(org.json.JSONException::class.java) { ReadingJournal(path).close() }
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.rawQuery("PRAGMA table_info(measurements)", null).use { c -> while (c.moveToNext()) assertNotEquals("sensor_elapsed", c.getString(1)) }
                db.rawQuery("SELECT COUNT(*) FROM reading_boots", null).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
                db.execSQL("UPDATE measurements SET payload=? WHERE id=?", arrayOf(originals.getValue(second.batch.id), second.batch.id))
                db.execSQL("UPDATE receipts SET hash=? WHERE batch=?", arrayOf("0".repeat(64), second.batch.id))
            }
            assertThrows(IllegalStateException::class.java) { ReadingJournal(path).close() }
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.execSQL("UPDATE receipts SET hash=? WHERE batch=?", arrayOf(ReadingWire.digest(originals.getValue(second.batch.id)), second.batch.id))
            }
            repeat(2) { ReadingJournal(path).use { journal ->
                journal.assertTraversal(source, listOf(second.batch.id, first.batch.id))
                val pending = journal.pending()
                assertEquals(2, pending.size)
                pending.forEach { assertArrayEquals(originals.getValue(it.id), it.bytes) }
                listOf(first, second).forEach { result ->
                    val bytes = originals.getValue(result.batch.id)
                    assertEquals(ReadingReceipt(result.batch.id, ReadingWire.digest(bytes)), journal.receiveMeasurement(bytes, 50000))
                }
            } }
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.rawQuery("SELECT id,payload FROM measurements", null).use { c -> while (c.moveToNext()) assertArrayEquals(originals.getValue(c.getString(0)), c.getBlob(1)) }
                db.rawQuery("EXPLAIN QUERY PLAN SELECT id FROM measurements WHERE installation=? AND boot=? ORDER BY sensor_elapsed DESC,id DESC LIMIT 1", arrayOf(source, boot)).use {
                    assertTrue(it.moveToFirst()); assertTrue(it.getString(3).contains("measurement_sensor_order"))
                }
                db.rawQuery("EXPLAIN QUERY PLAN SELECT id FROM measurements WHERE installation=? AND boot=? AND (sensor_elapsed,id)<(?,?) ORDER BY sensor_elapsed DESC,id DESC LIMIT 1",
                    arrayOf(source, boot, "200", second.batch.id)).use {
                    assertTrue(it.moveToFirst()); val plan = it.getString(3)
                    assertTrue(plan, plan.contains("measurement_sensor_order") &&
                        (plan.contains("(sensor_elapsed,id)<(?,?)") || plan.contains("sensor_elapsed<?")))
                }
                // An older version can still insert its original payload without the new metadata.
                db.execSQL("UPDATE measurements SET boot=NULL,sensor_elapsed=NULL WHERE id=?", arrayOf(second.batch.id))
            }
            ReadingJournal(path).use { it.assertTraversal(source, listOf(second.batch.id, first.batch.id)) }
        } finally { SQLiteDatabase.deleteDatabase(path) }
    }
}
