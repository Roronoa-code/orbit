package com.mani.orbit.wear

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
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

class WatchHeartTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun context(): Context {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        return ApplicationProvider.getApplicationContext<Context>().also {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(it.packageName, WatchPermissions.heart)
        }
    }
    @Test fun sharedSensorLeaseCoversGuardAndCancelledSamsungStartup() = runBlocking {
        val source = SensorSdkHeartRateSource(context())
        try {
            source.batches(beforeStart = { throw SensorSdkHeartRateException.SessionAlreadyActive() }).collect()
            fail("Occupied workout must reject collection")
        } catch (_: SensorSdkHeartRateException.SessionAlreadyActive) { }
        requireNotNull(deviceSensorLeaseGate.tryAcquire()).close()
        val entered = CountDownLatch(1)
        val task = launch(Dispatchers.Default) {
            source.batches(beforeStart = { entered.countDown(); Thread.sleep(30_000) }).collect()
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            assertNull(deviceSensorLeaseGate.tryAcquire())
        } finally { withTimeout(5000) { task.cancelAndJoin() } }
        requireNotNull(deviceSensorLeaseGate.tryAcquire()).close()
    }
    @Test fun actualLiveRouteStartsExplicitlyAndRecoversUnsupportedServiceWithoutReadings() {
        val context = context()
        ActivityScenario.launch(WatchHeartActivity::class.java).use { scenario ->
            compose.onNodeWithText("Live heart rate").assertIsDisplayed()
            compose.onNodeWithTag("live-heart-action").assertIsDisplayed().assertIsEnabled()
            capture("heart-watch-ready", context)
            compose.onNodeWithText("Start").performClick()
            compose.waitUntil(40_000) { WatchHeartService.state.value.message != null && !WatchHeartService.state.value.active }
            assertNull(WatchHeartService.state.value.reading)
            assertEquals(0L, WatchHeartService.state.value.callbacks)
            compose.onNodeWithText("Start").assertIsEnabled()
            capture("heart-watch-unavailable", context)
            scenario.recreate()
            compose.onNodeWithText("Start").assertIsEnabled()
            requireNotNull(deviceSensorLeaseGate.tryAcquire()).close()
        }
    }
    private fun capture(name: String, context: Context) {
        compose.waitForIdle()
        File(context.cacheDir, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
