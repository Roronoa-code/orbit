package com.mani.orbit.wear

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.ongoing.OngoingActivity
import com.mani.orbit.sync.WatchWorkout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WatchAmbientTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    @Before fun emulatorOnly() { check(Build.HARDWARE in setOf("ranchu", "goldfish")) }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText().trim() }
    private fun phase(wanted: String) = compose.waitUntil(25000) {
        WatchWorkoutService.state.value.let { it.workout?.phase == wanted && !it.busy }
    }
    private fun capture(name: String): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap().also { bitmap ->
        File(context.cacheDir, "watch-ambient-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun buildingOngoingMetadataDoesNotPostItsOwnNotification() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = "orbit-notification-check"
        val id = 9191
        manager.createNotificationChannel(android.app.NotificationChannel(channel, "Local check", NotificationManager.IMPORTANCE_LOW))
        try {
            manager.cancel(id)
            val notification = WatchWorkoutNotification(context, channel, id)
            val w = WatchWorkout(UUID.randomUUID().toString(), 1, "Walking", UUID.randomUUID().toString(),
                10000, 1000, 40000, 31000, 30000, "active", false)
            notification.build(w)
            val paused = notification.build(w.copy(phase = "paused"))
            assertEquals("Paused · tap to return", paused.extras.getString(android.app.Notification.EXTRA_TEXT))
            assertNotNull(paused.contentIntent)
            assertFalse("Only the recording service may publish its foreground notification",
                manager.activeNotifications.any { it.id == id })
        } finally { manager.cancel(id); manager.deleteNotificationChannel(channel) }
    }
    private fun ongoingWith(text: String): OngoingActivity {
        var result: OngoingActivity? = null
        compose.waitUntil(5000) {
            result = OngoingActivity.recoverOngoingActivity(context, 32)
            result?.status?.getText(context, SystemClock.elapsedRealtime())?.contains(text) == true
        }
        return requireNotNull(result)
    }

    @Test fun systemAmbientKeepsRecordingAndOngoingIntentReturnsToLive() {
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACTIVITY_RECOGNITION)
        automation.grantRuntimePermission(context.packageName, WatchPermissions.heart)
        if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val client = HealthServices.getClient(context).exerciseClient
        check(client.getCurrentExerciseInfoAsync().get(5, TimeUnit.SECONDS).exerciseTrackedStatus == ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS)
        val enabled = shell("settings get global ambient_enabled")
        shell("settings put global ambient_enabled 1")
        try {
            ActivityScenario.launch(WatchWorkoutActivity::class.java).use { scenario ->
                scenario.onActivity { WatchWorkoutService.send(it, WatchWorkoutService.START, kind = "Walking") }
                phase("active")
                val started = WatchWorkoutService.state.value.workout!!
                val ongoing = ongoingWith("Walking")
                assertTrue(ongoing.status!!.getText(context, SystemClock.elapsedRealtime()).contains("Walking"))
                assertEquals(1, context.getSystemService(NotificationManager::class.java).activeNotifications.count { it.id == 32 })
                shell("input keyevent KEYCODE_SLEEP")
                compose.waitUntil(10000) { compose.onAllNodesWithTag("watch-ambient").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
                compose.onNodeWithTag("watch-workout-clock").assertDoesNotExist()
                compose.onNodeWithText("Pause").assertDoesNotExist()
                capture("system")
                SystemClock.sleep(2200)
                assertEquals(ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS, client.getCurrentExerciseInfoAsync().get(5, TimeUnit.SECONDS).exerciseTrackedStatus)
                shell("input keyevent KEYCODE_WAKEUP")
                compose.waitUntil(10000) { compose.onAllNodesWithTag("watch-workout-clock").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
                compose.waitUntil(5000) {
                    var focused = false
                    if (scenario.state == androidx.lifecycle.Lifecycle.State.RESUMED) scenario.onActivity { focused = it.hasWindowFocus() }
                    focused
                }
                compose.onNodeWithTag("watch-workout-pager").performTouchInput { swipeLeft() }
                capture("after-wake-swipe")
                compose.onNodeWithTag("watch-pause-resume").performScrollTo().performClick()
                phase("paused")
                val paused = ongoingWith("Paused").status!!
                val now = SystemClock.elapsedRealtime()
                assertEquals(paused.getText(context, now).toString(), paused.getText(context, now + 60_000).toString())
                assertTrue(paused.getText(context, now).contains("Paused"))
                shell("input keyevent KEYCODE_HOME")
                // Input dispatch returns before Home has finished taking the foreground.
                compose.waitUntil(5000) { scenario.state == androidx.lifecycle.Lifecycle.State.CREATED }
                ongoing.touchIntent.send()
                compose.waitUntil(10000) { compose.onAllNodesWithTag("watch-workout-clock").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
                compose.onNodeWithTag("watch-workout-phase").assertTextEquals("Paused")
                capture("returned")
                scenario.onActivity { WatchWorkoutService.send(it, WatchWorkoutService.FINISH, id = started.id) }
                phase("ended")
                compose.waitUntil(5000) { OngoingActivity.recoverOngoingActivity(context, 32) == null }
            }
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            shell(if (enabled == "null") "settings delete global ambient_enabled" else "settings put global ambient_enabled $enabled")
            if (client.getCurrentExerciseInfoAsync().get(5, TimeUnit.SECONDS).exerciseTrackedStatus == ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS)
                client.endExerciseAsync().get(5, TimeUnit.SECONDS)
            context.stopService(Intent(context, WatchWorkoutService::class.java))
        }
    }

    @Test fun ambientRoutesKeepTextInsideRoundBoundsAtBothSizesAndLargeText() {
        val width = mutableStateOf(192)
        val scale = mutableStateOf(1f)
        val route = mutableStateOf("Orbit" to "Your health, on your wrist")
        val display = mutableStateOf(WatchDisplay(AmbientMode.Ambient(true, true), 1789450200000, 60_000))
        val routes = listOf("Orbit" to "Your health, on your wrist", "Walking" to "Recording",
            "Running" to "Recording interrupted", "Cycling" to "Reconnecting…", "Strength" to "Paused",
            "Workout history" to "Saved on watch", "Sleep & energy" to "Open for saved readings")
        ActivityScenario.launch(WatchWorkoutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val pixels = activity.windowManager.currentWindowMetrics.bounds.width().toFloat()
                activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(pixels / width.value, scale.value)) {
                        WatchAmbientScreen(route.value.first, route.value.second, display.value)
                    }
                }
            }
            for (dpWidth in listOf(192, 228)) for (font in listOf(1f, 1.5f)) for ((i, value) in routes.withIndex()) {
                compose.runOnIdle { width.value = dpWidth; scale.value = font; route.value = value }
                for (minute in listOf(1L, 3L)) {
                    compose.runOnIdle { display.value = display.value.copy(elapsed = minute * 60_000) }
                    compose.onNodeWithTag("watch-ambient").assertContentDescriptionContains(value.second, substring = true)
                    val viewport = compose.onNodeWithTag("watch-safe-viewport").fetchSemanticsNode().boundsInRoot
                    val bitmap = capture("bounds-$dpWidth-$font-$i-$minute")
                    var lit = 0
                    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                        val pixel = bitmap.getPixel(x, y) and 0xFFFFFF
                        assertTrue("Low bit", pixel == 0 || pixel == 0xFFFFFF)
                        if (pixel != 0) {
                            lit++
                            assertTrue("$dpWidth/$font/$value text clipped at $x,$y", x >= viewport.left && x < viewport.right && y >= viewport.top && y < viewport.bottom)
                            assertTrue("Keep the complete text inside the circular display",
                                kotlin.math.hypot(x - bitmap.width / 2f, y - bitmap.height / 2f) < bitmap.width / 2f - 2)
                        }
                    }
                    assertTrue("Ambient remains mostly dark", lit in 100..(bitmap.width * bitmap.height * .15).toInt())
                }
            }
        }
    }

    @Test fun lowBitLayoutIsDarkShiftsSafelyAndStopwatchUsesElapsedTime() {
        val display = mutableStateOf(WatchDisplay(AmbientMode.Ambient(true, true), 1789450200000, 60_000))
        ActivityScenario.launch(WatchWorkoutActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { WatchAmbientScreen("Walking", "Recording", display.value) } }
            val before = capture("low-bit")
            val pixels = IntArray(before.width * before.height)
            before.getPixels(pixels, 0, before.width, 0, 0, before.width, before.height)
            assertTrue("Ambient must leave at least 85% black", pixels.count { it and 0xFFFFFF == 0 }.toDouble() / pixels.size >= .85)
            assertTrue("Low-bit text must not introduce shaded edge pixels", pixels.all { it and 0xFFFFFF in setOf(0, 0xFFFFFF) })
            compose.runOnIdle { display.value = display.value.copy(elapsed = 120_000) }
            val after = capture("shifted")
            assertFalse(before.sameAs(after))
        }
        fun id() = UUID.randomUUID().toString()
        val w = WatchWorkout(id(), 1, "Walking", id(), 10000, 1000, 40000, 31000, 30000, "active", false)
        val running = WatchWorkoutNotification.status(w)
        assertNotEquals(running.getText(context, 31000).toString(), running.getText(context, 91000).toString())
        val paused = WatchWorkoutNotification.status(w.copy(phase = "paused"))
        assertEquals(paused.getText(context, 31000).toString(), paused.getText(context, 91000).toString())
    }
}
