package com.mani.orbit.wear

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.location.LocationManager
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.data.*
import com.mani.health.core.model.deviceSensorLeaseGate
import com.mani.orbit.sync.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CompletableFuture
import kotlin.math.max

data class WatchWorkoutUi(val workout: WatchWorkout? = null, val busy: Boolean = false,
                          val error: String? = null, val attached: Boolean = false, val operation: Long = 0)

/** Health Services owns exercise timing; this foreground service owns its durable Orbit record. */
class WatchWorkoutService : Service() {
    private val serial = Executors.newSingleThreadScheduledExecutor()
    private val client by lazy { HealthServices.getClient(this).exerciseClient }
    private val store by lazy { WatchStore(this) }
    private val notification by lazy { WatchWorkoutNotification(this, CHANNEL, NOTIFICATION) }
    @Volatile private var workout: WatchWorkout? = null
    private var lastPoint: WatchRoutePoint? = null
    private var registered = false
    private val attachmentLock = Any()
    private var registrationRequested = false
    private var pendingStart: ExerciseConfig? = null
    private val starting = AtomicBoolean(false)
    private var pendingAction: String? = null
    private var traceOperation = 0L
    private var feedbackOperation = 0L
    @Volatile private var alive = true
    private val controls = WorkoutControlGate()
    @Volatile private var sweat: WatchSweatCapture? = null

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        owner = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Watch workout", NotificationManager.IMPORTANCE_LOW))
        serial.scheduleWithFixedDelay({ guarded { if (workout?.terminal == false) WatchSyncWorker.schedule(this) } }, 5, 5, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: RECOVER
        val operation = intent?.getLongExtra("diagnosticOperation", 0) ?: 0
        try { foreground(live.value.workout?.takeUnless { it.terminal }?.gps == true) }
        catch (error: Exception) {
            NativeDiagnostics.mark(operation, TraceStage.FAILED)
            if (operation != 0L) feedbackOperation = operation
            fail("Activity permission is needed to run a workout"); stopSelf(); return START_NOT_STICKY
        }
        if (action == START && !starting.compareAndSet(false, true)) {
            NativeDiagnostics.mark(operation, TraceStage.CANCELLED); return START_STICKY
        }
        if (action == START) live.value = live.value.copy(busy = true, error = null)
        serial.execute { guarded { try {
            if (action != RECOVER && pendingAction == null) {
                NativeDiagnostics.mark(traceOperation, TraceStage.SUPERSEDED)
                traceOperation = operation
                feedbackOperation = operation
                NativeDiagnostics.mark(operation, TraceStage.COMMAND_REQUESTED)
            }
            if (workout == null) workout = store.journal().use { it.workouts(store.installation, 1).firstOrNull()?.second }
            if (action == START) start(intent!!.getStringExtra("kind") ?: "", intent.getBooleanExtra("gps", false))
            else {
                recover()
                if (action != RECOVER) command(action, intent?.getStringExtra("id"), operation)
            }
        } catch (error: Exception) { NativeDiagnostics.mark(operation, TraceStage.FAILED); throw error } } }
        return START_STICKY
    }

