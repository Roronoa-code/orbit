package com.mani.orbit.wear

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.*
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mani.orbit.sync.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WatchPipelineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Before fun emulatorOnly() { check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Local emulator tests only" } }

    @Test fun healthServicesPointsKeepQualityDailySemanticsAndOfflineHistory() {
        val elapsed = SystemClock.elapsedRealtime()
        val store = WatchStore(context)
        val clock = store.clock()
        val points = DataPointContainer(listOf(
            SampleDataPoint(DataType.HEART_RATE_BPM, 79.0, Duration.ofMillis(elapsed), accuracy = HeartRateAccuracy(HeartRateAccuracy.SensorStatus.ACCURACY_HIGH)),
            IntervalDataPoint(DataType.STEPS_DAILY, 510L, Duration.ofMillis(-1000), Duration.ofMillis(elapsed)),
            IntervalDataPoint(DataType.DISTANCE_DAILY, 382.5, Duration.ofMillis(-1000), Duration.ofMillis(elapsed)),
            IntervalDataPoint(DataType.CALORIES_DAILY, 1240.0, Duration.ofMillis(-1000), Duration.ofMillis(elapsed)),
            IntervalDataPoint(DataType.FLOORS_DAILY, 2.5, Duration.ofMillis(-1000), Duration.ofMillis(elapsed))))
        assertEquals(5, WatchSamples.capture(context, points))
        val queued = store.journal().use { it.pendingCount() }
        assertEquals(0, WatchSamples.capture(context, points))
        store.journal().use {
            assertEquals(queued, it.pendingCount())
            val steps = it.latest("steps")!!
            assertEquals("daily", steps.getString("semantics"))
            assertEquals(clock.wallAt(-1000), steps.getLong("start"))
            assertEquals(510.0, steps.getDouble("value"), 0.0)
            assertEquals("health_services", steps.getString("source"))
            assertEquals(clock.boot, steps.getString("boot"))
            assertEquals(android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT), steps.getInt("bootCount"))
            assertEquals(steps.getString("id"), it.latest("steps", store.installation, clock.boot)!!.getString("id"))
            assertEquals(2.5, it.latest("floors")!!.getDouble("value"), 0.0)
            assertEquals("510", watchValue(steps, System.currentTimeMillis(), true))
            assertEquals("—", watchValue(steps, System.currentTimeMillis() + 2 * 86_400_000, true))
        }
        WatchSamples.capture(context, DataPointContainer(listOf(SampleDataPoint(DataType.HEART_RATE_BPM, 80.0, Duration.ofMillis(elapsed + 10),
            accuracy = HeartRateAccuracy(HeartRateAccuracy.SensorStatus.NO_CONTACT)))))
        store.journal().use {
            val heart = it.latest("heart")!!
            assertEquals("no_contact", heart.getString("quality"))
            assertEquals("—", watchValue(heart, System.currentTimeMillis()))
            assertEquals("Check watch contact", readingAge(heart, System.currentTimeMillis()))
        }
        val shifted = clock.copy(wall = clock.wall + 60_000, elapsed = elapsed + 100, changed = true)
        assertTrue(store.sample(shifted, "heart", elapsed, elapsed, 80.0, "valid").timeUncertain)
        assertEquals(store.installation, WatchStore(context).installation)
        assertEquals(clock, store.clock())
    }

    @Test fun actualMeasureClientOnWearEmulatorSavesCallbacksAndStopsWithItsOwner() = runBlocking {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, WatchPermissions.heart)
        ActivityScenario.launch(WatchActivity::class.java).use {
            val supported = HealthServices.getClient(context).measureClient.getCapabilitiesAsync().get(15, TimeUnit.SECONDS).supportedDataTypesMeasure
            assertTrue("The Wear AVD must offer heart measurement", DataType.HEART_RATE_BPM in supported)
            val started = System.currentTimeMillis()
            val measurement = launch(Dispatchers.Default) { HeartMeasurement(context).run() }
            try {
                withTimeout(25_000) {
                    while (true) {
                        val row = WatchStore(context).journal().use { it.latest("heart") }
                        if (row != null && row.getLong("end") >= started) {
                            assertTrue(row.getDouble("value") > 0)
                            assertEquals("health_services", row.getString("source"))
                            break
                        }
                        delay(250)
                    }
                }
            } finally { measurement.cancelAndJoin() }
            assertEquals("Measurement stopped", WatchStore(context).status("measure"))
            requireNotNull(com.mani.health.core.model.deviceSensorLeaseGate.tryAcquire()).close()
        }
    }
}
