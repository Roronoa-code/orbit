package com.mani.orbit

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
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
class HomeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val date = LocalDate.of(2026, 9, 14)
    private fun start(day: LocalDate) = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun row(type: String, day: LocalDate, value: Double? = null, suffix: String = "") = JSONObject()
        .put("type", type).put("id", "$type-$day-$suffix").put("source", "com.sec.android.app.shealth")
        .put("start", start(day)).put("end", start(day)).also { if (value != null) it.put("value", value) }
    private fun heart(day: LocalDate, hour: Int, count: Int, mean: Double) = JSONObject().put("type", "heartHour")
        .put("source", "com.sec.android.app.shealth").put("date", day.toString()).put("hour", hour)
        .put("count", count).put("sum", count * mean).put("low", mean).put("high", mean).put("latest", mean)
        .put("latestTime", start(day) + hour * 3600000)
    private fun data(rows: JSONArray) = JSONObject().put("schema", 1).put("date", date.toString()).put("rows", rows)

    @Before fun emulatorOnly() { check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) }

    private fun save(name: String) = rule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
        File(rule.activity.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun allDaysWeightedHeartSleepOverlapAndLiveReplacementKeepTheirMeaning() {
        val at = start(date)
        val rows = JSONArray().put(row("stepsDay", date, 1200.0)).put(row("stepsDay", date.minusDays(2), 0.0))
            .put(heart(date, 8, 20, 80.0)).put(heart(date.minusDays(1), 8, 1, 120.0))
            .put(row("nutrition", date).put("calories", 400).put("protein", 25).put("name", "Lunch"))
            .put(row("water", date, 500.0))
            .put(row("sleep", date).put("end", at + 900000).put("stages", JSONArray()
                .put(JSONArray(listOf(at, at + 600000, 4))).put(JSONArray(listOf(at + 600000, at + 900000, 1)))))
            .put(row("sleep", date, suffix = "overlap").put("start", at + 300000).put("end", at + 900000)
                .put("stages", JSONArray().put(JSONArray(listOf(at + 300000, at + 600000, 1))).put(JSONArray(listOf(at + 600000, at + 900000, 3)))))
        val result = NativeHealthProjection.project(data(rows), date)
        assertEquals(3, result.days.size)
        assertEquals(5.0, result.day.asleepMinutes!!, 0.0)
        assertEquals(5.0, result.day.sleepStages["awake"]!!, 0.0)
        assertTrue(result.day.sleepIncomplete)
        assertEquals(2, result.day.nights.size)
        assertEquals(80.0, result.day.hourlyHeart.single().value, 0.0)
        val state = HealthScreenState(result.day.copy(steps = 1300.0), loading = false, days = result.days)
        val steps = HomeSummary.from(state, HomeMetric.Steps, 7, 10000)
        assertEquals("1,300", steps.primary)
        assertEquals(2, steps.comparison.currentDays)
        assertEquals(650.0, steps.comparison.current!!, 0.0)
        assertNull(steps.comparison.previous)
        val pulse = HomeSummary.from(state, HomeMetric.Heart, 7, 10000)
        assertEquals("82", pulse.primary) // 1720/21, not an average of two daily means (100).
        // Resting is each day's calmest hour, averaged over the days that have one: (80 + 120) / 2.
        assertEquals(HomeFact("Resting", "100", "bpm"), pulse.facts.last())
        val food = HomeSummary.from(state, HomeMetric.Intake, 30, 10000)
        assertEquals("400", food.primary)
        assertEquals("25", food.facts.first().value)
        assertEquals("0.5", food.facts.last().value)
        assertEquals(0.0, steps.week[4].value!!, 0.0)
        assertNull(steps.week[3].value)
    }

    @Test fun dstHoursAndInvalidOriginalsAreNotInventedOrSilentlyAccepted() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
            val springDate = LocalDate.of(2026, 3, 29)
            val state = HealthScreenState(day = HealthDay(springDate), loading = false)
            val hours = HomeSummary.from(state, HomeMetric.Steps, 1, 10000).movement
            assertEquals(23, hours.size)
            assertFalse(hours.any { it.label == "01:00" })
            assertTrue(hours.all { it.value == null })
            val live = HomeSummary.from(state.copy(liveStepsAt = start(springDate) + 7200000), HomeMetric.Steps, 1, 10000)
            assertEquals(3, live.movement.count { it.value == 0.0 })
        } finally { TimeZone.setDefault(previous) }
        val duplicate = JSONArray().put(row("stepsDay", date, 100.0, "a")).put(row("stepsDay", date, 200.0, "b"))
        assertThrows(IllegalArgumentException::class.java) { NativeHealthProjection.project(data(duplicate), date) }
        assertThrows(IllegalArgumentException::class.java) { NativeHealthProjection.project(data(JSONArray().put(heart(date, 24, 1, 80.0))), date) }
        assertThrows(IllegalArgumentException::class.java) { NativeHealthProjection.project(data(JSONArray().put(row("nutrition", date).put("protein", -1))), date) }
        assertThrows(IllegalArgumentException::class.java) { NativeHealthProjection.project(data(JSONArray().put(row("stepsDay", date).put("value", "100"))), date) }
        assertThrows(IllegalArgumentException::class.java) { NativeHealthProjection.project(data(JSONArray().put(row("sleep", date).put("end", start(date) + 60000)
            .put("stages", JSONArray().put(JSONArray(listOf(start(date), start(date) + 60000, 4.5)))))), date) }
    }

    /**
     * The deck and the floating bar are one material, so over the same page they are one shade.
     *
     * They sample different recordings by necessity — a card cannot sample the recording it is drawn
     * into — and that is exactly how they drifted apart. A folded deck sits below the globe, so when
     * the globe's recording stopped where the globe stopped, every folded card sampled the edge of
     * that recording rather than the page and came out lighter than the bar.
     */
    @Test fun theFoldedDeckAndTheFloatingBarAreOneShadeOverOnePage() {
        val state = HealthScreenState(day = HealthDay(date, steps = 4000.0, distance = 3200.0, floors = 2.0, energy = 200.0),
            loading = false, days = mapOf(date to HealthDay(date, steps = 4000.0)))
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = OrbitTypography) {
                val layer = rememberGlassBackdrop()
                Box(Modifier.width(411.dp).fillMaxHeight().background(Color(0xFF0B0A0F)).safeDrawingPadding()) {
                    Box(Modifier.fillMaxSize().recordBackdrop(layer)) {
                        HomeScreen(state, HomeMetric.Steps, 1, 10000, false, false, {}, {}, {}, {})
                    }
                    ExploreIsland("Steps", NativeWorkoutState(), false, {}, {}, { _, _, _, _ -> },
                        layer, Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth())
                }
            }
        }
        rule.waitForIdle()
        val root = rule.onRoot().captureToImage().asAndroidBitmap()
        val origin = rule.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
        val density = rule.activity.resources.displayMetrics.density
        // The folded deck clears its cards' semantics, so the front card is measured from the deck.
        val deck = rule.onNodeWithTag("home-deck").fetchSemanticsNode().boundsInRoot.translate(-origin)
        val bar = rule.onNodeWithTag("explore-bar").fetchSemanticsNode().boundsInRoot.translate(-origin)
        val card = root.getPixel(deck.center.x.toInt(), (deck.top + 80 * density).toInt())
        val island = root.getPixel(bar.center.x.toInt(), (bar.top + bar.height * .5f).toInt())
        fun channel(pixel: Int, shift: Int) = pixel shr shift and 255
        val drift = listOf(16, 8, 0).maxOf { kotlin.math.abs(channel(card, it) - channel(island, it)) }
        save("one-shade.png")
        assertTrue("The folded deck and the bar must be one shade: card=$card bar=$island drift=$drift", drift <= 4)
    }

    /**
     * The storm flows round the number and never through it, it is never still, and its purple light
     * comes and goes: strikes light the cloud and die away.
     */
    @Test fun theStormKeepsTheNumbersCentreClearFlowsAndFlashes() {
        val storm = Storm.load(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.assets)
        val radii = FloatArray(storm.count)
        val start = storm.shown.dotMesh.copyOf()
        var drawn = 0
        var t = 0.0
        while (t < 3.0) {
            t += 1 / 60.0
            storm.advance(t)
            storm.build(t, storm.shown, radii)
            for (r in radii) if (r >= 0) {
                drawn++
                assertTrue("A particle entered the number's centre: $r", r >= RingClearRadius)
                assertTrue("A particle left the storm: $r", r <= 246f)  // the reference's own outermost dots reach 241.6
            }
        }
        assertTrue(drawn > storm.count)
        val moved = start.indices.count { kotlin.math.abs(start[it] - storm.shown.dotMesh[it]) > .5f }
        assertTrue("The storm stood still: $moved of ${start.size}", moved > start.size / 2)
        val light = FloatArray(LightSteps); val at = DoubleArray(1)
        var brightest = 0.0; var darkest = Double.MAX_VALUE
        var s = 0.0
        while (s < 12.0) {
            val peak = stormLight(s, light, at)
            brightest = maxOf(brightest, peak)
            if (peak == 0.0) darkest = minOf(darkest, light.max().toDouble())
            for (v in light) assertTrue(v.isFinite() && v >= 0f && v <= 1.25f)
            s += .05
        }
        assertTrue("No strike lit the storm: $brightest", brightest > .6)
        assertTrue("Between strikes the cloud stays dark: $darkest", darkest < .3)
    }

    @Test fun cardsReverseScrollAndExploreStaysOpenAboveThem() {
        val days = (0..13).associate { i -> date.minusDays(i.toLong()).let { it to HealthDay(it, steps = 4000.0 + i * 250, distance = 3200.0, floors = 2.0, energy = 200.0) } }
        val hourly = List(24) { hour -> Reading(start(date) + hour * 3600000, listOf(100, 200, 300, 600, 1200, 1000, 600).getOrNull(hour - 8)?.toDouble() ?: 0.0) }
        val state = HealthScreenState(day = days.getValue(date).copy(hourlySteps = hourly, heart = 80.0, heartSum = 160.0, heartCount = 2,
            heartLow = 70.0, heartHigh = 90.0, hourlyHeart = listOf(Reading(start(date) + 8 * 3600000, 80.0)),
            asleepMinutes = 420.0, sleepStages = mapOf("light" to 210.0, "deep" to 90.0, "rem" to 120.0, "awake" to 30.0),
            nutrition = 500.0, meals = listOf(HealthMeal(start(date), "Breakfast", 500.0, 20.0, 60.0, 15.0))), loading = false, days = days)
        val metric = mutableStateOf(HomeMetric.Steps)
        val period = mutableIntStateOf(1)
        val explore = mutableStateOf(false)
        val large = mutableStateOf(false)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large.value) 1.5f else 1f)) {
            MaterialTheme(colorScheme = darkColorScheme(), typography = OrbitTypography) {
                val layer = rememberGlassBackdrop()
                Box(Modifier.width(if (large.value) 320.dp else 411.dp).fillMaxHeight().background(Color(0xFF0B0A0F)).safeDrawingPadding()) {
                    Box(Modifier.fillMaxSize().recordBackdrop(layer)) {
                        HomeScreen(state, metric.value, period.intValue, 10000, false, explore.value, { explore.value = false },
                            { metric.value = it }, { period.intValue = if (period.intValue == 1) 7 else 1 }, {})
                    }
                    ExploreIsland("Steps", NativeWorkoutState(), explore.value, { explore.value = it }, {}, { _, _, _, _ -> },
                        layer, Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth())
                }
            }
            }
        }
        save("home-folded.png")
        rule.onNodeWithTag("expand-home-cards").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("home-movement-chart").assertIsDisplayed()
        val facts = rule.onNodeWithTag("home-card-0").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("explore-bar").performTouchInput { swipe(center, center + Offset(0f, -240f), 400) }
        rule.waitForIdle(); assertTrue(explore.value)
        assertEquals(facts, rule.onNodeWithTag("home-card-0").fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithText("Health").assertIsDisplayed()
        save("home-stack-explore.png")
        rule.onNodeWithTag("explore-bar").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("home-scene").performTouchInput { swipe(Offset(centerX, height * .42f), Offset(centerX, height * .15f), 350) }
        rule.waitForIdle()
        rule.onNodeWithText("Daily average comparison").assertIsDisplayed()
        save("home-scrolled.png")
        rule.onNodeWithTag("home-scene").performTouchInput { swipe(Offset(centerX, height * .4f), Offset(centerX, height * .85f), 450) }
        rule.waitForIdle()
        rule.onNodeWithTag("home-scene").performTouchInput { swipe(Offset(centerX, height * .4f), Offset(centerX, height * .8f), 400) }
        rule.waitForIdle()
        rule.onNodeWithTag("expand-home-cards").assertExists()
        rule.onNodeWithTag("home-orb").performTouchInput { swipe(center, center + Offset(-180f, 0f), 300) }
        rule.waitForIdle(); assertEquals(HomeMetric.Heart, metric.value)
        rule.onNodeWithTag("home-orb").performClick(); rule.waitForIdle(); assertEquals(7, period.intValue)
        rule.onNodeWithTag("expand-home-cards").performTouchInput { down(center); moveBy(Offset(0f, -130f), 150); cancel() }
        rule.waitForIdle(); rule.onNodeWithTag("expand-home-cards").assertExists()
        // Reversing in flight must preserve the current pose, then return to a usable folded state.
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("expand-home-cards").performClick()
        rule.mainClock.advanceTimeBy(80)
        save("home-opening-80ms.png")
        rule.onNodeWithTag("home-scene").performTouchInput { down(Offset(centerX, height * .3f)); moveBy(Offset(0f, 120f), 150); up() }
        rule.mainClock.autoAdvance = true; rule.waitForIdle()
        rule.onNodeWithTag("expand-home-cards").assertExists()
        rule.runOnIdle { large.value = true; period.intValue = 30 }
        for (kind in HomeMetric.entries) {
            rule.runOnIdle { metric.value = kind }
            rule.onNodeWithTag("expand-home-cards").performClick(); rule.waitForIdle()
            rule.onNodeWithTag("home-movement-chart").assertIsDisplayed()
            save("home-large-${kind.name.lowercase()}.png")
            rule.onNodeWithTag("home-scene").performTouchInput { swipe(Offset(centerX, height * .4f), Offset(centerX, height * .8f), 400) }
            rule.waitForIdle(); rule.onNodeWithTag("expand-home-cards").assertExists()
        }
    }
}
