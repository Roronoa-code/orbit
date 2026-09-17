package com.mani.orbit

import android.content.Context
import android.util.Log
import androidx.work.*
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.mani.orbit.sync.HealthContext
import com.mani.orbit.sync.HealthContextWire
import com.mani.orbit.sync.RecoveryDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Read only the relevant committed records; do not scan a year of pulse data or workout traces. */
internal fun phoneHealthContext(file: File, today: LocalDate = LocalDate.now(), now: Long = System.currentTimeMillis()): HealthContext {
    val zone = ZoneId.systemDefault()
    return HealthRecordStore(file).use { store ->
        store.beginRead()
        try {
            val importedAt = store.metadata().optLong("lastSync", 0)
            val rows = JSONArray()
            val first = today.minusDays(6)
            store.window(first.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), today.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()).use { cursor ->
                while (cursor.moveToNext()) {
                    val row = JSONObject(cursor.getString(0))
                    if (row.getString("type") in setOf("sleep", "energyScore")) rows.put(store.hydrate(row))
                }
            }
            val snapshot = NativeHealthProjection.project(JSONObject().put("schema", 1).put("rows", rows), today)
            HealthContext(zone.id, now, importedAt, if (importedAt == 0L) emptyList() else snapshot.days.values
                .filter { it.date in first..today && (it.nights.isNotEmpty() || it.energyScore != null) }.sortedByDescending { it.date }
                .map { day -> RecoveryDay(day.date, day.nights.minOfOrNull { it.start }, day.nights.maxOfOrNull { it.end }, day.nights.size,
                    SleepTimeline.merge(day.nights).groupBy { it.stage }.mapValues { (_, intervals) -> intervals.sumOf { it.end - it.start } },
                    day.sleepScore, day.energyScore) })
        } finally { store.endRead() }
    }
}

class PhoneHealthContextWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = publishing.withLock { withContext(Dispatchers.IO) {
        val profilePublished = try {
            val profile = NativeProfile.read(AppPreferences(applicationContext).read("orbit-profile-v1"))
            val measurementProfile = com.mani.orbit.sync.MeasurementProfile(
                profile.getString("birthDate").takeIf { it.isNotEmpty() }?.let(java.time.LocalDate::parse),
                profile.optString("sex").takeIf { it in setOf("female", "male") },
                profile.optDouble("heightCm").takeIf { it.isFinite() }, profile.optDouble("weightKg").takeIf { it.isFinite() })
            Tasks.await(Wearable.getDataClient(applicationContext).putDataItem(PutDataRequest.create(com.mani.orbit.sync.MeasurementProfileWire.PATH)
                .setData(com.mani.orbit.sync.MeasurementProfileWire.encode(measurementProfile)).setUrgent()), 15, TimeUnit.SECONDS)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.w("OrbitSync", "Measurement profile remains on phone: ${error.javaClass.simpleName}")
            false
        }
        try {
            val context = phoneHealthContext(applicationContext.getDatabasePath("samsung-health.db"))
            val request = PutDataRequest.create(HealthContextWire.PATH).setData(HealthContextWire.encode(context))
            if (inputData.getBoolean("urgent", false)) request.setUrgent()
            Tasks.await(Wearable.getDataClient(applicationContext).putDataItem(request), 15, TimeUnit.SECONDS)
            if (profilePublished) Result.success() else Result.retry()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.w("OrbitSync", "Health context remains on phone: ${error.javaClass.simpleName}")
            Result.retry()
        }
    } }
    companion object {
        private val publishing = Mutex()
        // Coalesce refreshes; each retry reads the latest committed state. Data Layer buffers offline.
        fun schedule(context: Context, urgent: Boolean = false) = WorkManager.getInstance(context).enqueueUniqueWork("phone-health-context",
            if (urgent) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<PhoneHealthContextWorker>()
                .setInputData(workDataOf("urgent" to urgent)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        fun periodic(context: Context) = WorkManager.getInstance(context).enqueueUniquePeriodicWork("phone-health-context-recovery",
            ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<PhoneHealthContextWorker>(15, TimeUnit.MINUTES).build())
    }
}
