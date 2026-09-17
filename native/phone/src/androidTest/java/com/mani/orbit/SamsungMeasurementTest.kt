package com.mani.orbit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mani.orbit.sync.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

class SamsungMeasurementTest {
    private fun id() = UUID.randomUUID().toString()
    private val profile = MeasurementProfile(LocalDate.of(1995, 5, 2), "female", 175.0, 80.0)
    private fun result(): WatchMeasurement {
        val values = listOf(MeasurementValue("BODY_FAT", 20.0, "%"), MeasurementValue("BODY_FAT_MASS", 16.0, "kg"),
            MeasurementValue("BODY_WATER", 44.0, "L"), MeasurementValue("SKELETAL_MUSCLE_MASS", 32.0, "kg"),
            MeasurementValue("FAT_FREE_MASS", 64.0, "kg"), MeasurementValue("BASAL_METABOLIC_RATE", 1500.0, "kcal"))
        val boot = id()
        val readings = values.mapNotNull { value -> WatchMeasurement.PROJECTIONS[value.metric]?.let {
            WatchReading(id(), 1, 1000, 1000, 0, it, value.value, WatchReading.UNITS.getValue(it), "valid", "instant", boot, 500, source = "samsung_sensor")
        } }
        return WatchMeasurement(ReadingBatch(id(), id(), readings), "BIA", values, profile)
    }

