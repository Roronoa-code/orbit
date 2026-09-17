package com.mani.orbit.wear

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class GlanceReading(val value: Double?, val at: Long?, val expires: Long, val explanation: String)

/** Shortcuts never present yesterday's steps or an old pulse as current. No sensor starts here. */
internal fun glanceReading(row: JSONObject?, metric: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): GlanceReading {
    require(metric in setOf("steps", "heart"))
    val at = row?.optLong("end")
    val midnight = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val valid = row != null && row.optString("metric") == metric && !row.isNull("value") &&
        row.optString("quality") == "valid" && !row.optBoolean("timeUncertain") && at!! <= now &&
        row.optDouble("value").isFinite() && row.optDouble("value") >= 0 && (metric != "heart" || row.optDouble("value") > 0) &&
        (metric != "steps" || row.optString("semantics") == "daily" && Instant.ofEpochMilli(at).atZone(zone).toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
    val expires = if (metric == "steps") midnight else (at ?: 0) + 5 * 60_000
    return GlanceReading(row?.optDouble("value")?.takeIf { valid && expires > now }, at?.takeIf { valid }, expires,
        when {
            valid && expires <= now -> "Open Orbit for a new pulse reading"
            valid -> "Recorded at ${Instant.ofEpochMilli(at!!).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", Locale.UK))}"
            else -> readingAge(row, now)
        })
}

internal object WatchGlanceUpdates {
    @Synchronized fun request(context: Context) {
        val now = SystemClock.elapsedRealtime()
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        val prefs = context.getSharedPreferences("watch-glance-updates", Context.MODE_PRIVATE)
        try {
            if (claimGlanceRefresh(prefs, "tile", 30_000, boot, now))
                TileService.getUpdater(context).requestUpdate(WatchTodayTile::class.java)
        } catch (error: Exception) { Log.w("OrbitWatch", "Tile refresh deferred to system schedule: ${error.javaClass.simpleName}") }
        try {
            if (!claimGlanceRefresh(prefs, "complications", 300_000, boot, now)) return
            for (type in listOf(WatchStepsComplication::class.java, WatchHeartComplication::class.java))
                ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, type)).requestUpdateAll()
        } catch (error: Exception) { Log.w("OrbitWatch", "Complication refresh deferred to system schedule: ${error.javaClass.simpleName}") }
    }
}

/** Reserve before dispatch, including failed requests. Restarts cannot reset the host's budget. */
internal fun claimGlanceRefresh(prefs: SharedPreferences, key: String, interval: Long, boot: Int, now: Long): Boolean {
    require(interval > 0 && now >= 0)
    if (boot < 0) return false // The system can still pull fresh data when our boot clock is unavailable.
    val last = prefs.getLong("$key-at", -1)
    if (prefs.getInt("$key-boot", -1) == boot && last >= 0 && now >= last && now - last < interval) return false
    check(prefs.edit().putInt("$key-boot", boot).putLong("$key-at", now).commit()) { "Glance refresh budget could not be saved" }
    return true
}
