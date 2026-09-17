package com.mani.orbit.wear

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.hypot

@RunWith(AndroidJUnit4::class)
class WatchLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun emulatorOnly() { check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) }
    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.cacheDir, "layout-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun textLayout(tag: String): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single()
    }

    @Test fun roundCornersAndCompleteTouchTargetsFitAtBothSizesAndLargeText() {
        var width by mutableIntStateOf(192)
        var scale by mutableFloatStateOf(1f)
        val window = compose.activity.windowManager.currentWindowMetrics.bounds
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(window.width().toFloat() / width, scale)) {
                MaterialTheme { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    WorkoutPage {
                        Text("Saved workout", style = MaterialTheme.typography.titleSmall)
                        WatchNumber("123,456", Modifier.testTag("long-number"))
                        repeat(3) { i -> FilledTonalButton(onClick = {}, contentPadding = PaddingValues(horizontal = if (i == 1) 8.dp else 14.dp, vertical = 8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("action-$i")) {
                            Text(if (i == 1) "Allow background heart rate" else "Details",
                                modifier = if (i == 1) Modifier.testTag("long-action-label") else Modifier,
                                style = if (i == 1) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        } }
                    }
                } }
            }
        }
        for (dpWidth in listOf(192, 228)) for (font in listOf(1f, 1.5f)) {
            compose.runOnIdle { width = dpWidth; scale = font }
            val viewport = bounds("watch-safe-viewport")
            val center = Offset(window.width() / 2f, window.height() / 2f)
            for (corner in listOf(viewport.topLeft, viewport.topRight, viewport.bottomLeft, viewport.bottomRight))
                assertTrue("$dpWidth/$font corner $corner", hypot(corner.x - center.x, corner.y - center.y) < window.width() / 2f)
            compose.onNodeWithTag("long-number").performScrollTo()
            assertFalse("Full value remains readable", textLayout("long-number").hasVisualOverflow)
            assertEquals(1, textLayout("long-number").lineCount)
            for (i in 0..2) {
                compose.onNodeWithTag("action-$i").performScrollTo().assertIsDisplayed()
                val node = compose.onNodeWithTag("action-$i").fetchSemanticsNode()
                val action = node.boundsInRoot
                assertTrue(viewport.left <= action.left && viewport.right >= action.right)
                assertTrue(viewport.top <= action.top + 1 && viewport.bottom >= action.bottom - 1)
                assertEquals("Entire control height visible", node.layoutInfo.height.toFloat(), action.height, 1f)
                assertTrue("Touch target >= 48dp", action.height >= 48 * window.width().toFloat() / dpWidth - 1)
                if (i == 1) {
                    val label = textLayout("long-action-label")
                    assertFalse(label.hasVisualOverflow)
                    for (line in 0 until label.lineCount)
                        assertFalse("Keep background as a whole word", label.getLineEnd(line, visibleEnd = true) in 7..15)
                    capture("long-action-$dpWidth-$font")
                }
            }
            capture("bounds-$dpWidth-$font")
        }
    }

    @Test fun numeralAdvancesAndTimerRolloverKeepOtherSlotsStable() {
        var ms by mutableLongStateOf(3_599_000)
        var heart by mutableStateOf<Int?>(null)
        var distance by mutableStateOf<Double?>(null)
        var scale by mutableFloatStateOf(1f)
        var number by mutableStateOf("111.18")
        compose.setContent {
            val device = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(device.density, scale)) {
                MaterialTheme { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    WorkoutPage(compact = true) {
                        WatchTime(ms, Modifier.testTag("timer"))
                        Text("Recording", style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("phase"))
                        WatchLiveMetrics(distance, heart)
                        WatchNumber(number, Modifier.testTag("numerals"))
                        Text("More readings", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("following"))
                    }
                } }
            }
        }
        for (font in listOf(1f, 1.5f)) {
            compose.runOnIdle { scale = font; ms = 3_599_000; heart = null; distance = null; number = "111.18" }
            compose.onNodeWithTag("timer").performScrollTo()
            val before = listOf("timer", "phase", "live-distance", "live-pulse").map(::bounds)
            capture("missing-$font")
            compose.runOnIdle { ms = 3_600_000; heart = 88; distance = 12340.0 }
            compose.onNodeWithTag("timer").assertContentDescriptionEquals("1:00:00")
            assertEquals(before, listOf("timer", "phase", "live-distance", "live-pulse").map(::bounds))
            capture("hour-$font")
            compose.onNodeWithTag("numerals").performScrollTo()
            val digits = textLayout("numerals")
            assertFalse(digits.hasVisualOverflow)
            assertEquals(digits.getBoundingBox(0).width, digits.getBoundingBox(5).width, .25f)
            assertTrue(digits.getBoundingBox(3).width > 0)
            val numericalHeight = bounds("numerals").height
            compose.runOnIdle { number = "888.81" }
            assertEquals(digits.size, textLayout("numerals").size)
            compose.runOnIdle { number = "12h 59m" }
            assertFalse(textLayout("numerals").hasVisualOverflow)
            assertEquals(numericalHeight, bounds("numerals").height, 1f)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun nativePagerReversalAndVerticalButtonCrossingDoNotClick() {
        var clicks = 0
        var backs = 0
        var touchSlop = 0f
        var tick by mutableIntStateOf(0)
        var enabled by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                val configuration = LocalViewConfiguration.current
                SideEffect { touchSlop = configuration.touchSlop }
                val displayedTick = tick
                WatchBackSurface(enabled, { backs++ }) {
                val pager = rememberPagerState { 2 }
                WatchPager(pager, Modifier.fillMaxSize().testTag("pager")) { page ->
                    WorkoutPage(active = page == pager.settledPage) {
                        Text("Page $page · $displayedTick", Modifier.testTag("page-$page"))
                        FilledTonalButton(onClick = { clicks++ }, modifier = Modifier.fillMaxWidth().testTag("tap-$page")) { Text("Details") }
                        Spacer(Modifier.height(120.dp))
                        FilledTonalButton(onClick = { clicks++ }, modifier = Modifier.fillMaxWidth().testTag("last-$page")) { Text("Finish") }
                    }
                }
                }
            }
        }
        compose.onNodeWithTag("tap-0").performTouchInput { down(center); moveBy(Offset(0f, -70f)); up() }
        assertEquals(0, clicks)
        compose.onNodeWithTag("tap-0").performScrollTo().performTouchInput { down(center); advanceEventTime(400); cancel() }
        assertEquals(0, clicks)
        compose.onNodeWithTag("pager").performTouchInput {
            down(Offset(width * .8f, height * .5f))
            moveTo(Offset(width * .2f, height * .5f), 160)
            moveTo(Offset(width * .8f, height * .5f), 160)
            cancel()
        }
        compose.onNodeWithTag("page-0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pager").performTouchInput {
            down(Offset(width * .8f, height * .5f))
            moveBy(Offset(-touchSlop * 1.05f, 0f), 16)
        }
        compose.runOnIdle { tick++ }
        compose.onNodeWithTag("pager").performTouchInput {
            moveTo(Offset(width * .2f, height * .5f), 200)
            up()
        }
        capture("held-reading-change")
        compose.onNodeWithTag("tap-1").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, clicks)
        val beforeRotary = bounds("tap-1").top
        compose.onNodeWithTag("pager").performRotaryScrollInput {
            repeat(3) { rotateToScrollVertically(30f) }
        }
        compose.waitUntil(3000) { bounds("tap-1").top < beforeRotary - 2 }
        assertEquals(1, clicks)
        compose.onNodeWithTag("pager").performTouchInput {
            swipe(Offset(width * .3f, height * .5f), Offset(width * .95f, height * .5f), 300)
        }
        compose.onNodeWithTag("tap-0").performScrollTo().performClick()
        assertEquals(2, clicks)
        assertEquals(0, backs)
        compose.onNodeWithTag("watch-back-surface").performTouchInput {
            down(Offset(2f, height * .5f)); moveTo(Offset(width * .25f, height * .5f), 160); cancel()
        }
        assertEquals(0, backs)
        compose.onNodeWithTag("watch-back-surface").performTouchInput {
            down(Offset(2f, height * .5f)); moveTo(Offset(width * .25f, height * .5f), 160)
        }
        compose.runOnIdle { enabled = false }
        compose.onNodeWithTag("watch-back-surface").performTouchInput { up() }
        assertEquals(0, backs)
        compose.onNodeWithTag("watch-back-surface").performTouchInput {
            swipe(Offset(2f, height * .5f), Offset(width - 2f, height * .5f), 350)
        }
        assertEquals(0, backs)
        compose.runOnIdle { enabled = true }
        repeat(2) { i ->
            compose.onNodeWithTag("watch-back-surface").performTouchInput {
                swipe(Offset(2f, height * .5f), Offset(width - 2f, height * .5f), 350)
            }
            compose.waitForIdle()
            assertEquals(i + 1, backs)
        }
        assertEquals(2, clicks)
    }
}
