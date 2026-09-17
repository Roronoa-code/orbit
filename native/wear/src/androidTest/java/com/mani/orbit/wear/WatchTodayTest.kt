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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WatchTodayTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun setupActionFitsBeforeScrollingAndKeepsCachedFactsSecondary() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertEquals(WatchStepSetup.Enable, watchStepSetup(false, false, true, false))
        assertEquals(WatchStepSetup.Updating, watchStepSetup(false, true, false, false))
        assertEquals(WatchStepSetup.Allow, watchStepSetup(true, false, false, true))
        assertEquals(WatchStepSetup.Settings, watchStepSetup(true, false, false, false))
        assertNull(watchStepSetup(true, false, true, false))
        var setup by mutableStateOf(WatchStepSetup.Enable)
        var cached by mutableStateOf(false)
        var failed by mutableStateOf(false)
        var width by mutableIntStateOf(192)
        var font by mutableFloatStateOf(1f)
        var clicks = 0
        val now = System.currentTimeMillis()
        val rows = mapOf("steps" to JSONObject().put("metric", "steps").put("value", 4321)
            .put("end", now).put("quality", "valid"))
        val pixels = compose.activity.windowManager.currentWindowMetrics.bounds.width()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(pixels.toFloat() / width, font)) {
                MaterialTheme(colorScheme = ColorScheme(primary = Color(0xFFBBA1ED), background = Color(0xFF0B0A0F), surfaceContainer = Color(0xFF201D27))) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        Box(Modifier.weight(1f)) { WorkoutPage(compact = true) {
                            Text("Watch steps", style = MaterialTheme.typography.titleSmall)
                            WatchToday(if (cached) rows else emptyMap(), now, false, null, {}, {}, {}, setup,
                                if (failed) "Tracking setting could not be saved" else null, { clicks++ })
                        } }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
        for (size in listOf(192, 228)) for (scale in listOf(1f, 1.5f)) {
            compose.runOnIdle { width = size; font = scale }
            val cases = WatchStepSetup.entries.map { Triple(it, false, false) } +
                listOf(Triple(WatchStepSetup.Enable, true, false), Triple(WatchStepSetup.Enable, true, true))
            for ((state, saved, error) in cases) {
                compose.runOnIdle { setup = state; cached = saved; failed = error }
                compose.onNodeWithText("Watch steps").performScrollTo()
                val caption = compose.onNodeWithTag("today-setup-context")
                caption.assertTextEquals(if (error) "Update failed" else state.caption)
                val context = caption.fetchSemanticsNode()
                assertEquals("Setup context clipped at $size/$scale/$state", context.layoutInfo.height.toFloat(), context.boundsInRoot.height, 1f)
                if (error) compose.onNodeWithText("Retry").assertIsDisplayed()
                val action = compose.onNodeWithTag("today-setup").fetchSemanticsNode()
                val viewport = compose.onNodeWithTag("watch-safe-viewport").fetchSemanticsNode().boundsInRoot
                assertEquals("Setup clipped at $size/$scale/$state", action.layoutInfo.height.toFloat(), action.boundsInRoot.height, 1f)
                assertTrue("Setup enters fade at $size/$scale/$state", action.boundsInRoot.bottom <= viewport.bottom - 12f * pixels / size + 1f)
                compose.onNodeWithTag("today-steps").assertDoesNotExist()
                if (state == WatchStepSetup.Updating) compose.onNodeWithTag("today-setup").assertIsNotEnabled()
                else {
                    val before = clicks
                    compose.onNodeWithTag("today-setup").performClick()
                    compose.runOnIdle { assertEquals(before + 1, clicks) }
                }
                val name = if (failed) "failed" else if (cached) "cached" else state.name
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                compose.activity.cacheDir.resolve("setup-$name-$size-$scale.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                if (cached) compose.onNodeWithTag("today-saved-steps").performScrollTo().assertIsDisplayed()
                if (failed) compose.onNodeWithText("Tracking setting could not be saved").performScrollTo().assertIsDisplayed()
                for (label in listOf("Check pulse", "Workouts", "Sleep & energy"))
                    compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            }
        }
    }

    @Test fun loadingMissingStaleAndFailureKeepTheRecordedFactStableAndActionsReachable() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val now = LocalDate.of(2026, 9, 15).atTime(13, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun row(metric: String, value: Double, age: Long = 0, quality: String = "valid") = JSONObject()
            .put("metric", metric).put("value", value).put("end", now - age).put("quality", quality)
        data class Case(val name: String, val rows: Map<String, JSONObject?>, val expected: String, val context: String,
            val loading: Boolean = false, val error: String? = null)
        val valid = mapOf("steps" to row("steps", 4321.0), "distance" to row("distance", 3150.0),
            "energy" to row("energy", 610.0), "floors" to row("floors", 3.0))
        val cases = listOf(
            Case("loading", emptyMap(), "—", "Loading readings…", loading = true),
            Case("missing", emptyMap(), "—", "No reading yet"),
            Case("normal", valid, "4,321", "Recorded just now"),
            Case("stale", mapOf("steps" to row("steps", 3210.0, 2 * 3600000)), "3,210", "Recorded 2h ago"),
            Case("yesterday", mapOf("steps" to row("steps", 9876.0, 24 * 3600000)), "—", "No reading today"),
            Case("uncertain", mapOf("steps" to row("steps", 876.0, -60000)), "—", "Reading time uncertain"),
            Case("unreliable", mapOf("steps" to row("steps", 876.0, quality = "unreliable")), "—", "Waiting for reliable data"),
            Case("unavailable", emptyMap(), "—", "Couldn't load\nRetrying…", error = "Saved readings unavailable. Retrying…"),
            Case("saved", valid, "4,321", "Refresh failed\nSaved just now", error = "Saved readings unavailable. Retrying…"),
            Case("zero", mapOf("steps" to row("steps", 0.0), "distance" to row("distance", 0.0),
                "floors" to row("floors", 0.0), "energy" to row("energy", 0.0)), "0", "Recorded just now"))
        var current by mutableStateOf(cases.first())
        var width by mutableIntStateOf(192)
        var font by mutableFloatStateOf(1f)
        val pixels = compose.activity.windowManager.currentWindowMetrics.bounds.width()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(pixels.toFloat() / width, font)) {
                MaterialTheme(colorScheme = ColorScheme(primary = Color(0xFFBBA1ED), background = Color(0xFF0B0A0F), surfaceContainer = Color(0xFF201D27))) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        Box(Modifier.weight(1f)) { WorkoutPage(compact = true) {
                            Text("Watch steps", style = MaterialTheme.typography.titleSmall)
                            WatchToday(current.rows, now, current.loading, current.error, {}, {}, {})
                        } }
                        Spacer(Modifier.height(16.dp)) // Same remaining height as the home page indicator.
                    }
                }
            }
        }
        for (size in listOf(192, 228)) for (scale in listOf(1f, 1.5f)) {
            compose.runOnIdle { width = size; font = scale }
            var stepTop: Float? = null
            for (case in cases) {
                compose.runOnIdle { current = case }
                compose.onNodeWithText("Watch steps").performScrollTo()
                compose.onNodeWithTag("today-steps").assertTextEquals(case.expected).assertIsDisplayed()
                compose.onNodeWithTag("today-context").assertTextEquals(case.context)
                val context = compose.onNodeWithTag("today-context").fetchSemanticsNode()
                val viewport = compose.onNodeWithTag("watch-safe-viewport").fetchSemanticsNode().boundsInRoot
                assertEquals("Complete initial context in ${case.name} at $size/$scale", context.layoutInfo.height.toFloat(), context.boundsInRoot.height, 1f)
                assertTrue("Context enters bottom fade in ${case.name} at $size/$scale",
                    context.boundsInRoot.bottom <= viewport.bottom - 12f * pixels / size + 1f)
                val top = compose.onNodeWithTag("today-steps").fetchSemanticsNode().boundsInRoot.top
                stepTop?.let { assertEquals("Step anchor moves in ${case.name}", it, top, 1f) }
                stepTop = top
                compose.onNodeWithText("— km").assertDoesNotExist()
                compose.onNodeWithText("— floors").assertDoesNotExist()
                compose.onNodeWithText("— kcal · total").assertDoesNotExist()
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                compose.activity.cacheDir.resolve("today-${case.name}-$size-$scale.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                for (label in listOf("Check pulse", "Workouts", "Sleep & energy"))
                    compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
                if (case.name == "zero") {
                    compose.onNodeWithText("0 floors").performScrollTo().assertIsDisplayed()
                    compose.onNodeWithText("0 kcal · total").performScrollTo().assertIsDisplayed()
                }
            }
        }
    }
}
