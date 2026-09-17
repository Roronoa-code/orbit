package com.mani.orbit.wear

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.mani.health.core.model.deviceSensorLeaseGate
import com.mani.health.integration.samsungsensor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WatchRawSensorTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun context(): Context {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        return ApplicationProvider.getApplicationContext<Context>().also { context ->
            SensorRawProbe.entries.flatMap { recordingPermissions(it).toList() }.distinct().forEach {
                InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, it)
            }
        }
    }
    @Test fun allRawModesRespectOwnershipAndCancelBeforeSamsungStartup() = runBlocking {
        val context = context()
        for (probe in SensorRawProbe.entries) {
            val source = SensorSdkRawProbeSource(context)
            try {
                source.chunks(probe, beforeStart = { throw SensorSdkRawProbeException.SessionAlreadyActive() }).collect()
                fail("Occupied workout must reject raw collection")
            } catch (_: SensorSdkRawProbeException.SessionAlreadyActive) { }
            requireNotNull(deviceSensorLeaseGate.tryAcquire()).close()
            val entered = CountDownLatch(1)
            val task = launch(Dispatchers.Default) {
                source.chunks(probe, beforeStart = { entered.countDown(); Thread.sleep(30_000) }).collect()
            }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                assertNull(deviceSensorLeaseGate.tryAcquire())
            } finally { withTimeout(5000) { task.cancelAndJoin() } }
            requireNotNull(deviceSensorLeaseGate.tryAcquire()).close()
        }
    }
    @Test fun nativePickerStartsEachExplicitModeAndRecoversWithoutFakeReadings() {
        val context = context()
        for (probe in SensorRawProbe.entries) {
            val intent = Intent(context, WatchHeartActivity::class.java).putExtra("selectRaw", true)
            ActivityScenario.launch<WatchHeartActivity>(intent).use { scenario ->
                compose.onAllNodesWithTag("watch-back-surface").assertCountEquals(1)
                compose.onNodeWithTag("raw-${probe.name}").performScrollTo().performClick()
                compose.onNodeWithText(recordingTitle(probe)).assertIsDisplayed()
                compose.onNodeWithTag("live-heart-action").performTouchInput { down(center); advanceEventTime(400); cancel() }
                assertFalse(WatchHeartService.state.value.active)
                compose.onNodeWithTag("watch-back-surface").performTouchInput {
                    down(Offset(2f, height * .5f)); moveTo(Offset(width * .25f, height * .5f), 160); cancel()
                }
                compose.onNodeWithText(recordingTitle(probe)).assertIsDisplayed()
                compose.onNodeWithTag("watch-back-surface").performTouchInput {
                    swipe(Offset(2f, height * .5f), Offset(width - 2f, height * .5f), 350)
                }
                compose.onNodeWithText("Sensor recordings").assertIsDisplayed()
                compose.onNodeWithTag("raw-${probe.name}").performScrollTo().performClick()
                compose.onNodeWithText("Start").performScrollTo().assertIsEnabled()
                if (probe == SensorRawProbe.PPG_CONTINUOUS) capture("raw-watch-ready", context)
                compose.onNodeWithText("Start").performClick()
                compose.waitUntil(40_000) { WatchHeartService.state.value.let { it.probe == probe && it.message != null && !it.active } }
                assertEquals(probe, WatchHeartService.state.value.probe)
                assertNull(WatchHeartService.state.value.reading)
                assertEquals(0L, WatchHeartService.state.value.samples)
                compose.onNodeWithText("Start").assertIsEnabled()
                if (probe == SensorRawProbe.PPG_CONTINUOUS) capture("raw-watch-unavailable", context)
                scenario.recreate()
                compose.onNodeWithText(recordingTitle(probe)).assertIsDisplayed()
                compose.onNodeWithText("Start").assertIsEnabled()
                requireNotNull(deviceSensorLeaseGate.tryAcquire()).close()
            }
        }
    }
    @Test fun edgeBackLeavesExplicitForegroundRecordingAliveDuringConnection() {
        val context = context()
        val lease = requireNotNull(deviceSensorLeaseGate.acquire(TimeUnit.SECONDS.toNanos(5)))
        try {
            ActivityScenario.launch<WatchHeartActivity>(Intent(context, WatchHeartActivity::class.java)
                .putExtra("probe", SensorRawProbe.PPG_CONTINUOUS.name)).use { scenario ->
                compose.onNodeWithText("Start").performScrollTo().performClick()
                compose.waitUntil(5000) { WatchHeartService.state.value.phase == "starting" }
                compose.onNodeWithTag("watch-back-surface").performTouchInput {
                    swipe(Offset(2f, height * .5f), Offset(width - 2f, height * .5f), 350)
                }
                compose.waitUntil(5000) { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
                assertTrue("Back must not stop the foreground recording", WatchHeartService.state.value.active)
                assertEquals(0L, WatchHeartService.state.value.samples)
            }
        } finally {
            try {
                WatchHeartService.stop(context)
                compose.waitUntil(10_000) { !WatchHeartService.state.value.active }
            } finally { lease.close() }
        }
        requireNotNull(deviceSensorLeaseGate.acquire(TimeUnit.SECONDS.toNanos(5))).close()
    }
    private fun capture(name: String, context: Context) {
        compose.waitForIdle()
        File(context.cacheDir, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
