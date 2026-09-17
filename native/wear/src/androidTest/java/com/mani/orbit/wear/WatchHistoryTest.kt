package com.mani.orbit.wear

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.foundation.AmbientMode
import com.mani.orbit.sync.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WatchHistoryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val installation = UUID.randomUUID().toString()
    private fun id() = UUID.randomUUID().toString()
    private val start = Instant.parse("2026-09-14T09:00:00Z").toEpochMilli()
    @Before fun emulatorOnly() { check(Build.HARDWARE in setOf("ranchu", "goldfish")) }
    private fun session(kind: String, at: Long = start, phase: String = "ended") = WatchWorkout(
        id(), 1, kind, id(), at, 1000, at + 1_200_000, 1_201_000, 1_140_000, phase, false,
        distance = 1800.0, steps = 2400, energy = 110.0)
    private fun database() = File.createTempFile("history-test-", ".db", compose.activity.cacheDir)

    @Test fun sourceScopedPagingRestoresIdentityAndHasNoArchiveLimit() {
        val file = database()
        try { ReadingJournal(file).use { journal ->
            assertEquals(0 to 0, journal.workoutHistoryPosition(installation))
            assertNull(journal.workoutHistoryAt(installation, 0))
            val walking = session("Walking")
            val running = session("Running", start - 1000, "interrupted")
            journal.captureWorkoutUpdate(installation, walking, emptyList())
            journal.captureWorkoutUpdate(installation, running, emptyList())
            journal.captureWorkoutUpdate(id(), session("Cycling", start + 5000), emptyList())
            journal.captureWorkoutUpdate(installation, session("Strength", start + 1000, "active"), emptyList())
            assertEquals(2 to 1, journal.workoutHistoryPosition(installation, running.id))
            assertEquals(walking.id, journal.workoutHistoryAt(installation, 0)!!.id)
            assertEquals(running.id, journal.workoutHistoryAt(installation, 1)!!.id)
            repeat(105) { journal.captureWorkoutUpdate(installation, session("Cycling", start + 10000 + it), emptyList()) }
            assertEquals(107 to 106, journal.workoutHistoryPosition(installation, running.id))
            assertEquals(running.id, journal.workoutHistoryAt(installation, 106)!!.id)
            assertNull(journal.workoutHistoryAt(installation, 107))
            try { journal.workoutHistoryAt(installation, -1); fail("Negative page accepted") } catch (_: IllegalArgumentException) { }
        } } finally { file.delete() }
    }

    @Test fun swipeDetailsRestorationAndLargeTextUseNativeRecordedHistory() {
        val file = database()
        try {
            ReadingJournal(file).use { journal ->
                journal.captureWorkoutUpdate(installation, session("Walking"), emptyList())
                journal.captureWorkoutUpdate(installation, session("Running", start - 86_400_000, "interrupted").copy(distance = null, energy = null), emptyList())
            }
            val largeText = mutableStateOf(false)
            val display = mutableStateOf(WatchDisplay(AmbientMode.Interactive, start, 60000))
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeText.value) 1.5f else 1f)) {
                    WatchEnvironment { WatchHistoryScreen(file, installation, display.value) }
                }
            }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Walking").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Walking").assertIsDisplayed()
            capture("latest")
            compose.onNodeWithTag("watch-history-pager").performTouchInput { swipeLeft() }
            compose.onNodeWithText("Running").assertIsDisplayed()
            compose.onNodeWithTag("history-details-1").performScrollTo().performClick()
            compose.onAllNodesWithTag("watch-back-surface").assertCountEquals(1)
            compose.onNodeWithTag("watch-back-surface").performTouchInput {
                down(Offset(2f, height * .5f)); moveTo(Offset(width * .25f, height * .5f), 160); cancel()
            }
            compose.onNodeWithText("Recorded energy").assertDoesNotExist()
            compose.onNodeWithText("Distance").assertDoesNotExist()
            compose.onNodeWithText("Steps").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Interrupted · last confirmed totals").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("watch-back-surface").performTouchInput {
                swipe(Offset(2f, height * .5f), Offset(width - 2f, height * .5f), 350)
            }
            compose.onNodeWithText("Running").assertIsDisplayed()
            // A failed real database refresh retains the selected session, including its details.
            val backup = File(file.path + ".saved")
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            assertTrue(file.renameTo(backup)); assertTrue(file.mkdir())
            try {
                compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                compose.waitUntil(5000) { compose.onAllNodesWithText("Update failed").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Running").assertIsDisplayed()
                capture("failed-refresh")
                compose.onNodeWithTag("history-details-1").performScrollTo().performClick()
                compose.onNodeWithText("Update failed").assertIsDisplayed()
                compose.onNodeWithText("Running").assertIsDisplayed()
                compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.onNodeWithText("Running").assertIsDisplayed()
            } finally {
                assertTrue(file.delete()); assertTrue(backup.renameTo(file))
            }
            compose.onNodeWithTag("history-retry-1").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Update failed").fetchSemanticsNodes().isEmpty() }
            compose.onNode(hasTestTag("history-kind") and hasText("Running")).performScrollTo()
            compose.onNodeWithText("Running").assertIsDisplayed()
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("history-details-1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Running").assertIsDisplayed()
            compose.runOnIdle { largeText.value = true }
            compose.onNodeWithTag("history-details-1").performScrollTo().performClick()
            compose.onNodeWithTag("history-close-details").performScrollTo().assertIsDisplayed()
            capture("large-details")
            compose.runOnIdle { display.value = display.value.copy(mode = AmbientMode.Ambient(false, false)) }
            compose.onNodeWithTag("watch-ambient").assertExists()
            compose.onNodeWithTag("history-close-details").assertDoesNotExist()
        } finally { file.delete() }
    }
    private fun capture(name: String) { compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
        File(compose.activity.cacheDir, "history-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    } }
}
