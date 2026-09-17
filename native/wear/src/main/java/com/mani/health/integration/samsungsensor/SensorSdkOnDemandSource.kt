package com.mani.health.integration.samsungsensor

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.mani.health.core.model.measurement.MeasurementTracker
import com.mani.health.core.model.measurement.SensorProfile
import com.mani.health.core.model.measurement.measurementPermission
import com.samsung.android.service.health.tracking.*
import com.samsung.android.service.health.tracking.data.*
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

/** Reused sensor engine. Orbit starts only after an explicit action; Samsung's service authorizes SDK access. */
class SensorSdkOnDemandSource(context:Context) {
    private val context=context.applicationContext
    private val active=AtomicReference<Active?>()

    fun frames(tracker:MeasurementTracker,profile:SensorProfile?=null,onReady:()->Unit={},beforeStart:()->Unit={}):Flow<OnDemandFrame> = callbackFlow {
        require(tracker!=MeasurementTracker.HEART_RATE)
        if(tracker==MeasurementTracker.BIA && profile==null) throw OnDemandException("PROFILE_REQUIRED")
        val permission=measurementPermission(tracker,Build.VERSION.SDK_INT)
        if(context.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED) throw OnDemandException("PERMISSION_REQUIRED")
        if(active.get()!=null) throw OnDemandException("SESSION_BUSY")
        val lease=samsungSensorConnectionGate.tryAcquire() ?: throw OnDemandException("SESSION_BUSY")
        // Keep the ownership check and tracker startup in the same lease. Cancellation must return it.
        try { runInterruptible(Dispatchers.IO) { beforeStart() } }
        catch(error:Throwable) { lease.close(); throw error }
        val lifecycle=SensorSessionLifecycle(lease,500)
        val session=Active(lifecycle) { close() }
        if(!active.compareAndSet(null,session)) { lifecycle.cleanup(); throw OnDemandException("SESSION_BUSY") }
        var service:HealthTrackingService?=null
        val trackerStarting=CompletableDeferred<Unit>()
        val callbackSequence=AtomicLong(0)
        val trackerListener=object:HealthTracker.TrackerEventListener {
            override fun onDataReceived(points:List<DataPoint>) {
                if(!lifecycle.acceptsCallbacks) return
                try {
                    require(points.size<=10000)
                    if(tracker==MeasurementTracker.ECG) {
                        if(points.isEmpty()) return
                        if(points.size !in setOf(5,10)) { close(OnDemandException("UNSUPPORTED_BATCH_LAYOUT")); return }
                        val receivedAt=Instant.now(); val elapsed=SystemClock.elapsedRealtimeNanos()
                        val copied=points.mapIndexed { index,point -> copyEcgPoint(index,point.timestamp,point.getValue(ValueKey.EcgSet.ECG_MV)) { field -> when(field) {
                            EcgAuxField.LEAD_OFF->point.getValue(ValueKey.EcgSet.LEAD_OFF)
                            EcgAuxField.SEQUENCE->point.getValue(ValueKey.EcgSet.SEQUENCE)
                            EcgAuxField.MAX_THRESHOLD_MV->point.getValue(ValueKey.EcgSet.MAX_THRESHOLD_MV)
                            EcgAuxField.MIN_THRESHOLD_MV->point.getValue(ValueKey.EcgSet.MIN_THRESHOLD_MV)
                            EcgAuxField.PPG_GREEN->point.getValue(ValueKey.EcgSet.PPG_GREEN)
                        } } }
                        if(lifecycle.deliverIfActive {
                            val frame=OnDemandFrame(tracker,receivedAt,null,false,ecg=copied,callbackSequence=callbackSequence.getAndIncrement(),receivedElapsedNanos=elapsed)
                            trySend(frame).isSuccess
                        }==SensorSessionLifecycle.DeliveryResult.REJECTED)
                            close(OnDemandException("BUFFER_FULL"))
                    } else for(point in points) {
                        val frame=mapPoint(tracker,point)
                        if(lifecycle.deliverIfActive { trySend(frame).isSuccess }==SensorSessionLifecycle.DeliveryResult.REJECTED) close(OnDemandException("BUFFER_FULL"))
                        if(frame.terminal) { close(); break }
                    }
                } catch(_:Exception) { close(OnDemandException("MALFORMED_RESULT")) }
            }
            override fun onFlushCompleted() { lifecycle.onFlushCompleted() }
            override fun onError(error:HealthTracker.TrackerError) { close(OnDemandException(when(error) {
                HealthTracker.TrackerError.PERMISSION_ERROR->"PERMISSION_REQUIRED"
                HealthTracker.TrackerError.SDK_POLICY_ERROR->"SDK_POLICY_REJECTED"
            })) }
        }
        val connection=object:ConnectionListener {
            override fun onConnectionSuccess() {
                if(!lifecycle.acceptsCallbacks) return
                try {
                    val client=service ?: return
                    val kind=when(tracker) {
                        MeasurementTracker.SPO2->HealthTrackerType.SPO2_ON_DEMAND
                        MeasurementTracker.SKIN_TEMPERATURE->HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND
                        MeasurementTracker.ECG->HealthTrackerType.ECG_ON_DEMAND
                        MeasurementTracker.BIA->HealthTrackerType.BIA_ON_DEMAND
                        MeasurementTracker.HEART_RATE->throw OnDemandException("USE_CONTINUOUS_HR_SOURCE")
                    }
                    if(kind !in client.trackingCapability.supportHealthTrackerTypes) { close(OnDemandException("UNSUPPORTED")); return }
                    val handle=if(tracker==MeasurementTracker.BIA) {
                        val p=requireNotNull(profile)
                        client.getHealthTracker(kind,TrackerUserProfile.Builder().setAge(p.ageYears).setGender(p.genderCode).setHeight(p.heightCm).setWeight(p.weightKg).build())
                    } else client.getHealthTracker(kind)
                    if(lifecycle.attachTracker({handle.setEventListener(trackerListener)},handle::flush,handle::unsetEventListener)) {
                        trackerStarting.complete(Unit)
                        onReady()
                    }
                } catch(_:Exception) { close(OnDemandException("START_FAILED")) }
            }
            override fun onConnectionEnded() { close(OnDemandException("SERVICE_UNAVAILABLE")) }
            override fun onConnectionFailed(error:HealthTrackerException) { close(OnDemandException(when(error.errorCode) {
                HealthTrackerException.PACKAGE_NOT_INSTALLED -> "SERVICE_MISSING"
                HealthTrackerException.OLD_PLATFORM_VERSION -> "SERVICE_UPDATE_REQUIRED"
                else -> "SERVICE_UNAVAILABLE"
            })) }
        }
        val timeout=launch {
            try {
                withTimeout(15_000) { trackerStarting.await() }
                delay(30_000) // Samsung on-demand trackers must not be used as continuous collectors.
                if(tracker==MeasurementTracker.ECG) close() else close(OnDemandException("TIMED_OUT"))
            } catch(_:TimeoutCancellationException) { close(OnDemandException("SERVICE_UNAVAILABLE")) }
        }
        try {
            val client=HealthTrackingService(connection,context)
            service=client
            lifecycle.attachConnection(AutoCloseable { }) { client.disconnectService() }
            client.connectService()
            awaitClose { }
        } finally {
            timeout.cancel()
            lifecycle.beginStop(); lifecycle.cleanup(); active.compareAndSet(session,null)
        }
    }.buffer(16).flowOn(Dispatchers.IO)

