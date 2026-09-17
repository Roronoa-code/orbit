package com.mani.orbit.wear

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.foundation.AmbientMode
import com.mani.orbit.sync.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * W1's rendered review for the routes whose states were still open: Sleep & energy and the saved
 * workout, each in its normal, absent, stale and failure state, plus optional-metric slot stability.
 */
@RunWith(AndroidJUnit4::class)
class WatchRouteStatesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val now = Instant.parse("2026-09-15T12:00:00Z").toEpochMilli()
    private val today = LocalDate.of(2026, 9, 15)
    private val installation = UUID.randomUUID().toString()
    private val display = WatchDisplay(AmbientMode.Interactive, now, 0)

    @Before fun emulatorOnly() { check(Build.HARDWARE in setOf("ranchu", "goldfish")) }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.activity.cacheDir.resolve("route-state-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun day(date: LocalDate, score: Double?, pulse: Double?) = RecoveryDay(date,
        now - 13 * 3600000, now - 5 * 3600000, 1,
        mapOf("light" to 4 * 3600000L, "deep" to 2 * 3600000L, "rem" to 3600000L, "awake" to 3600000L), score, pulse)

    @Test fun sleepAndEnergyRendersNormalAbsentStaleAndFailure() {
        var incoming by mutableStateOf<HealthContext?>(HealthContext("UTC", now, now, listOf(day(today, 84.0, 77.0))))
        var loading by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var refreshes = 0
        compose.setContent { WatchEnvironment { WatchRecoveryScreen(incoming, loading, error, display) { refreshes++ } } }
        compose.onNodeWithTag("recovery-value-0").assertIsDisplayed()
        val normalDate = compose.onNodeWithTag("recovery-date-0").fetchSemanticsNode().boundsInRoot
        capture("recovery-normal")

        // Absent: a published context with no recorded day must say so and stay actionable.
        compose.runOnIdle { incoming = HealthContext("UTC", now, 0, emptyList()) }
        compose.waitForIdle()
        compose.onAllNodesWithTag("recovery-value-0").assertCountEquals(0)
        capture("recovery-absent")

        // Stale: an old publication keeps the same slots and still names its recorded day.
        val old = now - 6 * 86_400_000L
        compose.runOnIdle { incoming = HealthContext("UTC", old, old, listOf(day(today.minusDays(6), 71.0, null))) }
        compose.waitForIdle()
        compose.onNodeWithTag("recovery-date-0").assertIsDisplayed()
        assertEquals("A stale publication moved the dated summary", normalDate.top,
            compose.onNodeWithTag("recovery-date-0").fetchSemanticsNode().boundsInRoot.top, 1f)
        capture("recovery-stale")

        // Failure: the summary stays quiet and keeps its saved day; the detail carries the message.
        compose.runOnIdle { error = "Watch context could not refresh." }
        compose.waitForIdle()
        compose.onNodeWithText("Saved view", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("recovery-date-0").assertIsDisplayed()
        // The first action must stay whole on the initial render, not slide under the bottom fade.
        val window = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithTag("recovery-details-0").fetchSemanticsNode().boundsInRoot
        assertTrue("Details is cut off at $action inside $window", action.bottom <= window.bottom + .5f)
        capture("recovery-failure")
        compose.onNodeWithTag("recovery-details-0").performScrollTo().performClick()
        compose.onNodeWithText("Watch context could not refresh.", substring = true).performScrollTo().assertIsDisplayed()
        capture("recovery-failure-detail")
        compose.onNodeWithText("Back").performScrollTo().performClick()
        compose.waitForIdle()

        // Loading keeps the previous recorded day rather than blanking it.
        compose.runOnIdle { error = null; loading = true }
        compose.waitForIdle()
        compose.onNodeWithTag("recovery-date-0").assertIsDisplayed()
        capture("recovery-loading")
        assertEquals(0, refreshes)
    }

    @Test fun savedWorkoutRendersNormalAbsentAndFailure() {
        val file = File.createTempFile("route-state-history-", ".db", compose.activity.cacheDir)
        try {
            ReadingJournal(file).use { journal ->
                journal.captureWorkoutUpdate(installation, WatchWorkout(UUID.randomUUID().toString(), 1, "Walking",
                    UUID.randomUUID().toString(), now - 7_200_000, 1000, now - 6_000_000, 1_201_000, 1_140_000,
                    "ended", false, distance = 1800.0, steps = 2400, energy = 110.0), emptyList())
            }
            compose.setContent { WatchEnvironment { WatchHistoryScreen(file, installation, display) } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("history-kind").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("history-details-0").assertIsDisplayed()
            capture("history-normal")
            compose.onNodeWithTag("history-details-0").performClick()
            compose.onNodeWithTag("history-close-details").performScrollTo().assertIsDisplayed()
            capture("history-detail")
        } finally { file.delete() }
    }

    @Test fun savedWorkoutAbsentAndUnreadableStatesStayActionable() {
        val empty = File.createTempFile("route-state-empty-", ".db", compose.activity.cacheDir)
        try {
            ReadingJournal(empty).use { }
            compose.setContent { WatchEnvironment { WatchHistoryScreen(empty, installation, display) } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("history-kind").fetchSemanticsNodes().isEmpty() }
            compose.waitForIdle()
            capture("history-absent")
            compose.onAllNodesWithTag("history-details-0").assertCountEquals(0)
        } finally { empty.delete() }
    }

    @Test fun optionalTodayFactsComeAndGoWithoutMovingTheAnchorOrItsAction() {
        fun reading(value: Int) = JSONObject().put("value", value).put("end", now - 120_000)
            .put("quality", "valid").put("unit", "steps").put("boot", "boot-1")
        var rows by mutableStateOf(mapOf<String, JSONObject?>("steps" to reading(4210),
            "distance" to reading(3200), "floors" to reading(3), "energy" to reading(180)))
        compose.setContent {
            WatchEnvironment {
                WorkoutPage(compact = true) {
                    WatchToday(rows, now, loading = false, error = null, pulse = {}, workout = {}, recovery = {})
                }
            }
        }
        compose.onNodeWithTag("today-steps").assertIsDisplayed()
        val anchor = compose.onNodeWithTag("today-steps").fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithTag("today-pulse").fetchSemanticsNode().boundsInRoot
        // The reserved optional line keeps the gap between the recorded context and the first action.
        val gap = action.top - compose.onNodeWithTag("today-context").fetchSemanticsNode().boundsInRoot.bottom
        capture("today-complete")
        // Dropout: every optional fact disappears while the anchor and its action stay put.
        compose.runOnIdle { rows = mapOf("steps" to reading(4210)) }
        compose.waitForIdle()
        assertEquals("The step anchor moved during dropout", anchor,
            compose.onNodeWithTag("today-steps").fetchSemanticsNode().boundsInRoot)
        assertEquals("The pulse action moved during dropout", action,
            compose.onNodeWithTag("today-pulse").fetchSemanticsNode().boundsInRoot)
        assertEquals("The reserved optional line collapsed during dropout", gap,
            compose.onNodeWithTag("today-pulse").fetchSemanticsNode().boundsInRoot.top -
                compose.onNodeWithTag("today-context").fetchSemanticsNode().boundsInRoot.bottom, .5f)
        capture("today-dropout")
        // Acquisition: the same facts return to the same places.
        compose.runOnIdle { rows = mapOf("steps" to reading(4210), "distance" to reading(3300)) }
        compose.waitForIdle()
        assertEquals("The step anchor moved during acquisition", anchor,
            compose.onNodeWithTag("today-steps").fetchSemanticsNode().boundsInRoot)
        assertEquals("The pulse action moved during acquisition", action,
            compose.onNodeWithTag("today-pulse").fetchSemanticsNode().boundsInRoot)
        capture("today-acquisition")
    }
}
