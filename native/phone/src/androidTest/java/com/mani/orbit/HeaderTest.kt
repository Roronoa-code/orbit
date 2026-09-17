package com.mani.orbit

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeaderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun capture(name: String) = rule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
        java.io.File(rule.activity.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun largeTitlesFixedControlsAndCancelledHoldKeepOneHeader() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        var title by mutableStateOf("Steps")
        var home by mutableStateOf(true)
        var backCalls = 0
        var dateCalls = 0
        rule.setContent {
            val density = LocalDensity.current
            val scene = rememberGlassBackdrop()
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme(colorScheme = darkColorScheme(), typography = OrbitTypography) {
                    Column(Modifier.width(320.dp).fillMaxHeight().background(Color(0xFF0B0A0F))) {
                        OrbitHeader(title, if (home) "15 Sept 2026" else null, true, { backCalls++ }, { dateCalls++ }, {}, scene)
                        Box(Modifier.fillMaxSize().recordBackdrop(scene).background(Color(0xFF0B0A0F)))
                    }
                }
            }
        }
        val back = rule.onNodeWithContentDescription("Back")
        val bounds = back.fetchSemanticsNode().boundsInRoot
        rule.onNodeWithContentDescription("Choose date").performClick()
        assertEquals(1, dateCalls)
        assertTrue(rule.onNodeWithText("15 Sept 2026", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top >=
            rule.onNodeWithText("Steps", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.bottom)
        back.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(100)
        assertEquals(bounds, back.fetchSemanticsNode().boundsInRoot)
        capture("header-contact-large.png")
        back.performTouchInput { moveTo(Offset(-100f, -100f)); up() }
        assertEquals(0, backCalls)
        back.performClick(); assertEquals(1, backCalls)
        rule.runOnIdle { home = false; title = "Measurements" }
        rule.onNodeWithContentDescription("Settings").assertDoesNotExist()
        val heading = rule.onNodeWithText("Measurements").fetchSemanticsNode().boundsInRoot
        assertTrue(heading.left >= back.fetchSemanticsNode().boundsInRoot.right)
        assertTrue(heading.right <= rule.onNodeWithTag("orbit-header").fetchSemanticsNode().boundsInRoot.right)
        capture("header-measurements-large.png")
    }

    @Test fun pinnedFrostFeathersAndRefreshesTheRetainedScene() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        var opacity by mutableFloatStateOf(0f)
        var colour by mutableStateOf(Color(0xFFE07058))
        rule.setContent {
            val scene = rememberGlassBackdrop()
            Box(Modifier.width(300.dp).height(140.dp).testTag("frost-proof")) {
                Box(Modifier.fillMaxSize().recordBackdrop(scene).drawWithContent { drawRect(colour) })
                HomeScrollFrost(scene, { opacity }, Modifier.fillMaxWidth().height(30.dp), pinnedEdge = true)
            }
        }
        fun pixels() = rule.onNodeWithTag("frost-proof").captureToImage().toPixelMap()
        val idle = pixels()
        val x = idle.width / 2
        val last = (idle.height * 29f / 140f).toInt()
        rule.runOnIdle { opacity = 1f }
        val frosted = pixels()
        assertTrue(frosted[x, 1].red < idle[x, 1].red * .3f)
        assertTrue(frosted[x, last].red > idle[x, last].red * .9f)
        rule.runOnIdle { colour = Color(0xFF5489EF) }
        val changed = pixels()
        assertTrue(changed[x, last].blue > changed[x, last].red * 1.5f)
        capture("header-frost-live.png")
        rule.runOnIdle { opacity = 0f }
        val cleared = pixels()
        assertTrue(cleared[x, 1].blue > .8f)
        assertEquals(cleared[x, 1].blue, cleared[x, last].blue, .02f)
    }
}
