package com.mani.orbit

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class SamsungStorageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val date = LocalDate.now()
    private val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    @Before fun emulatorOnly() { check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) }
    private fun row(kind: String, id: String) = JSONObject().put("type", kind).put("id", id)
        .put("source", "com.sec.android.app.shealth").put("start", start).put("end", start + 86_400_000)
    @Test fun longWorkoutAndDayOfPulseCanBeReadWithoutACursorFailure() {
        val file = context.getDatabasePath("samsung-large-records-check.db")
        context.deleteDatabase(file.name)
        try { HealthRecordStore(file).use { records ->
            val samples = JSONArray()
            repeat(86_400) { i -> samples.put(JSONArray().put(start + i * 1000L).put(70 + i % 30).put(60).put(110)) }
            val route = JSONArray()
            repeat(30_000) { i -> route.put(JSONObject().put("at", start + i * 1000L).put("lat", 51.0 + i / 1000000.0)
                .put("lon", -.2 + i / 1000000.0).put("altitude", 20.123456789).put("accuracy", 5.123456789)) }
            val workout = row("exercise", "long-route").put("kind", "Walking").put("hasRoute", true).put("route", route)
                .put("summary", JSONObject().put("distance", 30_000)).put("laps", JSONArray()).put("segments", JSONArray())
            assertTrue(workout.toString().length > 2_000_000)
            records.beginImport(); records.stage(JSONArray().put(workout).put(row("heart", "day-of-pulse").put("samples", samples)))
            records.finishImport(listOf("exercise", "heart"), 0, start + 86_400_001, true, "samsung_sdk")
            val projection = HealthProjection.read(records, date, true)
            val native = NativeHealthProjection.project(projection, date)
            assertEquals(86_400L, native.day.heartCount)
            assertEquals(1, native.workouts.size)
            assertTrue(native.workouts.single().points.isEmpty())
            assertTrue(native.workouts.single().detailsDeferred)
            assertTrue(projection.toString().length < 20_000)
            val full = records.record("exercise", "long-route")!!
            assertEquals(30_000, full.getJSONArray("route").length())
            assertEquals(route.toString(), full.getJSONArray("route").toString())
            assertEquals(2, records.metadata().getInt("recordCount"))
        } } finally { context.deleteDatabase(file.name) }
    }

    @Test fun oldInlineDatabaseUpgradesWithoutLosingArraysUnicodeOrMetadata() {
        val file = context.getDatabasePath("samsung-legacy-upgrade-check.db")
        context.deleteDatabase(file.name)
        val samples = JSONArray(); repeat(86_400) { samples.put(JSONArray(listOf(start + it * 1000L, 80))) }
        val old = row("heart", "old-pulse").put("samples", samples)
        val workout = row("exercise", "old-workout").put("kind", "Walking").put("notes", "Route 🌌 王 notes")
            .put("laps", JSONArray().put(JSONObject().put("start", start).put("end", start + 1000)))
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE records(kind TEXT NOT NULL,id TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(kind,id))")
            db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            for (item in listOf(old, workout)) db.insertOrThrow("records", null, ContentValues().apply {
                put("kind", item.getString("type")); put("id", item.getString("id")); put("start", start); put("end", start + 86_400_000); put("payload", item.toString())
            })
            db.execSQL("INSERT INTO metadata VALUES('import','{\"lastSync\":42,\"transport\":\"health_connect\"}')")
        }
        try { HealthRecordStore(file).use { records ->
            assertEquals(samples.toString(), records.record("heart", "old-pulse")!!.getJSONArray("samples").toString())
            val imported = records.record("exercise", "old-workout")!!
            assertEquals("Route 🌌 王 notes", imported.getString("notes"))
            assertEquals(workout.getJSONArray("laps").toString(), imported.getJSONArray("laps").toString())
            assertEquals(42, records.metadata().getInt("lastSync"))
            assertEquals(86_400L, NativeHealthProjection.read(HealthProjection.read(records, date, true), date).heartCount)
        }
            HealthRecordStore(file).use { assertEquals(42, it.metadata().getInt("lastSync")) }
        } finally { context.deleteDatabase(file.name) }
    }

    @Test fun detailUpdatesCommitTogetherAndInterruptedReplacementKeepsOriginal() {
        val file = context.getDatabasePath("samsung-detail-atomic-check.db"); context.deleteDatabase(file.name)
        try { HealthRecordStore(file).use { records ->
            fun record(id: String, count: Int) = row("exercise", id).put("kind", "Walking")
                .put("route", JSONArray().apply { repeat(count) { i -> put(JSONObject().put("at", start + i * 1000L).put("lat", 51).put("lon", 0)) } })
            fun commit() = records.finishImport(listOf("exercise"), 0, start + 86_400_001, true, "samsung_sdk")
            fun revision() = records.workouts().use { it.moveToFirst(); JSONObject(it.getString(0)).getString("_revision") }
            records.beginImport(); records.stage(JSONArray().put(record("one", 3))); commit()
            val first = revision()
            records.beginImport(); records.stage(JSONArray().put(record("one", 3))); commit()
            assertEquals(first, revision())
            records.beginImport(); records.stage(JSONArray().put(record("one", 9)))
            assertEquals(3, records.record("exercise", "one")!!.getJSONArray("route").length())
            val invalid = record("one", 1).put("notes", "x".repeat(70_000))
            assertThrows(IllegalArgumentException::class.java) { records.stage(JSONArray().put(invalid)) }
            records.pendingWorkouts().use { cursor ->
                assertTrue(cursor.moveToFirst())
                val header = JSONObject(cursor.getString(0))
                assertThrows(IllegalArgumentException::class.java) { records.stage(JSONArray().put(header)) }
                assertEquals(9, records.hydratePending(header).getJSONArray("route").length())
            }
            records.beginImport(); assertEquals(3, records.record("exercise", "one")!!.getJSONArray("route").length())
            records.stage(JSONArray().put(record("one", 1))); commit()
            assertEquals(1, records.record("exercise", "one")!!.getJSONArray("route").length())
            assertNotEquals(first, revision())
            records.beginImport(); commit(); assertNull(records.record("exercise", "one"))
        }
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT COUNT(*) FROM record_parts", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            }
        } finally { context.deleteDatabase(file.name) }
    }

    @Test fun missingDetailShowsRetryAndReloadsWithoutChangingWorkouts() {
        val file = context.getDatabasePath("samsung-detail-ui-check.db"); context.deleteDatabase(file.name)
        val original = row("exercise", "ui-route").put("kind", "Walking").put("title", "Recorded walk")
            .put("route", JSONArray().put(JSONObject().put("at", start).put("lat", 51).put("lon", 0))
                .put(JSONObject().put("at", start + 1000).put("lat", 51.001).put("lon", .001)))
        fun save() = HealthRecordStore(file).use {
            it.beginImport(); it.stage(JSONArray().put(original)); it.finishImport(listOf("exercise"), 0, start + 86_400_001, true, "samsung_sdk")
        }
        save()
        val summary = HealthRecordStore(file).use { NativeHealthProjection.project(HealthProjection.read(it, date, true), date).workouts.single() }
        HealthRecordStore(file).use { it.beginImport(); it.finishImport(listOf("exercise"), 0, start + 86_400_001, true, "samsung_sdk") }
        try {
            compose.setContent { MaterialTheme { ImportedWorkoutScreen(summary, file) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Retry recorded details").fetchSemanticsNodes().isNotEmpty() }
            save()
            compose.onNodeWithText("Retry recorded details").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Recorded route").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Recorded route").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Samsung Health route", substring = true).performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithText("Recorded on this phone", substring = true).assertCountEquals(0)
            compose.onAllNodesWithText("Retry recorded details").assertCountEquals(0)
            compose.onAllNodesWithText("No route shared.").assertCountEquals(0)
            assertEquals(2, readImportedWorkout(file, summary.id)!!.points.size)
        } finally { compose.activityRule.scenario.close(); context.deleteDatabase(file.name) }
    }
}
