package com.mani.orbit.wear

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WatchUiTest {
    @get:Rule val rule = createAndroidComposeRule<WatchActivity>()
    @Test fun pulseNavigationAndBackgroundStopUseTheRealNativeScreen() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val activity = rule.activity
        val store = WatchStore(activity)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(activity.packageName, WatchPermissions.heart)
        rule.onNodeWithText("Check pulse").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText("Measure").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText("Stop").performScrollTo().assertIsDisplayed()
        rule.waitUntil(20_000) { store.status("measure") in setOf("Measuring", "Finding your pulse…") }
        automation.takeScreenshot().let { bitmap ->
            File(activity.cacheDir, "watch-pulse.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        // Rapid restart must not let the previous owner's cleanup stop the new request.
        repeat(2) {
            rule.onNodeWithText("Stop").performScrollTo().performClick()
            val started = android.os.SystemClock.elapsedRealtime()
            rule.onNodeWithText("Measure").performScrollTo().performClick()
            rule.waitUntil(20_000) {
                store.journal().use { it.latest("heart") }?.let {
                    it.optLong("elapsedMs") >= started && it.optString("boot") == store.clock().boot &&
                        it.optString("source") == "health_services"
                } == true
            }
            rule.onNodeWithText("Stop").performScrollTo().assertIsDisplayed()
        }
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.waitUntil(20_000) { store.status("measure") == "Measurement stopped" }
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.onNodeWithText("Measure").performScrollTo().assertIsDisplayed()
        assertEquals("Measurement stopped", store.status("measure"))
        rule.activityRule.scenario.recreate()
        rule.onNodeWithText("Measure").performScrollTo().assertIsDisplayed()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("Check pulse").performScrollTo().assertIsDisplayed()
        repeat(2) { rule.onNodeWithTag("watch-home-pager").performTouchInput { swipeLeft() } }
        rule.onNodeWithText("How sync works").performScrollTo().performClick()
        rule.onNodeWithText("Google Play services uses Bluetooth", substring = true).performScrollTo().assertIsDisplayed()
        rule.activityRule.scenario.recreate()
        rule.onNodeWithText("No Orbit cloud account", substring = true).performScrollTo().assertIsDisplayed()
        automation.takeScreenshot().let { bitmap ->
            File(activity.cacheDir, "watch-sync-privacy.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        rule.onNodeWithText("Hide sync details").performScrollTo().performClick()
        rule.onNodeWithText("Google Play services uses Bluetooth", substring = true).assertDoesNotExist()
    }
}