    suspend fun stopGracefully():Unit=withContext(Dispatchers.IO) {
        active.get()?.let { session ->
            if(session.lifecycle.beginGracefulStop()) session.lifecycle.flushAndStop()
            session.lifecycle.beginStop(); session.lifecycle.cleanup(); session.finish()
            active.compareAndSet(session,null)
        }
        Unit
    }

    private class Active(val lifecycle:SensorSessionLifecycle,val finish:()->Unit)
}

private fun mapPoint(tracker:MeasurementTracker,point:DataPoint):OnDemandFrame {
    val status=when(tracker) {
        MeasurementTracker.SPO2->point.getValue(ValueKey.SpO2Set.STATUS)
        MeasurementTracker.SKIN_TEMPERATURE->point.getValue(ValueKey.SkinTemperatureSet.STATUS)
        MeasurementTracker.BIA->point.getValue(ValueKey.BiaSet.STATUS)
        else->null
    }
    val state=onDemandStatus(tracker,status)
    // The API does not specify the progress scale. Retain its raw finite value only for diagnostics.
    val progress=if(tracker==MeasurementTracker.BIA) runCatching { point.getValue(ValueKey.BiaSet.PROGRESS).toDouble() }.getOrNull()?.takeIf { it.isFinite() } else null
    if(state!="COMPLETE") return OnDemandFrame(tracker,Instant.now(),status,false,errorCode=state.takeUnless { it=="MEASURING" },sensorProgress=progress)
    val values=when(tracker) {
        MeasurementTracker.SPO2->listOf(OnDemandValue("SPO2",point.getValue(ValueKey.SpO2Set.SPO2).toDouble(),"%"))
        MeasurementTracker.SKIN_TEMPERATURE->listOf(OnDemandValue("SKIN_TEMPERATURE",point.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE).toDouble(),"°C"),
            OnDemandValue("AMBIENT_TEMPERATURE",point.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE).toDouble(),"°C"))
        MeasurementTracker.BIA->listOf(OnDemandValue("BODY_FAT",point.getValue(ValueKey.BiaSet.BODY_FAT_RATIO).toDouble(),"%"),
            OnDemandValue("BODY_FAT_MASS",point.getValue(ValueKey.BiaSet.BODY_FAT_MASS).toDouble(),"kg"),
            OnDemandValue("BODY_WATER",point.getValue(ValueKey.BiaSet.TOTAL_BODY_WATER).toDouble(),"L"),
            OnDemandValue("SKELETAL_MUSCLE_MASS",point.getValue(ValueKey.BiaSet.SKELETAL_MUSCLE_MASS).toDouble(),"kg"),
            OnDemandValue("FAT_FREE_MASS",point.getValue(ValueKey.BiaSet.FAT_FREE_MASS).toDouble(),"kg"),
            OnDemandValue("BASAL_METABOLIC_RATE",point.getValue(ValueKey.BiaSet.BASAL_METABOLIC_RATE).toDouble(),"kcal"))
        else->emptyList()
    }
    if(tracker==MeasurementTracker.SPO2) require(values.single().value in 0.0..100.0)
    return OnDemandFrame(tracker,Instant.now(),status,true,values,sensorProgress=progress)
}
