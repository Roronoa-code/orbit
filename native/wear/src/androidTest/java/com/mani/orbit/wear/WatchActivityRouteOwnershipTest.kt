package com.mani.orbit.wear

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.mani.health.integration.samsungsensor.SensorRawProbe
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The W3 matrix on the routes that own an Activity. Nothing here starts a workout, a measurement or
 * a sensor stream: every gesture is cancelled or crosses a control, and the route must stay put.
 */
class WatchActivityRouteOwnershipTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun emulatorOnly() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, WatchPermissions.heart)
    }

    /** A cancelled press and a vertical drag across the control leave the route exactly as it was. */
    private fun inertPressAndCrossing(tag: String, stillThere: () -> Unit) {
        fun node() = compose.onNodeWithTag(tag)
        runCatching { node().performScrollTo() }
        node().performTouchInput { down(center); advanceEventTime(400); cancel() }
        compose.waitForIdle()
        stillThere()
        runCatching { node().performScrollTo() }
        node().performTouchInput { down(center); moveBy(Offset(0f, -90f), 120); up() }
        compose.waitForIdle()
        stillThere()
    }

    private fun reverseDrag(tag: String) {
        compose.onNodeWithTag(tag).performTouchInput {
            down(Offset(width * .8f, height * .5f))
            moveTo(Offset(width * .2f, height * .5f), 150)
            moveTo(Offset(width * .8f, height * .5f), 150)
            up()
        }
        compose.waitForIdle()
    }

    @Test fun homeRouteKeepsItsPageAndStartsNothingFromACancelledPress() {
        ActivityScenario.launch(WatchActivity::class.java).use {
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Check pulse").fetchSemanticsNodes().isNotEmpty() }
            inertPressAndCrossing("today-pulse") { compose.onNodeWithTag("today-pulse").assertExists() }
            reverseDrag("watch-home-pager")
            compose.onNodeWithText("Check pulse").assertIsDisplayed()
        }
    }

    @Test fun workoutRouteKeepsItsStateThroughACancelledPressAndAReversal() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.also { automation ->
            automation.grantRuntimePermission(context.packageName, android.Manifest.permission.ACTIVITY_RECOGNITION)
            automation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityScenario.launch(WatchWorkoutActivity::class.java).use {
            compose.waitUntil(20_000) {
                compose.onAllNodesWithTag("watch-choose-Walking").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithTag("watch-workout-clock").fetchSemanticsNodes().isNotEmpty()
            }
            // A finished session left by an earlier check is dismissed first, so the route starts at its choices.
            if (compose.onAllNodesWithTag("watch-choose-Walking").fetchSemanticsNodes().isEmpty()) {
                compose.onNodeWithTag("watch-workout-pager").performTouchInput { swipeLeft() }
                compose.waitForIdle()
                if (compose.onAllNodesWithText("Done").fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNodeWithText("Done").performScrollTo().performClick()
                    compose.waitUntil(10_000) { compose.onAllNodesWithTag("watch-choose-Walking").fetchSemanticsNodes().isNotEmpty() }
                }
            }
            if (compose.onAllNodesWithTag("watch-choose-Walking").fetchSemanticsNodes().isNotEmpty()) {
                // The choice list is not a pager, so its own scroll container owns the gesture.
                inertPressAndCrossing("watch-choose-Walking") {
                    compose.onNodeWithTag("watch-choose-Walking").assertExists()
                    compose.onAllNodesWithTag("watch-start").assertCountEquals(0)
                }
                compose.onNodeWithTag("watch-choose-Walking").performTouchInput {
                    down(center); moveTo(center + Offset(120f, 0f), 120); moveTo(center, 120); cancel()
                }
                compose.waitForIdle()
                compose.onNodeWithTag("watch-choose-Walking").assertExists()
                compose.onAllNodesWithTag("watch-start").assertCountEquals(0)
            } else {
                // A live session is still recording: the same gestures must not pause or finish it.
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("watch-pause-resume").fetchSemanticsNodes().isNotEmpty() }
                fun phase() = compose.onNodeWithTag("watch-workout-phase", useUnmergedTree = true)
                    .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text
                val settled = phase()
                inertPressAndCrossing("watch-pause-resume") {
                    assertEquals("A cancelled gesture changed the recording phase", settled, phase())
                    compose.onAllNodesWithTag("watch-confirm-finish").assertCountEquals(0)
                }
                reverseDrag("watch-workout-pager")
                compose.onNodeWithTag("watch-workout-clock").assertExists()
            }
        }
    }

    @Test fun measurementRouteKeepsItsMenuAndCollectsNothingFromACancelledPress() {
        ActivityScenario.launch(WatchMeasurementActivity::class.java).use {
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("measure-HEART").fetchSemanticsNodes().isNotEmpty() }
            inertPressAndCrossing("measure-HEART") {
                compose.onNodeWithTag("measure-HEART").assertExists()
                compose.onAllNodesWithText("Stop").assertCountEquals(0)
            }
        }
    }

    @Test fun ecgRouteRequiresADeliberateStart() {
        ActivityScenario.launch(WatchEcgActivity::class.java).use {
            compose.waitUntil(30_000) {
                compose.onAllNodesWithTag("start-ecg").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText("not available", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            if (compose.onAllNodesWithTag("start-ecg").fetchSemanticsNodes().isEmpty()) return  // unsupported service route
            inertPressAndCrossing("start-ecg") {
                compose.onNodeWithTag("start-ecg").assertExists()
                compose.onAllNodesWithText("Stop").assertCountEquals(0)
            }
        }
    }

    @Test fun continuousSensorRouteRequiresADeliberateStart() {
        val probe = SensorRawProbe.entries.first()
        val intent = Intent(context, WatchHeartActivity::class.java).putExtra("selectRaw", true)
        ActivityScenario.launch<WatchHeartActivity>(intent).use {
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("raw-" + probe.name).fetchSemanticsNodes().isNotEmpty() }
            inertPressAndCrossing("raw-" + probe.name) {
                compose.onNodeWithTag("raw-" + probe.name).assertExists()
                compose.onAllNodesWithText("Stop").assertCountEquals(0)
            }
        }
    }
}
