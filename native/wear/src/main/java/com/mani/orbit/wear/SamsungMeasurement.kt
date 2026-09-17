package com.mani.orbit.wear

import android.content.Context
import android.os.SystemClock
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.ExerciseTrackedStatus
import com.mani.health.core.model.measurement.MeasurementTracker
import com.mani.health.core.model.measurement.SensorProfile
import com.mani.health.integration.samsungsensor.OnDemandException
import com.mani.health.integration.samsungsensor.OnDemandFrame
import com.mani.health.integration.samsungsensor.SensorSdkOnDemandSource
import com.mani.orbit.sync.*
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.Period
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Orbit adapter around the reused SDK engine. Only a completed vendor frame produces readings. */
internal suspend fun samsungMeasurement(context: Context, tracker: MeasurementTracker, profile: MeasurementProfile?,
    onResult: suspend (WatchMeasurement) -> Unit, progress: (OnDemandFrame?) -> Unit) {
    require(tracker in setOf(MeasurementTracker.BIA, MeasurementTracker.SPO2, MeasurementTracker.SKIN_TEMPERATURE))
    val sensorProfile = if (tracker == MeasurementTracker.BIA) {
        val input = requireNotNull(profile) { "Complete your phone profile first" }
        require(input.complete(LocalDate.now())) { "Complete your phone profile first" }
        SensorProfile(Period.between(input.birth, LocalDate.now()).years, if (input.sex == "male") 1 else 0,
            requireNotNull(input.heightCm).toFloat(), requireNotNull(input.weightKg).toFloat())
    } else null
    val store = WatchStore(context)
    val clock = withContext(Dispatchers.IO) { store.clock() }
    val source = SensorSdkOnDemandSource(context)
    var result: WatchMeasurement? = null
    try {
        source.frames(tracker, sensorProfile, beforeStart = { requireSamsungSensorAvailable(context, store, clock.boot) }).collect { frame ->
            progress(frame)
            if (frame.complete) {
                check(result == null) { "Duplicate terminal result" }
                val elapsed = SystemClock.elapsedRealtime()
                check(abs(System.currentTimeMillis() - clock.wallAt(elapsed)) <= 2000) { "Clock changed. Measure again." }
                val values = frame.values.map { MeasurementValue(it.metric, it.value, it.unit) }
                val readings = values.mapNotNull { value -> WatchMeasurement.PROJECTIONS[value.metric]?.let { metric ->
                    store.sample(clock, metric, elapsed, elapsed, value.value, "valid", source = "samsung_sensor")
                } }
                result = WatchMeasurement(ReadingBatch(UUID.randomUUID().toString(), store.installation, readings), tracker.name,
                    values, if (tracker == MeasurementTracker.BIA) profile else null)
                // Hand off a completed vendor result before cancellation can discard it at a suspension boundary.
                withContext(NonCancellable) { onResult(requireNotNull(result)) }
            } else if (frame.terminal && frame.errorCode != null) throw OnDemandException(frame.errorCode)
        }
    } finally { withContext(NonCancellable) { source.stopGracefully() } }
    if (result == null) throw OnDemandException("NO_RESULT")
}

/** Called while the SDK source holds the shared lease, including during ECG startup. */
internal fun requireSamsungSensorAvailable(context: Context, store: WatchStore, boot: String) {
    if (store.journal().use { it.hasOpenWorkout(store.installation, boot) }) throw OnDemandException("WORKOUT_ACTIVE")
    val info = HealthServices.getClient(context).exerciseClient.getCurrentExerciseInfoAsync().get(10, TimeUnit.SECONDS)
    if (info.exerciseTrackedStatus != NO_WORKOUT) throw OnDemandException("WORKOUT_ACTIVE")
}

// Same pinned-SDK IntDef exception as WatchWorkoutService; keep the actual ownership check.
@android.annotation.SuppressLint("RestrictedApi")
private const val NO_WORKOUT = ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS

internal fun saveSamsungMeasurement(context: Context, value: WatchMeasurement) {
    check(context.filesDir.usableSpace >= 16 * 1024 * 1024) { "Watch storage is nearly full" }
    WatchStore(context).journal().use { it.captureMeasurement(value) }
}

/** A scheduling failure cannot turn an already committed sensor result into an unsaved one. */
internal fun requestMeasurementSync(context: Context): String? = try {
    WatchSyncWorker.schedule(context)
    null
} catch (_: Exception) { "Saved on Watch · sync pending" }

internal fun samsungMeasurementError(error: Throwable): String = when ((error as? OnDemandException)?.code) {
    "UNSUPPORTED" -> "This Watch does not support this measurement."
    "SDK_POLICY_REJECTED" -> "Samsung has not authorized this Orbit build for sensor access."
    "SERVICE_MISSING" -> "Samsung Health Sensor Service is not installed on this Watch."
    "SERVICE_UPDATE_REQUIRED" -> "Update Samsung Health Sensor Service on your Watch."
    "PERMISSION_REQUIRED" -> "Allow sensor access in Orbit’s permissions."
    "SERVICE_UNAVAILABLE", "START_FAILED" -> "Samsung’s sensor service could not connect."
    "SESSION_BUSY" -> "Another measurement is using the sensor. Try again when it finishes."
    "WORKOUT_ACTIVE" -> "Finish your workout before starting this measurement."
    "CONTACT_NEEDED" -> "Adjust your contact with the Watch and try again."
    "UNSTABLE_SIGNAL", "MOVEMENT", "LOW_SIGNAL" -> "Keep still and try again."
    "PROFILE_INVALID", "PROFILE_REQUIRED" -> "Check your phone profile before measuring."
    "TIMED_OUT", "NO_RESULT" -> "No completed reading. Adjust the Watch and try again."
    else -> "Measurement could not finish. Please try again."
}
