package com.mani.orbit.wear

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseUpdate
import com.mani.health.core.model.deviceSensorLeaseGate
import com.mani.health.integration.samsungsensor.OnDemandException
import com.mani.orbit.sync.*
import java.time.LocalDate
import kotlin.math.abs

/** Optional running attachment. Failure here never changes Health Services' workout measurements. */
internal class WatchSweatCapture private constructor(private val context: Context, private val lease: AutoCloseable,
    private val clock: WatchClock, private var record: SweatEstimate) : AutoCloseable {
    private val store = WatchStore(context)
    private var sensor: SamsungSweatSession? = null
    private var lastCadence = record.startElapsed - 1
    private var lastValue: Float? = null
    var needsSave = false
        private set
    val waiting get() = !record.terminal || needsSave

    private fun save(value: SweatEstimate = record) {
        record = value; needsSave = true
        store.journal().use { SweatJournal(it).capture(record) }
        needsSave = false
    }
    private fun next(phase: String, reason: String? = null): SweatEstimate {
        val elapsed = SystemClock.elapsedRealtime()
        return record.copy(revision = Math.addExact(record.revision, 1), phase = phase, reason = reason,
            elapsed = elapsed, at = System.currentTimeMillis(), timeUncertain = abs(System.currentTimeMillis() - clock.wallAt(elapsed)) > 2000)
    }
    private fun unavailable(error: Exception) {
        disposeSensor()
        val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
        val code = causes.filterIsInstance<OnDemandException>().firstOrNull()?.code ?: when {
            causes.any { it is java.util.concurrent.TimeoutException } -> "TIMED_OUT"
            causes.any { it is SecurityException } -> "PERMISSION_REQUIRED"
            causes.any { it is InterruptedException } -> "INTERRUPTED"
            else -> "SENSOR_UNAVAILABLE"
        }
        save(next("unavailable", code))
        close()
        if (causes.any { it is InterruptedException }) Thread.currentThread().interrupt()
    }
    fun started() {
        if (record.terminal) return
        try { requireNotNull(sensor).exercise("active") }
        catch (error: Exception) { unavailable(error) }
    }
    fun update(workout: WatchWorkout, update: ExerciseUpdate) {
        if (needsSave) save()
        if (record.terminal) return
        try {
            if (abs(System.currentTimeMillis() - clock.wallAt(SystemClock.elapsedRealtime())) > 2000) throw OnDemandException("CLOCK_CHANGED")
            val points = update.latestMetrics.getData(DataType.STEPS_PER_MINUTE)
            if (points.size > 3600) throw OnDemandException("CADENCE_OVERFLOW")
            val samples = points.sortedBy { it.timeDurationFromBoot }.mapNotNull {
                val elapsed = it.timeDurationFromBoot.toMillis(); val value = it.value.toFloat()
                require(value.isFinite() && value >= 0 && elapsed <= workout.updatedElapsed + 1000)
                if (elapsed == lastCadence) require(value == lastValue) { "Cadence sample changed" }
                if (elapsed < record.startElapsed || elapsed <= lastCadence) null
                else (value to clock.wallAt(elapsed)).also { lastCadence = elapsed; lastValue = value }
            }
            requireNotNull(sensor).cadence(samples.map { it.first }.toFloatArray(), samples.map { it.second }.toLongArray())
            if (!workout.terminal) sensor?.exercise(workout.phase)
            // Surface SDK policy/permission failures without waiting until the run ends.
            sensor?.completed()?.let { if (!workout.terminal) throw OnDemandException("EARLY_RESULT") }
        } catch (error: Exception) { unavailable(error) }
    }
    fun finish() {
        if (needsSave) save()
        if (record.terminal) { close(); return }
        if (record.phase != "pending") save(next("pending"))
        val result = try { requireNotNull(sensor).finish() }
        catch (error: Exception) { unavailable(error); return }
        finally { disposeSensor() }
        val uncertain = abs(result.at - clock.wallAt(result.elapsed)) > 2000
        val final = if (uncertain || result.elapsed < record.elapsed) next("unavailable", if (uncertain) "CLOCK_CHANGED" else "EARLY_RESULT") else record.copy(
            revision = Math.addExact(record.revision, 1),
            phase = if (result.status == 0) "complete" else "unavailable", reason = if (result.status == 0) null else "SDK_STATUS",
            at = result.at, elapsed = result.elapsed, status = result.status, rawMl = result.ml, sensorAt = result.sensorAt, timeUncertain = false)
        save(final)
        close()
    }
    fun interrupt() { if (needsSave) save(); if (!record.terminal) unavailable(OnDemandException("INTERRUPTED")); close() }
    private fun disposeSensor() { try { sensor?.close() } finally { sensor = null } }
    override fun close() { try { disposeSensor() } finally { lease.close() } }

    companion object {
        val permission get() = if (Build.VERSION.SDK_INT >= 36) "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA" else Manifest.permission.BODY_SENSORS
        fun start(context: Context, w: WatchWorkout, clock: WatchClock, cadenceSupported: Boolean, lease: AutoCloseable): WatchSweatCapture {
            require(w.kind == "Running")
            var profileFailed = false
            val profile = try { loadPhoneData(context, MeasurementProfileWire.PATH)?.let(MeasurementProfileWire::decode) }
                catch (error: Exception) {
                    if (error is InterruptedException) { Thread.currentThread().interrupt(); throw error }
                    profileFailed = true; null
                }
            val reason = when {
                profileFailed -> "PROFILE_UNAVAILABLE"
                profile?.complete(LocalDate.now()) != true -> "PROFILE_REQUIRED"
                !cadenceSupported -> "CADENCE_UNSUPPORTED"
                !WatchPermissions.granted(context, permission) -> "PERMISSION_REQUIRED"
                else -> null
            }
            val r = SweatEstimate(WatchStore(context).installation, w.id, w.boot, w.startElapsed, 1,
                if (reason == null) "tracking" else "unavailable", clock.wallAt(w.startElapsed), w.startElapsed, profile, reason = reason)
            val capture = WatchSweatCapture(context, lease, clock, r)
            try {
                capture.save()
                if (reason != null) capture.close() else {
                    // Keep the owner lease through the final durable write, after SDK cleanup.
                    try { capture.sensor = SamsungSweatSession(context, requireNotNull(profile), AutoCloseable { }) }
                    catch (error: Exception) { capture.unavailable(error) }
                }
                return capture
            } catch (error: Exception) { capture.close(); throw error }
        }
        fun recover(context: Context) {
            val lease = deviceSensorLeaseGate.tryAcquire() ?: return
            lease.use { val store = WatchStore(context); store.journal().use { SweatJournal(it).recoverInterrupted(store.installation) } }
        }
    }
}
