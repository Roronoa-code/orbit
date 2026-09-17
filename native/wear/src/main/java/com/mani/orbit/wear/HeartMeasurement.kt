package com.mani.orbit.wear

import android.content.Context
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.*
import com.mani.health.core.model.deviceSensorLeaseGate
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** MeasureClient is a short, visible spot check. Passive tracking owns background collection. */
class HeartMeasurement(private val context: Context) {
    suspend fun run() {
        val store = WatchStore(context)
        val client = HealthServices.getClient(context).measureClient
        val lease = deviceSensorLeaseGate.tryAcquire()
            ?: error("Another measurement is using the sensor. Try again when it finishes.")
        val executor = Executors.newSingleThreadExecutor()
        var registrationAttempted = false
        val failed = CompletableDeferred<Unit>()
        val callback = object : MeasureCallback {
            override fun onRegistered() { store.status("measure", "Finding your pulse…") }
            override fun onRegistrationFailed(throwable: Throwable) { failed.completeExceptionally(throwable) }
            override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
                store.status("measure", if (availability == DataTypeAvailability.AVAILABLE) "Measuring" else "Adjust your watch and keep still")
            }
            override fun onDataReceived(data: DataPointContainer) {
                try { WatchSamples.capture(context, data) }
                catch (error: Exception) { failed.completeExceptionally(error) }
            }
        }
        try {
            check(WatchPermissions.granted(context, WatchPermissions.heart)) { "Heart-rate permission required" }
            val supported = withContext(Dispatchers.IO) { client.getCapabilitiesAsync().get(15, TimeUnit.SECONDS).supportedDataTypesMeasure }
            check(DataType.HEART_RATE_BPM in supported) { "Heart-rate measurement unsupported" }
            store.status("measure", "Starting measurement…")
            registrationAttempted = true
            client.registerMeasureCallback(DataType.HEART_RATE_BPM, executor, callback)
            withTimeoutOrNull(60_000) { failed.await() }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (registrationAttempted) {
                    val stopped = try { client.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback) }
                    catch (failure: Exception) {
                        executor.shutdown()
                        store.status("measure", "Sensor cleanup failed. Restart your Watch before measuring again.")
                        throw failure // Keep the lease: native sensor ownership is still unknown.
                    }
                    // A late unregister still owns the lease. Return it only after queued captures drain.
                    stopped.addListener({
                        try { stopped.get(); executor.execute { lease.close() } }
                        catch (_: Exception) { store.status("measure", "Sensor cleanup failed. Restart your Watch before measuring again.") }
                        finally { executor.shutdown() }
                    }, { it.run() })
                    stopped.get(15, TimeUnit.SECONDS)
                } else {
                    lease.close(); executor.shutdown()
                }
                check(executor.awaitTermination(15, TimeUnit.SECONDS)) { "Measurement capture has not finished" }
                store.status("measure", "Measurement stopped")
            }
        }
    }
}
