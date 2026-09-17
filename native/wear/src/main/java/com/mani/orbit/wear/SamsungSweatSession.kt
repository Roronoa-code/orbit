package com.mani.orbit.wear

import android.content.Context
import android.os.SystemClock
import com.mani.health.integration.samsungsensor.OnDemandException
import com.mani.health.integration.samsungsensor.SensorSessionLifecycle
import com.mani.orbit.sync.MeasurementProfile
import com.samsung.android.service.health.tracking.*
import com.samsung.android.service.health.tracking.data.*
import java.time.LocalDate
import java.time.Period
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal data class SweatSensorResult(val status: Int, val ml: Double, val sensorAt: Long, val at: Long, val elapsed: Long)

/** The workout actor calls this adapter. SDK callbacks only complete futures, never touch its state. */
internal class SamsungSweatSession(context: Context, profile: MeasurementProfile, lease: AutoCloseable) : AutoCloseable {
    private val lifecycle = SensorSessionLifecycle(lease, 0)
    private val ready = CompletableFuture<HealthTracker>()
    private val result = CompletableFuture<SweatSensorResult>()
    private lateinit var service: HealthTrackingService
    private var phase = "starting"
    private val listener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(points: List<DataPoint>) {
            lifecycle.deliverIfActive {
                try {
                    // One terminal estimate per run. Empty callbacks carry no estimate.
                    if (points.isNotEmpty()) {
                        require(points.size == 1)
                        val point = points.single()
                        val ml = point.getValue(ValueKey.SweatLossSet.SWEAT_LOSS).toDouble()
                        require(ml.isFinite() && ml >= 0)
                        result.complete(SweatSensorResult(point.getValue(ValueKey.SweatLossSet.STATUS), ml,
                            point.timestamp, System.currentTimeMillis(), SystemClock.elapsedRealtime()))
                    }
                } catch (_: Exception) { fail("MALFORMED_RESULT") }
                true
            }
        }
        override fun onFlushCompleted() = Unit
        override fun onError(error: HealthTracker.TrackerError) { fail(when (error) {
            HealthTracker.TrackerError.PERMISSION_ERROR -> "PERMISSION_REQUIRED"
            HealthTracker.TrackerError.SDK_POLICY_ERROR -> "SDK_POLICY_REJECTED"
        }) }
    }
    init {
        try {
            val nativeProfile = TrackerUserProfile.Builder().setAge(Period.between(profile.birth, LocalDate.now()).years)
                .setGender(if (profile.sex == "male") 1 else 0).setHeight(requireNotNull(profile.heightCm).toFloat())
                .setWeight(requireNotNull(profile.weightKg).toFloat()).build()
            service = HealthTrackingService(object : ConnectionListener {
                override fun onConnectionSuccess() {
                    if (!lifecycle.acceptsCallbacks) return
                    try {
                        if (HealthTrackerType.SWEAT_LOSS !in service.trackingCapability.supportHealthTrackerTypes) {
                            fail("UNSUPPORTED"); return
                        }
                        val handle = service.getHealthTracker(HealthTrackerType.SWEAT_LOSS, nativeProfile, ExerciseType.RUNNING)
                        if (lifecycle.attachTracker({ handle.setEventListener(listener) }, handle::flush, handle::unsetEventListener)) ready.complete(handle)
                    } catch (_: Exception) { fail("START_FAILED") }
                }
                override fun onConnectionEnded() { fail("SERVICE_UNAVAILABLE") }
                override fun onConnectionFailed(error: HealthTrackerException) { fail(when (error.errorCode) {
                    HealthTrackerException.PACKAGE_NOT_INSTALLED -> "SERVICE_MISSING"
                    HealthTrackerException.OLD_PLATFORM_VERSION -> "SERVICE_UPDATE_REQUIRED"
                    else -> "SERVICE_UNAVAILABLE"
                }) }
            }, context.applicationContext)
            lifecycle.attachConnection(AutoCloseable { }) { service.disconnectService() }
            service.connectService()
            ready.get(15, TimeUnit.SECONDS)
        } catch (error: Exception) { close(); throw error }
    }
    private fun fail(code: String) {
        val error = OnDemandException(code)
        ready.completeExceptionally(error); result.completeExceptionally(error)
    }
    fun exercise(confirmedPhase: String) {
        if (phase == confirmedPhase) return
        val state = when (confirmedPhase) {
            "active" -> if (phase == "starting") ExerciseState.START else ExerciseState.RESUME
            "paused" -> ExerciseState.PAUSE
            "ended", "interrupted" -> ExerciseState.STOP
            else -> return
        }
        ready.get().setExerciseState(state); phase = confirmedPhase
    }
    fun cadence(values: FloatArray, epochMillis: LongArray) {
        require(values.size == epochMillis.size && values.all { it.isFinite() && it >= 0 })
        if (values.isNotEmpty()) ready.get().setExerciseData(DataType.STEP_PER_MINUTE, values, epochMillis)
    }
    fun completed(): SweatSensorResult? = if (result.isDone) result.get() else null
    fun finish(): SweatSensorResult {
        exercise("ended")
        return result.get(15, TimeUnit.SECONDS)
    }
    override fun close() { lifecycle.beginStop(); lifecycle.cleanup() }
}
