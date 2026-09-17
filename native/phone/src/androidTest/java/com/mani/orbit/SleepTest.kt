package com.mani.orbit

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
class SleepTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val date = LocalDate.of(2026, 9, 13)
    private fun at(hour: Int, minute: Int = 0) = date.atStartOfDay(ZoneId.systemDefault()).plusHours(hour.toLong()).plusMinutes(minute.toLong()).toInstant().toEpochMilli()
    @Before fun emulatorOnly() { check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) }
    private fun dense(): SleepDay {
        val start = at(7, 35); val end = at(14, 9); val intervals = mutableListOf<SleepInterval>()
        var cursor = start; var i = 0
        while (cursor < end) {
            val pattern = listOf("light" to 4, "awake" to 1, "light" to 4, "awake" to 1, "deep" to 18, "awake" to 1, "rem" to 12)
            val (kind, minutes) = pattern[i++ % pattern.size]; val next = minOf(end, cursor + minutes * 60000)
            intervals += SleepInterval(cursor, next, kind); cursor = next
        }
        return SleepDay(date, listOf(SleepNight("main", start, end, intervals), SleepNight("nap", at(14, 10), at(15, 38), listOf(SleepInterval(at(14, 10), at(15, 38), "light")))), date.minusDays(10))
    }
    private fun save(name: String) = rule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
        File(rule.activity.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun denseOriginalsUseFewerBlocksWithoutErasingGapsOrInflatingTotals() {
        val day = dense()
        assertTrue(day.blocks.size < day.segments.size)
        assertEquals(1.0, day.totals["unrecorded"]!!, 0.0)
        assertEquals(day.totals.filterKeys { it in setOf("light", "deep", "rem") }.values.sum(), day.asleep!!, .0001)
        assertEquals("unrecorded", day.locate(at(14, 9) + 30000)!!.stage)
        assertEquals(day.start!!, day.at(-1f)); assertEquals(day.end!! - 1, day.at(2f))
        val overlapped = day.copy(nights = day.nights + SleepNight("conflict", at(7, 40), at(7, 45), listOf(SleepInterval(at(7, 40), at(7, 45), "rem"))))
        assertTrue(overlapped.totals["unknown"]!! > 0)
        assertEquals(day.end!! - day.start!!, overlapped.segments.sumOf { it.end - it.start })
        val allUnknown = SleepDay(date, listOf(SleepNight("unknown", at(1), at(2), listOf(SleepInterval(at(1), at(2), "unknown")))))
        assertNull(allUnknown.asleep); assertEquals(listOf("awake", "rem", "light", "deep", "unknown"), allUnknown.lanes)
        val awake = SleepDay(date, listOf(SleepNight("awake", at(1), at(2), listOf(SleepInterval(at(1), at(2), "awake")))))
        assertEquals(0.0, awake.asleep!!, 0.0)
    }
    @Test fun nativeInspectionCategoryHoldAndRapidDaySwipesKeepOneTimeline() {
        val initial = dense(); var value by mutableStateOf(initial)
        val dates = mutableListOf<LocalDate>()
        rule.setContent { MaterialTheme(colorScheme = darkColorScheme(), typography = OrbitTypography) {
            Box(Modifier.fillMaxSize().background(Color(0xFF0B0A0F))) {
                SleepScreen(value, false, null, false, { dates += it; value = initial.copy(date = it) }, {})
            }
        } }
        rule.onAllNodesWithContentDescription("Previous day").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Next day").assertCountEquals(1)
        val chart = rule.onNodeWithTag("sleep-chart")
        chart.performScrollTo()
        val before = chart.fetchSemanticsNode().boundsInRoot
        chart.performTouchInput { down(center); advanceEventTime(650); moveBy(Offset(55f, 0f)); up() }
        rule.onNodeWithTag("sleep-inspection").assertExists()
        assertTrue(dates.isEmpty())
        val after = chart.fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, after.top, .1f); assertEquals(before.height, after.height, .1f)
        rule.onNodeWithTag("sleep-stage-deep").performScrollTo().performClick().assertIsSelected()
        chart.performSemanticsAction(SemanticsActions.SetProgress) { it((at(14, 9) + 30000 - initial.start!!) / 60000f) }
        rule.onNodeWithText("Not recorded").assertExists()
        chart.performScrollTo(); save("sleep-native-inspection.png")
        val originalText = rule.onNodeWithTag("sleep-inspection").fetchSemanticsNode().config.toString()
        chart.performTouchInput { down(Offset(width * .2f, height * .4f)); moveBy(Offset(25f, 0f)); cancel() }
        assertEquals(originalText, rule.onNodeWithTag("sleep-inspection").fetchSemanticsNode().config.toString())
        rule.onNodeWithTag("sleep-summary").performScrollTo()
        val summary = rule.onNodeWithTag("sleep-summary")
        summary.performTouchInput { swipe(Offset(width * .8f, height * .7f), Offset(width * .2f, height * .7f), 160) }
        summary.performTouchInput { swipe(Offset(width * .8f, height * .7f), Offset(width * .2f, height * .7f), 160) }
        assertEquals(listOf(date.plusDays(1), date.plusDays(2)), dates)
        summary.performTouchInput { down(center); moveBy(Offset(100f, 0f)); cancel() }
        assertEquals(2, dates.size)
        rule.onNodeWithContentDescription("Previous day").performClick()
        assertEquals(date.plusDays(1), dates.last())
        save("sleep-native-dense.png")
    }

    @Test fun narrowLargeTextEmptyAndErrorViewsRemainUsable() {
        var loading by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var value by mutableStateOf(dense())
        var retries = 0
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme(colorScheme = darkColorScheme(), typography = OrbitTypography) {
                    Box(Modifier.width(320.dp).fillMaxHeight().background(Color(0xFF0B0A0F))) {
                        SleepScreen(value, loading, error, true, { value = value.copy(date = it) }, { retries++ })
                    }
                }
            }
        }
        rule.onNodeWithTag("sleep-stage-rem").performScrollTo().performClick().assertIsSelected()
        rule.onNodeWithTag("sleep-chart").performScrollTo(); save("sleep-native-large.png")
        rule.runOnIdle { value = SleepDay(date, emptyList(), date.minusDays(2)) }
        rule.onNodeWithTag("sleep-empty").assertExists()
        rule.onNodeWithTag("sleep-chart").assertDoesNotExist()
        rule.runOnIdle { loading = true }
        rule.onNodeWithTag("sleep-empty").assertDoesNotExist()
        rule.onNodeWithTag("sleep-loading").assertExists()
        rule.runOnIdle { loading = false; error = "Could not read saved sleep" }
        rule.onNodeWithText("Try again").performScrollTo().performClick()
        assertEquals(1, retries)
        rule.onNodeWithTag("sleep-empty").assertDoesNotExist()
    }

    @Test fun existingCommittedStoreAndClockChangesRetainActualDurations() {
        val path = File(rule.activity.cacheDir, "native-sleep-${System.nanoTime()}.db")
        try {
            HealthRecordStore(path).use { store ->
                val start = at(0) - 3600000; val end = at(7)
                val row = JSONObject().put("type", "sleep").put("id", "original").put("source", "com.sec.android.app.shealth")
                    .put("start", start).put("end", end).put("stages", JSONArray().put(JSONArray(listOf(start, end, 4))))
                store.beginImport(); store.stage(JSONArray().put(row)); store.finishImport(listOf("sleep"), start, end + 1, true)
            }
            val loaded = SleepDay.read(path, date)
            assertEquals(480.0, loaded.asleep!!, 0.0)
            assertEquals(date.minusDays(1), loaded.firstDate)
            assertEquals("original", loaded.nights.single().id)
            assertTrue(SleepDay.read(path, date.minusDays(1)).nights.isEmpty())
        } finally { path.delete(); File(path.path + "-journal").delete() }
        val before = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
            val zone = ZoneId.systemDefault(); val endDate = LocalDate.of(2025, 10, 26)
            val start = endDate.minusDays(1).atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
            val end = endDate.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
            val day = SleepDay(endDate, listOf(SleepNight("clock", start, end, listOf(SleepInterval(start, end, "light")))))
            assertEquals(540.0, day.asleep!!, 0.0)
            assertEquals(end - start, day.blocks.sumOf { it.end - it.start })
            assertTrue(day.ticks.zipWithNext().all { it.second > it.first })
        } finally { TimeZone.setDefault(before) }
    }
}
