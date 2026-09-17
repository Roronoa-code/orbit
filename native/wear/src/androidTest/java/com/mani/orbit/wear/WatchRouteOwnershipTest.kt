package com.mani.orbit.wear

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
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
 * The W3 matrix on each mapped route that renders without a service: a cancelled press, a vertical
 * drag crossing a control, a rapid horizontal reversal, and a detail return to the same selection.
 */
@RunWith(AndroidJUnit4::class)
class WatchRouteOwnershipTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val now = Instant.parse("2026-09-15T12:00:00Z").toEpochMilli()
    private val today = LocalDate.of(2026, 9, 15)
    private val installation = UUID.randomUUID().toString()
    private fun id() = UUID.randomUUID().toString()
    private val display = WatchDisplay(AmbientMode.Interactive, now, 0)

    @Before fun emulatorOnly() { check(Build.HARDWARE in setOf("ranchu", "goldfish")) }

    private fun session(kind: String, at: Long) = WatchWorkout(
        id(), 1, kind, id(), at, 1000, at + 1_200_000, 1_201_000, 1_140_000, "ended", false,
        distance = 1800.0, steps = 2400, energy = 110.0)

    /** A press that is cancelled, and a vertical drag that crosses the control, never activate it. */
    private fun pressAndCrossingStayInert(tag: String, activated: () -> Boolean) {
        compose.onNodeWithTag(tag).performScrollTo().performTouchInput { down(center); advanceEventTime(400); cancel() }
        compose.waitForIdle()
        assertFalse("A cancelled press on $tag activated it", activated())
        compose.onNodeWithTag(tag).performScrollTo().performTouchInput { down(center); moveBy(Offset(0f, -90f), 120); up() }
        compose.waitForIdle()
        assertFalse("A vertical drag across $tag activated it", activated())
    }

    /** A drag that reverses before release leaves the settled page where it was. */
    private fun reversalKeepsPage(pagerTag: String) {
        compose.onNodeWithTag(pagerTag).performTouchInput {
            down(Offset(width * .8f, height * .5f))
            moveTo(Offset(width * .2f, height * .5f), 150)
            moveTo(Offset(width * .8f, height * .5f), 150)
            up()
        }
        compose.waitForIdle()
    }

    @Test fun historyRouteKeepsItsSelectionThroughCancellationReversalAndDetailReturn() {
        val file = File.createTempFile("route-history-", ".db", compose.activity.cacheDir)
        try {
            ReadingJournal(file).use { journal ->
                journal.captureWorkoutUpdate(installation, session("Walking", now - 7_200_000), emptyList())
                journal.captureWorkoutUpdate(installation, session("Running", now - 3_600_000), emptyList())
            }
            compose.setContent { WatchEnvironment { WatchHistoryScreen(file, installation, display) } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("history-kind").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("history-details-0").assertIsDisplayed()
            pressAndCrossingStayInert("history-details-0") {
                compose.onAllNodesWithTag("history-close-details").fetchSemanticsNodes().isNotEmpty()
            }
            reversalKeepsPage("watch-history-pager")
            compose.onNodeWithTag("history-details-0").assertIsDisplayed()
            // Move to the second session, open its details, and return to the same session.
            compose.onNodeWithTag("watch-history-pager").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            compose.onNodeWithTag("history-details-1").assertIsDisplayed().performClick()
            compose.onNodeWithTag("history-close-details").performScrollTo().assertIsDisplayed()
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            compose.onAllNodesWithTag("history-close-details").assertCountEquals(0)
            compose.onNodeWithTag("history-details-1").assertIsDisplayed()
        } finally { file.delete() }
    }

    @Test fun recoveryRouteKeepsItsDayThroughCancellationReversalAndDetailReturn() {
        val day = RecoveryDay(today, now - 13 * 3600000, now - 5 * 3600000, 1,
            mapOf("light" to 4 * 3600000L, "deep" to 2 * 3600000L, "rem" to 3600000L, "awake" to 3600000L), 84.0, 77.0)
        val previous = RecoveryDay(today.minusDays(1), day.start!! - 86400000, day.end!! - 86400000, 1,
            mapOf("unknown" to 8 * 3600000L), null, null)
        compose.setContent {
            WatchEnvironment { WatchRecoveryScreen(HealthContext("UTC", now, now, listOf(day, previous)), false, null, display) {} }
        }
        compose.onNodeWithTag("recovery-date-0").assertIsDisplayed()
        val firstDate = compose.onNodeWithTag("recovery-date-0", useUnmergedTree = true).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text
        pressAndCrossingStayInert("recovery-details-0") {
            compose.onAllNodesWithTag("recovery-detail-sleep").fetchSemanticsNodes().isNotEmpty()
        }
        reversalKeepsPage("watch-recovery")
        compose.onNodeWithTag("recovery-date-0").assertIsDisplayed()
        assertEquals("A reversed drag changed the settled day", firstDate,
            compose.onNodeWithTag("recovery-date-0", useUnmergedTree = true).fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text)
        compose.onNodeWithTag("recovery-details-0").performScrollTo().performClick()
        compose.onNodeWithTag("recovery-detail-sleep").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onAllNodesWithTag("recovery-detail-sleep").assertCountEquals(0)
        assertEquals("Back from details lost the selected day", firstDate,
            compose.onNodeWithTag("recovery-date-0", useUnmergedTree = true).fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text)
    }

    @Test fun todayActionsOnlyRunOnADeliberateTap() {
        var pulses = 0
        var workouts = 0
        var recoveries = 0
        val steps = JSONObject().put("value", 4210).put("end", now - 120_000).put("quality", "valid")
            .put("unit", "steps").put("boot", "boot-1")
        compose.setContent {
            WatchEnvironment {
                WorkoutPage(compact = true) {
                    WatchToday(mapOf("steps" to steps), now, loading = false, error = null,
                        pulse = { pulses++ }, workout = { workouts++ }, recovery = { recoveries++ })
                }
            }
        }
        compose.onNodeWithTag("today-steps").assertIsDisplayed()
        compose.onNodeWithText("Check pulse").performScrollTo().performTouchInput { down(center); advanceEventTime(400); cancel() }
        compose.waitForIdle()
        assertEquals(0, pulses)
        compose.onNodeWithText("Check pulse").performScrollTo().performTouchInput { down(center); moveBy(Offset(0f, -90f), 120); up() }
        compose.waitForIdle()
        assertEquals(0, pulses)
        compose.onNodeWithText("Check pulse").performScrollTo().performClick()
        assertEquals(1, pulses)
        assertEquals(0, workouts)
        assertEquals(0, recoveries)
    }
}