    @Test fun completeResultAndProjectionsSurviveOfflineReplayAndExactReceipts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "sensor-journal-${id()}").apply { check(mkdirs()) }
        val result = result()
        try {
            ReadingJournal(File(dir, "watch.db")).use { it.captureMeasurement(result) }
            val pending = ReadingJournal(File(dir, "watch.db")).use {
                assertEquals(result.values, it.latestMeasurement(result.batch.installation)!!.values)
                assertEquals(64.0, it.latest("lean")!!.getDouble("value"), .0)
                assertNull(it.latest("weight")) // Profile weight must never become a measured weight.
                it.pending().single()
            }
            assertEquals(MeasurementWire.PATH, pending.path)
            val receipt = ReadingJournal(File(dir, "phone.db")).use {
                val receipt = it.receiveMeasurement(pending.bytes, 2000)
                assertEquals(receipt, it.receiveMeasurement(pending.bytes, 3000))
                assertEquals(result.values, it.latestMeasurement()!!.values)
                assertEquals(1, it.readings("fat", 0, 2000).size)
                val changed = JSONObject(pending.bytes.toString(Charsets.UTF_8))
                changed.getJSONObject("measurement").getJSONArray("values").getJSONObject(1).put("value", 17.0)
                assertThrows(IllegalArgumentException::class.java) { it.receiveMeasurement(changed.toString().toByteArray(), 3001) }
                assertEquals(result.values, it.latestMeasurement()!!.values)
                receipt
            }
            ReadingJournal(File(dir, "watch.db")).use {
                assertFalse(it.acknowledge(receipt.copy(hash = "0".repeat(64))))
                assertEquals(1L, it.pendingCount())
                assertTrue(it.acknowledge(receipt)); assertEquals(0L, it.pendingCount())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun measurementTrustBoundaryRejectsFalseStatusMismatchedProjectionAndMissingProfile() {
        val bytes = MeasurementWire.encode(result())
        fun reject(edit: (JSONObject) -> Unit) {
            val root = JSONObject(bytes.toString(Charsets.UTF_8)); edit(root)
            assertThrows(IllegalArgumentException::class.java) { MeasurementWire.decode(root.toString().toByteArray()) }
        }
        reject { it.getJSONObject("measurement").put("status", "measuring") }
        reject { it.getJSONObject("measurement").put("profile", JSONObject.NULL) }
        reject { it.getJSONArray("readings").getJSONObject(0).put("value", 99.0) }
        reject { it.getJSONObject("measurement").getJSONArray("values").getJSONObject(0).put("unit", "kg") }
        reject { it.getJSONArray("readings").getJSONObject(0).put("source", "health_services") }
        assertThrows(IllegalArgumentException::class.java) { MeasurementWire.decode(bytes + byteArrayOf(65)) }
    }

    @Test fun profileIsOptionalValidatedAndSeparateFromSamsungOriginals() {
        assertEquals(profile, MeasurementProfileWire.decode(MeasurementProfileWire.encode(profile)))
        assertFalse(profile.copy(sex = null).complete(LocalDate.now()))
        assertFalse(profile.copy(birth = LocalDate.now().plusDays(1)).complete(LocalDate.now()))
        assertEquals(null, MeasurementProfileWire.decode(MeasurementProfileWire.encode(MeasurementProfile(null, null, null, null))).weightKg)
        val root = JSONObject(MeasurementProfileWire.encode(profile).toString(Charsets.UTF_8)).put("source", "com.sec.android.app.shealth")
        assertThrows(IllegalArgumentException::class.java) { MeasurementProfileWire.decode(root.toString().toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { MeasurementProfile(profile.birth, "unknown", 175.0, 80.0) }
        assertThrows(IllegalArgumentException::class.java) { MeasurementProfile(profile.birth, "female", Double.NaN, 80.0) }
    }

    @Test fun historyIsSourceScopedStableAcrossArrivalAndKeepsAllOriginalValues() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "sensor-history-${id()}").apply { check(mkdirs()) }
        val original = result()
        fun at(time: Long, installation: String = original.batch.installation): WatchMeasurement = WatchMeasurement(
            ReadingBatch(id(), installation, original.batch.readings.map { it.copy(id = id(), start = time, end = time, elapsedMs = time, anchorElapsedMs = time, capturedAt = time) }),
            original.tracker, original.values.reversed(), profile)
        val older = at(1000); val equalTime = at(1000); val newer = at(2000)
        try { ReadingJournal(File(dir, "journal.db")).use { journal ->
            assertNull(journal.measurementPage(original.batch.installation))
            listOf(newer, older, equalTime, at(3000, id())).forEach { journal.captureMeasurement(it) }
            val first = journal.measurementPage(original.batch.installation)!!
            assertEquals(newer.batch.id, first.result.batch.id); assertNull(first.newerId)
            val middle = journal.measurementPage(original.batch.installation, first.olderId)!!
            val last = journal.measurementPage(original.batch.installation, middle.olderId)!!
            assertNull(last.olderId)
            assertEquals(3, setOf(first.result.batch.id, middle.result.batch.id, last.result.batch.id).size)
            assertEquals(first.result.batch.id, middle.newerId)
            assertEquals(middle.result.batch.id, last.newerId)
            journal.captureMeasurement(at(4000))
            assertEquals(middle.result.batch.id, journal.measurementPage(original.batch.installation, middle.result.batch.id)!!.result.batch.id)
            assertEquals(original.values.toSet(), middle.result.values.toSet())
            assertEquals("BODY_FAT", middle.result.primary.metric)
            assertEquals(20.0, middle.result.primary.value, 0.0)
            assertThrows(IllegalArgumentException::class.java) { journal.measurementPage("bad-source") }
        } } finally { dir.deleteRecursively() }
    }

    @Test fun pendingWorkoutOwnershipSurvivesClockRollbackAndIsScopedToTheCurrentBoot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "sensor-owner-${id()}").apply { check(mkdirs()) }
        val installation = id(); val boot = id()
        val pending = WatchWorkout(id(), 1, "Walking", boot, 1000, 1000, 1000, 1000, 0, "starting", false)
        try { ReadingJournal(File(dir, "journal.db")).use { journal ->
            journal.captureWorkoutUpdate(installation, pending, emptyList())
            journal.captureWorkoutUpdate(installation, pending.copy(id = id(), start = 10000, updatedAt = 10000, phase = "ended"), emptyList())
            assertTrue(journal.hasOpenWorkout(installation, boot))
            assertFalse(journal.hasOpenWorkout(installation, id()))
            assertFalse(journal.hasOpenWorkout(id(), boot))
            journal.captureWorkoutUpdate(installation, pending.copy(revision = 2, phase = "interrupted"), emptyList())
            assertFalse(journal.hasOpenWorkout(installation, boot))
        } } finally { dir.deleteRecursively() }
    }
}
