package com.mani.orbit

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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Every phone route in the real shell at the narrow width and the large text scale. */
class PhoneResponsiveTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val date: LocalDate = LocalDate.of(2026, 9, 14)
    private val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun emulatorOnly() { check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) }

    private fun save(name: String) {
        rule.waitForIdle()
        rule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(rule.activity.cacheDir, "narrow-$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    /** Nothing may be pushed outside the narrow window, and nothing may collapse to no height. */
    private fun fits(vararg tags: String) {
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        for (tag in tags) {
            val node = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull() ?: continue
            val bounds = node.boundsInRoot
            assertTrue("$tag spills out of $root: $bounds",
                bounds.left >= root.left - .5f && bounds.right <= root.right + .5f)
            assertTrue("$tag has no height: $bounds", bounds.height > 1f)
        }
    }

    @Test fun everyRouteStaysInsideTheNarrowWindowAtLargeText() {
        val rows = JSONArray()
        fun row(type: String, value: Double, at: Long = start) = JSONObject()
            .put("id", "$type-$at").put("type", type).put("source", "com.sec.android.app.shealth")
            .put("start", at).put("end", at).put("value", value).also(rows::put)
        row("stepsDay", 8420.0); row("distanceDay", 6320.0); row("energyDay", 337.0); row("floorsDay", 2.0)
        row("weight", 75.8); row("fat", 18.0); row("lean", 62.0); row("muscle", 34.0)
        row("oxygen", 98.0).put("low", 96).put("high", 99)
        row("nutrition", 0.0).put("name", "Breakfast").put("calories", 550).put("protein", 28)
            .put("carbs", 64).put("fat", 18)
        row("water", 1500.0)
        for (hour in 8..16) rows.put(JSONObject().put("type", "heartHour")
            .put("source", "com.sec.android.app.shealth").put("date", date.toString()).put("hour", hour)
            .put("count", 1).put("sum", 72 + hour % 5 * 3).put("low", 72 + hour % 5 * 3)
            .put("high", 72 + hour % 5 * 3).put("latest", 72 + hour % 5 * 3)
            .put("latestTime", start + hour * 3600000L))
        rows.put(JSONObject().put("id", "sleep").put("type", "sleep").put("source", "com.sec.android.app.shealth")
            .put("start", start).put("end", start + 8 * 3600000L).put("stages", JSONArray().apply {
                listOf(1, 4, 5, 4, 6, 4, 6, 6).forEachIndexed { i, stage ->
                    put(JSONArray().put(start + i * 3600000L).put(start + (i + 1) * 3600000L).put(stage))
                }
            }))
        val model = OrbitModel(rule.activity.application, SavedStateHandle(mapOf("date" to date.toString())))
        val owner = ViewModelStore().apply { put("narrow", model) }
        try {
            model.acceptHealth(model.attachHealthSource(), HealthUpdate("narrow", JSONObject()
                .put("available", true).put("permitted", true).put("status", "Samsung Health")
                .put("meta", JSONObject().put("firstRecord", start - 120L * 86400000L)),
                NativeHealthProjection.project(JSONObject().put("schema", 1).put("date", date.toString()).put("rows", rows), date)))
            rule.waitUntil(5000) { !model.health.value.loading && model.cardLayout.value != null }
            val record = WorkoutRecord("narrow-walk", "Walking", start + 10 * 3600000, start + 11 * 3600000,
                1800000, 1800000, weight = 75.8, distance = 2100.0)
            val workout = MutableStateFlow(NativeWorkoutState(loading = false, history = listOf(record)))
            val music = MutableStateFlow(NativeMusicState())
            rule.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    Box(Modifier.width(320.dp).fillMaxHeight().background(Color(0xFF0A0A0C))) {
                        OrbitApp(model, workout, MutableStateFlow(false), {}, { _, _, _, _ -> }, music, { _, _, _ -> })
                    }
                }
            }
            rule.waitForIdle()
            fits("orbit-header", "explore-bar", "home-orb")
            save("home")
            rule.onNodeWithTag("expand-home-cards").performClick()
            fits("home-card-0", "explore-bar")
            save("home-expanded")
            rule.onNodeWithContentDescription("Choose date").performClick()
            rule.waitForIdle()
            fits("date-chooser", "date-range")
            rule.onNodeWithText("View day").assertIsDisplayed()
            save("date-chooser")
            rule.onNodeWithText("Cancel").performClick()
            for (route in listOf("Health", "Measurements", "Sleep", "Settings", "Galaxy Watch", "Workouts")) {
                rule.runOnIdle { model.navigate(route) }
                fits("orbit-header", "explore-bar")
                save(route.lowercase().replace(' ', '-'))
            }
            rule.onNode(hasText("History") and hasAnyAncestor(hasTestTag("workout-tabs"))).performClick()
            fits("workout-tabs", "workout-days")
            save("workout-history")
            rule.onNodeWithTag("workout-record-narrow-walk").performClick()
            fits("orbit-header")
            save("workout-record")
            rule.onNodeWithContentDescription("Back").performClick()
            rule.onNode(hasText("Train") and hasAnyAncestor(hasTestTag("workout-tabs"))).performClick()
            rule.onNodeWithTag("choose-Walking").performClick()
            fits("workout-start")
            save("workout-setup")
            rule.runOnIdle {
                workout.value = NativeWorkoutState(loading = false,
                    active = record.copy(end = null, elapsed = 96000), elapsedMs = 96000)
                music.value = NativeMusicState(status = "ready", id = "narrow-song",
                    title = "A quiet morning with a long track title", artist = "A longer artist name",
                    duration = 240000, position = 90000, playing = true, canToggle = true,
                    canNext = true, canPrevious = true, canSeek = true)
            }
            fits("workout-live", "workout-timer", "music-heading")
            save("workout-live")
            rule.onNodeWithTag("music-heading").performClick()
            rule.waitForIdle()
            fits("workout-timer", "music-seek")
            save("music-open")
        } finally { rule.runOnUiThread { owner.clear() } }
    }
}
