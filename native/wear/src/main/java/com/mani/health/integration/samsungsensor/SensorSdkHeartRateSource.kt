package com.mani.health.integration.samsungsensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.mani.health.core.model.heart.HeartBeatBatch
import com.mani.health.core.model.SensorLeaseGate
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class SensorSdkHeartRateSource(context: Context) {
    private val appContext = context.applicationContext
    private val activeSession = AtomicReference<SamsungHeartRateSession?>()

    fun batches(beforeStart: () -> Unit = {}): Flow<HeartBeatBatch> = callbackFlow {
        ensurePermission()
        val callbackSequence = AtomicLong(0)
        val sessionLease = samsungHeartRateSessionGate.tryAcquire()
            ?: throw SensorSdkHeartRateException.SessionAlreadyActive()
        val session = SamsungHeartRateSession(
            appContext = appContext,
            beforeStart = beforeStart,
            sessionLease = sessionLease,
            nextSequence = callbackSequence::getAndIncrement,
            emit = { batch -> trySend(batch).isSuccess },
            fail = ::close,
            complete = { close() },
        )
        if (!activeSession.compareAndSet(null, session)) {
            session.cancelImmediately()
            throw SensorSdkHeartRateException.SessionAlreadyActive()
        }
        try {
            session.start()
        } catch (error: Exception) {
            session.cancelImmediately()
            activeSession.compareAndSet(session, null)
            throw SensorSdkHeartRateException.Unexpected(error.javaClass.name)
        }
        awaitClose {
            session.cancelImmediately()
            activeSession.compareAndSet(session, null)
        }
    }.buffer(Channel.BUFFERED).flowOn(Dispatchers.IO)

    suspend fun stopGracefully() {
        val session = activeSession.get() ?: return
        session.stopGracefully()
        activeSession.compareAndSet(session, null)
    }

    private fun ensurePermission() {
        val granted = try {
            appContext.checkSelfPermission(requiredHeartRatePermission()) == PackageManager.PERMISSION_GRANTED
        } catch (error: Exception) {
            throw SensorSdkHeartRateException.Unexpected(error.javaClass.name)
        }
        if (!granted) throw SensorSdkHeartRateException.PermissionRequired()
    }
}

private class SamsungHeartRateSession(
    private val appContext: Context,
    private val beforeStart: () -> Unit,
    sessionLease: AutoCloseable,
    private val nextSequence: () -> Long,
    private val emit: (HeartBeatBatch) -> Boolean,
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
                    val receivedElapsed = android.os.SystemClock.elapsedRealtime()
                    val reads = dataPoints.toList().map(DataPoint::toHeartRatePointRead)
                    val mapped = mapSamsungHeartRateBatch(sequence, receivedAt, reads)
                    val batch = HeartBeatBatch(sequence, receivedAt, mapped.points, mapped.issues, receivedElapsed)
                    if (lifecycle.deliverIfActive { emit(batch) } ==
                        SensorSessionLifecycle.DeliveryResult.REJECTED
                    ) {
                        failWith(SensorSdkHeartRateException.CallbackBufferOverflow())
                    }
                } catch (error: Exception) {
                    failWith(SensorSdkHeartRateException.Unexpected(error.javaClass.name))
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
                        SensorSdkHeartRateException.PermissionRequired()
                    HealthTracker.TrackerError.SDK_POLICY_ERROR ->
                        SensorSdkHeartRateException.SdkPolicyRejected()
                },
            )
        }
    }

    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            val trackingService = service.get() ?: return
            try {
                val supportedTypes = trackingService.trackingCapability.supportHealthTrackerTypes
                if (HealthTrackerType.HEART_RATE_CONTINUOUS !in supportedTypes) {
                    failWith(SensorSdkHeartRateException.TrackerUnsupported())
                    return
                }

                val tracker = trackingService.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
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
            failWith(SensorSdkHeartRateException.ServiceUnavailable())
        }

        override fun onConnectionFailed(error: HealthTrackerException) {
            startupFinished.countDown()
            failWith(error.toSourceException())
        }
    }

    private val worker = Thread(::connect, "samsung-heart-rate-connect")

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
                failWith(SensorSdkHeartRateException.ServiceUnavailable())
                return
            }
            if (!lifecycle.acceptsCallbacks) return

            beforeStart() // Orbit ownership check runs while the shared device lease is held.
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
                failWith(SensorSdkHeartRateException.ServiceUnavailable())
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            if (lifecycle.acceptsCallbacks) failWith(SensorSdkHeartRateException.ServiceUnavailable())
        } catch (error: Exception) {
            failWith(error.toSourceException())
        } finally {
            connectionLease?.close()
        }
    }

    private fun failWith(error: SensorSdkHeartRateException) {
        if (lifecycle.beginStop()) fail(error)
    }
}

private fun DataPoint.toHeartRatePointRead() = SamsungHeartRatePointRead(
    sensorTimestampEpochMillis = readSamsungField { timestamp },
    heartRateBpm = readInt(ValueKey.HeartRateSet.HEART_RATE),
    heartRateStatus = readInt(ValueKey.HeartRateSet.HEART_RATE_STATUS),
    ibiMillis = readIntList(ValueKey.HeartRateSet.IBI_LIST),
    ibiStatuses = readIntList(ValueKey.HeartRateSet.IBI_STATUS_LIST),
)

private fun DataPoint.readInt(key: ValueKey<Int>): SamsungFieldRead<Int?> =
    readSamsungField { getValue(key) }

private fun DataPoint.readIntList(key: ValueKey<List<Int>>): SamsungFieldRead<List<Int>?> =
    readSamsungField { getValue(key)?.map(Int::toInt) }

private fun HealthTrackerException.toSourceException(): SensorSdkHeartRateException = when (errorCode) {
    HealthTrackerException.PACKAGE_NOT_INSTALLED -> SensorSdkHeartRateException.ServiceUnavailable()
    HealthTrackerException.OLD_PLATFORM_VERSION -> SensorSdkHeartRateException.ServiceUpdateRequired()
    else -> SensorSdkHeartRateException.ServiceUnavailable()
}

private fun Exception.toSourceException(): SensorSdkHeartRateException = when (this) {
    is SensorSdkHeartRateException -> this
    is SecurityException -> SensorSdkHeartRateException.PermissionRequired()
    is UnsupportedOperationException -> SensorSdkHeartRateException.TrackerUnsupported()
    is IllegalStateException -> SensorSdkHeartRateException.ServiceUnavailable()
    else -> SensorSdkHeartRateException.Unexpected(javaClass.name)
}

private fun requiredHeartRatePermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        READ_HEART_RATE_PERMISSION
    } else {
        Manifest.permission.BODY_SENSORS
    }

private fun remainingNanos(deadlineNanos: Long): Long =
    (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

private const val READ_HEART_RATE_PERMISSION = "android.permission.health.READ_HEART_RATE"
private const val CONNECT_TIMEOUT_MILLIS = 30_000L
private const val WORKER_STOP_TIMEOUT_MILLIS = 21_000L
private const val FLUSH_TIMEOUT_MILLIS = 2_000L
private const val GRACEFUL_STOP_TIMEOUT_MILLIS =
    CONNECT_TIMEOUT_MILLIS + WORKER_STOP_TIMEOUT_MILLIS + FLUSH_TIMEOUT_MILLIS
