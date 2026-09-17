package com.mani.orbit.wear

import android.Manifest
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.mani.health.core.model.deviceSensorLeaseGate
import com.mani.health.integration.samsungsensor.OnDemandException
import com.mani.orbit.sync.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class WatchSweatTest {
    // Background sync briefly owns this same lease for orphan recovery. Wait for ownership;
    // an actual leaked SDK lease still fails the bounded wait.
    private fun lease() = requireNotNull(deviceSensorLeaseGate.acquire(TimeUnit.SECONDS.toNanos(5)))
    private fun context() = ApplicationProvider.getApplicationContext<Context>().also {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.grantRuntimePermission(it.packageName, Manifest.permission.ACTIVITY_RECOGNITION)
        ui.grantRuntimePermission(it.packageName, WatchPermissions.heart)
        ui.grantRuntimePermission(it.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun awaitPhase(phase: String): WatchWorkout {
        val deadline = android.os.SystemClock.elapsedRealtime() + 45_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val state = WatchWorkoutService.state.value
            if (state.workout?.phase == phase && !state.busy) return state.workout
            Thread.sleep(100)
        }
        error("Wanted $phase; actual ${WatchWorkoutService.state.value}")
    }
    @Test fun nativeRunRemainsUsableWithoutSamsungProfileOrService() {
        val context = context()
        ActivityScenario.launch(WatchWorkoutActivity::class.java).use { scenario ->
            var started: WatchWorkout? = null
            try {
                scenario.onActivity { WatchWorkoutService.send(it, WatchWorkoutService.START, kind = "Running") }
                started = awaitPhase("active")
                val store = WatchStore(context)
                val estimate = store.journal().use { SweatJournal(it).forWorkout(store.installation, started) }!!
                assertTrue(estimate.terminal); assertEquals("unavailable", estimate.phase); assertNull(estimate.millilitres)
                scenario.moveToState(Lifecycle.State.CREATED); Thread.sleep(1200)
                scenario.moveToState(Lifecycle.State.RESUMED); scenario.recreate()
                assertEquals(started.id, awaitPhase("active").id)
                scenario.onActivity { WatchWorkoutService.send(it, WatchWorkoutService.PAUSE, id = started.id) }
                awaitPhase("paused")
                scenario.onActivity { WatchWorkoutService.send(it, WatchWorkoutService.RESUME, id = started.id) }
                awaitPhase("active")
                scenario.onActivity { WatchWorkoutService.send(it, WatchWorkoutService.FINISH, id = started.id) }
                val ended = awaitPhase("ended")
                assertTrue(ended.activeMs > 0)
                store.journal().use {
                    assertEquals(ended, it.workout(store.installation, started.id))
                    assertEquals(estimate, SweatJournal(it).forWorkout(store.installation, ended))
                    assertTrue(it.pending(100, WorkoutWire.PATH).isNotEmpty())
                    assertTrue(it.pending(100, SweatWire.PATH).isNotEmpty())
                }
                lease().close()
            } finally {
                if (WatchWorkoutService.state.value.workout?.terminal == false && started != null) {
                    scenario.onActivity { WatchWorkoutService.send(it, WatchWorkoutService.FINISH, id = started.id) }; awaitPhase("ended")
                }
            }
        }
    }
    @Test fun actualUnsupportedSdkConnectionReleasesItsOwnerLease() {
        val context = context(); val lease = lease()
        try {
            SamsungSweatSession(context, MeasurementProfile(LocalDate.of(1995, 2, 3), "male", 180.0, 80.0), lease).use {
                fail("The emulator must not report Samsung sensor support")
            }
        } catch (error: Exception) {
            assertTrue(generateSequence<Throwable>(error) { it.cause }.take(8).any { it is OnDemandException })
        } finally { lease.close() }
        lease().close()
    }
}
