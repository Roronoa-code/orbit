package com.mani.orbit.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.mani.health.core.model.deviceSensorLeaseGate
import com.mani.health.core.model.measurement.MeasurementTracker
import com.mani.health.integration.samsungsensor.OnDemandException
import com.mani.health.integration.samsungsensor.SensorSdkOnDemandSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MeasurementOwnershipTest {
    @Test fun pulseCannotStealTheSensorAndStartupCancellationReturnsItsLease() = runBlocking {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, WatchPermissions.heart)
        requireNotNull(deviceSensorLeaseGate.tryAcquire()).use {
            try { HeartMeasurement(context).run(); fail("Pulse must not take an occupied sensor") }
            catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("Another measurement")) }
        }
        val source = SensorSdkOnDemandSource(context)
        try {
            source.frames(MeasurementTracker.SPO2, beforeStart = { throw OnDemandException("WORKOUT_ACTIVE") }).collect()
            fail("Workout guard must reject startup")
        } catch (expected: OnDemandException) { assertEquals("WORKOUT_ACTIVE", expected.code) }
        requireNotNull(deviceSensorLeaseGate.tryAcquire()).close()
        val entered = CountDownLatch(1)
        val task = launch(Dispatchers.Default) {
            source.frames(MeasurementTracker.SPO2, beforeStart = { entered.countDown(); Thread.sleep(30_000) }).collect()
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            assertNull(deviceSensorLeaseGate.tryAcquire())
        } finally { withTimeout(5000) { task.cancelAndJoin() } }
        requireNotNull(deviceSensorLeaseGate.tryAcquire()).close()
    }
}
