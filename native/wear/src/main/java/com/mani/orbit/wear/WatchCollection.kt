package com.mani.orbit.wear

import android.Manifest
import com.mani.orbit.sync.DiagnosticApplication
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.*
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

object WatchPermissions {
    val heart get() = if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS
    val background get() = when {
        Build.VERSION.SDK_INT >= 36 -> "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
        Build.VERSION.SDK_INT >= 33 -> "android.permission.BODY_SENSORS_BACKGROUND"
        else -> null
    }
    fun granted(context: Context, permission: String?) = permission == null || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

object WatchSamples {
    val dailyTypes = setOf(DataType.STEPS_DAILY, DataType.DISTANCE_DAILY, DataType.CALORIES_DAILY, DataType.FLOORS_DAILY)
    fun capture(context: Context, data: DataPointContainer): Int {
        val store = WatchStore(context)
        val clock = store.clock()
        val readings = buildList {
            for (point in data.getData(DataType.HEART_RATE_BPM)) {
                val value = point.value.takeIf { it.isFinite() && it > 0 }
                val quality = if (value == null) "unavailable" else when ((point.accuracy as? HeartRateAccuracy)?.sensorStatus) {
                    HeartRateAccuracy.SensorStatus.ACCURACY_HIGH, HeartRateAccuracy.SensorStatus.ACCURACY_MEDIUM -> "valid"
                    HeartRateAccuracy.SensorStatus.NO_CONTACT -> "no_contact"
                    HeartRateAccuracy.SensorStatus.ACCURACY_LOW, HeartRateAccuracy.SensorStatus.UNRELIABLE -> "unreliable"
                    else -> "unknown"
                }
                val elapsed = point.timeDurationFromBoot.toMillis()
                add(store.sample(clock, "heart", elapsed, elapsed, value, quality))
            }
            fun daily(metric: String, points: List<IntervalDataPoint<out Number>>) {
                for (point in points) {
                    val value = point.value.toDouble().takeIf { it.isFinite() && it >= 0 }
                    add(store.sample(clock, metric, point.startDurationFromBoot.toMillis(), point.endDurationFromBoot.toMillis(),
                        value, if (value == null) "unavailable" else "valid", "daily"))
                }
            }
            daily("steps", data.getData(DataType.STEPS_DAILY))
            daily("distance", data.getData(DataType.DISTANCE_DAILY))
            daily("energy", data.getData(DataType.CALORIES_DAILY))
            daily("floors", data.getData(DataType.FLOORS_DAILY))
        }
        val saved = try { store.save(readings) } finally { WatchSyncWorker.schedule(context) }
        // A phone showing Orbit gets the newest heart reading now, not with the next queued transfer.
        try { WatchLive.offer(context, readings) }
        catch (error: Exception) { Log.w("OrbitWatch", "Live heart offer incomplete: ${error.javaClass.simpleName}") }
        return saved
    }
    fun battery(context: Context) {
        val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (scale <= 0 || level !in 0..scale) return
        val store = WatchStore(context)
        val now = SystemClock.elapsedRealtime()
        store.save(listOf(store.sample(store.clock(), "battery", now, now, 100.0 * level / scale, "valid", source = "android_battery")))
    }
}

class WatchPassiveService : PassiveListenerService() {
    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val store = WatchStore(this)
        if (!store.enabled()) return
        try { WatchSamples.capture(this, dataPoints) }
        catch (error: Exception) {
            Log.e("OrbitWatch", "Capture failed: ${error.javaClass.simpleName}")
            store.status("collection", "Some new readings could not be saved. Check storage and retry.")
        }
    }
    override fun onPermissionLost() {
        WatchStore(this).status("collection", "Health access changed. Open Orbit to reconnect.")
        WatchCollectionWorker.schedule(this)
    }
}

class WatchCollectionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = registration.withLock { withContext(Dispatchers.IO) {
        val store = WatchStore(applicationContext)
        try {
            val client = HealthServices.getClient(applicationContext).passiveMonitoringClient
            if (!store.enabled()) {
                client.clearPassiveListenerServiceAsync().get(15, TimeUnit.SECONDS)
                store.status("collection", "Background collection off")
                return@withContext Result.success()
            }
            val supported = client.getCapabilitiesAsync().get(15, TimeUnit.SECONDS).supportedDataTypesPassiveMonitoring
            val allowed = buildSet {
                if (WatchPermissions.granted(applicationContext, Manifest.permission.ACTIVITY_RECOGNITION)) addAll(WatchSamples.dailyTypes)
                if (WatchPermissions.granted(applicationContext, WatchPermissions.heart) && WatchPermissions.granted(applicationContext, WatchPermissions.background)) add(DataType.HEART_RATE_BPM)
            }.intersect(supported)
            store.status("supported", supported.filter { it in WatchSamples.dailyTypes || it == DataType.HEART_RATE_BPM }.joinToString { it.name })
            if (allowed.isEmpty()) {
                client.clearPassiveListenerServiceAsync().get(15, TimeUnit.SECONDS)
                store.status("collection", if (supported.isEmpty()) "Health Services has no supported readings" else "Allow health and activity access to collect readings")
                return@withContext Result.success()
            }
            client.setPassiveListenerServiceAsync(WatchPassiveService::class.java,
                PassiveListenerConfig.builder().setDataTypes(allowed).build()).get(15, TimeUnit.SECONDS)
            // An off action during registration wins, including after process recreation.
            if (!store.enabled()) {
                client.clearPassiveListenerServiceAsync().get(15, TimeUnit.SECONDS)
                store.status("collection", "Background collection off")
            } else {
                store.status("collection", if (DataType.HEART_RATE_BPM in allowed) "Background health collection on" else "Activity collection on · heart access needed")
                WatchSamples.battery(applicationContext)
                WatchSyncWorker.schedule(applicationContext)
            }
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.w("OrbitWatch", "Registration incomplete: ${error.javaClass.simpleName}")
            store.status("collection", "Collection unavailable · retrying")
            Result.retry()
        }
    } }
    companion object {
        private val registration = Mutex()
        fun schedule(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork("watch-collection", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WatchCollectionWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
}

class OrbitWatch : DiagnosticApplication() {
    override fun onCreate() {
        super.onCreate()
        WatchSyncWorker.periodic(this)
        WatchCollectionWorker.schedule(this)
    }
}

class WatchBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        WatchCollectionWorker.schedule(context)
        WatchSyncWorker.schedule(context, reconnect = true)
    }
}
