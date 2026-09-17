package com.mani.orbit.wear

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.mani.orbit.sync.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

internal fun measurementFixture(kind: String = "BIA"): WatchMeasurement {
    fun id() = UUID.randomUUID().toString()
    val profile = if (kind == "BIA") MeasurementProfile(LocalDate.of(1995, 5, 2), "female", 175.0, 80.0) else null
    val values = when (kind) {
        "SPO2" -> listOf(MeasurementValue("SPO2", 98.0, "%"))
        "SKIN_TEMPERATURE" -> listOf(MeasurementValue("SKIN_TEMPERATURE", 33.4, "°C"), MeasurementValue("AMBIENT_TEMPERATURE", 22.5, "°C"))
        else -> listOf(MeasurementValue("BODY_FAT", 20.0, "%"), MeasurementValue("BODY_FAT_MASS", 16.0, "kg"),
            MeasurementValue("BODY_WATER", 44.0, "L"), MeasurementValue("SKELETAL_MUSCLE_MASS", 32.0, "kg"),
            MeasurementValue("FAT_FREE_MASS", 64.0, "kg"), MeasurementValue("BASAL_METABOLIC_RATE", 1500.0, "kcal"))
    }
    val boot = id()
    val readings = values.mapNotNull { value -> WatchMeasurement.PROJECTIONS[value.metric]?.let {
        WatchReading(id(), 1, 1000, 1000, 0, it, value.value, WatchReading.UNITS.getValue(it), "valid", "instant", boot, 500, source = "samsung_sensor")
    } }
    return WatchMeasurement(ReadingBatch(id(), id(), readings), kind, values, profile)
}

class WatchMeasurementStateTest {
    @Before fun emulatorOnly() { check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) }
    /** Real SQLite faults in a cache directory; no sample enters the app's production journal. */
    private class JournalApplication(base: Context, val directory: File) : Application() {
        init { attachBaseContext(base) }
        override fun getDatabasePath(name: String) = File(directory, name)
        override fun getFilesDir() = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("${directory.name}-$name", mode)
    }
    private fun isolated(block: suspend (WatchMeasurementModel, JournalApplication) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.cacheDir, "measurement-state-${UUID.randomUUID()}").apply { check(mkdir()) }
        val app = JournalApplication(context, folder)
        try { block(WatchMeasurementModel(app), app) }
        finally { context.deleteSharedPreferences("${folder.name}-watch-collection"); check(folder.deleteRecursively()) }
    }

    @Test fun refreshedBootOrderAndItsQualificationDoNotReplaceTheOpenResult() = isolated { model, app ->
        val store = WatchStore(app)
        val fixture = measurementFixture("SPO2")
        val legacy = WatchMeasurement(ReadingBatch(fixture.batch.id, store.installation, fixture.batch.readings), fixture.tracker, fixture.values)
        val newer = WatchMeasurement(ReadingBatch(UUID.randomUUID().toString(), store.installation, legacy.batch.readings.map {
            it.copy(id = UUID.randomUUID().toString(), boot = UUID.randomUUID().toString(), bootCount = 8, elapsedMs = 100, start = 500, end = 500)
        }), fixture.tracker, fixture.values)
        store.journal().use { it.captureMeasurement(legacy); it.captureMeasurement(newer) }
        suspend fun refresh() {
            withContext(Dispatchers.Main) { model.refresh() }
            withTimeout(10000) { while (model.loading) delay(20) }
        }
        refresh()
        withContext(Dispatchers.Main) { model.openLatest() }
        assertEquals(legacy.batch.id, model.result!!.batch.id)
        assertTrue(model.resultOrderUncertain)
        store.journal().use { it.receive(ReadingWire.encode(ReadingBatch(UUID.randomUUID().toString(), store.installation,
            legacy.batch.readings.map { reading -> reading.copy(revision = 2, bootCount = 7) })), 10000) }
        refresh()
        assertEquals(newer.batch.id, model.latest!!.batch.id)
        assertFalse(model.latestOrderUncertain)
        assertEquals(legacy.batch.id, model.result!!.batch.id)
        assertTrue(model.resultOrderUncertain)
        withContext(Dispatchers.Main) { model.openLatest() }
        assertEquals(newer.batch.id, model.result!!.batch.id)
        assertFalse(model.resultOrderUncertain)
    }

    @Test fun failedWriteRetainsTheCompletedResultAndRetryCommitsExactlyOnce() = isolated { model, app ->
        val value = measurementFixture()
        val blockedPath = app.getDatabasePath("watch-readings.db").apply { check(mkdir()) }
        withContext(Dispatchers.Main) {
            try { model.acceptResult(value); fail("A directory cannot accept a database write") } catch (_: android.database.sqlite.SQLiteException) { }
            assertSame(value, model.pending); assertNull(model.result); assertFalse(model.saving)
        }
        check(blockedPath.delete())
        withContext(Dispatchers.Main) { model.retrySave(); model.retrySave() }
        withTimeout(5000) { while (withContext(Dispatchers.Main) { model.saving }) delay(10) }
        assertSame(value, model.result); assertNull(model.pending)
        ReadingJournal(app.getDatabasePath("watch-readings.db")).use {
            assertEquals(value.values, it.latestMeasurement(value.batch.installation)!!.values)
            assertEquals(1, it.pending(path = MeasurementWire.PATH).size)
        }
    }

    @Test fun cancellationAfterCompletionStillPersistsAndRetainsTheResult() = isolated { model, app ->
        val value = measurementFixture("SKIN_TEMPERATURE")
        val entered = CompletableDeferred<Unit>()
        coroutineScope {
            val saving = launch(Dispatchers.Main) { entered.complete(Unit); model.acceptResult(value) }
            entered.await(); saving.cancelAndJoin()
        }
        assertNull(model.pending); assertSame(value, model.result)
        ReadingJournal(app.getDatabasePath("watch-readings.db")).use {
            assertEquals(value.values, it.latestMeasurement(value.batch.installation)!!.values)
            assertEquals(1, it.pending(path = MeasurementWire.PATH).size)
        }
    }
}
