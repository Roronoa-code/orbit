package com.mani.orbit

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** The actual themed phone shell, with in-memory readings and no connected services. */
@RunWith(AndroidJUnit4::class)
class PhoneVisualParityTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun exploreState(value: String) = rule.onNodeWithTag("explore-bar")
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))

    private fun save(name: String) {
        rule.waitForIdle()
        rule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(rule.activity.cacheDir, "parity-$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun fullShellScreensAndReversibleNavigation() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        rule.runOnUiThread {
            rule.activity.enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
            rule.activity.window.isNavigationBarContrastEnforced = false
        }
        val date = LocalDate.of(2026, 9, 14)
        val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val rows = JSONArray()
        fun row(type: String, value: Double, at: Long = start) = JSONObject()
            .put("id", "$type-$at").put("type", type).put("source", "com.sec.android.app.shealth")
            .put("start", at).put("end", at).put("value", value).also(rows::put)
        row("stepsDay", 8420.0); row("distanceDay", 6320.0); row("energyDay", 337.0); row("floorsDay", 2.0)
        row("weight", 75.8); row("fat", 18.0); row("lean", 62.0); row("muscle", 34.0)
        row("oxygen", 98.0).put("low", 96).put("high", 99)
        row("nutrition", 0.0).put("name", "Breakfast").put("calories", 550).put("protein", 28).put("carbs", 64).put("fat", 18)
        row("water", 1500.0)
        for (hour in 8..16) rows.put(JSONObject().put("type", "heartHour").put("source", "com.sec.android.app.shealth")
            .put("date", date.toString()).put("hour", hour).put("count", 1).put("sum", 72 + hour % 5 * 3)
            .put("low", 72 + hour % 5 * 3).put("high", 72 + hour % 5 * 3).put("latest", 72 + hour % 5 * 3)
            .put("latestTime", start + hour * 3600000L))
        rows.put(JSONObject().put("id", "sleep").put("type", "sleep").put("source", "com.sec.android.app.shealth")
            .put("start", start).put("end", start + 8 * 3600000L).put("stages", JSONArray().apply {
                listOf(1, 4, 5, 4, 6, 4, 6, 6).forEachIndexed { i, stage ->
                    put(JSONArray().put(start + i * 3600000L).put(start + (i + 1) * 3600000L).put(stage))
                }
            }))
        val model = OrbitModel(rule.activity.application, SavedStateHandle(mapOf("date" to date.toString())))
        val owner = ViewModelStore().apply { put("visual", model) }
        try {
            model.acceptHealth(model.attachHealthSource(), JSONObject().put("revision", "ui-parity")
                .put("available", true).put("permitted", true).put("status", "Samsung Health")
                .put("data", JSONObject().put("schema", 1).put("date", date.toString()).put("rows", rows)).toString())
            rule.waitUntil(5000) { !model.health.value.loading && model.cardLayout.value != null }
            val record = WorkoutRecord("parity-walk", "Walking", start + 10 * 3600000, start + 11 * 3600000,
                1800000, 1800000, weight = 75.8, distance = 2100.0)
            val workout = MutableStateFlow(NativeWorkoutState(loading = false, history = listOf(record)))
            val music = MutableStateFlow(NativeMusicState())
            rule.setContent { OrbitApp(model, workout, MutableStateFlow(false), {},
                { _, _, _, _ -> }, music, { _, _, _ -> }) }
            save("home")
            rule.onNodeWithTag("expand-home-cards").performClick()
            save("home-expanded")
            rule.onNodeWithTag("explore-bar").performTouchInput { swipe(center, center + Offset(0f, -240f), 450) }
            rule.waitForIdle()
            exploreState("Expanded")
            rule.onNodeWithTag("explore-choices").assertIsDisplayed()
            save("explore-open")
            rule.onNodeWithTag("explore-bar").performClick()
            rule.waitForIdle()
            exploreState("Collapsed")
            for (route in listOf("Health", "Measurements", "Sleep", "Settings", "Workouts")) {
                rule.runOnIdle { model.navigate(route) }
                save(route.lowercase())
            }
            rule.onNode(hasText("History") and hasAnyAncestor(hasTestTag("workout-tabs"))).performClick()
            save("workout-history")
            rule.onNodeWithTag("workout-record-parity-walk").performClick()
            save("workout-record")
            rule.onNodeWithContentDescription("Back").performClick()
            rule.onNode(hasText("Train") and hasAnyAncestor(hasTestTag("workout-tabs"))).performClick()
            rule.onNodeWithTag("choose-Walking").performClick()
            save("workout-setup")
            rule.onNodeWithText("Time", useUnmergedTree = true).performClick()
            save("workout-target")
            rule.runOnIdle {
                workout.value = NativeWorkoutState(loading = false, active = record.copy(end = null, elapsed = 96000), elapsedMs = 96000)
                music.value = NativeMusicState(status = "ready", id = "parity-song", title = "Local artwork preview", artist = "Orbit UI check",
                    duration = 240000, position = 90000, playing = true, canToggle = true, canNext = true, canPrevious = true,
                    artwork = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xff57416f.toInt()) })
            }
            save("workout-live")
            rule.onNodeWithTag("music-heading").performClick()
            save("music-open")
            rule.onNodeWithTag("music-heading").performClick()
            save("music-closed")
            rule.runOnIdle { model.navigate("Health") }
            rule.onNodeWithTag("health-card-steps").assertExists()
            save("health-return")
        } finally { rule.runOnUiThread { owner.clear() } }
    }
}
