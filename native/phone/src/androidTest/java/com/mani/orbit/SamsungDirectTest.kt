package com.mani.orbit

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.data.entries.*
import com.samsung.android.sdk.health.data.request.DataType.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*

/** Real SDK builders and real SQLite, with isolated emulator-only inputs. No Samsung server simulation. */
@RunWith(AndroidJUnit4::class)
class SamsungDirectTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val date = LocalDate.now()
    private val start = date.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant()
    private val end = start.plusSeconds(3600)
    @Before fun emulatorOnly() { check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) }
    private fun point(id: String, fields: HealthDataPoint.Builder.() -> Unit): HealthDataPoint =
        HealthDataPoint.builder().setStartTime(start, ZoneOffset.UTC).setEndTime(end, ZoneOffset.UTC).apply(fields).build().also {
            // SDK assigns read UIDs internally. Populate only that field in test inputs, never production code.
            HealthDataPoint::class.java.getDeclaredField("b").apply { isAccessible = true }.set(it, id)
            assertEquals(id, it.uid)
        }
    private fun store(block: (HealthRecordStore) -> Unit) {
        val file = context.getDatabasePath("samsung-sdk-check.db")
        context.deleteDatabase(file.name)
        try { HealthRecordStore(file).use(block) } finally { context.deleteDatabase(file.name) }
    }
    private fun row(type: String, id: String, value: Double) = JSONObject().put("type", type).put("id", id)
        .put("source", "com.sec.android.app.shealth").put("start", start.toEpochMilli()).put("end", end.toEpochMilli()).put("value", value)

    @Test fun directSdkUnitsAndSourceFieldsReachNativeProjection() = store { records ->
        val body = point("body") {
            addFieldData(BodyCompositionType.WEIGHT, 84f); addFieldData(BodyCompositionType.HEIGHT, 178f)
            addFieldData(BodyCompositionType.SKELETAL_MUSCLE_MASS, 34.1f); addFieldData(BodyCompositionType.BODY_FAT, 21f)
        }
        val meal = point("meal") {
            addFieldData(NutritionType.TITLE, "Recorded meal"); addFieldData(NutritionType.CALORIES, 500f)
            addFieldData(NutritionType.PROTEIN, 25f); addFieldData(NutritionType.SODIUM, 600f); addFieldData(NutritionType.VITAMIN_A, 900f)
        }
        val heart = point("heart") { addFieldData(HeartRateType.SERIES_DATA, listOf(HeartRate.of(80f, 60f, 105f, start, end))) }
        val oxygen = point("oxygen") { addFieldData(BloodOxygenType.SERIES_DATA, listOf(OxygenSaturation.of(96f, 91f, 99f, start, end))) }
        val score = point("score") { addFieldData(EnergyScoreType.ENERGY_SCORE, 72f) }
        val water = point("water") { addFieldData(WaterIntakeType.AMOUNT, 250f) }
        val encoded = listOf("body" to body, "nutrition" to meal, "heart" to heart, "oxygen" to oxygen, "energyScore" to score, "water" to water)
            .flatMap { (type, point) -> SamsungRecordCodec.encode(type, point) }
        assertEquals(1.78, encoded.single { it.getString("type") == "height" }.getDouble("value"), .00001)
        assertEquals(.6, encoded.single { it.getString("type") == "nutrition" }.getJSONObject("nutrientsGrams").getDouble("Sodium"), .00001)
        assertEquals(.0009, encoded.single { it.getString("type") == "nutrition" }.getJSONObject("nutrientsGrams").getDouble("VitaminA"), .000001)
        records.beginImport(); records.stage(JSONArray(encoded))
        records.finishImport(encoded.map { it.getString("type") }.distinct(), 0, end.toEpochMilli() + 1, true, "samsung_sdk")
        val day = NativeHealthProjection.read(HealthProjection.read(records, date), date)
        assertEquals(84.0, day.weight!!, .001); assertEquals(34.1, day.measurements.muscle.single().value, .001)
        assertEquals(72.0, day.energyScore!!, 0.0); assertEquals(250.0, day.water!!, 0.0)
        assertEquals(80.0, day.heart!!, 0.0); assertEquals(60.0, day.heartLow!!, 0.0); assertEquals(105.0, day.heartHigh!!, 0.0)
        assertEquals(91.0, day.oxygenLow!!, 0.0); assertEquals(99.0, day.oxygenHigh!!, 0.0)
        assertTrue(encoded.all { it.getString("transport") == "samsung_sdk" && it.getString("id").startsWith("sdk:") })
    }

    @Test fun sleepWindowNeverBecomesInventedTimeAsleep() {
        val stages = listOf(SleepSession.SleepStage.of(start, start.plusSeconds(600), SleepType.StageType.AWAKE),
            SleepSession.SleepStage.of(start.plusSeconds(600), end, SleepType.StageType.LIGHT))
        val original = point("night") {
            addFieldData(SleepType.SESSIONS, listOf(SleepSession.of(start, end, Duration.ofHours(1), stages)))
        }
        // Sleep scores are read-only; the SDK's write builder deliberately rejects them.
        val readFields = HealthDataPoint::class.java.getDeclaredField("a").apply { isAccessible = true }.get(original)
        readFields.javaClass.getMethod("setValue", String::class.java, Any::class.java).invoke(readFields, "sleep_score", 84)
        assertEquals(84, original.getValue(SleepType.SLEEP_SCORE))
        val rows = JSONArray(SamsungRecordCodec.encode("sleep", original))
        fun project() = NativeHealthProjection.read(JSONObject().put("schema", 1).put("rows", rows), date)
        assertEquals(50.0, project().asleepMinutes!!, 0.0)
        assertEquals(84.0, project().sleepScore!!, 0.0)
        assertEquals(3_600_000L, project().nights.single().recordedWindowMs)
        rows.getJSONObject(0).put("stages", JSONArray())
        assertNull(project().asleepMinutes)
        assertTrue(project().sleepIncomplete)
        rows.getJSONObject(0).put("recordedWindowMs", 3_600_001)
        assertThrows(IllegalArgumentException::class.java) { project() }
    }

    @Test fun workoutReportedDurationAndRouteSurviveImport() {
        val session = ExerciseSession.builder().setStartTime(start).setEndTime(end).setDuration(Duration.ofMinutes(45))
            .setExerciseType(ExerciseType.PredefinedExerciseType.WALKING).setCalories(150f).setDistance(2300f)
            .setRoute(listOf(ExerciseLocation.of(start, 0f, 51f, 20f, 5f), ExerciseLocation.of(start.plusSeconds(120), .01f, 51.01f, 22f, 5f))).build()
        val original = point("exercise") { addFieldData(ExerciseType.SESSIONS, listOf(session)) }
        val rows = JSONArray(SamsungRecordCodec.encode("exercise", original))
        val record = WorkoutData.imported(rows).single()
        assertEquals(2_700_000L, record.elapsed); assertEquals(3_600_000L, record.total)
        assertEquals(2300.0, record.distance!!, 0.0); assertEquals(2, record.points.size)
        assertTrue(record.points.last().breakBefore); assertFalse(record.hasUnsharedRoute); assertTrue(record.reportedDuration)
        rows.getJSONObject(0).put("durationMs", 3_600_001)
        assertThrows(IllegalArgumentException::class.java) { WorkoutData.imported(rows) }
    }

    @Test fun interruptedImportAndNewPermissionsCannotExposePartialHistory() = store { records ->
        records.beginImport(); records.stage(JSONArray().put(row("weight", "old-body", 80.0)).put(row("stepsDay", "old-steps", 123.0)))
        records.finishImport(listOf("weight", "stepsDay"), 0, end.toEpochMilli() + 1, true)
        records.beginImport(); records.stage(JSONArray().put(row("stepsDay", "sdk-steps", 456.0)))
        val before = NativeHealthProjection.read(HealthProjection.read(records, date), date)
        assertEquals(123.0, before.steps!!, 0.0); assertEquals(80.0, before.weight!!, 0.0)
        assertEquals("health_connect", records.metadata().getString("transport"))
        records.finishImport(listOf("stepsDay"), 0, end.toEpochMilli() + 1, true, "samsung_sdk")
        val after = NativeHealthProjection.read(HealthProjection.read(records, date), date)
        assertEquals(456.0, after.steps!!, 0.0); assertEquals(80.0, after.weight!!, 0.0)
        val meta = records.metadata(); val now = System.currentTimeMillis()
        assertTrue(SamsungDataImport.canRefreshRecent(meta, listOf("stepsDay"), now))
        assertFalse(SamsungDataImport.canRefreshRecent(meta, listOf("stepsDay", "weight"), now))
        assertFalse(SamsungDataImport.canRefreshRecent(meta, listOf("stepsDay", "route"), now))
        assertFalse(SamsungDataImport.canRefreshRecent(meta, listOf("stepsDay"), now + 86_400_001))
        assertFalse(SamsungDataImport.canRefreshRecent(meta, listOf("stepsDay"), 0))
    }

    @Test fun allSdkReadRequestsBuildAndNativeAppHasNoHealthConnectPermissions() {
        for (type in listOf("heart", "sleep", "nutrition", "water", "body", "oxygen", "exercise", "energyScore"))
            assertNotNull(SamsungDataImport.request(type, start, end, null))
        assertEquals(12, SamsungDataImport.permissions.size)
        assertFalse(SamsungDataImport.hasReadingsPermission(setOf(SamsungDataImport.permissions.getValue("route"))))
        assertTrue(SamsungDataImport.hasReadingsPermission(setOf(SamsungDataImport.permissions.getValue("exercise"))))
        @Suppress("DEPRECATION") val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.any { it.startsWith("android.permission.health.") })
        assertThrows(ClassNotFoundException::class.java) { Class.forName("com.mani.orbit.HealthConnectReader") }
        assertThrows(IllegalArgumentException::class.java) {
            SamsungRecordCodec.encode("body", point("bad") { addFieldData(BodyCompositionType.BODY_FAT, 101f) })
        }
    }
}
