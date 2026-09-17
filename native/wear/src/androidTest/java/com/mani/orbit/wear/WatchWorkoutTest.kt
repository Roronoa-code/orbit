package com.mani.orbit.wear

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.ExerciseTrackedStatus
import com.mani.orbit.sync.WatchWorkout
import com.mani.orbit.sync.WorkoutWire
import com.mani.orbit.sync.WorkoutControl
import com.mani.orbit.sync.WorkoutControlWire
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*

@RunWith(AndroidJUnit4::class)
class WatchWorkoutTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Before fun emulatorOnly() { check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Local Wear emulator only" } }
    private fun awaitPhase(phase: String): WatchWorkout {
        val deadline = android.os.SystemClock.elapsedRealtime() + 25000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val state = WatchWorkoutService.state.value
            if (state.workout?.phase == phase && !state.busy) return state.workout
            Thread.sleep(100)
        }
        error("Wanted $phase; actual ${WatchWorkoutService.state.value}")
    }
    private fun remote(workout: WatchWorkout, action: String) {
        val request = WorkoutControl(java.util.UUID.randomUUID().toString(), WatchStore(context).installation,
            workout.id, workout.boot, workout.phase, action)
        val ready = WatchWorkoutService.remote(WorkoutControlWire.decode(WorkoutControlWire.encode(request))).get(5, TimeUnit.SECONDS)
        assertEquals("ready", ready.stage)
        val commit = WorkoutControlWire.decode(WorkoutControlWire.encode(ready.copy(stage = "commit")))
        assertEquals("accepted", WatchWorkoutService.remote(commit).get(15, TimeUnit.SECONDS).stage)
        assertEquals("rejected", WatchWorkoutService.remote(commit).get(5, TimeUnit.SECONDS).stage)
    }
    private fun assertConfirmedTrace() {
        val op = WatchWorkoutService.state.value.operation
        assertTrue("Actual service confirmation must carry an anonymous operation", op > 0)
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        var stages = emptyList<String>()
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val events = com.mani.orbit.sync.NativeDiagnostics.trace.snapshot().getJSONArray("events")
            stages = (0 until events.length()).map { events.getJSONObject(it) }.filter { it.getLong("op") == op }.map { it.getString("stage") }
            if (stages.lastOrNull() == "DISPLAY_UPDATED") break
            Thread.sleep(50)
        }
        assertTrue(stages.containsAll(listOf("COMMAND_REQUESTED", "API_ACCEPTED", "PLATFORM_CONFIRMED", "DURABLE_COMMIT", "DISPLAY_UPDATED")))
        assertTrue(stages.indexOf("API_ACCEPTED") < stages.indexOf("PLATFORM_CONFIRMED"))
        assertTrue(stages.indexOf("PLATFORM_CONFIRMED") < stages.indexOf("DURABLE_COMMIT"))
        assertTrue(stages.indexOf("DURABLE_COMMIT") < stages.indexOf("DISPLAY_UPDATED"))
    }

    @Test fun actualExerciseSurvivesBackgroundAndRecreationThenSavesAndQueues() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACTIVITY_RECOGNITION)
        automation.grantRuntimePermission(context.packageName, WatchPermissions.heart)
        if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val client = HealthServices.getClient(context).exerciseClient
        check(client.getCurrentExerciseInfoAsync().get(10, TimeUnit.SECONDS).exerciseTrackedStatus == ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS)
        // History queries intentionally cap a page at 100. Count durable rows, not that page.
        fun sessionCount() = android.database.sqlite.SQLiteDatabase.openDatabase(
            context.getDatabasePath("watch-readings.db").path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        ).use { db -> db.rawQuery("SELECT COUNT(*) FROM workouts", null).use { it.moveToFirst(); it.getLong(0) } }
        val feedback = CopyOnWriteArrayList<Boolean>()
        val listener = CoroutineScope(Dispatchers.Unconfined).launch {
            WatchWorkoutService.feedback.collect { success ->
                if (success) {
                    val shown = requireNotNull(WatchWorkoutService.state.value.workout)
                    val store = WatchStore(context)
                    store.journal().use { assertEquals("Feedback follows the durable phase", shown.phase,
                        it.workout(store.installation, shown.id)?.phase) }
                }
                feedback.add(success)
            }
        }
        ActivityScenario.launch(WatchWorkoutActivity::class.java).use { scenario ->
            try {
                WatchStore(context).journal().close()
                val before = sessionCount()
                scenario.onActivity {
                    WatchWorkoutService.send(it, WatchWorkoutService.START, kind = "Walking")
                    WatchWorkoutService.send(it, WatchWorkoutService.START, kind = "Walking")
                }
                val started = awaitPhase("active")
                assertConfirmedTrace()
                assertEquals("A repeated Start must not create two sessions", before + 1, sessionCount())
                assertFalse(started.gps)
                assertEquals("Repeated Start confirms once", listOf(true), feedback.toList())
                scenario.onActivity { WatchWorkoutService.send(it, WatchWorkoutService.PAUSE, id = java.util.UUID.randomUUID().toString()) }
                val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                while (feedback.size < 2 && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
                assertEquals("Stale control rejects without success", listOf(true, false), feedback.toList())
                assertEquals("active", WatchWorkoutService.state.value.workout?.phase)
                scenario.moveToState(Lifecycle.State.CREATED)
                Thread.sleep(4000)
                assertEquals(ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS, client.getCurrentExerciseInfoAsync().get(10, TimeUnit.SECONDS).exerciseTrackedStatus)
                scenario.moveToState(Lifecycle.State.RESUMED); scenario.recreate()
                remote(WatchWorkoutService.state.value.workout!!, "pause")
                val paused = awaitPhase("paused")
                assertConfirmedTrace()
                assertTrue(paused.activeMs >= 3000)
                val store = WatchStore(context)
                store.journal().use {
                    val durable = requireNotNull(it.workout(store.installation, started.id))
                    // Sensor callbacks can advance a paused revision between observing UI and reading SQLite.
                    assertEquals(paused.id, durable.id)
                    assertEquals(paused.boot, durable.boot)
                    assertEquals("paused", durable.phase)
                    assertEquals(paused.activeMs, durable.activeMs)
                    assertTrue("Displayed state must already be durable", durable.revision >= paused.revision)
                }
                Thread.sleep(1200)
                assertEquals(paused.activeMs, WatchWorkoutService.state.value.workout!!.activeMs)
                remote(paused, "resume")
                awaitPhase("active"); Thread.sleep(2000)
                remote(WatchWorkoutService.state.value.workout!!, "finish")
                val ended = awaitPhase("ended")
                assertConfirmedTrace()
                assertTrue(ended.activeMs > paused.activeMs)
                store.journal().use {
                    assertEquals(ended, it.workout(store.installation, started.id))
                }
                // Verify this terminal revision, not just an arbitrary old packet in the first queue page.
                android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("watch-readings.db").path,
                    null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT bytes FROM outbox WHERE path=? AND instr(CAST(bytes AS TEXT),?)>0",
                        arrayOf(WorkoutWire.PATH, ended.id)).use { cursor ->
                        var queued = false
                        while (cursor.moveToNext()) {
                            val packet = WorkoutWire.decode(cursor.getBlob(0))
                            if (packet.installation == store.installation && packet.workout == ended) queued = true
                        }
                        assertTrue("The confirmed terminal revision must be queued", queued)
                    }
                }
                assertEquals(ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS, client.getCurrentExerciseInfoAsync().get(10, TimeUnit.SECONDS).exerciseTrackedStatus)
                assertEquals("Phone commands, passive updates and recreation do not confirm on Watch", listOf(true, false), feedback.toList())
            } finally {
                listener.cancel()
                if (client.getCurrentExerciseInfoAsync().get(10, TimeUnit.SECONDS).exerciseTrackedStatus == ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS)
                    client.endExerciseAsync().get(10, TimeUnit.SECONDS)
                context.stopService(Intent(context, WatchWorkoutService::class.java))
            }
        }
    }
}
