package com.mani.health.integration.samsungsensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.Build
import com.mani.health.core.model.SensorLeaseGate
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.PpgType
import com.samsung.android.service.health.tracking.data.ValueKey
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * A cold, bounded, disposable source for one explicitly selected raw tracker.
 * This source never starts a tracker until its flow is collected.
 */
class SensorSdkRawProbeSource(context: Context) {
    private val appContext = context.applicationContext
    private val activeSession = AtomicReference<SamsungRawProbeSession?>()

    fun chunks(probe: SensorRawProbe, beforeStart: () -> Unit = {}): Flow<RawProbeChunk> = callbackFlow {
        ensurePermission(probe)
        val callbackSequence = AtomicLong(0)
        val sessionLease = samsungRawProbeSessionGate.tryAcquire()
            ?: throw SensorSdkRawProbeException.SessionAlreadyActive()
        val session = SamsungRawProbeSession(
            appContext = appContext,
            probe = probe,
            sessionLease = sessionLease,
            beforeStart = beforeStart,
            nextSequence = callbackSequence::getAndIncrement,
            emit = { chunk -> trySend(chunk).isSuccess },
            fail = ::close,
            complete = { close() },
        )
        if (!activeSession.compareAndSet(null, session)) {
            session.cancelImmediately()
            throw SensorSdkRawProbeException.SessionAlreadyActive()
        }
        try {
            session.start()
        } catch (error: Exception) {
            session.cancelImmediately()
            activeSession.compareAndSet(session, null)
            throw error.toSourceException()
        }
        awaitClose {
            session.cancelImmediately()
            activeSession.compareAndSet(session, null)
        }
    }.buffer(RAW_PROBE_BUFFER_CAPACITY).flowOn(Dispatchers.IO)

    suspend fun stopGracefully() {
        val session = activeSession.get() ?: return
        session.stopGracefully()
        activeSession.compareAndSet(session, null)
    }

    private fun ensurePermission(probe: SensorRawProbe) {
        val granted = try {
            appContext.checkSelfPermission(requiredRawProbePermission(probe)) ==
                PackageManager.PERMISSION_GRANTED
        } catch (error: Exception) {
            throw error.toSourceException()
        }
        if (!granted) throw SensorSdkRawProbeException.PermissionRequired()
    }
}

