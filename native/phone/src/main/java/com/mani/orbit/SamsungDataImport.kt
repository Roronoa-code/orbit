package com.mani.orbit

import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.AggregateOperation
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.*
import com.samsung.android.sdk.health.data.request.DataType.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import java.time.*

/** Read-only SDK import into the same staged originals used by native charts and workout history. */
internal object SamsungDataImport {
    // One staging table is shared by every Activity instance and background caller.
    val importLock = Mutex()
    val types = linkedMapOf<String, DataType>("steps" to DataTypes.STEPS, "activity" to DataTypes.ACTIVITY_SUMMARY,
        "floors" to DataTypes.FLOORS_CLIMBED, "heart" to DataTypes.HEART_RATE, "sleep" to DataTypes.SLEEP,
        "nutrition" to DataTypes.NUTRITION, "water" to DataTypes.WATER_INTAKE, "body" to DataTypes.BODY_COMPOSITION,
        "oxygen" to DataTypes.BLOOD_OXYGEN, "exercise" to DataTypes.EXERCISE, "energyScore" to DataTypes.ENERGY_SCORE,
        "route" to DataTypes.EXERCISE_LOCATION)
    val permissions = types.mapValues { Permission.of(it.value, AccessType.READ) }
    fun hasReadingsPermission(granted: Set<Permission>) = permissions.any { (key, permission) -> key != "route" && permission in granted }
    private fun kinds(keys: Collection<String>) = keys.flatMap { when (it) {
        "body" -> listOf("weight", "fat", "lean", "muscle", "height")
        "activity" -> listOf("distance", "distanceDay", "energy", "energyDay", "totalEnergy", "totalEnergyDay")
        "steps", "floors" -> listOf(it, "${it}Day")
        else -> listOf(it)
    } }

    suspend fun sync(sdk: HealthDataStore, store: HealthRecordStore, granted: Set<Permission>, recent: Boolean,
                     progress: (Int) -> Unit) {
        val allowed = permissions.filterValues(granted::contains).keys
        require(hasReadingsPermission(granted)) { "Allow Samsung Health read access" }
        val meta = store.metadata()
        // Switching transport must replace the entire granted history to avoid duplicate old bridge IDs.
        val from = if (recent && canRefreshRecent(meta, kinds(allowed), System.currentTimeMillis()))
            LocalDate.now().minusDays(2).atStartOfDay(ZoneId.systemDefault()).toInstant() else Instant.EPOCH
        val until = Instant.now()
        var count = 0
        var skipped = 0
        store.beginImport()
        for (key in allowed) {
            currentCoroutineContext().ensureActive()
            when (key) {
                // Permission-only type: route data lives inside Exercise sessions. Its completion marker
                // forces a full re-read when route access is granted after an earlier route-less import.
                "route" -> Unit
                "steps" -> count += aggregate(sdk, store, "stepsDay", StepsType.TOTAL, from, until)
                "floors" -> count += aggregate(sdk, store, "floorsDay", FloorsClimbedType.TOTAL, from, until)
                "activity" -> {
                    count += aggregate(sdk, store, "distanceDay", ActivitySummaryType.TOTAL_DISTANCE, from, until)
                    count += aggregate(sdk, store, "energyDay", ActivitySummaryType.TOTAL_ACTIVE_CALORIES_BURNED, from, until)
                    count += aggregate(sdk, store, "totalEnergyDay", ActivitySummaryType.TOTAL_CALORIES_BURNED, from, until)
                }
                else -> {
                    var token: String? = null
                    val seen = HashSet<String>()
                    do {
                        currentCoroutineContext().ensureActive()
                        val request = request(key, from, until, token)
                        val page = withTimeout(45_000) { sdk.readData(request) }
                        // Keep only one decoded source record alive, even when a page contains long routes/series.
                        for (point in page.dataList) {
                            currentCoroutineContext().ensureActive()
                            // One record Orbit cannot read is that record's problem, not the import's.
                            // Letting it throw stopped every later type: a single odd heart record kept
                            // the owner's sleep out of the app for days. It is skipped, and said so.
                            val rows = try { SamsungRecordCodec.encode(key, point) }
                            catch (unreadable: IllegalArgumentException) {
                                skipped++
                                android.util.Log.w("OrbitImport", "Skipped an unreadable Samsung $key record", unreadable)
                                continue
                            }
                            store.stage(JSONArray(rows)); count = Math.addExact(count, rows.size)
                        }
                        progress(count)
                        token = page.pageToken?.takeIf { it.isNotEmpty() }
                        require(token == null || seen.add(token)) { "Repeated Samsung page token" }
                    } while (token != null)
                }
            }
            progress(count)
        }
        // Recheck permission before exposing a complete import. A revoked or failed page stays staged.
        val stillGranted = withTimeout(12_000) { sdk.getGrantedPermissions(permissions.values.toSet()) }
        require(allowed.all { permissions.getValue(it) in stillGranted }) { "Samsung Health access changed" }
        currentCoroutineContext().ensureActive()
        if (skipped > 0) android.util.Log.w("OrbitImport", "Samsung import finished with $skipped unreadable records skipped")
        store.finishImport(kinds(allowed), from.toEpochMilli(), until.toEpochMilli(), true, "samsung_sdk")
    }

