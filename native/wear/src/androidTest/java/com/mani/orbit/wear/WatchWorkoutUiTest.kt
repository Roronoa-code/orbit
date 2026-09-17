package com.mani.orbit.wear

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.ExerciseTrackedStatus
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WatchWorkoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<WatchWorkoutActivity>()
    @Before fun emulatorOnly() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(compose.activity.packageName, Manifest.permission.ACTIVITY_RECOGNITION)
        automation.grantRuntimePermission(compose.activity.packageName, WatchPermissions.heart)
        automation.grantRuntimePermission(compose.activity.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }
    @Test fun touchControlsPagerAndFinishConfirmation() {
        val client = HealthServices.getClient(compose.activity).exerciseClient
        try {
            compose.waitUntil(5000) { compose.onAllNodesWithTag("watch-workout-clock").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("watch-choose-Walking").fetchSemanticsNodes().isNotEmpty() }
            if (compose.onAllNodesWithTag("watch-workout-clock").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("watch-workout-pager").performTouchInput { swipeLeft() }
                compose.onNodeWithText("Done").performScrollTo().performClick()
            }
            compose.onNodeWithTag("watch-choose-Running").performScrollTo().performClick()
            capture("setup")
            compose.onNodeWithTag("watch-start").performScrollTo().performClick()
            compose.waitUntil(25000) { WatchWorkoutService.state.value.workout?.phase == "active" }
            compose.onNodeWithTag("watch-workout-clock").performScrollTo().assertIsDisplayed()
            capture("live")
            compose.onNodeWithTag("watch-workout-pager").performTouchInput { swipeLeft() }
            compose.onNodeWithTag("watch-pause-resume").performScrollTo().performClick()
            compose.waitUntil(15000) { WatchWorkoutService.state.value.workout?.phase == "paused" }
            capture("controls")
            compose.activityRule.scenario.onActivity { it.startActivity(android.content.Intent(it, WatchWorkoutActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("watch-workout-clock").fetchSemanticsNodes().any { it.boundsInRoot.left >= 0 && it.boundsInRoot.right <= 384 } }
            compose.onNodeWithTag("watch-workout-clock").assertIsDisplayed()
            compose.onNodeWithTag("watch-workout-pager").performTouchInput { swipeLeft() }
            compose.onNodeWithTag("watch-pause-resume").assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("watch-pause-resume").performScrollTo().assertIsDisplayed()
            check(WatchWorkoutService.state.value.workout?.phase == "paused")
            compose.onNodeWithTag("watch-back-surface").performTouchInput {
                swipe(Offset(2f, height * .5f), Offset(width - 2f, height * .5f), 350)
            }
            compose.onNodeWithTag("watch-workout-clock").performScrollTo().assertIsDisplayed()
            check(WatchWorkoutService.state.value.workout?.phase == "paused")
            compose.onNodeWithTag("watch-workout-pager").performTouchInput { swipeLeft() }
            compose.onNodeWithTag("watch-finish").performScrollTo().performClick()
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("watch-confirm-finish").assertDoesNotExist()
            check(WatchWorkoutService.state.value.workout?.phase == "paused")
            compose.onNodeWithTag("watch-finish").performScrollTo().performClick()
            compose.onNodeWithText("Keep going").performScrollTo().performClick()
            compose.onNodeWithText("Resume").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("watch-finish").performScrollTo().performClick()
            compose.onNodeWithTag("watch-confirm-finish").performScrollTo().performClick()
            compose.waitUntil(15000) { WatchWorkoutService.state.value.workout?.phase == "ended" }
            compose.onNodeWithText("Done").performScrollTo().assertIsDisplayed()
        } finally {
            if (client.getCurrentExerciseInfoAsync().get(10, TimeUnit.SECONDS).exerciseTrackedStatus == ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS)
                client.endExerciseAsync().get(10, TimeUnit.SECONDS)
        }
    }
    private fun capture(name: String) { compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
        File(compose.activity.cacheDir, "workout-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    } }
}
