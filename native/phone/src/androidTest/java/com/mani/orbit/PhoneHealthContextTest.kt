package com.mani.orbit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mani.orbit.sync.HealthContextWire
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class PhoneHealthContextTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val today = LocalDate.now()
    private val midnight = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    @Before fun emulatorOnly() { check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) }
    @Test fun committedSamsungContextPreservesOverlapsMissingStagesScoresAndDeletions() {
        val file = context.getDatabasePath("phone-context-check.db"); context.deleteDatabase(file.name)
        fun row(kind: String, id: String, start: Long, end: Long) = JSONObject().put("type", kind).put("id", id)
            .put("source", "com.sec.android.app.shealth").put("start", start).put("end", end)
        fun sleep(id: String, from: Int, until: Int, stage: Int?, score: Int) = row("sleep", id, midnight + from * 3600000L, midnight + until * 3600000L)
            .put("sleepScore", score).put("stages", JSONArray().apply { if (stage != null) put(JSONArray().put(midnight + from * 3600000L).put(midnight + until * 3600000L).put(stage)) })
        try {
            HealthRecordStore(file).use { store ->
                store.beginImport()
                store.stage(JSONArray().put(sleep("first", -2, 2, 4, 84)).put(sleep("second", 1, 5, 5, 90))
                    .put(sleep("unknown", -4, -3, null, 60))
                    .put(row("energyScore", "energy", midnight, midnight).put("date", today.toString()).put("value", 77))
                    .put(row("energyScore", "older", midnight - 12 * 86400000L, midnight - 12 * 86400000L).put("date", today.minusDays(12).toString()).put("value", 88)))
                store.finishImport(listOf("sleep", "energyScore"), 0, midnight + 86400000, true, "samsung_sdk")
            }
            val result = phoneHealthContext(file, today, midnight + 12 * 3600000)
            assertEquals(listOf(today, today.minusDays(1)), result.days.map { it.date })
            val day = result.days.first()
            assertEquals(2, day.sessions); assertEquals(6 * 3600000L, day.asleepMs)
            assertEquals(3600000L, day.stages["unknown"])
            assertEquals(90.0, day.sleepScore!!, 0.0); assertEquals(77.0, day.energyScore!!, 0.0)
            assertNull(result.days.last().asleepMs)
            val bytes = HealthContextWire.encode(result)
            assertEquals(result, HealthContextWire.decode(bytes)); assertTrue(bytes.size < 4096)
            context.cacheDir.resolve("phone-context-roundtrip.json").writeBytes(bytes)
            HealthRecordStore(file).use { store ->
                assertEquals(5, store.metadata().getInt("recordCount"))
                store.beginImport(); store.finishImport(listOf("sleep", "energyScore"), 0, midnight + 86400000, true, "samsung_sdk")
            }
            assertTrue(phoneHealthContext(file, today).days.isEmpty())
        } finally { context.deleteDatabase(file.name) }
    }

    @Test fun wireRejectsMalformedUntrustedOrAmbiguousHealthValues() {
        val baseline = JSONObject().put("version", 1).put("source", "com.sec.android.app.shealth").put("zone", "Europe/London")
            .put("generatedAt", 1000).put("importedAt", 1000).put("days", JSONArray())
        fun rejects(row: JSONObject) = assertThrows(IllegalArgumentException::class.java) { HealthContextWire.decode(row.toString().toByteArray()) }
        rejects(JSONObject(baseline.toString()).put("source", "unrelated.app"))
        rejects(JSONObject(baseline.toString()).put("generatedAt", 1.5))
        assertThrows(IllegalArgumentException::class.java) { HealthContextWire.decode((baseline.toString() + "{}").toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { HealthContextWire.decode(ByteArray(HealthContextWire.MAX_BYTES + 1)) }
        val day = JSONObject().put("date", today.toString()).put("start", 0).put("end", 1000).put("sessions", 1)
            .put("stages", JSONObject().put("light", 2000)).put("sleepScore", JSONObject.NULL).put("energyScore", 101)
        rejects(JSONObject(baseline.toString()).put("days", JSONArray().put(day)))
        day.put("stages", JSONObject().put("light", 1000)).put("energyScore", 80)
        rejects(JSONObject(baseline.toString()).put("days", JSONArray().put(day).put(day)))
        day.put("stages", JSONObject().put("light", -1))
        rejects(JSONObject(baseline.toString()).put("days", JSONArray().put(day)))
    }
}
