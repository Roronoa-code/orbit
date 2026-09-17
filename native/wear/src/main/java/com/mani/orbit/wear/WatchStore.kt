package com.mani.orbit.wear

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.mani.orbit.sync.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs

/** Clock anchors keep a boot's sensor timestamps stable across batched and duplicate callbacks. */
data class WatchClock(val boot: String, val wall: Long, val elapsed: Long, val uncertainty: Long, val changed: Boolean, val bootCount: Int? = null) {
    fun wallAt(elapsedMs: Long) = Math.addExact(wall, Math.subtractExact(elapsedMs, elapsed))
}

class WatchStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("watch-collection", Context.MODE_PRIVATE)
    val installation: String get() = synchronized(lock) {
        prefs.getString("installation", null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString("installation", it).commit()) { "Installation could not be saved" }
        }
    }
    fun journal() = ReadingJournal(context.getDatabasePath("watch-readings.db"))

    fun clock(): WatchClock = synchronized(lock) {
        val before = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val after = SystemClock.elapsedRealtime()
        check(after - before in 0..58_000) { "Clock capture was interrupted" }
        val elapsed = before + (after - before) / 2
        val count = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        check(count >= 0) { "Boot clock unavailable" }
        val boot = UUID.nameUUIDFromBytes("$installation:boot:$count".toByteArray()).toString()
        val sameBoot = prefs.getString("boot", null) == boot
        val anchorWall = prefs.getLong("anchorWall", wall)
        val anchorElapsed = prefs.getLong("anchorElapsed", elapsed)
        val shifted = sameBoot && abs(wall - (anchorWall + elapsed - anchorElapsed)) > 2000
        if (!sameBoot || shifted) {
            check(prefs.edit().putString("boot", boot).putLong("anchorWall", wall).putLong("anchorElapsed", elapsed)
                .putLong("uncertainty", after - before + 2000).putBoolean("clockChanged", shifted).commit()) { "Clock could not be saved" }
        }
        WatchClock(boot, prefs.getLong("anchorWall", wall), prefs.getLong("anchorElapsed", elapsed),
            prefs.getLong("uncertainty", 1), prefs.getBoolean("clockChanged", false), count)
    }

    fun sample(clock: WatchClock, metric: String, startElapsed: Long, endElapsed: Long, value: Double?, quality: String,
               semantics: String = "instant", source: String = "health_services"): WatchReading {
        val nowElapsed = SystemClock.elapsedRealtime()
        require(endElapsed >= 0 && startElapsed <= endElapsed && endElapsed <= nowElapsed + 1000)
        val start = clock.wallAt(startElapsed)
        val end = clock.wallAt(endElapsed)
        val id = UUID.nameUUIDFromBytes("$installation:${clock.boot}:$source:$metric:$semantics:$startElapsed:$endElapsed".toByteArray()).toString()
        return WatchReading(id, 1, start, end, ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(start)).totalSeconds,
            metric, value, WatchReading.UNITS.getValue(metric), quality, semantics, clock.boot, endElapsed, source,
            clock.wallAt(nowElapsed), nowElapsed, clock.uncertainty, clock.changed && startElapsed < clock.elapsed, clock.bootCount)
    }

    fun save(readings: List<WatchReading>): Int {
        if (readings.isEmpty()) return 0
        check(context.filesDir.usableSpace >= 16 * 1024 * 1024) { "Watch storage is nearly full" }
        var count = 0
        journal().use { journal ->
            readings.associateBy { it.id }.values.toList().chunked(ReadingWire.MAX_READINGS).forEach {
                if (journal.capture(installation, it) != null) count += it.size
            }
        }
        if (count > 0 && readings.any { it.metric == "steps" || it.metric == "heart" }) WatchGlanceUpdates.request(context)
        return count
    }

    fun status(key: String, value: String) {
        require(key in setOf("collection", "sync", "measure", "supported", "phone"))
        check(prefs.edit().putString(key, value.take(240)).putLong("${key}At", System.currentTimeMillis()).commit()) { "Status could not be saved" }
    }
    fun status(key: String) = prefs.getString(key, "") ?: ""
    fun enabled() = prefs.getBoolean("enabled", false)
    fun enable(value: Boolean) { check(prefs.edit().putBoolean("enabled", value).commit()) }
    companion object { private val lock = Any() }
}