    private fun foreground(gps: Boolean) {
        val type = (if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0) or
            (if (gps) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        startForeground(NOTIFICATION, notification.build(workout), type)
    }

    private fun start(kind: String, gps: Boolean) {
        val lease = deviceSensorLeaseGate.tryAcquire()
            ?: error("A measurement is using the sensor. Finish it before starting a workout.")
        try {
            store.journal().use { SweatJournal(it).recoverInterrupted(store.installation) }
            startWithSensor(kind, gps, lease)
        } catch (error: Exception) { sweat?.interrupt(); throw error }
        finally { if (sweat?.waiting != true) lease.close() }
    }

    private fun startWithSensor(kind: String, gps: Boolean, lease: AutoCloseable) {
        require(kind in WatchWorkout.KINDS && (!gps || kind != "Strength"))
        check(WatchPermissions.granted(this, Manifest.permission.ACTIVITY_RECOGNITION)) { "Activity permission is needed" }
        check(!gps || WatchPermissions.granted(this, Manifest.permission.ACCESS_FINE_LOCATION)) { "Allow precise location or turn outdoor tracking off" }
        check(!gps || getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER)) { "Turn location on in Watch settings or switch outdoor tracking off" }
        val info = client.getCurrentExerciseInfoAsync().get(10, TimeUnit.SECONDS)
        if (info.exerciseTrackedStatus == OTHER_WORKOUT) {
            if (workout?.terminal == false) interruptSaved()
            fail("Another app is recording a workout. Finish it there first."); stopSelf(); return
        }
        if (info.exerciseTrackedStatus == OWNED_WORKOUT) {
            recover(); starting.set(false); NativeDiagnostics.mark(traceOperation, TraceStage.CANCELLED)
            traceOperation = 0; feedbackOperation = 0; return
        }
        check(info.exerciseTrackedStatus == NO_WORKOUT) { "Workout ownership is unavailable" }
        if (workout?.terminal == false) interruptSaved()
        val type = TYPES.getValue(kind)
        val caps = client.getCapabilitiesAsync().get(10, TimeUnit.SECONDS)
        check(type in caps.supportedExerciseTypes) { "$kind is not supported on this watch" }
        val supported = caps.getExerciseTypeCapabilities(type).supportedDataTypes
        val wanted = setOf(DataType.STEPS_TOTAL, DataType.DISTANCE_TOTAL, DataType.CALORIES_TOTAL, DataType.ELEVATION_GAIN_TOTAL, DataType.SPEED) +
            (if (kind == "Running") setOf(DataType.STEPS_PER_MINUTE) else emptySet()) +
            (if (WatchPermissions.granted(this, WatchPermissions.heart)) setOf(DataType.HEART_RATE_BPM) else emptySet()) +
            (if (gps) setOf(DataType.LOCATION) else emptySet())
        check(!gps || DataType.LOCATION in supported) { "Outdoor tracking is unavailable for this workout" }
        val clock = store.clock(); val now = SystemClock.elapsedRealtime()
        val initial = WatchWorkout(UUID.randomUUID().toString(), 1, kind, clock.boot, clock.wallAt(now), now,
            clock.wallAt(now), now, 0, "starting", gps, timeUncertain = clock.changed)
        commit(initial, emptyList())
        sweat = if (kind == "Running") WatchSweatCapture.start(this, initial, clock, DataType.STEPS_PER_MINUTE in supported, lease) else null
        check(alive && !Thread.currentThread().isInterrupted) { "Workout startup was interrupted" }
        lastPoint = null
        pendingStart = ExerciseConfig(type, wanted.intersect(supported), isAutoPauseAndResumeEnabled = false, isGpsEnabled = gps)
        pendingAction = START
        live.value = WatchWorkoutUi(workout, busy = true)
        foreground(gps)
        attach()
    }

    private fun attach() {
        if (registered) { startPending(); return }
        // Registration completion arrives on this executor. Never wait for it while holding that executor.
        synchronized(attachmentLock) {
            if (!alive) return
            registrationRequested = true
            client.setUpdateCallback(serial, callback)
        }
    }

    private fun startPending() {
        val config = pendingStart ?: return
        pendingStart = null
        client.startExerciseAsync(config).get(15, TimeUnit.SECONDS)
        sweat?.started()
        NativeDiagnostics.mark(traceOperation, TraceStage.API_ACCEPTED)
        // Keep "starting" until an actual ExerciseUpdate reports ACTIVE.
    }