private class SamsungRawProbeSession(
    private val appContext: Context,
    private val probe: SensorRawProbe,
    sessionLease: AutoCloseable,
    private val beforeStart: () -> Unit,
    private val nextSequence: () -> Long,
    private val emit: (RawProbeChunk) -> Boolean,
    private val fail: (Throwable) -> Boolean,
    private val complete: () -> Boolean,
) {
    private val lifecycle = SensorSessionLifecycle(sessionLease, FLUSH_TIMEOUT_MILLIS)
    private val startupFinished = CountDownLatch(1)
    private val closed = CountDownLatch(1)
    private val service = AtomicReference<HealthTrackingService?>()
    private val dataCallbackLock = Any()

    private val trackerListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            synchronized(dataCallbackLock) {
                if (!lifecycle.acceptsCallbacks) return
                try {
                    val sequence = nextSequence()
                    val receivedAt = Instant.now()
                    val receivedElapsed = SystemClock.elapsedRealtime()
                    require(dataPoints.size <= 16384) { "Raw sensor callback exceeds storage limit" }
                    val reads = dataPoints.toList().map { it.toSamsungRawPointRead(probe) }
                    val chunk = mapSamsungRawProbeChunk(probe, sequence, receivedAt, reads, receivedElapsed)
                    if (lifecycle.deliverIfActive { emit(chunk) } ==
                        SensorSessionLifecycle.DeliveryResult.REJECTED
                    ) {
                        failWith(SensorSdkRawProbeException.CallbackBufferOverflow())
                    }
                } catch (error: Exception) {
                    failWith(error.toSourceException())
                }
            }
        }

        override fun onFlushCompleted() {
            lifecycle.onFlushCompleted()
        }

        override fun onError(error: HealthTracker.TrackerError) {
            failWith(
                when (error) {
                    HealthTracker.TrackerError.PERMISSION_ERROR ->
                        SensorSdkRawProbeException.PermissionRequired()
                    HealthTracker.TrackerError.SDK_POLICY_ERROR ->
                        SensorSdkRawProbeException.SdkPolicyRejected()
                },
            )
        }
    }

    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            val trackingService = service.get() ?: return
            try {
                val supportedTypes = trackingService.trackingCapability.supportHealthTrackerTypes
                val trackerType = probe.toHealthTrackerType()
                if (trackerType !in supportedTypes) {
                    failWith(SensorSdkRawProbeException.TrackerUnsupported())
                    return
                }

                val tracker = when (probe) {
                    SensorRawProbe.PPG_CONTINUOUS -> trackingService.getHealthTracker(
                        HealthTrackerType.PPG_CONTINUOUS,
                        setOf(PpgType.GREEN, PpgType.RED, PpgType.IR),
                    )
                    SensorRawProbe.ACCELEROMETER_CONTINUOUS ->
                        trackingService.getHealthTracker(HealthTrackerType.ACCELEROMETER_CONTINUOUS)
                    SensorRawProbe.SKIN_TEMPERATURE_CONTINUOUS ->
                        trackingService.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS)
                }
                lifecycle.attachTracker(
                    setListener = { tracker.setEventListener(trackerListener) },
                    flush = tracker::flush,
                    unsetListener = tracker::unsetEventListener,
                )
            } catch (error: Exception) {
                failWith(error.toSourceException())
            } finally {
                startupFinished.countDown()
            }
        }

        override fun onConnectionEnded() {
            startupFinished.countDown()
            failWith(SensorSdkRawProbeException.ServiceUnavailable())
        }

        override fun onConnectionFailed(error: HealthTrackerException) {
            startupFinished.countDown()
            failWith(error.toSourceException())
        }
    }

    private val worker = Thread(::connect, "samsung-raw-probe-${probe.name.lowercase()}")

    fun start() {
        worker.start()
    }

    suspend fun stopGracefully() = withContext(Dispatchers.IO) {
        if (!lifecycle.beginGracefulStop()) {
            closed.await(GRACEFUL_STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            return@withContext
        }
        try {
            startupFinished.await(CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            lifecycle.flushAndStop()
        } finally {
            lifecycle.beginStop()
            try {
                stopWorkerAndCleanup()
            } finally {
                complete()
            }
        }
    }

    fun cancelImmediately() {
        lifecycle.beginStop()
        stopWorkerAndCleanup()
    }

    private fun stopWorkerAndCleanup() {
        worker.interrupt()
        startupFinished.countDown()
        try {
            worker.join(WORKER_STOP_TIMEOUT_MILLIS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            lifecycle.cleanup()
        } finally {
            closed.countDown()
        }
    }

    private fun connect() {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONNECT_TIMEOUT_MILLIS)
        var connectionLease: SensorLeaseGate.Lease? = null
        try {
            connectionLease = samsungSensorConnectionGate.acquire(remainingNanos(deadlineNanos))
            if (connectionLease == null) {
                failWith(SensorSdkRawProbeException.ServiceUnavailable())
                return
            }
            if (!lifecycle.acceptsCallbacks) return
            beforeStart()
            if (!lifecycle.acceptsCallbacks) return

            val trackingService = HealthTrackingService(connectionListener, appContext)
            service.set(trackingService)
            if (!lifecycle.attachConnection(
                    connectionLease,
                    disconnect = {
                        service.compareAndSet(trackingService, null)
                        trackingService.disconnectService()
                    },
                )
            ) {
                service.compareAndSet(trackingService, null)
                return
            }
            connectionLease = null

            if (!lifecycle.acceptsCallbacks) return
            trackingService.connectService()

            val remaining = remainingNanos(deadlineNanos)
            if (remaining == 0L || !startupFinished.await(remaining, TimeUnit.NANOSECONDS)) {
                failWith(SensorSdkRawProbeException.ServiceUnavailable())
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            if (lifecycle.acceptsCallbacks) failWith(SensorSdkRawProbeException.ServiceUnavailable())
        } catch (error: Exception) {
            failWith(error.toSourceException())
        } finally {
            startupFinished.countDown()
            connectionLease?.close()
        }
    }

    private fun failWith(error: SensorSdkRawProbeException) {
        if (lifecycle.beginStop()) fail(error)
    }
}

private fun DataPoint.toSamsungRawPointRead(probe: SensorRawProbe): SamsungRawPointRead = when (probe) {
    SensorRawProbe.PPG_CONTINUOUS -> SamsungPpgPointRead(
        sensorTimestampEpochMillis = readSamsungField { timestamp },
        ppgGreen = readSamsungField { getValue(ValueKey.PpgSet.PPG_GREEN) },
        greenStatus = readSamsungField { getValue(ValueKey.PpgSet.GREEN_STATUS) },
        ppgIr = readSamsungField { getValue(ValueKey.PpgSet.PPG_IR) },
        irStatus = readSamsungField { getValue(ValueKey.PpgSet.IR_STATUS) },
        ppgRed = readSamsungField { getValue(ValueKey.PpgSet.PPG_RED) },
        redStatus = readSamsungField { getValue(ValueKey.PpgSet.RED_STATUS) },
    )
    SensorRawProbe.ACCELEROMETER_CONTINUOUS -> SamsungAccelerometerPointRead(
        sensorTimestampEpochMillis = readSamsungField { timestamp },
        accelerometerX = readSamsungField {
            getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X)
        },
        accelerometerY = readSamsungField {
            getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y)
        },
        accelerometerZ = readSamsungField {
            getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z)
        },
    )
    SensorRawProbe.SKIN_TEMPERATURE_CONTINUOUS -> SamsungSkinTemperaturePointRead(
        sensorTimestampEpochMillis = readSamsungField { timestamp },
        objectTemperature = readSamsungField {
            getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)
        },
        ambientTemperature = readSamsungField {
            getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE)
        },
        status = readSamsungField { getValue(ValueKey.SkinTemperatureSet.STATUS) },
    )
}

