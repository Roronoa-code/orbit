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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** The header's date panel: real coverage bounds, day stepping and an explicit confirmation. */
class DateChooserTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val today: LocalDate = LocalDate.now()
    private val chosen = mutableListOf<LocalDate>()
    private var dismissed = 0
    private var closed = 0
    private var pageFill = Color.Transparent
    private val open = androidx.compose.runtime.mutableStateOf(true)

    @Before fun emulatorOnly() { check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) }

    private fun save(name: String) = rule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
        File(rule.activity.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun show(first: LocalDate?, date: LocalDate, width: Int = 411, scale: Float = 1f) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                MaterialTheme(colorScheme = darkColorScheme(), typography = OrbitTypography) {
                    val layer = rememberGlassBackdrop()
                    Box(Modifier.width(width.dp).fillMaxHeight().background(Color(0xFF0B0A0F)).safeDrawingPadding()) {
                        Box(Modifier.fillMaxSize().recordBackdrop(layer).background(pageFill))
                        // Where the header's date sits: the panel grows out of exactly this.
                        val anchor = with(LocalDensity.current) { androidx.compose.ui.geometry.Rect(14.dp.toPx(), 9.dp.toPx(), 160.dp.toPx(), 63.dp.toPx()) }
                        OrbitDateChooser(open.value, anchor, date, first, layer, { closed++ }, { dismissed++ }) { chosen += it }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun label() = rule.onNodeWithTag("date-choice", useUnmergedTree = true)
        .fetchSemanticsNode().config[SemanticsProperties.Text].first().text

    @Test fun realCoverageBoundsTheChoiceAndBothEndsStop() {
        val first = today.minusDays(92)
        show(first, today)
        rule.onNodeWithTag("date-chooser").assertIsDisplayed()
        rule.onNodeWithText("Choose a day").assertIsDisplayed()
        rule.onNodeWithText("Explore your shared health history.").assertIsDisplayed()
        assertEquals(today.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMM", java.util.Locale.UK)), label())
        rule.onNodeWithContentDescription("Next day").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Previous day").assertIsEnabled()
        save("date-chooser-today.png")
        repeat(3) { rule.onNodeWithContentDescription("Previous day").performClick() }
        assertEquals(today.minusDays(3).format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMM", java.util.Locale.UK)), label())
        rule.onNodeWithContentDescription("Next day").assertIsEnabled()
        rule.onNodeWithTag("date-range").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            today.minusDays(3).format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMM", java.util.Locale.UK))))
        // The earliest imported day is the floor; the control stops there rather than inventing history.
        rule.onNodeWithTag("date-range").performTouchInput { swipe(center, centerLeft - Offset(240f, 0f), 240) }
        rule.waitForIdle()
        assertEquals(first.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMM", java.util.Locale.UK)), label())
        rule.onNodeWithContentDescription("Previous day").assertIsNotEnabled()
        save("date-chooser-earliest.png")
        rule.onNodeWithText("View day").performClick()
        assertEquals(listOf(first), chosen)
        assertEquals(0, dismissed)
    }

    @Test fun cancelKeepsTheOriginalDayAndNoCoverageFallsBackToThirtyDays() {
        show(null, today.minusDays(2))
        rule.onNodeWithText(today.minusDays(29).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.UK))).assertIsDisplayed()
        rule.onNodeWithText(today.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.UK))).assertIsDisplayed()
        rule.onNodeWithContentDescription("Previous day").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertTrue(chosen.isEmpty())
        assertEquals(1, dismissed)
    }

    /** The panel comes out of the date that opened it, and goes back into it when it closes. */
    @Test fun thePanelGrowsOutOfTheDateAndGoesBackIntoIt() {
        pageFill = Color(0xFF218AE8)
        rule.mainClock.autoAdvance = false
        show(today.minusDays(40), today)
        rule.mainClock.advanceTimeBy(48)
        val early = rule.onRoot().captureToImage().asAndroidBitmap()
        rule.mainClock.advanceTimeBy(900)
        val formed = rule.onRoot().captureToImage().asAndroidBitmap()
        val panel = rule.onNodeWithTag("date-chooser").fetchSemanticsNode().boundsInRoot
        // Far from the date, the panel's corner is not there yet, then it is.
        val x = (panel.right - 20 * rule.density.density).toInt(); val y = (panel.bottom - 20 * rule.density.density).toInt()
        fun apart(a: Int, b: Int) = listOf(16, 8, 0).maxOf { kotlin.math.abs((a shr it and 255) - (b shr it and 255)) }
        val page = pageFill.toArgb()
        assertTrue("The far corner is still page while the panel grows", apart(page, early.getPixel(x, y)) <= 6)
        assertTrue("and glass once it has formed", apart(page, formed.getPixel(x, y)) > 40)
        rule.runOnIdle { open.value = false }
        rule.mainClock.advanceTimeBy(120)
        assertEquals("Closing takes the way it came", 0, closed)
        rule.mainClock.advanceTimeBy(600)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals("and hands back once it is inside the date again", 1, closed)
    }

    @Test fun narrowWidthAndLargeTextKeepEveryControlReachable() {
        show(today.minusDays(400), today.minusDays(1), width = 320, scale = 1.5f)
        val panel = rule.onNodeWithTag("date-chooser").fetchSemanticsNode().boundsInRoot
        for (node in listOf(rule.onNodeWithContentDescription("Previous day"), rule.onNodeWithContentDescription("Next day"),
            rule.onNodeWithTag("date-range"), rule.onNodeWithText("Cancel"), rule.onNodeWithText("View day"))) {
            node.assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("$bounds must stay inside $panel", bounds.left >= panel.left - .5f && bounds.right <= panel.right + .5f)
            assertTrue("Touch height", bounds.height >= 44 * rule.density.density - 1f)
        }
        assertTrue("Panel must not exceed the 300dp reference width",
            panel.width <= 300 * rule.density.density + 1f)
        save("date-chooser-large-text.png")
    }
}
