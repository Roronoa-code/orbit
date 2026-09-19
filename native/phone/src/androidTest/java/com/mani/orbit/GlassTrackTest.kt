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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The glass track every choice in the app is made on, the Explore island's included: a flat pill at
 * rest that lifts into a lens while held, carried or travelling, and is flat again on the frame it
 * arrives. Here as a floating track of four over a page, the way the island's choices sit.
 */
@RunWith(AndroidJUnit4::class)
class GlassTrackTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val labels = listOf("Home", "Body", "Workouts", "Health")
    private val selected = mutableStateOf(0)
    private val textShown = mutableStateOf(true)
    private val pageColor = mutableStateOf(Color(0xFF352B4B))
    private val readability = mutableStateOf(GlassReadability())
    private val reduced = mutableStateOf(false)
    private val quality = mutableStateOf(GlassQualityState(GlassQuality.OPTICAL))
    private val chosen = mutableListOf<String>()

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
                        }
                        GlassTrack(labels, selected.value, { chosen += labels[it]; selected.value = it }, "orbit-tabs",
                            Modifier.align(Alignment.BottomCenter).padding(12.dp).width(if (large) 320.dp else 411.dp),
                            TrackRole.Navigation, layer, style = if (textShown.value) TrackStyle()
                                else TrackStyle(ink = Color.Transparent, chosenInk = Color.Transparent))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun save(name: String) = rule.onRoot().captureToImage().asAndroidBitmap().also { bitmap ->
        File(rule.activity.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun tab(label: String) = rule.onNode(hasText(label) and hasAnyAncestor(hasTestTag("orbit-tabs")))

    /** The glass's timing is one journey: fast then slow, lifted on the way, flat on arrival. */
    @Test fun theGlassTimelineLiftsOnTheWayAndIsFlatOnArrival() {
        var previous = -1f
        for (i in 0..100) {
            val t = i / 100f
            val eased = travelEase(t)
            assertTrue("Travel only moves forward at $t", eased >= previous); previous = eased
        }
        assertEquals(0f, travelEase(0f), 0f); assertEquals(1f, travelEase(1f), 0f)
        assertEquals("A quarter of the way is left for the second half", .75f, travelEase(.5f), .001f)
        for (initial in listOf(0f, .4f, 1f)) {
            assertEquals("Flat on the arrival frame from $initial", 0f, travelPickup(1f, initial), 0f)
            assertEquals("It starts from where the glass was", initial, travelPickup(0f, initial), .001f)
        }
        assertTrue("A tap gains glass near departure", travelPickup(.16f, 0f) > .9f)
        var held = 2f
        for (i in 0..20) { val p = travelPickup(i / 20f, 1f); assertTrue("A held lens only descends", p <= held); held = p }
        val out = FloatArray(2)
        var value = 0f; var speed = 0f
        repeat(40) {
            stepPickup(value, speed, 1f, 1 / 60f, out); value = out[0]; speed = out[1]
            assertTrue("The glass never passes its target", value in 0f..1f)
        }
        assertEquals("and arrives within a fraction of a second", 1f, value, .01f)
        assertTrue("A pill pushed past the end gives, but never escapes", rubberband(10_000f, 100f) < 100f)
    }

    @Test fun aTapTravelsTheSelectionAsGlassAndLandsFlat() {
        show()
        val home = bounds("orbit-tabs-indicator")
        val body = bounds("orbit-tabs-1")
        rule.mainClock.autoAdvance = false
        tab("Body").performClick()
        rule.mainClock.advanceTimeBy(96)
        rule.onNodeWithTag("orbit-tabs-lens", useUnmergedTree = true).assertExists()
        val moving = bounds("orbit-tabs-indicator")
        assertTrue("On its way: $moving", moving.center.x > home.center.x && moving.center.x < body.center.x)
        save("tabs-travelling.png")
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("orbit-tabs-lens", useUnmergedTree = true).assertDoesNotExist()
        assertEquals("It lands on its slot", body.center.x, bounds("orbit-tabs-indicator").center.x, 2f)
        rule.mainClock.autoAdvance = true
        assertEquals(listOf("Body"), chosen)
        tab("Body").assertIsSelected()
        tab("Home").assertIsNotSelected()
    }

    @Test fun aHoldLiftsTheLensPastTheRailWithoutChoosingAnything() {
        show()
        val rail = bounds("orbit-tabs")
        val resting = rule.onNodeWithTag("orbit-tabs").captureToImage().asAndroidBitmap()
        tab("Home").performTouchInput { down(center) }
        rule.waitForIdle()
        rule.onNodeWithTag("orbit-tabs-lens", useUnmergedTree = true).assertExists()
        val lens = rule.onNodeWithTag("orbit-tabs-lens", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("The held lens rises past the rail: lens=$lens rail=$rail", lens.top < rail.top && lens.bottom > rail.bottom)
        val held = save("tabs-held.png")
        tab("Home").performTouchInput { up() }
        rule.waitForIdle()
        rule.onNodeWithTag("orbit-tabs-lens", useUnmergedTree = true).assertDoesNotExist()
        val after = rule.onNodeWithTag("orbit-tabs").captureToImage().asAndroidBitmap()
        assertEquals("A hold changes nothing", emptyList<String>(), chosen)
        assertEquals(resting.width, after.width); assertEquals(resting.height, after.height)
        assertNotNull(held)
    }

    @Test fun aCarryFollowsTheFingerAndChoosesExactlyOnce() {
        show()
        val health = bounds("orbit-tabs-3")
        val origin = bounds("orbit-tabs").topLeft
        rule.onNodeWithTag("orbit-tabs").performTouchInput {
            down(Offset(width / 8f, centerY)); advanceEventTime(200)
            moveTo(Offset(width * .55f, centerY), 300)
        }
        rule.waitForIdle()
        val lens = rule.onNodeWithTag("orbit-tabs-lens", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("The lens rides under the finger: $lens", kotlin.math.abs(lens.center.x - origin.x - bounds("orbit-tabs").width * .55f) < lens.width / 2)
        save("tabs-carried.png")
        rule.onNodeWithTag("orbit-tabs").performTouchInput { moveTo(Offset(health.center.x - origin.x, centerY), 300); advanceEventTime(120); up() }
        rule.waitForIdle()
        assertEquals(listOf("Health"), chosen)
        assertEquals(health.center.x, bounds("orbit-tabs-indicator").center.x, 2f)
    }

    @Test fun slidingOffTheRailLetsGoWithoutChoosing() {
        show()
        val home = bounds("orbit-tabs-indicator")
        rule.onNodeWithTag("orbit-tabs").performTouchInput {
            down(Offset(width / 8f, centerY)); moveTo(Offset(width * .6f, centerY), 200)
            moveTo(Offset(width * .6f, centerY - 260.dp.toPx()), 200); up()
        }
        rule.waitForIdle()
        assertTrue(chosen.isEmpty())
        assertEquals("The pill goes home", home, bounds("orbit-tabs-indicator"))
        rule.onNodeWithTag("orbit-tabs").performTouchInput { down(center); moveBy(Offset(60f, 0f), 120); cancel() }
        rule.waitForIdle()
        assertTrue(chosen.isEmpty())
    }

    @Test fun withNothingChosenTheLensRisesFromNothingAndSinksIfLetGo() {
        selected.value = -1
        show()
        rule.onNodeWithTag("orbit-tabs-indicator").assertDoesNotExist()
        labels.forEach { tab(it).assertIsNotSelected() }
        tab("Workouts").performTouchInput { down(center) }
        rule.waitForIdle()
        rule.onNodeWithTag("orbit-tabs-lens", useUnmergedTree = true).assertExists()
        save("track-lens-from-nothing.png")
        // Slid well off and let go: nothing is chosen, and the lens sinks away.
        tab("Workouts").performTouchInput { moveBy(Offset(0f, -300.dp.toPx()), 200); up() }
        rule.waitForIdle()
        assertTrue(chosen.isEmpty())
        rule.onNodeWithTag("orbit-tabs-lens", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("orbit-tabs-indicator").assertDoesNotExist()
        tab("Workouts").performClick()
        rule.waitForIdle()
        assertEquals(listOf("Workouts"), chosen)
        rule.onNodeWithTag("orbit-tabs-indicator").assertExists()
        tab("Workouts").assertIsSelected()
    }

    @Test fun reducedMotionAndReadabilityKeepTheSelectionFlatAndReadable() {
        pageColor.value = Color.White
        show()
        var expected = 0
        for (motion in listOf(true, false)) for (opaque in listOf(true, false)) for (contrast in listOf(0f, 1f)) {
            if (!motion && !opaque) continue
            rule.runOnIdle { reduced.value = motion; readability.value = GlassReadability(opaque, contrast) }
            val next = listOf("Body", "Workouts", "Health", "Home")[expected % 4]
            tab(next).performTouchInput { down(center) }
            rule.waitForIdle()
            rule.onNodeWithTag("orbit-tabs-lens", useUnmergedTree = true).assertDoesNotExist()
            tab(next).performTouchInput { up() }
            rule.waitForIdle()
            tab(next).assertIsSelected()
            expected++
            for (background in listOf(Color.White, Color.Black, Color(0xFFFFE300), Color(0xFF218AE8))) {
                rule.runOnIdle { pageColor.value = background }
                val bitmap = rule.onNodeWithTag("orbit-tabs").captureToImage().asAndroidBitmap()
                val density = rule.activity.resources.displayMetrics.density
                // The rail's own ground, in the inset band clear of the labels.
                val ground = bitmap.getPixel(bitmap.width / 2, (3 * density).toInt())
                val ink = readability.value.foreground(TrackInk).toArgb()
                assertTrue("Label contrast: motion=$motion opaque=$opaque contrast=$contrast ground=$ground",
                    androidx.core.graphics.ColorUtils.calculateContrast(ink, ground) >= if (contrast > 0) 7.0 else 4.5)
            }
        }
        assertEquals(expected, chosen.size)
        assertTrue("The flat selection reads", androidx.core.graphics.ColorUtils.calculateContrast(TrackChosenInk.toArgb(), TrackFlatFill.toArgb()) >= 7.0)
    }

    @Test fun opticalTierChangesKeepTheCarryAndTheLabels() {
        show()
        val rail = bounds("orbit-tabs")
        val slots = (0..3).map { rail.left + rail.width * it / 4f to rail.left + rail.width * (it + 1) / 4f }
        rule.onNodeWithTag("orbit-tabs").performTouchInput {
            down(Offset(width / 8f, centerY)); advanceEventTime(120)
            moveTo(Offset(width * 7f / 8f, centerY), 240)
        }
        for (tier in listOf(GlassQuality.READABILITY, GlassQuality.FROST, GlassQuality.OPTICAL)) {
            rule.runOnIdle { quality.value = GlassQualityState(tier, com.mani.orbit.sync.TraceQualityReason.THERMAL) }
            rule.waitForIdle()
            // The rail may draw in under the lens, but the bar the finger owns keeps its place and
            // every label stays over its own slot.
            assertEquals(rail, bounds("orbit-tabs"))
            (0..3).forEach { val x = bounds("orbit-tabs-$it").center.x; assertTrue("Label $it at $x", x in slots[it].first..slots[it].second) }
            save("tabs-quality-${tier.name}.png")
        }
        rule.onNodeWithTag("orbit-tabs").performTouchInput { advanceEventTime(120); up() }
        rule.waitForIdle()
        assertEquals(listOf("Health"), chosen)
    }

    @Test fun theRailSamplesTheLivePage() {
        show()
        val before = rule.onNodeWithTag("orbit-tabs").captureToImage().asAndroidBitmap()
        rule.runOnIdle { pageColor.value = Color(0xFF218AE8) }
        rule.waitForIdle()
        val after = rule.onNodeWithTag("orbit-tabs").captureToImage().asAndroidBitmap()
        val density = rule.activity.resources.displayMetrics.density
        val x = (before.width * .62f).toInt(); val y = (3 * density).toInt()
        assertNotEquals("The rail is glass over the page, not paint", before.getPixel(x, y), after.getPixel(x, y))
    }

    /**
     * The letters under the lens bend with the glass, not just the page behind them: the US app's
     * four-image check. The lens is held still with its rim across letters and captured with the text
     * shown and hidden, each with the refraction on and off. What the refraction does to the page and
     * the rail is the same with or without the text, so it cancels; what is left is the text bending.
     */
    @Test fun theLettersUnderTheLensBendWithIt() {
        pageColor.value = Color(0xFF0B0A0F)
        show()
        rule.onNodeWithTag("orbit-tabs").performTouchInput {
            down(Offset(width / 8f, centerY)); moveTo(Offset(width / 8f + width * .1f, centerY), 250)
        }
        rule.waitForIdle()
        val lens = rule.onNodeWithTag("orbit-tabs-lens", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        fun capture(): Bitmap {
            rule.waitForIdle()
            val root = rule.onRoot().captureToImage().asAndroidBitmap()
            val left = lens.left.toInt().coerceAtLeast(0); val top = lens.top.toInt().coerceAtLeast(0)
            return Bitmap.createBitmap(root, left, top, minOf(lens.width.toInt(), root.width - left), minOf(lens.height.toInt(), root.height - top))
        }
        fun tier(value: GlassQuality) { rule.runOnIdle { quality.value = GlassQualityState(value) } }
        val shownBent = capture()
        tier(GlassQuality.FROST)
        val shownFlat = capture()
        rule.runOnIdle { textShown.value = false }
        val hiddenFlat = capture()
        tier(GlassQuality.OPTICAL)
        val hiddenBent = capture()
        rule.onNodeWithTag("orbit-tabs").performTouchInput { up() }
        rule.runOnIdle { textShown.value = true }
        fun channel(pixel: Int, shift: Int) = pixel shr shift and 255
        var glyphs = 0; var bent = 0; var total = 0.0
        for (y in 0 until shownBent.height) for (x in 0 until shownBent.width) {
            val g = listOf(16, 8, 0).maxOf { c ->
                kotlin.math.abs((channel(shownBent.getPixel(x, y), c) - channel(shownFlat.getPixel(x, y), c)) -
                    (channel(hiddenBent.getPixel(x, y), c) - channel(hiddenFlat.getPixel(x, y), c)))
            }
            total += g
            if (listOf(16, 8, 0).maxOf { c -> channel(shownFlat.getPixel(x, y), c) - channel(hiddenFlat.getPixel(x, y), c) } > 60) glyphs++
            if (g > 32) bent++
        }
        listOf("bent-text" to shownBent, "flat-text" to shownFlat, "bent-empty" to hiddenBent, "flat-empty" to hiddenFlat).forEach { (name, bitmap) ->
            File(rule.activity.cacheDir, "lens-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        File(rule.activity.cacheDir, "lens-letters.json").writeText(org.json.JSONObject().put("glyphPixels", glyphs)
            .put("bentGlyphPixels", bent).put("meanGlyphDifference", total / (shownBent.width * shownBent.height)).toString())
        assertTrue("There are letters under the lens: $glyphs", glyphs > 100)
        assertTrue("and the refraction moves them, apart from anything it does to the page: $bent of $glyphs", bent > 40)
    }
}