private fun SensorRawProbe.toHealthTrackerType(): HealthTrackerType = when (this) {
    SensorRawProbe.PPG_CONTINUOUS -> HealthTrackerType.PPG_CONTINUOUS
    SensorRawProbe.ACCELEROMETER_CONTINUOUS -> HealthTrackerType.ACCELEROMETER_CONTINUOUS
    SensorRawProbe.SKIN_TEMPERATURE_CONTINUOUS -> HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS
}

private fun HealthTrackerException.toSourceException(): SensorSdkRawProbeException = when (errorCode) {
    HealthTrackerException.PACKAGE_NOT_INSTALLED -> SensorSdkRawProbeException.ServiceUnavailable()
    HealthTrackerException.OLD_PLATFORM_VERSION -> SensorSdkRawProbeException.ServiceUpdateRequired()
    else -> SensorSdkRawProbeException.ServiceUnavailable()
}

private fun Exception.toSourceException(): SensorSdkRawProbeException = when (this) {
    is SensorSdkRawProbeException -> this
    is SecurityException -> SensorSdkRawProbeException.PermissionRequired()
    is UnsupportedOperationException -> SensorSdkRawProbeException.TrackerUnsupported()
    is IllegalStateException -> SensorSdkRawProbeException.ServiceUnavailable()
    else -> SensorSdkRawProbeException.Unexpected(javaClass.name)
}

internal fun requiredRawProbePermission(probe: SensorRawProbe): String = when (probe) {
    SensorRawProbe.PPG_CONTINUOUS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        READ_ADDITIONAL_HEALTH_DATA_PERMISSION
    } else {
        Manifest.permission.BODY_SENSORS
    }
    SensorRawProbe.ACCELEROMETER_CONTINUOUS -> Manifest.permission.ACTIVITY_RECOGNITION
    SensorRawProbe.SKIN_TEMPERATURE_CONTINUOUS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        READ_SKIN_TEMPERATURE_PERMISSION
    } else {
        Manifest.permission.BODY_SENSORS
    }
}

private fun remainingNanos(deadlineNanos: Long): Long =
    (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

private const val READ_ADDITIONAL_HEALTH_DATA_PERMISSION =
    "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
private const val READ_SKIN_TEMPERATURE_PERMISSION = "android.permission.health.READ_SKIN_TEMPERATURE"
private const val RAW_PROBE_BUFFER_CAPACITY = 16
private const val CONNECT_TIMEOUT_MILLIS = 30_000L
private const val WORKER_STOP_TIMEOUT_MILLIS = 21_000L
private const val FLUSH_TIMEOUT_MILLIS = 2_000L
private const val GRACEFUL_STOP_TIMEOUT_MILLIS =
    CONNECT_TIMEOUT_MILLIS + WORKER_STOP_TIMEOUT_MILLIS + FLUSH_TIMEOUT_MILLIS
