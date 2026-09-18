package com.mani.orbit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExploreIslandTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val expanded = mutableStateOf(false)
    private val route = mutableStateOf("Steps")
    private val session = mutableStateOf(NativeWorkoutState())
    private val pageColor = mutableStateOf(Color(0xFF352B4B))
    private val readability = mutableStateOf(GlassReadability())
    private val reduced = mutableStateOf(false)
    private val quality = mutableStateOf(GlassQualityState(GlassQuality.OPTICAL))
    private val backdropOffset = mutableStateOf<Float?>(null)
    private val selected = mutableListOf<String>()
    private val actions = mutableListOf<String>()
    private val opened = mutableListOf<String>()

    @Before fun emulatorOnly() { check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) }

    private fun show(large: Boolean = false) {
        rule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, if (large) 1.5f else 1f),
                LocalGlassReadability provides readability.value, LocalOrbitReducedMotion provides reduced.value,
                LocalGlassQuality provides quality.value) {
                MaterialTheme(colorScheme = darkColorScheme(), typography = OrbitTypography) {
                    val layer = rememberGlassBackdrop()
                    Box(Modifier.fillMaxSize().background(Color(0xFF0B0A0F)).safeDrawingPadding()) {
                        Canvas(Modifier.fillMaxSize().testTag("underlying-page").recordBackdrop(layer)) {
                            drawRect(Color(0xFF0B0A0F))
                            drawRoundRect(pageColor.value, Offset(24.dp.toPx(), size.height - 250.dp.toPx()),
                                Size(size.width - 48.dp.toPx(), 230.dp.toPx()))
                            backdropOffset.value?.let { offset ->
                                val pitch = 32.dp.toPx()
                                for (i in -2..(size.width / pitch).toInt() + 2) {
                                    drawRect(if (i % 2 == 0) Color.White else Color.Black,
                                        Offset(i * pitch + offset, 0f), Size(pitch, size.height))
                                }
                                for (i in -1..(size.height / pitch).toInt() + 1) drawLine(Color(0xFFED2A99),
                                    Offset(0f, i * pitch + offset), Offset(size.width, i * pitch + offset), 2.dp.toPx())
                            }
                        }
                        if (backdropOffset.value != null) OrbitHeader("Steps", "15 Sept 2026", true, {}, {}, {}, layer)
                        ExploreIsland(route.value, session.value, expanded.value, { expanded.value = it }, {
                            selected += it; route.value = it; expanded.value = false
                        }, { action, _, _, _ -> actions += action }, layer,
                            Modifier.align(Alignment.BottomCenter).padding(12.dp).width(if (large) 320.dp else 411.dp)) {
                                opened += it; selected += "Workouts"
                            }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun save(name: String) = rule.onRoot().captureToImage().asAndroidBitmap().also { bitmap ->
        File(rule.activity.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun state(value: String) = rule.onNodeWithTag("explore-bar")
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))

    /** The morph is two phases, and an interrupted first phase must never reach its spring. */
    @Test fun theShellCompressesBeforeItTravelsAndAReversalDropsThePendingSpring() {
        lateinit var motion: ExploreMotion
        rule.setContent {
            val scope = rememberCoroutineScope()
            motion = remember { ExploreMotion(scope, false) }
        }
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { motion.settle(true, 0f, false, morph = true) }
        rule.mainClock.advanceTimeBy(50)
        rule.runOnIdle {
            assertTrue("The shell compresses first: ${motion.squeeze}", motion.squeeze > .2f)
            assertTrue("It has not travelled yet: ${motion.value}", motion.value < .2f)
        }
        rule.mainClock.advanceTimeBy(1200)
        rule.runOnIdle {
            assertEquals("It arrives open", 1f, motion.value, .02f)
            assertEquals("and fills out again", 0f, motion.squeeze, .02f)
        }
        // Reverse inside the compression, then reverse back: no stale spring may finish.
        rule.runOnIdle { motion.settle(false, 0f, false, morph = true) }
        rule.mainClock.advanceTimeBy(40)
        rule.runOnIdle { motion.settle(true, 0f, false, morph = true) }
        rule.mainClock.advanceTimeBy(1500)
        rule.runOnIdle {
            assertEquals("The last request owns the pose", 1f, motion.value, .02f)
            assertEquals(0f, motion.squeeze, .02f)
        }
        // A grab takes the shell back to its own footprint rather than a compressed one.
        rule.runOnIdle { motion.settle(false, 0f, false, morph = true) }
        rule.mainClock.advanceTimeBy(40)
        rule.runOnIdle { motion.grab(); assertEquals(0f, motion.squeeze, 0f) }
        rule.mainClock.autoAdvance = true
    }

    @Test fun readabilityModesKeepLocalContrastAndGestureOwnership() {
        pageColor.value = Color.White
        show()
        val bounds = rule.onNodeWithTag("explore-bar").fetchSemanticsNode().boundsInRoot
        for (motion in listOf(false, true)) for (opaque in listOf(false, true)) for (contrast in listOf(0f, 1f)) {
            rule.runOnIdle { reduced.value = motion; readability.value = GlassReadability(opaque, contrast) }
            rule.onNodeWithTag("explore-bar").performClick(); rule.waitForIdle(); state("Expanded")
            for (background in listOf(Color.White, Color.Black, Color(0xFFFFE300), Color(0xFF218AE8))) {
                rule.runOnIdle { pageColor.value = background }
                val bitmap = rule.onNodeWithTag("explore-bar").captureToImage().asAndroidBitmap()
                val density = rule.activity.resources.displayMetrics.density
                val local = bitmap.getPixel(bitmap.width - (52 * density).toInt(), bitmap.height - (12 * density).toInt())
                val ink = readability.value.foreground(Color(0xFFB3AEBE)).toArgb()
                assertTrue("Local label contrast: opaque=$opaque contrast=$contrast color=$local",
                    androidx.core.graphics.ColorUtils.calculateContrast(ink, local) >= if (contrast > 0) 7.0 else 4.5)
                assertEquals(bounds, rule.onNodeWithTag("explore-bar").fetchSemanticsNode().boundsInRoot)
            }
            if (!motion) save("readability-$opaque-$contrast.png")
            rule.onNodeWithTag("explore-bar").performClick(); rule.waitForIdle(); state("Collapsed")
        }
        rule.runOnIdle { reduced.value = false }
        // A mode change while the pointer is owned must not recreate its detector or lose release.
        rule.onNodeWithTag("explore-bar").performTouchInput { down(center); moveBy(Offset(0f, -60f), 140) }
        rule.runOnIdle { readability.value = GlassReadability(false, 0f); pageColor.value = Color.White }
        rule.onNodeWithTag("explore-bar").performTouchInput { moveBy(Offset(0f, -200f), 200); up() }
        rule.waitForIdle(); state("Expanded")
        rule.onNodeWithText("Health").performClick(); rule.waitForIdle(); state("Collapsed")
        assertEquals(listOf("Health"), selected)
    }

    @Test fun movingMixedBackdropKeepsExpandedLabelsStableAndHeaderActionsAvailable() {
        backdropOffset.value = 0f
        show()
        rule.onNodeWithTag("explore-bar").performClick(); rule.waitForIdle(); state("Expanded")
        val bar = rule.onNodeWithTag("explore-bar").fetchSemanticsNode().boundsInRoot
        val header = rule.onNodeWithTag("header-settings").fetchSemanticsNode().boundsInRoot
        val samples = org.json.JSONArray()
        for (frame in 0..23) {
            rule.runOnIdle { backdropOffset.value = frame * 6f }
            val bitmap = rule.onNodeWithTag("explore-bar").captureToImage().asAndroidBitmap()
            val density = rule.activity.resources.displayMetrics.density
            val background = bitmap.getPixel(bitmap.width - (52 * density).toInt(), bitmap.height - (12 * density).toInt())
            val contrast = androidx.core.graphics.ColorUtils.calculateContrast(Color(0xFFB3AEBE).toArgb(), background)
            assertTrue("Mixed backdrop frame $frame", contrast >= 4.5)
            samples.put(JSONObject().put("frame", frame).put("offsetPx", frame * 6).put("background", background).put("contrast", contrast))
            assertEquals(bar, rule.onNodeWithTag("explore-bar").fetchSemanticsNode().boundsInRoot)
            assertEquals(header, rule.onNodeWithTag("header-settings").fetchSemanticsNode().boundsInRoot)
            if (frame in listOf(0, 12, 23)) save("readability-grid-$frame.png")
        }
        for (mode in listOf(GlassReadability(true, 0f), GlassReadability(false, 1f), GlassReadability(true, 1f))) {
            rule.runOnIdle { readability.value = mode }
            assertEquals(header, rule.onNodeWithTag("header-settings").fetchSemanticsNode().boundsInRoot)
            rule.onNodeWithTag("header-settings").assertHasClickAction().performClick()
            rule.onNodeWithTag("header-back").assertHasClickAction().performClick()
            state("Expanded")
        }
        File(rule.activity.cacheDir, "readability-grid.json").writeText(samples.toString())
    }

    @Test fun seededReversalWithSourceUpdateAndTapAfterSettle() {
        val random = java.util.Random(915)
        val trace = com.mani.orbit.sync.NativeDiagnostics.trace
        val before = trace.snapshot().getJSONArray("events").let { a ->
            (0 until a.length()).maxOfOrNull { a.getJSONObject(it).getLong("op") } ?: 0
        }
        show()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("explore-bar").performClick()
        rule.mainClock.advanceTimeBy(96)
        rule.onNodeWithTag("explore-bar").performTouchInput {
            down(center); moveBy(Offset(0f, 100f), 120); up()
        }
        rule.runOnIdle {
            session.value = NativeWorkoutState(JSONObject().put("active", JSONObject().put("kind", "Walking").put("resumedAt", 1000)),
                (300 + random.nextInt(60)) * 1000L)
            pageColor.value = Color(0xFF284B45)
        }
        rule.mainClock.advanceTimeBy(1400)
        state("Collapsed")
        rule.onNodeWithTag("explore-bar").performClick()
        rule.mainClock.advanceTimeBy(1400)
        state("Expanded")
        rule.onNodeWithText("Health").performClick()
        rule.mainClock.advanceTimeBy(1400)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        state("Collapsed")
        assertEquals(listOf("Health"), selected)
        assertTrue(actions.isEmpty())
        val captured = trace.snapshot()
        val events = captured.getJSONArray("events")
        val stages = (0 until events.length()).map { events.getJSONObject(it) }
            .filter { it.getLong("op") > before && it.getString("feature") == "EXPLORE" }.map { it.getString("stage") }
        assertTrue(stages.contains("SUPERSEDED"))
        assertTrue(stages.contains("DISPLAY_UPDATED"))
        val identity = rule.activity.assets.open("orbit-native-build.json").bufferedReader().use { JSONObject(it.readText()) }
        captured.put("build", identity).put("case", "explore-source-reversal").put("seed", 915)
            .put("inputs", org.json.JSONArray(listOf("open", "advance 96ms", "reverse 100px over 120ms", "synthetic source update", "settle 1400ms", "open", "settle 1400ms", "Health tap", "settle 1400ms")))
        File(rule.activity.cacheDir, "explore-source-reversal.json").writeText(captured.toString())
    }

    @Test fun dragReversalCancellationAndOneContinuousLauncher() {
        show()
        val page = rule.onNodeWithTag("underlying-page").fetchSemanticsNode().boundsInRoot
        val bar = rule.onNodeWithTag("explore-bar").fetchSemanticsNode().boundsInRoot
        rule.onAllNodesWithText("Health", substring = false).assertCountEquals(0)
        rule.onNodeWithTag("explore-bar").performTouchInput { swipe(center, center + Offset(0f, -240f), 450) }
        rule.waitForIdle(); state("Expanded")
        rule.onNodeWithText("Health").assertIsDisplayed()
        assertEquals(page, rule.onNodeWithTag("underlying-page").fetchSemanticsNode().boundsInRoot)
        assertEquals(bar, rule.onNodeWithTag("explore-bar").fetchSemanticsNode().boundsInRoot)
        save("explore-open.png")
        rule.onNodeWithTag("explore-bar").performTouchInput { swipe(center, center + Offset(0f, 240f), 450) }
        rule.waitForIdle(); state("Collapsed")
        rule.onNodeWithTag("explore-bar").performTouchInput { down(center); moveBy(Offset(0f, -130f), 160); cancel() }
        rule.waitForIdle(); state("Collapsed")
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("explore-bar").performClick()
        rule.mainClock.advanceTimeBy(96)
        rule.onNodeWithTag("explore-bar").performTouchInput { down(center); moveBy(Offset(0f, 100f), 120); up() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle(); state("Collapsed")
        repeat(3) {
            rule.onNodeWithTag("explore-bar").performClick(); rule.waitForIdle(); state("Expanded")
            rule.onNodeWithTag("explore-bar").performClick(); rule.waitForIdle(); state("Collapsed")
        }
        assertTrue(selected.isEmpty())
        assertEquals(bar, rule.onNodeWithTag("explore-bar").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun heldChoiceTracksAcrossItemsAndBackdropRemainsLive() {
        show()
        rule.onNodeWithTag("explore-bar").performClick(); rule.waitForIdle()
        val before = save("explore-frost-before.png")
        rule.runOnIdle { pageColor.value = Color(0xFF284B45) }
        rule.waitForIdle()
        val after = save("explore-frost-after.png")
        val box = rule.onNodeWithTag("explore-island").fetchSemanticsNode().boundsInRoot
        val sampleY = (box.bottom - 6 * rule.density.density).toInt()
        assertNotEquals("Frost must sample new page content", before.getPixel(box.center.x.toInt(), sampleY), after.getPixel(box.center.x.toInt(), sampleY))
        rule.onNodeWithTag("explore-choices").performTouchInput {
            down(Offset(width / 6f, centerY)); advanceEventTime(400)
            moveTo(Offset(width * 5f / 6f, centerY), 400)
        }
        save("explore-held-health.png")
        rule.onNodeWithTag("explore-choices").performTouchInput { advanceEventTime(100); up() }
        rule.waitForIdle()
        assertEquals(listOf("Health"), selected)
        state("Collapsed")
        rule.onNodeWithTag("explore-bar").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("explore-choices").performTouchInput { down(center); moveBy(Offset(40f, 0f), 120); cancel() }
        rule.waitForIdle()
        assertEquals(1, selected.size)
        save("explore-after-cancel.png")
    }

    @Test fun opticalTierChangesKeepTheOwnedGestureAndForegroundGeometry() {
        show()
        rule.onNodeWithTag("explore-bar").performClick(); rule.waitForIdle()
        val bounds = rule.onNodeWithTag("explore-bar").fetchSemanticsNode().boundsInRoot
        val labels = listOf("Body", "Workouts", "Health").map { rule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        rule.onNodeWithTag("explore-choices").performTouchInput {
            down(Offset(width / 6f, centerY)); advanceEventTime(120)
            moveTo(Offset(width * 5f / 6f, centerY), 240)
        }
        for (tier in listOf(GlassQuality.READABILITY, GlassQuality.FROST, GlassQuality.OPTICAL)) {
            rule.runOnIdle { quality.value = GlassQualityState(tier, com.mani.orbit.sync.TraceQualityReason.THERMAL) }
            rule.waitForIdle()
            assertEquals(bounds, rule.onNodeWithTag("explore-bar").fetchSemanticsNode().boundsInRoot)
            assertEquals(labels, listOf("Body", "Workouts", "Health").map { rule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot })
            save("explore-quality-${tier.name}.png")
            assertEquals(tier.traceTier.name, com.mani.orbit.sync.NativeDiagnostics.trace.snapshot().getJSONObject("rendering").getString("tier"))
        }
        rule.onNodeWithTag("explore-choices").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(listOf("Health"), selected)
        state("Collapsed")
    }

    @Test fun activeWorkoutControlsStayReachableWithLargeText() {
        session.value = NativeWorkoutState(JSONObject().put("active", JSONObject().put("kind", "Walking").put("resumedAt", 1000)), 3_661_000)
        show(large = true)
        rule.onNodeWithTag("explore-bar").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Health").assertIsDisplayed()
        rule.onNodeWithText("1:01:01").assertIsDisplayed()
        rule.onNodeWithText("Pause", substring = false).performClick()
        rule.onNodeWithText("Finish & save").performClick()
        assertEquals(listOf("pause", "finish"), actions)
        rule.runOnIdle { session.value = session.value.copy(elapsedMs = 3_662_000) }
        rule.onNodeWithText("1:01:02").assertIsDisplayed()
        save("explore-workout-large.png")
        rule.onNodeWithText("Open workout").performClick()
        assertEquals(listOf("Workouts"), selected)
    }

    @Test fun watchWorkoutUsesTheSameLiveBarAndOpensItsOwnRecord() {
        fun id() = java.util.UUID.randomUUID().toString()
        val w = com.mani.orbit.sync.WatchWorkout(id(), 1, "Running", id(), 10000, 1000, 71000, 62000, 61000, "active", false)
        val record = WatchWorkoutProjection.record(id(), w)
        session.value = NativeWorkoutState(loading = false, watchRecords = listOf(record))
        show()
        rule.onNodeWithText("01:01").assertIsDisplayed()
        rule.onNodeWithTag("explore-bar").performTouchInput { swipe(center, center + Offset(0f, -240f), 450) }
        rule.waitForIdle(); state("Expanded")
        rule.onNodeWithText("Pause workout").assertIsDisplayed()
        save("explore-watch-workout.png")
        rule.onNodeWithText("Open Watch workout").performClick()
        assertEquals(listOf(record.id), opened)
        assertTrue(actions.isEmpty())
        rule.runOnIdle { session.value = session.value.copy(watchRecords = listOf(record.copy(watch = w.copy(phase = "ended")))) }
        rule.onNodeWithText("01:01").assertDoesNotExist()
        rule.onNodeWithText("Explore").assertIsDisplayed()
    }
}
