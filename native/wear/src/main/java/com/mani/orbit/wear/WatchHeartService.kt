package com.mani.orbit.wear

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.mani.health.integration.samsungsensor.*
import com.mani.orbit.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs

internal data class WatchHeartState(val phase: String = "idle", val message: String? = null, val reading: WatchReading? = null,
    val callbacks: Long = 0, val intervals: Long = 0, val probe: SensorRawProbe? = null,
    val samples: Long = 0) {
    val active get() = phase in setOf("starting", "running", "stopping")
}

/** Adapted from the older LiveHeartService: explicit foreground owner, flush/drain, no silent restart. */
class WatchHeartService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var sensor: SensorSdkHeartRateSource? = null
    private var rawSensor: SensorSdkRawProbeSource? = null
    private var stopping = false
    private val prefs by lazy { getSharedPreferences("samsung-heart", MODE_PRIVATE) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); owner = this }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> stopCapture()
            START -> if (job?.isCompleted != false) {
                val name = intent.getStringExtra("probe")
                val probe = SensorRawProbe.entries.firstOrNull { it.name == name }
                if (name != null && probe == null) { stopSelfResult(startId); return START_NOT_STICKY }
                stopping = false
                mutableState.value = WatchHeartState("starting", probe = probe)
                try { notification() } catch (_: Exception) {
                    mutableState.value = WatchHeartState(message = "Open Orbit and allow sensor access before starting.", probe = probe)
                    stopSelfResult(startId); return START_NOT_STICKY
                }
                job = scope.launch {
                    var entered = false
                    try { sessions.withLock { entered = true; capture(probe) } }
                    finally { if (!entered && owner === this@WatchHeartService) {
                        mutableState.value = WatchHeartState(message = "Live reading stopped")
                        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                    } }
                }
            }
            else -> if (job?.isActive != true) stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun capture(probe: SensorRawProbe?) {
        var outcome = "Live reading stopped"
        var marker = false
        var callbacks = 0L; var intervals = 0L; var samples = 0L
        try {
            withContext(Dispatchers.IO) {
                check(prefs.edit().putBoolean("active", true).putString("probe", probe?.name)
                    .putString("outcome", "Live reading was interrupted. Saved readings are retained.").commit())
                marker = true
            }
            val store = WatchStore(this)
            val clock = withContext(Dispatchers.IO) { store.clock() }
            val session = UUID.randomUUID().toString()
            var lastSync = -5000L
            var lastUi = -500L
            val guard = {
                try { requireSamsungSensorAvailable(this, store, clock.boot) }
                catch (error: OnDemandException) {
                    if (error.code == "WORKOUT_ACTIVE") {
                        if (probe == null) throw SensorSdkHeartRateException.SessionAlreadyActive()
                        throw SensorSdkRawProbeException.SessionAlreadyActive()
                    }
                    throw error
                }
            }
            val frames = if (probe == null) SensorSdkHeartRateSource(this).also { sensor = it }.batches(guard).map { batch ->
                val elapsed = requireNotNull(batch.receivedElapsedRealtimeMillis)
                HeartFrame(store.installation, session, clock.boot, requireNotNull(clock.bootCount), elapsed,
                    ZoneId.systemDefault().rules.getOffset(batch.receivedAt).totalSeconds,
                    (clock.changed && batch.points.any { (it.sensorTimestamp?.toEpochMilli() ?: Long.MAX_VALUE) < clock.wall }) ||
                        abs(batch.receivedAt.toEpochMilli() - clock.wallAt(elapsed)) > 2000, batch)
            } else SensorSdkRawProbeSource(this).also { rawSensor = it }.chunks(probe, guard).map { chunk ->
                val elapsed = requireNotNull(chunk.receivedElapsedRealtimeMillis)
                RawSensorFrame(store.installation, session, clock.boot, requireNotNull(clock.bootCount), elapsed,
                    ZoneId.systemDefault().rules.getOffset(chunk.receivedAt).totalSeconds,
                    (clock.changed && chunk.samples.any { (it.rawSensorTimestampEpochMillis ?: Long.MAX_VALUE) < clock.wall }) ||
                        abs(chunk.receivedAt.toEpochMilli() - clock.wallAt(elapsed)) > 2000, chunk)
            }
            frames.collect { frame ->
                if (!recordingPermissions(probe).all { WatchPermissions.granted(this, it) }) throw SecurityException("Sensor access changed")
                val elapsed = frame.receivedElapsed
                // Once delivered, persist the whole callback even if Stop arrives during the transaction.
                val reading = withContext(NonCancellable + Dispatchers.IO) {
                    check(filesDir.usableSpace >= 16L * 1024 * 1024) { "Watch storage is nearly full" }
                    store.journal().use {
                        when (frame) {
                            is HeartFrame -> HeartJournal(it).capture(frame)
                            is RawSensorFrame -> RawSensorJournal(it).capture(frame)
                        }
                    }
                    if (elapsed - lastSync >= 5000) {
                        requestMeasurementSync(this@WatchHeartService)
                        if (probe == null || probe == SensorRawProbe.SKIN_TEMPERATURE_CONTINUOUS) WatchGlanceUpdates.request(this@WatchHeartService)
                        lastSync = elapsed
                    }
                    frame.readings().maxByOrNull { it.elapsedMs }
                }
                callbacks++
                if (frame is HeartFrame) intervals += frame.batch.points.sumOf { it.rawIbiMillis?.size?.toLong() ?: 0L }
                val chunk = (frame as? RawSensorFrame)?.chunk
                samples += chunk?.samples?.size ?: 0
                if (owner === this@WatchHeartService && (probe == null || stopping || elapsed - lastUi >= 500)) {
                    mutableState.value = WatchHeartState(if (stopping) "stopping" else "running", reading = reading,
                        callbacks = callbacks, intervals = intervals, probe = probe, samples = samples)
                    lastUi = elapsed
                }
            }
            if (!stopping) outcome = "The sensor stream ended. Saved readings are retained."
        } catch (_: CancellationException) {
            outcome = "Live reading interrupted. Earlier saved readings are retained."
        } catch (error: Exception) {
            outcome = when (error) {
                is SensorSdkRawProbeException.PermissionRequired, is SensorSdkHeartRateException.PermissionRequired, is SecurityException -> "Allow sensor access in Orbit’s permissions."
                is SensorSdkRawProbeException.TrackerUnsupported -> "This sensor is not available on your Watch."
                is SensorSdkHeartRateException.TrackerUnsupported -> "This Watch does not support Samsung live heart rate."
                is SensorSdkRawProbeException.SdkPolicyRejected, is SensorSdkHeartRateException.SdkPolicyRejected -> "Samsung sensor access is not enabled for this build."
                is SensorSdkRawProbeException.ServiceUpdateRequired, is SensorSdkHeartRateException.ServiceUpdateRequired -> "Update Samsung Health Sensor Service on your Watch."
                is SensorSdkRawProbeException.SessionAlreadyActive, is SensorSdkHeartRateException.SessionAlreadyActive -> "Finish the other workout or measurement before starting this recording."
                is SensorSdkRawProbeException.ServiceUnavailable, is SensorSdkHeartRateException.ServiceUnavailable -> "Samsung Health Sensor Service could not connect."
                is SensorSdkRawProbeException.CallbackBufferOverflow, is SensorSdkHeartRateException.CallbackBufferOverflow -> "Live reading stopped because capture could not keep up. Earlier readings are saved."
                else -> "Live reading could not finish. Check available storage and sensor access; saved readings are retained."
            }
        } finally {
            // Cancellation must not interrupt the return from IO before the foreground owner is cleared.
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) {
                    try { stopSensors() } catch (_: Exception) { outcome = "Sensor cleanup failed. Restart your Watch before measuring again." }
                    sensor = null
                    rawSensor = null
                    if (marker && !prefs.edit().putBoolean("active", false).putString("outcome", outcome).commit())
                        outcome = "Session recovery needs retry. Saved readings are retained."
                    requestMeasurementSync(this@WatchHeartService)
                }
                if (owner === this@WatchHeartService) {
                    mutableState.value = mutableState.value.copy(phase = "idle", message = outcome, reading = null,
                        callbacks = callbacks, intervals = intervals, samples = samples)
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                }
            }
        }
    }

    private fun stopCapture() {
        if (job?.isCompleted != false) { stopSelf(); return }
        if (stopping) return
        stopping = true
        val starting = mutableState.value.phase == "starting"
        mutableState.value = mutableState.value.copy(phase = "stopping")
        if (starting) { job?.cancel(); return }
        scope.launch {
            try { withTimeout(5000) { stopSensors(); job?.join() } }
            catch (_: Exception) { job?.cancel() }
        }
    }

    private suspend fun stopSensors() { sensor?.stopGracefully(); rawSensor?.stopGracefully() }

    private fun notification() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("orbit-live-heart", "Sensor recording", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, WatchHeartActivity::class.java).putExtra("probe", mutableState.value.probe?.name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 0, Intent(this, WatchHeartService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notice = Notification.Builder(this, "orbit-live-heart").setSmallIcon(R.drawable.orbit_icon)
            .setContentTitle(recordingTitle(mutableState.value.probe)).setContentText("Recording on your Watch").setContentIntent(open)
            .setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(104, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH) else startForeground(104, notice)
    }

    override fun onDestroy() {
        scope.cancel()
        if (owner === this) {
            if (mutableState.value.active) mutableState.value = mutableState.value.copy(phase = "idle", reading = null,
                message = "Live reading interrupted. Saved readings are retained.")
            owner = null
        }
        super.onDestroy()
    }
    companion object {
        private val mutableState = MutableStateFlow(WatchHeartState())
        private var owner: WatchHeartService? = null
        private val sessions = Mutex()
        internal val state = mutableState.asStateFlow()
        const val START = "com.mani.orbit.START_LIVE_HEART"
        const val STOP = "com.mani.orbit.STOP_LIVE_HEART"
        fun start(context: Context, probe: SensorRawProbe? = null) {
            if (mutableState.value.active) return
            mutableState.value = WatchHeartState("starting", probe = probe)
            try { context.startForegroundService(Intent(context, WatchHeartService::class.java).setAction(START).putExtra("probe", probe?.name)) }
            catch (error: Exception) { mutableState.value = WatchHeartState(probe = probe); throw error }
        }
        fun stop(context: Context) = context.startService(Intent(context, WatchHeartService::class.java).setAction(STOP))
    }
}
