package com.mani.orbit

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.floor
import com.mani.orbit.sync.WatchWorkout

internal val WorkoutKinds = listOf("Walking", "Running", "Cycling", "Strength")
data class WorkoutPoint(val lat: Double, val lon: Double, val elapsed: Long, val altitude: Double?, val speed: Double?, val breakBefore: Boolean)
data class WorkoutLap(val start: Long, val end: Long, val distance: Double?)
data class WorkoutRecord(
    val id: String, val kind: String, val start: Long, val end: Long?, val elapsed: Long, val total: Long,
    val imported: Boolean = false, val target: Long = 0, val weight: Double? = null,
    val tracking: Boolean = false, val status: String = "off", val paused: Boolean = false,
    val distance: Double? = null, val maxSpeed: Double? = null, val altitudeLow: Double? = null, val altitudeHigh: Double? = null,
    val points: List<WorkoutPoint> = emptyList(), val summary: Map<String, Double> = emptyMap(),
    val laps: List<WorkoutLap> = emptyList(), val title: String = "", val notes: String = "", val hasUnsharedRoute: Boolean = false,
    val watch: WatchWorkout? = null, val watchInstallation: String? = null, val watchChange: Long = 0,
    val reportedDuration: Boolean = false,
    val detailsDeferred: Boolean = false, val detailRevision: String = "",
) {
    fun date(zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
    val averageSpeed get() = distance?.takeIf { elapsed > 0 }?.div(elapsed / 1000.0)
    val energy get() = if (watch != null) watch.energy else if (imported) summary["energy"] else weight?.let {
        // ponytail: existing broad activity MET estimate; measured effort is needed for individual accuracy.
        (when (kind) { "Running" -> 7.5; "Cycling" -> 4.0; else -> 3.5 }) * it * 3.5 / 200 * elapsed / 60000
    }
}

/** Projection only: original Samsung records and the existing session store remain authoritative. */
internal object WorkoutData {
    private fun number(value: Any): Double = (value as? Number)?.toDouble()?.also { require(it.isFinite()) }
        ?: error("Expected a workout number")
    private fun whole(value: Any): Long = number(value).also { require(it >= 0 && it <= 9007199254740991.0 && floor(it) == it) }.toLong()
    private fun JSONObject.amount(key: String, min: Double = 0.0, max: Double = Double.MAX_VALUE): Double? =
        if (!has(key) || isNull(key)) null else number(get(key)).also { require(it in min..max) }
    private fun JSONObject.text(key: String, limit: Int = 10000): String = if (!has(key) || isNull(key)) "" else
        (get(key) as? String ?: error("Expected text")).also { require(it.length <= limit) }
    private fun JSONObject.flag(key: String): Boolean = if (!has(key)) false else get(key) as? Boolean ?: error("Expected boolean")

    fun imported(rows: JSONArray?): List<WorkoutRecord> = if (rows == null) emptyList() else List(rows.length()) { i ->
        val row = rows.getJSONObject(i)
        require(row.get("source") == "com.sec.android.app.shealth" && row.get("type") == "exercise")
        val id = row.text("id", 512).also { require(it.isNotBlank()) }
        val kind = row.text("kind", 120).also { require(it.isNotBlank()) }
        val start = whole(row.get("start")); val end = whole(row.get("end")); require(end > start)
        val summary = row.optJSONObject("summary")?.let { data -> data.keys().asSequence().associateWith { key ->
            number(data.get(key)).also { require(it >= 0) }
        } }.orEmpty()
        require(!row.has("summary") || row.isNull("summary") || row.get("summary") is JSONObject)
        fun intervals(key: String): List<WorkoutLap> {
            if (!row.has(key)) return emptyList()
            val values = row.getJSONArray(key)
            return List(values.length()) { j ->
                val lap = values.getJSONObject(j); val a = whole(lap.get("start")); val b = whole(lap.get("end"))
                require(a >= start && b >= a && b <= end)
                WorkoutLap(a, b, lap.amount("distanceM"))
            }
        }
        val laps = intervals("laps"); intervals("segments")
        val counts = row.optJSONObject("_counts")
        val deferred = counts != null && counts.keys().asSequence().any { counts.getInt(it) > 0 }
        val duration = if (row.has("durationMs")) whole(row.get("durationMs")).also { require(it <= end - start) } else end - start
        var previous = start
        val route = row.optJSONArray("route")?.let { values -> List(values.length()) { j ->
            val p = values.getJSONObject(j); val at = whole(p.get("at")); require(at >= previous && at <= end)
            val gap = j > 0 && at - previous > 30_000; previous = at
            WorkoutPoint(requireNotNull(p.amount("lat", -90.0, 90.0)), requireNotNull(p.amount("lon", -180.0, 180.0)), at - start,
                p.amount("altitude", -12000.0, 100000.0), null, gap)
        } }.orEmpty()
        require(!row.has("route") || row.get("route") is JSONArray)
        WorkoutRecord("samsung:$id", kind, start, end, duration, end - start, imported = true,
            distance = summary["distance"], summary = summary, laps = laps, points = route,
            reportedDuration = row.has("durationMs"), maxSpeed = summary["maxSpeed"],
            detailsDeferred = deferred, detailRevision = row.text("_revision", 64),
            title = row.text("title"), notes = row.text("notes"), hasUnsharedRoute = row.flag("hasRoute") && route.isEmpty() && (counts?.optInt("route") ?: 0) == 0)
    }.also { require(it.map(WorkoutRecord::id).distinct().size == it.size) }.sortedByDescending { it.start }

    fun local(row: JSONObject, active: Boolean = false, elapsed: Long? = null, total: Long? = null): WorkoutRecord {
        val kind = row.text("kind").also { require(it in WorkoutKinds) }
        val start = whole(row.get("startedAt"))
        // The wall clock may move backwards; saved elapsed/total time comes from the backend's monotonic clock.
        val end = if (active) null else whole(row.get("endedAt"))
        val activeTime = elapsed ?: whole(row.get("elapsed"))
        val totalTime = total ?: if (row.has("totalMs")) whole(row.get("totalMs")) else maxOf(activeTime, (end ?: start) - start)
        require(activeTime >= 0 && totalTime >= activeTime)
        val target = if (row.has("targetMs") && !row.isNull("targetMs")) whole(row.get("targetMs")) else 0
        require(target == 0L || target in 60000L..86400000L)
        val weight = row.amount("weightKg", 0.0, 350.0)?.takeIf { it != 0.0 }?.also { require(it >= 20) }
        val tracking = row.flag("trackLocation"); require(!tracking || kind != "Strength")
        val metrics = row.optJSONObject("metrics")
        require(!tracking || metrics != null)
        val status = metrics?.getString("state") ?: "off"
        require(status in listOf("off", "permission", "searching", "tracking", "paused", "unavailable", "error", "finished"))
        var previous = -1L
        val points = metrics?.getJSONArray("points")?.let { values ->
            require(values.length() <= 4096)
            List(values.length()) { i ->
                val p = values.getJSONObject(i); val at = whole(p.get("elapsedMs")); require(at >= previous); previous = at
                WorkoutPoint(requireNotNull(p.amount("lat", -90.0, 90.0)), requireNotNull(p.amount("lon", -180.0, 180.0)), at,
                    p.amount("altitudeM", -12000.0, 100000.0), p.amount("speedMps", 0.0, 100.0), p.get("breakBefore") as? Boolean ?: error("Invalid route gap"))
            }
        }.orEmpty()
        val distance = metrics?.amount("distanceM"); val maxSpeed = metrics?.amount("maxSpeedMps", 0.0, 100.0)
        return WorkoutRecord("orbit:$start", kind, start, end, activeTime, totalTime, target = target, weight = weight,
            tracking = tracking, status = status, paused = active && row.isNull("resumedAt"),
            distance = distance?.takeIf { points.isNotEmpty() }, maxSpeed = maxSpeed?.takeIf { points.isNotEmpty() },
            altitudeLow = metrics?.amount("altitudeMinM", -12000.0, 100000.0),
            altitudeHigh = metrics?.amount("altitudeMaxM", -12000.0, 100000.0), points = points)
    }
    fun history(store: JSONObject): List<WorkoutRecord> {
        val rows = store.getJSONArray("history")
        return List(rows.length()) { local(rows.getJSONObject(it)) }
            .also { require(it.map(WorkoutRecord::id).distinct().size == it.size) }.sortedByDescending { it.start }
    }
}

internal fun workoutClock(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return (if (seconds >= 3600) "${seconds / 3600}:" else "") + "%02d:%02d".format(Locale.UK, seconds / 60 % 60, seconds % 60)
}
internal fun workoutDuration(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000; val m = s / 60
    return when { m >= 60 -> "${m / 60}h ${m % 60}m"; m > 0 -> "${m}m" + if (s % 60 > 0) " ${s % 60}s" else ""; else -> "${s}s" }
}
internal fun workoutNumber(value: Double?, digits: Int = 0): String = value?.let { String.format(Locale.UK, "%,.${digits}f", it) } ?: "—"
internal fun workoutPace(speed: Double?): String = speed?.takeIf { it >= .3 }?.let { workoutClock((1000000 / it).toLong()) } ?: "—"
internal fun workoutWeek(date: LocalDate) = date.minusDays(date.dayOfWeek.value - 1L)
