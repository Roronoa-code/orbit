package com.mani.orbit

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/** Emulator-only data and interaction checks. No fixture sources enter the application APK. */
@RunWith(AndroidJUnit4::class)
class NativeMigrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val today = LocalDate.now()
    private val start = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun row(type: String, id: String, at: Long = start) = JSONObject()
        .put("type", type).put("id", id).put("source", "com.sec.android.app.shealth").put("start", at).put("end", at)
    private fun projection(rows: JSONArray) = JSONObject().put("schema", 1).put("date", today.toString()).put("rows", rows)
    private fun openSettingsFromDetail() {
        compose.onNodeWithContentDescription("Explore").performClick()
        compose.onNode(hasText("Health") and hasClickAction()).performClick()
        compose.onNodeWithTag("health-source").performScrollTo().performClick()
    }

    @Test fun sparseReadingsAndOverlappingSleepKeepOriginalMeaning() {
        val weight = row("weight", "weight", start - 86400000).put("value", 76.0)
        val light = row("sleep", "night").put("end", start + 600000)
            .put("stages", JSONArray().put(JSONArray(listOf(start, start + 600000, 4))))
        val conflict = row("sleep", "overlap", start + 300000).put("end", start + 600000)
            .put("stages", JSONArray().put(JSONArray(listOf(start + 300000, start + 600000, 1))))
        val day = NativeHealthProjection.read(projection(JSONArray().put(weight).put(light).put(conflict)), today)
        assertNull(day.steps)
        assertNull(day.heart)
        assertEquals(76.0, day.weight!!, 0.0)
        assertEquals(today.minusDays(1), day.weightDate)
        assertEquals(1, day.measurements.weights.size)
        assertEquals(5.0, day.asleepMinutes!!, 0.0)
        val bad = row("stepsDay", "bad").put("source", "untrusted.app").put("value", 500)
        assertThrows(IllegalArgumentException::class.java) { NativeHealthProjection.read(projection(JSONArray().put(bad)), today) }
    }

    @Test fun nativeActivityReadsExistingStorageAndRestoresNavigation() {
        // Refuse accidental execution on a physical phone.
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish") && android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        val preferences = AppPreferences(context)
        val previous = preferences.read("orbit-profile-v1")
        val profile = JSONObject().put("name", "Native migration check").put("heightCm", 178).put("weightKg", 84)
            .put("birthDate", "1999-03-11")
        assertTrue(preferences.write("orbit-profile-v1", profile.toString()))
        val dbFile = context.getDatabasePath("samsung-health.db")
        HealthRecordStore(dbFile).use { store ->
            store.beginImport()
            store.stage(JSONArray().put(row("stepsDay", "native-check").put("date", today.toString()).put("value", 12345)))
            store.finishImport(listOf("stepsDay"), start, start + 86400000, true)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(10000) { compose.onAllNodesWithText("12,345", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                scenario.onActivity { activity ->
                    fun containsWeb(view: View): Boolean = view is WebView || view is ViewGroup && (0 until view.childCount).any { containsWeb(view.getChildAt(it)) }
                    assertFalse(containsWeb(activity.window.decorView))
                }
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Native migration check").assertExists()
                HealthRecordStore(dbFile).use { store ->
                    store.beginImport()
                    store.stage(JSONArray().put(row("stepsDay", "native-check").put("date", today.toString()).put("value", 22345)))
                    store.finishImport(listOf("stepsDay"), start, start + 86400000, true)
                }
                scenario.recreate()
                compose.onNodeWithText("Profile").assertExists()
                compose.onNodeWithText("178.0").assertExists()
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                compose.onNodeWithText("Profile").assertExists()
                compose.onNodeWithText("178.0").performTextReplacement("180.5")
                compose.onNodeWithText("Save profile").performClick()
                compose.waitUntil(5000) { preferences.read("orbit-profile-v1")?.let { JSONObject(it).optDouble("heightCm") == 180.5 } == true }
                assertEquals("1999-03-11", JSONObject(preferences.read("orbit-profile-v1")!!).getString("birthDate"))
                compose.onNodeWithContentDescription("Back").performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithText("22,345", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Explore").performClick()
                compose.waitForIdle()
                compose.onNodeWithText("Health").performClick()
                compose.onNodeWithText("Measurements").performScrollTo().performClick()
                compose.onNodeWithText("1Y").performScrollTo().performClick()
                java.io.File(context.cacheDir, "measurements-navigation.png").outputStream().use {
                    compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                compose.onNodeWithText("1Y").assertIsSelected()
                openSettingsFromDetail()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithText("1Y").assertIsSelected()
                scenario.recreate()
                compose.onNodeWithText("1Y").assertIsSelected()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithText("Measurements").assertExists()
            }
        } finally {
            if (previous != null) assertTrue(preferences.write("orbit-profile-v1", previous))
            else assertTrue(context.getSharedPreferences("orbit-settings", Context.MODE_PRIVATE).edit().remove("orbit-profile-v1").commit())
        }
    }

    @Test fun sleepRouteRestoresItsDateAndReloadsOriginalsOnResumeWithoutMovingHome() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val database = context.getDatabasePath("samsung-health.db")
        val yesterday = today.minusDays(1)
        val zone = ZoneId.systemDefault()
        fun original(day: LocalDate, hours: Int): JSONObject {
            val a = day.atTime(1, 0).atZone(zone).toInstant().toEpochMilli()
            val b = a + hours * 3600000L
            return row("sleep", "sleep-route-$day", a).put("end", b)
                .put("stages", JSONArray().put(JSONArray(listOf(a, b, 4))))
        }
        fun commit(hours: Int) = HealthRecordStore(database).use { store ->
            store.beginImport()
            store.stage(JSONArray().put(original(today, 7)).put(original(yesterday, hours)))
            store.finishImport(listOf("sleep"), yesterday.atStartOfDay(zone).toInstant().toEpochMilli(),
                today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), true)
        }
        fun awaitSleep() = compose.waitUntil(10000) {
            compose.onAllNodesWithTag("sleep-chart").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("sleep-loading").fetchSemanticsNodes().isEmpty()
        }
        commit(6)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithContentDescription("Explore").performClick()
            compose.onNodeWithText("Health").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Sleep").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Sleep").performScrollTo().performClick()
            awaitSleep()
            compose.onNodeWithContentDescription("Previous day").performClick()
            awaitSleep()
            val dateLabel = yesterday.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.UK))
            compose.onNodeWithText(dateLabel).assertExists()
            compose.onNodeWithText("01:00 – 07:00").assertExists()
            compose.onNodeWithTag("sleep-stage-light").performScrollTo().performClick().assertIsSelected()
            scenario.recreate()
            awaitSleep()
            compose.onNodeWithText(dateLabel).assertExists()
            compose.onNodeWithTag("sleep-stage-light").assertIsSelected()
            scenario.moveToState(Lifecycle.State.CREATED)
            commit(8)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitSleep()
            compose.waitUntil(10000) { compose.onAllNodesWithText("01:00 – 09:00").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("sleep-summary").performScrollTo()
            java.io.File(context.cacheDir, "sleep-native-route.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            openSettingsFromDetail()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithContentDescription("Back").performClick()
            awaitSleep()
            compose.onNodeWithText(dateLabel).assertExists()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithContentDescription("Back").performClick()
            scenario.onActivity { activity ->
                val model = androidx.lifecycle.ViewModelProvider(activity)[OrbitModel::class.java]
                assertEquals(today.toString(), model.selectedDate.value)
            }
        }
    }
}
