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
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
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

@RunWith(AndroidJUnit4::class)
class HealthDashboardTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val date = LocalDate.of(2026, 9, 14)
    private val at get() = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private var layout by mutableStateOf(HealthCardLayout())
    private var failSave = false
    private val opened = mutableListOf<HealthCard>()
    private var writes = 0
    @Before fun emulatorOnly() { check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) }
    private fun fixture(): HealthScreenState {
        val day = HealthDay(date, steps = 8420.0, distance = 6320.0, heart = 80.0, heartAt = at + 28800000,
            heartLow = 58.0, heartHigh = 121.0, weight = 75.8, weightDate = date, oxygen = 98.0, oxygenLow = 96.0, oxygenHigh = 99.0,
            nutrition = 550.0, asleepMinutes = 420.0, meals = listOf(HealthMeal(at, "Lunch", 550.0, 20.0, 52.0, 14.0)), water = 650.0,
            measurements = MeasurementHistory(listOf(Reading(at, 75.8)), listOf(Reading(at, 18.2)), lean = listOf(Reading(at, 62.0))),
            nights = listOf(SleepNight("test-only", at - 3600000, at + 25200000, listOf(
                SleepInterval(at - 3600000, at, "awake"), SleepInterval(at, at + 7200000, "light"),
                SleepInterval(at + 7200000, at + 14400000, "deep"), SleepInterval(at + 14400000, at + 25200000, "rem")))))
        return HealthScreenState(day, loading = false, days = mapOf(date.minusDays(2) to HealthDay(date.minusDays(2), oxygen = 96.0)))
    }
    private fun show(width: Int = 390, font: Float = 1f, state: HealthScreenState = fixture()) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                MaterialTheme(colorScheme = darkColorScheme(), typography = OrbitTypography) {
                    Column(Modifier.width(width.dp).fillMaxHeight().background(Color(0xFF0B0A0F))) {
                        Text("Health", color = Color(0xFFF7F2FC), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(20.dp))
                        HealthDashboard(state, layout, 10000, false, { id ->
                            writes++; if (failSave) false else { layout = layout.copy(wide = if (id in layout.wide) layout.wide - id else layout.wide + id); true }
                        }, { order -> writes++; if (failSave) false else { layout = layout.copy(order = order); true } }, { opened += it }, {})
                    }
                }
            }
        }
        rule.waitForIdle()
    }
    private fun save(name: String) = rule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
        File(rule.activity.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun card(id: HealthCard) = rule.onNodeWithTag("health-card-${id.key}")
    private fun reveal(id: HealthCard) {
        card(id).performScrollTo()
        card(id).performTouchInput { longClick(center, 650) }
        rule.onNodeWithTag("health-resize-${id.key}").assertIsDisplayed()
        assertTrue(opened.isEmpty())
    }

    @Test fun sixIndividualSizesHoldOnlyAndFailedSavesRestoreTheSavedLayout() {
        show(); save("health-native-overview.png")
        for (id in HealthCard.entries) {
            val before = layout
            reveal(id)
            rule.onNodeWithTag("health-resize-${id.key}").performTouchInput { click() }
            rule.waitForIdle()
            assertEquals(before.wide - id, layout.wide - id)
            assertEquals(id !in before.wide, id in layout.wide)
            rule.onNodeWithTag("health-resize-${id.key}").performTouchInput { click() }
            rule.waitForIdle(); assertEquals(before, layout)
        }
        failSave = true
        reveal(HealthCard.Body)
        val before = card(HealthCard.Body).fetchSemanticsNode().boundsInRoot.width
        rule.onNodeWithTag("health-resize-body").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(before, card(HealthCard.Body).fetchSemanticsNode().boundsInRoot.width, 1f)
        assertTrue(opened.isEmpty()); assertEquals(13, writes)
        save("health-native-held-measurements.png")
    }

    @Test fun heldDragShufflesNeighboursThenCancelRestoresWithoutNavigation() {
        show()
        val grid = rule.onNodeWithTag("health-grid")
        val origin = grid.fetchSemanticsNode().boundsInRoot.topLeft
        val from = card(HealthCard.Steps).fetchSemanticsNode().boundsInRoot.center - origin
        val to = card(HealthCard.Heart).fetchSemanticsNode().boundsInRoot.center - origin
        grid.performTouchInput { down(from); advanceEventTime(650); moveTo(from + Offset(18f, 0f), 16) }
        rule.waitForIdle()
        grid.performTouchInput { moveTo(to, 160) }
        rule.waitForIdle()
        rule.onNodeWithTag("health-drag-layer").assertExists()
        save("health-native-drag.png")
        grid.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(listOf(HealthCard.Sleep, HealthCard.Heart, HealthCard.Steps, HealthCard.Body, HealthCard.Intake, HealthCard.Oxygen), layout.order)
        assertTrue(opened.isEmpty())
        val saved = layout
        val a = card(HealthCard.Heart).fetchSemanticsNode().boundsInRoot.center - origin
        val b = card(HealthCard.Steps).fetchSemanticsNode().boundsInRoot.center - origin
        grid.performTouchInput { down(a); advanceEventTime(650); moveTo(a + Offset(18f, 0f), 16) }
        rule.waitForIdle()
        grid.performTouchInput { moveTo(b, 160) }
        rule.waitForIdle()
        grid.performTouchInput { cancel() }
        rule.waitForIdle()
        assertEquals(saved, layout); assertEquals(1, writes)
        rule.onNodeWithTag("health-drag-layer").assertDoesNotExist()
    }

    @Test fun oxygenIsACompactWeekAndLargeTextKeepsEveryControlReachable() {
        show(320, 1.5f)
        assertEquals(card(HealthCard.Body).fetchSemanticsNode().size.height, card(HealthCard.Intake).fetchSemanticsNode().size.height)
        card(HealthCard.Oxygen).performScrollTo()
        card(HealthCard.Oxygen).performTouchInput { click(center) }
        rule.onNodeWithTag("health-oxygen-week").assertExists()
        rule.onNodeWithTag("health-source").performScrollTo()
        rule.onNodeWithTag("health-oxygen-chart").performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }
        rule.onNodeWithText("No reading", useUnmergedTree = true).assertExists()
        save("health-native-oxygen-large.png")
        assertEquals(HealthCardLayout(), layout)
        assertTrue(opened.isEmpty())
        card(HealthCard.Oxygen).performScrollTo()
        card(HealthCard.Oxygen).performTouchInput { longClick(Offset(width * .4f, 30f), 650) }
        rule.onNodeWithTag("health-resize-oxygen").performTouchInput { click() }
        rule.waitForIdle()
        rule.onNodeWithTag("health-oxygen-week").assertDoesNotExist()
        assertFalse(HealthCard.Oxygen in layout.wide)
    }

    @Test fun shuffleAndResizeHaveIntermediatePosesAndEdgeDragScrolls() {
        show()
        val grid = rule.onNodeWithTag("health-grid")
        val origin = grid.fetchSemanticsNode().boundsInRoot.topLeft
        val from = card(HealthCard.Steps).fetchSemanticsNode().boundsInRoot.center - origin
        val heartBefore = card(HealthCard.Heart).fetchSemanticsNode().boundsInRoot
        val target = heartBefore.center - origin
        grid.performTouchInput { down(from); advanceEventTime(650); moveTo(from + Offset(18f, 0f), 16) }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        grid.performTouchInput { moveTo(target, 120) }
        rule.mainClock.advanceTimeBy(80)
        val moving = card(HealthCard.Heart).fetchSemanticsNode().boundsInRoot
        assertTrue("Neighbour must move, not jump", moving.left < heartBefore.left - 1 && moving.left > 30)
        save("health-native-shuffle-80ms.png")
        grid.performTouchInput { cancel() }
        rule.mainClock.autoAdvance = true; rule.waitForIdle()
        assertEquals(HealthCardLayout(), layout)

        reveal(HealthCard.Steps)
        val compact = card(HealthCard.Steps).fetchSemanticsNode().boundsInRoot.width
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("health-resize-steps").performTouchInput { click() }
        rule.mainClock.advanceTimeBy(96)
        val inBetween = card(HealthCard.Steps).fetchSemanticsNode().boundsInRoot.width
        assertTrue("Width must interpolate", inBetween > compact && inBetween < compact * 2)
        save("health-native-resize-96ms.png")
        rule.mainClock.autoAdvance = true; rule.waitForIdle()

        card(HealthCard.Oxygen).performScrollTo()
        val oldY = card(HealthCard.Sleep).fetchSemanticsNode().positionInRoot.y
        val bottom = card(HealthCard.Oxygen).fetchSemanticsNode().boundsInRoot.center - origin
        grid.performTouchInput { down(bottom); advanceEventTime(650); moveTo(bottom - Offset(0f, 20f), 16) }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        grid.performTouchInput { moveTo(Offset(bottom.x, 12f), 180) }
        rule.mainClock.advanceTimeBy(400)
        val newY = card(HealthCard.Sleep).fetchSemanticsNode().positionInRoot.y
        assertTrue("Held card must scroll at the edge", newY > oldY + 20)
        grid.performTouchInput { cancel() }
        rule.mainClock.autoAdvance = true; rule.waitForIdle()
        assertEquals(HealthCard.entries.toList(), layout.order)
        assertTrue(opened.isEmpty())
    }

    @Test fun scrollingBeforeHoldAndFailedDragNeverChangeSavedOrder() {
        show()
        val grid = rule.onNodeWithTag("health-grid")
        grid.performTouchInput { swipeUp(durationMillis = 180) }
        rule.onAllNodes(hasTestTag("health-resize-sleep") or hasTestTag("health-resize-steps") or hasTestTag("health-resize-heart")).assertCountEquals(0)
        assertTrue(opened.isEmpty()); assertEquals(0, writes)
        card(HealthCard.Sleep).performScrollTo()
        failSave = true
        val origin = grid.fetchSemanticsNode().boundsInRoot.topLeft
        val from = card(HealthCard.Steps).fetchSemanticsNode().boundsInRoot.center - origin
        val to = card(HealthCard.Heart).fetchSemanticsNode().boundsInRoot.center - origin
        grid.performTouchInput { down(from); advanceEventTime(650); moveTo(from + Offset(18f, 0f), 16) }
        rule.waitForIdle()
        grid.performTouchInput { moveTo(to, 140) }
        rule.waitForIdle()
        grid.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(HealthCardLayout(), layout)
        assertTrue(card(HealthCard.Steps).fetchSemanticsNode().boundsInRoot.left < card(HealthCard.Heart).fetchSemanticsNode().boundsInRoot.left)
        assertTrue(opened.isEmpty()); assertEquals(1, writes)
    }

    @Test fun originalSleepGapsOxygenRangesAndExistingPreferenceKeysStayIntact() {
        val source = "com.sec.android.app.shealth"
        val rows = JSONArray().put(JSONObject().put("type", "oxygen").put("id", "first").put("source", source).put("start", at).put("end", at).put("value", 96))
            .put(JSONObject().put("type", "oxygen").put("id", "second").put("source", source).put("start", at + 1000).put("end", at + 1000).put("value", 98))
        val day = NativeHealthProjection.read(JSONObject().put("schema", 1).put("rows", rows), date)
        assertEquals(96.0, day.oxygenLow!!, 0.0); assertEquals(98.0, day.oxygenHigh!!, 0.0); assertEquals(98.0, day.oxygen!!, 0.0)
        val original = listOf(SleepNight("a", at, at + 600000, listOf(SleepInterval(at, at + 600000, "light"))),
            SleepNight("b", at + 3600000, at + 4200000, listOf(SleepInterval(at + 3600000, at + 3660000, "unknown"), SleepInterval(at + 3660000, at + 4200000, "rem"))))
        val blocks = SleepTimeline.blocks(SleepTimeline.merge(original))
        assertEquals(SleepInterval(at + 600000, at + 3600000, "unrecorded"), blocks.first { it.stage == "unrecorded" })
        assertEquals(60000L, blocks.filter { it.stage == "unknown" }.sumOf { it.end - it.start })
        assertEquals(19.0, SleepTimeline.totals(original).filterKeys { it == "light" || it == "rem" }.values.sum(), 0.0)
        assertThrows(IllegalArgumentException::class.java) { HealthCardLayout.sizes("{\"steps\":1}") }
        assertThrows(IllegalArgumentException::class.java) { HealthCardLayout.order("[\"steps\",\"steps\"]") }
        assertEquals(setOf(HealthCard.Oxygen), HealthCardLayout.sizes("{\"sleep\":false}"))
        val preferences = AppPreferences(rule.activity)
        val oldSize = preferences.read(HealthCardLayout.SIZE_KEY); val oldOrder = preferences.read(HealthCardLayout.ORDER_KEY)
        try {
            val saved = HealthCardLayout(HealthCard.entries.reversed(), setOf(HealthCard.Body))
            assertTrue(preferences.write(HealthCardLayout.SIZE_KEY, saved.sizeJson())); assertTrue(preferences.write(HealthCardLayout.ORDER_KEY, saved.orderJson()))
            val model = OrbitModel(rule.activity.application, SavedStateHandle())
            rule.waitUntil(5000) { model.cardLayout.value != null }
            assertEquals(saved, model.cardLayout.value)
            runBlocking { assertTrue(model.resizeCard(HealthCard.Heart)); assertTrue(model.moveCards(HealthCard.entries.toList())) }
            assertEquals(saved.wide + HealthCard.Heart, HealthCardLayout.sizes(preferences.read(HealthCardLayout.SIZE_KEY)))
            assertEquals(HealthCard.entries.toList(), HealthCardLayout.order(preferences.read(HealthCardLayout.ORDER_KEY)))
        } finally {
            rule.activity.getSharedPreferences("orbit-settings", 0).edit().apply {
                if (oldSize == null) remove(HealthCardLayout.SIZE_KEY) else putString(HealthCardLayout.SIZE_KEY, oldSize)
                if (oldOrder == null) remove(HealthCardLayout.ORDER_KEY) else putString(HealthCardLayout.ORDER_KEY, oldOrder)
            }.commit()
        }
    }
}
