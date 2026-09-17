package com.mani.orbit.wear

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.*
import com.mani.orbit.sync.WatchWorkout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Renders the production content; fixture readings stay in composition, never in a health journal. */
class WatchWorkoutLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun reviewHistoryAndWorkoutAttentionAcrossRoundSizesAndLargeText() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val at = Instant.parse("2026-09-15T10:00:00Z").toEpochMilli()
        val saved = WatchWorkout(UUID.randomUUID().toString(), 1, "Walking", UUID.randomUUID().toString(),
            at, 1000, at + 1_200_000, 1_201_000, 1_140_000, "ended", false,
            distance = 1800.0, steps = 2400, energy = 110.0)
        val missing = saved.copy(distance = null, steps = null, energy = null)
        val active = saved.copy(phase = "active", heart = 108.0, heartElapsed = saved.updatedElapsed, heartQuality = "valid")
        data class Case(val name: String, val route: String, val session: WatchWorkout, val error: String? = null, val attached: Boolean = true)
        val cases = listOf(
            Case("history-normal", "preview", saved),
            Case("history-interrupted", "preview", missing.copy(phase = "interrupted")),
            Case("history-failed", "preview", saved, "Refresh failed"),
            Case("details-empty", "details", missing),
            Case("details-failed", "details", saved, "Refresh failed"),
            Case("details-zero", "details", saved.copy(distance = 0.0, steps = 0, energy = 0.0, elevation = 0.0)),
            Case("workout-normal", "live", active),
            Case("workout-missing", "live", missing.copy(phase = "active")),
            Case("workout-stale", "live", active.copy(heartElapsed = active.updatedElapsed - 60_000)),
            Case("workout-reconnecting", "live", active, "The last confirmed readings are saved. Reconnect to continue recording.", false),
            Case("workout-paused", "live", active.copy(phase = "paused")),
            Case("workout-ended", "live", saved))
        var current by mutableStateOf(cases.first())
        var width by mutableIntStateOf(192)
        var font by mutableFloatStateOf(1f)
        val pixels = compose.activity.windowManager.currentWindowMetrics.bounds.width()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(pixels.toFloat() / width, font)) {
                MaterialTheme(colorScheme = ColorScheme(primary = Color(0xFFBBA1ED), background = Color(0xFF0B0A0F), surfaceContainer = Color(0xFF201D27))) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        Box(Modifier.weight(1f)) { key(current.name) { WorkoutPage(compact = current.route != "details") {
                            when (current.route) {
                                "preview" -> WatchHistoryPreview(current.session, 0, current.error, {}, {})
                                "details" -> WatchHistoryDetails(current.session, current.error, {}, {})
                                else -> WatchWorkoutSummary(current.session, current.attached, saved.updatedElapsed, current.error, true) {}
                            }
                        } } }
                        Spacer(Modifier.height(if (current.route == "details") 0.dp else 16.dp))
                    }
                }
            }
        }
        for (size in listOf(192, 228)) for (scale in listOf(1f, 1.5f)) {
            compose.runOnIdle { width = size; font = scale }
            for (case in cases) {
                compose.runOnIdle { current = case }
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                compose.activity.cacheDir.resolve("attention-${case.name}-$size-$scale.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                if (case.route == "live" || case.route == "preview") {
                    val tag = if (case.route == "live") "watch-workout-clock" else "history-duration"
                    val clock = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
                    assertEquals("Clock clipped at $size/$scale/${case.name}", clock.layoutInfo.height.toFloat(), clock.boundsInRoot.height, 1f)
                }
                if (case.route == "preview" || case.name == "workout-reconnecting") {
                    val tag = if (case.route == "preview") "history-details-0" else "watch-reconnect"
                    val button = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    val viewport = compose.onNodeWithTag("watch-safe-viewport").fetchSemanticsNode().boundsInRoot
                    assertTrue("Primary action under the scroll fade at $size/$scale/${case.name}: $button in $viewport",
                        button.bottom <= viewport.bottom - 12f * pixels / size + 1f)
                }
                if (case.route == "live") {
                    compose.onNodeWithText("— kcal").assertDoesNotExist()
                    compose.onNodeWithText("— steps").assertDoesNotExist()
                    if (case.error != null) compose.onNodeWithText("Reconnect").performScrollTo().assertIsDisplayed()
                } else if (case.route == "preview") compose.onNodeWithText("Details").performScrollTo().assertIsDisplayed()
                else {
                    if (case.name == "details-empty") {
                        compose.onNodeWithText("Only time recorded").performScrollTo().assertIsDisplayed()
                        compose.onNodeWithText("—").assertDoesNotExist()
                    }
                    compose.onNodeWithText("Back to history").performScrollTo().assertIsDisplayed()
                }
            }
        }
    }
}