    internal fun canRefreshRecent(meta: JSONObject, kinds: List<String>, now: Long): Boolean {
        val complete = meta.optJSONArray("completeTypes") ?: return false
        val imported = (0 until complete.length()).map { complete.getString(it) }.toSet()
        val age = now - meta.optLong("lastFullSync", 0)
        // Daily reconciliation also picks up edits/deletions of old records, not just recent additions.
        return meta.optString("transport") == "samsung_sdk" && age in 0 until 86_400_000 && imported.containsAll(kinds)
    }

    internal fun request(key: String, from: Instant, until: Instant, token: String?): ReadDataRequest<HealthDataPoint> {
        if (key == "energyScore") return DataTypes.ENERGY_SCORE.readDataRequestBuilder.apply {
            val zone = ZoneId.systemDefault()
            setLocalDateFilter(LocalDateFilter.of(from.atZone(zone).toLocalDate(), until.atZone(zone).toLocalDate().plusDays(1)))
            setPageSize(100); if (token != null) setPageToken(token)
        }.build()
        val builder = when (key) {
            "heart" -> DataTypes.HEART_RATE.readDataRequestBuilder
            "sleep" -> DataTypes.SLEEP.readDataRequestBuilder
            "nutrition" -> DataTypes.NUTRITION.readDataRequestBuilder
            "water" -> DataTypes.WATER_INTAKE.readDataRequestBuilder
            "body" -> DataTypes.BODY_COMPOSITION.readDataRequestBuilder
            "oxygen" -> DataTypes.BLOOD_OXYGEN.readDataRequestBuilder
            "exercise" -> DataTypes.EXERCISE.readDataRequestBuilder
            else -> error("Unknown Samsung type")
        }
        return builder.setInstantTimeFilter(InstantTimeFilter.of(from, until)).setPageSize(100).apply {
            if (token != null) setPageToken(token)
        }.build()
    }

    private suspend fun <T : Number> aggregate(sdk: HealthDataStore, store: HealthRecordStore, kind: String,
        operation: AggregateOperation<T, AggregateRequest.LocalTimeBuilder<T>>, from: Instant, until: Instant): Int {
        val zone = ZoneId.systemDefault()
        var token: String? = null
        val seen = HashSet<String>()
        var count = 0
        do {
            currentCoroutineContext().ensureActive()
            val request = operation.requestBuilder.setLocalTimeFilterWithGroup(
                LocalTimeFilter.of(maxOf(LocalDate.of(1970, 1, 1), from.atZone(zone).toLocalDate()).atStartOfDay(), until.atZone(zone).toLocalDateTime()),
                LocalTimeGroup.of(LocalTimeGroupUnit.DAILY, 1)).setPageSize(100).apply { if (token != null) setPageToken(token) }.build()
            val page = withTimeout(45_000) { sdk.aggregateData(request) }
            val rows = JSONArray()
            for (entry in page.dataList) entry.value?.let { n ->
                val value = n.toDouble(); require(value.isFinite() && value >= 0)
                val day = entry.getStartLocalDateTime().toLocalDate()
                rows.put(JSONObject().put("type", kind).put("id", "sdk:$kind:$day").put("date", day.toString())
                    .put("source", "com.sec.android.app.shealth").put("transport", "samsung_sdk")
                    .put("start", maxOf(0, day.atStartOfDay(zone).toInstant().toEpochMilli()))
                    .put("end", minOf(day.plusDays(1).atStartOfDay(zone).toInstant(), until).toEpochMilli()).put("value", value))
            }
            store.stage(rows); count += rows.length()
            token = page.pageToken?.takeIf { it.isNotEmpty() }
            require(token == null || seen.add(token)) { "Repeated Samsung aggregate page token" }
        } while (token != null)
        return count
    }

    suspend fun hours(sdk: HealthDataStore, date: LocalDate): JSONArray {
        val zone = ZoneId.systemDefault()
        val end = minOf(date.plusDays(1).atStartOfDay(), LocalDateTime.now())
        if (end <= date.atStartOfDay()) return JSONArray()
        val rows = JSONArray(); val seen = HashSet<String>(); var token: String? = null
        do {
            val request = StepsType.TOTAL.requestBuilder.setLocalTimeFilterWithGroup(
                LocalTimeFilter.of(date.atStartOfDay(), end), LocalTimeGroup.of(LocalTimeGroupUnit.HOURLY, 1))
                .setPageSize(100).apply { if (token != null) setPageToken(token!!) }.build()
            val page = withTimeout(12_000) { sdk.aggregateData(request) }
            for (entry in page.dataList) entry.value?.let { value ->
                require(value >= 0)
                rows.put(JSONObject().put("start", entry.startTime?.toEpochMilli() ?: entry.getStartLocalDateTime().atZone(zone).toInstant().toEpochMilli())
                    .put("end", entry.endTime?.toEpochMilli() ?: entry.getEndLocalDateTime().atZone(zone).toInstant().toEpochMilli()).put("value", value))
            }
            token = page.pageToken?.takeIf { it.isNotEmpty() }
            require(token == null || seen.add(token!!)) { "Repeated Samsung hour token" }
        } while (token != null)
        return rows
    }
}