    private fun recover() {
        if (sweat?.needsSave == true && workout?.terminal == true) {
            sweat?.finish(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }
        if (sweat == null) WatchSweatCapture.recover(this)
        val info = client.getCurrentExerciseInfoAsync().get(10, TimeUnit.SECONDS)
        val own = info.exerciseTrackedStatus == OWNED_WORKOUT
        val saved = workout
        if (!own) {
            if (saved?.terminal == false) interruptSaved()
            live.value = WatchWorkoutUi(workout, error = if (info.exerciseTrackedStatus == OTHER_WORKOUT)
                "A workout is running in another app" else null)
            stopSelf(); return
        }
        check(saved != null && !saved.terminal && saved.boot == store.clock().boot && TYPES[saved.kind] == info.exerciseType) {
            "Health Services has a workout without a matching saved record. Recovery needs attention."
        }
        if (!registered) {
            lastPoint = store.journal().use { it.workoutPoints(store.installation, saved.id).lastOrNull() }
            foreground(saved.gps)
            live.value = WatchWorkoutUi(saved, busy = true)
            attach()
        }
    }

    private fun interruptSaved() {
        val w = workout ?: return
        sweat?.interrupt()
        commit(w.copy(revision = Math.addExact(w.revision, 1), phase = "interrupted", updatedAt = System.currentTimeMillis(), timeUncertain = true), emptyList())
    }

    private fun command(action: String, expectedId: String?, operation: Long = 0) {
        val w = workout ?: return
        require(expectedId == w.id) { "This control belongs to an older workout" }
        if (w.terminal || pendingAction != null) { NativeDiagnostics.mark(operation, TraceStage.CANCELLED); return }
        traceOperation = operation
        NativeDiagnostics.mark(operation, TraceStage.COMMAND_REQUESTED)
        controls.invalidate()
        pendingAction = action
        live.value = live.value.copy(busy = true, error = null)
        when (action) {
            PAUSE -> if (w.phase == "active") client.pauseExerciseAsync().get(10, TimeUnit.SECONDS) else { pendingAction = null; live.value = live.value.copy(busy = false) }
            RESUME -> if (w.phase == "paused") client.resumeExerciseAsync().get(10, TimeUnit.SECONDS) else { pendingAction = null; live.value = live.value.copy(busy = false) }
            FINISH -> client.endExerciseAsync().get(10, TimeUnit.SECONDS)
            else -> error("Unknown workout action")
        }
        NativeDiagnostics.mark(traceOperation, if (pendingAction != null) TraceStage.API_ACCEPTED else TraceStage.CANCELLED)
        if (pendingAction == null) { traceOperation = 0; feedbackOperation = 0 }
        // The callback confirms success and clears busy. No synthetic phase changes.
    }

    private fun receiveControl(message: WorkoutControl): CompletableFuture<WorkoutControl> {
        val result = CompletableFuture<WorkoutControl>()
        try { serial.execute {
            var applying = false
            try {
                check(alive && registered && pendingAction == null && !live.value.busy) { "Watch is busy" }
                val w = requireNotNull(workout)
                val boot = store.clock().boot
                val reply = if (message.stage == "request") {
                    controls.prepare(message, w, store.installation, boot, SystemClock.elapsedRealtime())
                } else {
                    require(message.stage == "commit")
                    // Ownership is checked again on the recording actor, before consuming the live offer.
                    val info = client.getCurrentExerciseInfoAsync().get(3, TimeUnit.SECONDS)
                    check(info.exerciseTrackedStatus == OWNED_WORKOUT && info.exerciseType == TYPES[w.kind])
                    controls.consume(message, w, store.installation, boot, SystemClock.elapsedRealtime())
                    applying = true
                    feedbackOperation = 0 // The requesting phone owns this command's confirmation.
                    val operation = NativeDiagnostics.begin(TraceFeature.WATCH_CONTROL, TraceRoute.WATCH_WORKOUT, TraceStage.COMMAND_REQUESTED)
                    command(when (message.action) { "pause" -> PAUSE; "resume" -> RESUME; else -> FINISH }, w.id, operation)
                    message.copy(stage = "accepted") // Actual phase still comes only from ExerciseClient's callback.
                }
                result.complete(reply)
            } catch (error: Exception) {
                controls.invalidate()
                if (applying && pendingAction != null) { pendingAction = null; fail("Phone control was not confirmed. Check your workout.") }
                result.complete(message.copy(stage = "rejected", token = null))
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            result.complete(message.copy(stage = "rejected", token = null))
        }
        return result
    }

    private val callback = object : ExerciseUpdateCallback {
        override fun onRegistered() { guarded { registered = true; startPending(); live.value = live.value.copy(attached = true) } }
        override fun onRegistrationFailed(throwable: Throwable) { guarded { registered = false; pendingStart = null; error("Health Services callback unavailable") } }
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) { guarded { update(update) } }
        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            // Sample quality and timestamps determine whether a displayed measurement is fresh.
        }
    }

    private fun update(update: ExerciseUpdate) {
        val prior = workout ?: error("Workout update has no saved owner")
        if (prior.terminal) return
        val now = update.getUpdateDurationFromBoot().toMillis()
        require(now in prior.startElapsed..SystemClock.elapsedRealtime() + 1000)
        if (now < prior.updatedElapsed) return
        val state = update.exerciseStateInfo.state
        val phase = when {
            state.isEnded -> "ended"; state.isPaused -> "paused"; state == ExerciseState.ACTIVE -> "active"
            else -> prior.phase
        }
        val checkpoint = update.activeDurationCheckpoint
        val clock = store.clock()
        require(clock.boot == prior.boot)
        val checkpointMs = checkpoint?.activeDuration?.toMillis()
        // A wall-clock adjustment must not turn a desk workout into hours of active time.
        val active = if (phase == "active" && clock.changed) max(checkpointMs ?: 0, prior.activeMs +
            (if (prior.phase == "active") now - prior.updatedElapsed else 0))
        else if (checkpointMs == null) prior.activeMs else checkpointMs +
            (if (phase == "active") (clock.wallAt(now) - checkpoint.time.toEpochMilli()).coerceAtLeast(0) else 0)
        val duration = max(prior.activeMs, active.coerceAtMost(now - prior.startElapsed))
        val metrics = update.latestMetrics
        val heart = metrics.getData(DataType.HEART_RATE_BPM).filter { it.timeDurationFromBoot.toMillis() >= prior.startElapsed }.maxByOrNull { it.timeDurationFromBoot }
        val heartQuality = if (heart == null) prior.heartQuality else when ((heart.accuracy as? HeartRateAccuracy)?.sensorStatus) {
            HeartRateAccuracy.SensorStatus.ACCURACY_HIGH, HeartRateAccuracy.SensorStatus.ACCURACY_MEDIUM -> "valid"
            HeartRateAccuracy.SensorStatus.NO_CONTACT -> "no_contact"
            HeartRateAccuracy.SensorStatus.UNRELIABLE -> "unreliable"
            else -> "unknown"
        }
        val speed = metrics.getData(DataType.SPEED).filter { it.timeDurationFromBoot.toMillis() >= prior.startElapsed }.maxByOrNull { it.timeDurationFromBoot }
        val next = prior.copy(revision = Math.addExact(prior.revision, 1), phase = phase, updatedElapsed = now,
            updatedAt = clock.wallAt(now), activeMs = duration, timeUncertain = prior.timeUncertain || clock.changed,
            distance = total(prior.distance, metrics.getData(DataType.DISTANCE_TOTAL)?.total),
            steps = metrics.getData(DataType.STEPS_TOTAL)?.total?.takeIf { it >= (prior.steps ?: 0) } ?: prior.steps,
            energy = total(prior.energy, metrics.getData(DataType.CALORIES_TOTAL)?.total),
            elevation = total(prior.elevation, metrics.getData(DataType.ELEVATION_GAIN_TOTAL)?.total),
            heart = if (heart == null) prior.heart else heart.value.takeIf { it.isFinite() && it in 1.0..350.0 },
            heartElapsed = heart?.timeDurationFromBoot?.toMillis() ?: prior.heartElapsed,
            heartQuality = if (heart != null && (!heart.value.isFinite() || heart.value !in 1.0..350.0)) "unavailable" else heartQuality,
            speed = if (speed == null) prior.speed else speed.value.takeIf { it.isFinite() && it in 0.0..100.0 },
            speedElapsed = speed?.timeDurationFromBoot?.toMillis() ?: prior.speedElapsed,
            endReason = if (state.isEnded) update.exerciseStateInfo.endReason else null)
        var previous = lastPoint
        val points = if (!prior.gps) emptyList() else metrics.getData(DataType.LOCATION).sortedBy { it.timeDurationFromBoot }.mapNotNull { point ->
            val at = point.timeDurationFromBoot.toMillis(); val location = point.value
            val accuracy = (point.accuracy as? LocationAccuracy)?.horizontalPositionErrorMeters
            if (at < prior.startElapsed || at > now + 1000 || at <= (previous?.elapsed ?: -1) || accuracy == null || accuracy !in 0.0..50.0 ||
                location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) null
            else WatchRoutePoint(at, location.latitude, location.longitude, accuracy,
                location.altitude.takeIf { it in -12000.0..100000.0 && it != -Double.MAX_VALUE },
                previous == null || prior.phase == "paused" || phase == "paused" || at - previous.elapsed > 30_000).also { previous = it }
        }
        val waiting = !next.terminal && when (pendingAction) { START, RESUME -> phase != "active"; PAUSE -> phase != "paused"; FINISH -> true; else -> false }
        val confirmed = traceOperation.takeIf { pendingAction != null && !waiting } ?: 0
        val successful = phase == when (pendingAction) { START, RESUME -> "active"; PAUSE -> "paused"; FINISH -> "ended"; else -> null }
        NativeDiagnostics.mark(confirmed, if (successful) TraceStage.PLATFORM_CONFIRMED else TraceStage.FAILED)
        commit(next, points)
        if (successful) NativeDiagnostics.mark(confirmed, TraceStage.DURABLE_COMMIT)
        lastPoint = previous
        sweat?.update(next, update)
        WatchSamples.capture(this, metrics)
        if (!waiting) { pendingAction = null; starting.set(false); traceOperation = 0 }
        live.value = WatchWorkoutUi(next, busy = waiting, attached = !next.terminal,
            operation = if (confirmed != 0L) confirmed else live.value.operation,
            error = if (confirmed != 0L && !successful) "Workout ended before the requested change was confirmed." else null)
        if (confirmed != 0L) completeFeedback(successful)
        if (phase != prior.phase && !next.terminal) foreground(next.gps)
        if (next.terminal) {
            if (sweat?.waiting == true) { foreground(false); sweat?.finish() }
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }

    private fun commit(next: WatchWorkout, points: List<WatchRoutePoint>) {
        if (next.id != workout?.id || next.phase != workout?.phase) controls.invalidate()
        check(filesDir.usableSpace >= 16 * 1024 * 1024) { "Watch storage is nearly full. Recording needs attention." }
        store.journal().use { journal ->
            journal.captureWorkoutUpdate(store.installation, next, points)
        }
        workout = next
        WatchSyncWorker.schedule(this)
    }

    private fun guarded(block: () -> Unit) {
        if (!alive) return
        try { block() }
        catch (error: Exception) {
            pendingAction = null; starting.set(false)
            if (workout?.phase == "starting") {
                try { sweat?.interrupt() } catch (_: Exception) { /* Retain the pending result for Retry save. */ }
            }
            Log.w("OrbitWorkout", "Watch workout operation failed: ${error.javaClass.simpleName}")
            fail(error.message?.takeIf { it.length <= 150 } ?: "Workout operation failed. Retry from your watch.")
            if (workout == null || workout?.terminal == true && sweat?.needsSave != true) stopSelf()
        }
    }
    private fun fail(message: String) {
        NativeDiagnostics.mark(traceOperation, TraceStage.FAILED); traceOperation = 0
        live.value = live.value.copy(workout = workout, busy = false, error = message)
        completeFeedback(false)
    }
    private fun completeFeedback(success: Boolean) {
        val local = feedbackOperation != 0L
        feedbackOperation = 0
        if (local) feedbackEvents.tryEmit(success)
    }
    override fun onDestroy() {
        NativeDiagnostics.mark(traceOperation, TraceStage.CANCELLED)
        feedbackOperation = 0
        synchronized(attachmentLock) {
            alive = false
            if (registrationRequested) client.clearUpdateCallbackAsync(callback)
        }
        if (owner === this) owner = null
        serial.shutdownNow()
        sweat?.close()
        live.value = live.value.copy(attached = false, busy = false)
        super.onDestroy()
    }

    companion object {
        // 1.1.0-rc02 restricts the IntDef for ExerciseInfo's documented ownership field. Android's
        // ExerciseClientKtx sample uses this workaround; scope it to the three constants only.
        @android.annotation.SuppressLint("RestrictedApi") private const val OTHER_WORKOUT = ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS
        @android.annotation.SuppressLint("RestrictedApi") private const val OWNED_WORKOUT = ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS
        @android.annotation.SuppressLint("RestrictedApi") private const val NO_WORKOUT = ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS
        private const val CHANNEL = "watch-workout"
        private const val NOTIFICATION = 32
        const val START = "orbit.workout.START"
        const val RECOVER = "orbit.workout.RECOVER"
        const val PAUSE = "orbit.workout.PAUSE"
        const val RESUME = "orbit.workout.RESUME"
        const val FINISH = "orbit.workout.FINISH"
        private val live = MutableStateFlow(WatchWorkoutUi())
        // Transient feedback, never replayed after ambient, backgrounding or recreation.
        private val feedbackEvents = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
        internal val feedback = feedbackEvents.asSharedFlow()
        @Volatile private var owner: WatchWorkoutService? = null
        internal fun remote(message: WorkoutControl): CompletableFuture<WorkoutControl> = owner?.receiveControl(message)
            ?: CompletableFuture.completedFuture(message.copy(stage = "rejected", token = null))
        val state = live.asStateFlow()
        val TYPES = mapOf("Walking" to ExerciseType.WALKING, "Running" to ExerciseType.RUNNING,
            "Cycling" to ExerciseType.BIKING, "Strength" to ExerciseType.STRENGTH_TRAINING)
        fun send(context: Context, action: String, id: String? = null, kind: String? = null, gps: Boolean = false) {
            val operation = if (action == RECOVER) 0 else NativeDiagnostics.begin(TraceFeature.WATCH_WORKOUT, TraceRoute.WATCH_WORKOUT)
            try { context.startForegroundService(Intent(context, WatchWorkoutService::class.java).setAction(action)
                .putExtra("id", id).putExtra("kind", kind).putExtra("gps", gps).putExtra("diagnosticOperation", operation)) }
            catch (error: Exception) {
                NativeDiagnostics.mark(operation, TraceStage.FAILED)
                live.value = live.value.copy(error = "Workout action could not start. Check activity access and try again.")
                if (action != RECOVER) feedbackEvents.tryEmit(false)
            }
        }
        private fun total(prior: Double?, next: Double?) = next?.takeIf { it.isFinite() && it >= (prior ?: 0.0) } ?: prior
    }
}
