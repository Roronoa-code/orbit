package com.mani.orbit

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class WorkoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val today = LocalDate.now()
    private fun at(day: LocalDate) = day.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun original(id: String = "workout", day: LocalDate = today) = JSONObject().put("id", id).put("source", "com.sec.android.app.shealth")
        .put("type", "exercise").put("kind", "Running").put("start", at(day)).put("end", at(day) + 1234000)
        .put("summary", JSONObject().put("distance", 2400).put("energy", 180).put("heartAverage", 117))
        .put("laps", JSONArray().put(JSONObject().put("start", at(day)).put("end", at(day) + 600000).put("distanceM", 1200)))
    private fun save(name: String) { java.io.File(rule.activity.cacheDir, name).outputStream().use {
        rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
    } }
    private fun content(large: Boolean = false, block: @Composable () -> Unit) {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = OrbitTypography) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.5f else 1f)) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF0B0A0F))) {
                        Box(Modifier.width(if (large) 320.dp else 390.dp).fillMaxHeight().safeDrawingPadding()) { block() }
                    }
                }
            }
        }
    }

    @Test fun originalIntervalsMissingValuesAndRouteGapsStayTruthful() {
        val item = WorkoutData.imported(JSONArray().put(original())).single()
        assertEquals(1234000L, item.elapsed)
        assertEquals(item.elapsed, item.total)
        assertEquals(180.0, item.energy!!, 0.0)
        assertFalse(item.summary.containsKey("steps"))
        assertEquals(1, item.laps.size)
        assertThrows(IllegalArgumentException::class.java) { WorkoutData.imported(JSONArray().put(original().put("source", "other"))) }
        assertThrows(IllegalArgumentException::class.java) { WorkoutData.imported(JSONArray().put(original()).put(original())) }
        assertThrows(IllegalArgumentException::class.java) { WorkoutData.imported(JSONArray().put(original().put("summary", JSONObject().put("distance", -1)))) }
        assertThrows(IllegalArgumentException::class.java) { WorkoutData.imported(JSONArray().put(original().put("laps", JSONArray().put(JSONObject().put("start", 1).put("end", 2))))) }
        val stationary = JSONObject().put("kind", "Walking").put("startedAt", at(today)).put("endedAt", at(today) + 120000)
            .put("elapsed", 90000).put("totalMs", 120000).put("weightKg", 84).put("targetMs", 90000).put("trackLocation", true)
            .put("metrics", JSONObject().put("state", "finished").put("distanceM", 0).put("maxSpeedMps", 0).put("points", JSONArray()))
        val local = WorkoutData.local(stationary)
        assertNull(local.distance); assertNull(local.averageSpeed); assertNotNull(local.energy)
        assertEquals(90000L, local.target) // Existing backend accepts exact milliseconds, including older non-minute targets.
        val clockChanged = WorkoutData.local(JSONObject(stationary.toString()).put("endedAt", at(today) - 1000))
        assertEquals(90000L, clockChanged.elapsed); assertEquals(120000L, clockChanged.total)
        val points = listOf(WorkoutPoint(0.0, 179.999, 0, 8.0, 1.0, true), WorkoutPoint(0.0, -179.999, 1000, 9.0, 2.0, false),
            WorkoutPoint(.001, -179.998, 2000, null, null, true), WorkoutPoint(.002, -179.997, 3000, 10.0, 2.0, false))
        val route = workoutPlot(points, 0, 3000)
        assertEquals(2, route.count { it == null })
        assertTrue(route.filterNotNull().all { it.x.isFinite() && it.y.isFinite() && it.x in 20f..300f })
        assertTrue(workoutPlot(points, 1, 3000).any { it == null })
        assertEquals("1:01:01", workoutClock(3661000))
        val data = JSONObject().put("schema", 1).put("rows", JSONArray()).put("workouts", JSONArray().put(original()))
        assertEquals(item, NativeHealthProjection.project(data, today).workouts.single())
    }

    @Test fun setupHasNoWeightOrMusicAndPreservesDraftOnFailedStartAndBack() {
        var back: () -> Boolean = { false }
        var title = ""
        var sent: Pair<Int, Boolean>? = null
        var state by mutableStateOf(NativeWorkoutState(loading = false))
        content { WorkoutScreen(state, emptyList(), true, { action, _, minutes, gps -> if (action == "start") {
            sent = minutes to gps; state = state.copy(actionError = "Save failed")
        } }) { label, _, handler -> title = label; back = handler } }
        rule.onNodeWithTag("choose-Running").performClick()
        rule.waitForIdle(); assertEquals("Running", title)
        rule.onNodeWithText("Weight").assertDoesNotExist(); rule.onNodeWithText("Music", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Time").performClick()
        rule.onNodeWithTag("workout-minutes").performTextReplacement("0")
        rule.onNodeWithTag("workout-start").assertIsNotEnabled()
        rule.onNodeWithTag("workout-minutes").performTextReplacement("45")
        rule.onNodeWithText("Track outdoors").performClick()
        rule.onNodeWithTag("workout-start").performClick()
        rule.waitForIdle(); assertEquals(45 to false, sent)
        rule.onNodeWithText("Save failed").assertExists()
        rule.onNodeWithTag("workout-minutes").assertTextEquals("45")
        save("workout-setup-native.png")
        rule.runOnIdle { assertTrue(back()) }
        rule.onNodeWithTag("choose-Strength").performClick()
        rule.onNodeWithText("Track outdoors").assertDoesNotExist()
        rule.onNodeWithText("No target").assertIsSelected()
    }

    @Test fun compactWeekDragRecordAndReturnKeepSelectionAtLargeText() {
        val records = WorkoutData.imported(JSONArray().put(original("latest")).put(original("older", today.minusWeeks(1))))
        var selected: String? = null
        content(large = true) { WorkoutHub(records, true, null, {}, { selected = it }) }
        rule.onNode(hasText("History") and hasAnyAncestor(hasTestTag("workout-tabs"))).performClick()
        rule.onNodeWithTag("workout-days").assertExists()
        rule.onNodeWithContentDescription("Previous week").performClick()
        rule.onNodeWithTag("workout-record-samsung:older").performScrollTo().assertExists()
        save("workout-history-large.png")
        rule.onNodeWithTag("workout-record-samsung:older").performClick()
        rule.runOnIdle { assertEquals("samsung:older", selected) }
        rule.onNodeWithTag("workout-days").performScrollTo().performTouchInput { swipe(centerRight, centerLeft, 360) }
        val monday = workoutWeek(today.minusWeeks(1))
        rule.onNodeWithContentDescription("${monday.format(WorkoutDate)}, ${records.count { it.date() == monday }} workouts").assertIsSelected()
        rule.onNodeWithContentDescription("Next week").performClick()
        rule.onNodeWithContentDescription("Next week").assertIsNotEnabled()
    }

    @Test fun importedDetailsNeverInventRouteOrCaloriesAndLocalChartsSwitch() {
        var record by mutableStateOf(WorkoutData.imported(JSONArray().put(original().put("hasRoute", true))).single())
        content { WorkoutRecordScreen(record, false, null, {}) }
        rule.onNodeWithText("Session duration").assertExists()
        rule.onNodeWithText("Recorded during this session").assertExists()
        rule.onNodeWithText("No route", substring = true).assertDoesNotExist()
        rule.onNodeWithText("A route exists", substring = true).performScrollTo().assertExists()
        save("workout-samsung-record.png")
        rule.runOnIdle { record = WorkoutRecord("orbit:1", "Walking", at(today), at(today) + 30000, 30000, 30000,
            tracking = true, points = listOf(WorkoutPoint(51.0, -.1, 0, 5.0, 1.0, true), WorkoutPoint(51.001, -.101, 30000, 6.0, 1.2, false)), distance = 140.0) }
        rule.onNodeWithTag("workout-chart").performScrollTo()
        rule.onNodeWithText("Speed").performClick()
        rule.onNodeWithContentDescription("Speed in kilometres per hour over active time").assertExists()
        rule.onNodeWithText("Elevation").performClick()
        rule.onNodeWithContentDescription("Elevation in metres over active time").assertExists()
        save("workout-local-route-detail.png")
    }

    @Test fun finishMovesDirectlyToSavedDetailsWithoutAnIntermediateChooser() {
        val active = WorkoutRecord("orbit:${at(today)}", "Running", at(today), null, 60000, 62000)
        var state by mutableStateOf(NativeWorkoutState(active = active, loading = false))
        val titles = mutableListOf<String>()
        content { WorkoutScreen(state, emptyList(), true, { _, _, _, _ -> }) { title, _, _ -> titles += title } }
        rule.waitForIdle()
        rule.runOnIdle { titles.clear(); state = state.copy(active = null, history = listOf(active.copy(end = at(today) + 62000))) }
        rule.waitForIdle()
        assertFalse(titles.contains("Workouts"))
        rule.onNodeWithText("Workout saved", substring = true).assertExists()
        rule.onNodeWithTag("choose-Running").assertDoesNotExist()
    }
}
