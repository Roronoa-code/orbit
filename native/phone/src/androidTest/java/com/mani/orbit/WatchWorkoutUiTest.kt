package com.mani.orbit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mani.orbit.sync.WatchWorkout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WatchWorkoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun emulatorOnly() { check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) }
    @Test fun presentationTraceWaitsForTheMatchingConfirmedRevisionEvenAfterFinish() {
        fun id() = UUID.randomUUID().toString()
        val installation = id()
        val w = WatchWorkout(id(), 4, "Walking", id(), 10000, 1000, 16000, 7000, 6000, "active", false)
        val original = WatchWorkoutProjection.record(installation, w)
        val record = mutableStateOf(original)
        val op = com.mani.orbit.sync.NativeDiagnostics.begin(com.mani.orbit.sync.TraceFeature.WATCH_CONTROL, com.mani.orbit.sync.TraceRoute.WORKOUTS)
        com.mani.orbit.sync.NativeDiagnostics.mark(op, com.mani.orbit.sync.TraceStage.PLATFORM_CONFIRMED)
        com.mani.orbit.sync.NativeDiagnostics.mark(op, com.mani.orbit.sync.TraceStage.DURABLE_COMMIT)
        val control = WatchControlState(original.id, operation = op, confirmedPhase = "ended", priorRevision = 4)
        fun stage(): String {
            val entries = com.mani.orbit.sync.NativeDiagnostics.trace.snapshot().getJSONArray("operations")
            return (0 until entries.length()).map { entries.getJSONObject(it) }.first { it.getLong("op") == op }.getString("lastStage")
        }
        compose.setContent { WatchControlPresentation(record.value, control) }
        compose.waitForIdle(); assertEquals("DURABLE_COMMIT", stage())
        compose.runOnIdle { record.value = WatchWorkoutProjection.record(installation, w.copy(revision = 5)) }
        compose.waitForIdle(); assertEquals("DURABLE_COMMIT", stage())
        compose.runOnIdle { record.value = WatchWorkoutProjection.record(installation, w.copy(id = id(), revision = 5, phase = "ended")) }
        compose.waitForIdle(); assertEquals("DURABLE_COMMIT", stage())
        compose.runOnIdle { record.value = WatchWorkoutProjection.record(installation, w.copy(revision = 5, phase = "ended")) }
        compose.waitForIdle(); assertEquals("DISPLAY_UPDATED", stage())
    }
    @Test fun watchLiveStateUsesItsOwnSourceAndFlowsIntoHistory() {
        fun id() = UUID.randomUUID().toString()
        val installation = id()
        val now = System.currentTimeMillis()
        val w = WatchWorkout(id(), 1, "Walking", id(), now - 60000, 1000, now, 61000, 45000, "active", false,
            distance = 52.0, steps = 81, energy = 4.5, heart = 92.0, heartElapsed = 61000, heartQuality = "valid")
        val state = mutableStateOf(NativeWorkoutState(loading = false, watchRecords = listOf(WatchWorkoutProjection.record(installation, w))))
        val requested = mutableStateOf<String?>(null)
        var phoneAction = false
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme(), typography = OrbitTypography) {
            Box(Modifier.fillMaxSize().background(Color(0xFF0B0A0F))) {
                WorkoutScreen(state.value, emptyList(), true, { _, _, _, _ -> phoneAction = true },
                    openRecord = requested.value, onOpened = { requested.value = null }, chrome = { _, _, _ -> })
            }
        } }
        compose.onNodeWithTag("watch-active-workout").performClick()
        compose.onNodeWithText("Watch · Health Services").assertIsDisplayed()
        compose.onNodeWithText("Pause workout").performScrollTo().performClick()
        compose.onNodeWithText("Connect your Watch and wait for its next update.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Finish", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Keep going").performScrollTo().performClick()
        compose.onNodeWithText("Finish this Watch workout?").assertDoesNotExist()
        compose.onNodeWithText("92 bpm").performScrollTo().assertIsDisplayed()
        capture("active")
        compose.runOnIdle { state.value = state.value.copy(watchRecords = listOf(WatchWorkoutProjection.record(installation, w.copy(
            revision = 2, phase = "ended", endReason = 1)))) }
        compose.onNodeWithText("92 bpm").assertDoesNotExist()
        compose.onAllNodesWithText("Active time", useUnmergedTree = true).onFirst().performScrollTo()
        capture("saved")
        val second = WatchWorkoutProjection.record(installation, w.copy(id = id(), kind = "Running"))
        compose.runOnIdle {
            state.value = state.value.copy(watchRecords = state.value.watchRecords + second)
            requested.value = second.id
        }
        compose.onNodeWithText("Recording on Watch").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("watch-workout-controls").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertNull(requested.value) }
        assertFalse("A Watch control must never dispatch into the phone recorder", phoneAction)
    }
    private fun capture(name: String) { compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
        File(compose.activity.cacheDir, "watch-workout-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    } }
}
