package com.mani.orbit

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil

internal data class SleepDay(val date: LocalDate, val nights: List<SleepNight>, val firstDate: LocalDate = date) {
    val segments = SleepTimeline.merge(nights)
    val blocks = SleepTimeline.blocks(segments)
    val totals = segments.groupBy { it.stage }.mapValues { (_, rows) -> rows.sumOf { (it.end - it.start) / 60000.0 } }
    val lanes = listOf("awake", "rem", "light", "deep") + listOf("sleeping", "unknown").filter { (totals[it] ?: 0.0) > 0 }
    val asleep: Double? = if (listOf("awake", "light", "deep", "rem", "sleeping").any { (totals[it] ?: 0.0) > 0 })
        listOf("light", "deep", "rem", "sleeping").sumOf { totals[it] ?: 0.0 } else null
    val start get() = segments.firstOrNull()?.start
    val end get() = segments.lastOrNull()?.end
    val axisStart: Long? = start?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli() }
    val axisEnd: Long? = end?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).plusHours(1).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli() }
    val ticks: List<Long> = if (axisStart == null || axisEnd == null) emptyList() else {
        val step = maxOf(1, ceil((axisEnd - axisStart) / 3600000.0 / 4).toInt()) * 3600000L
        generateSequence(axisStart) { it + step }.takeWhile { it <= axisEnd }.toList()
    }
    fun locate(at: Long): SleepInterval? = if (segments.isEmpty()) null else {
        val time = at.coerceIn(segments.first().start, segments.last().end - 1)
        segments.firstOrNull { time >= it.start && time < it.end }
    }
    fun fraction(at: Long): Float = if (axisStart == null || axisEnd == null) 0f else ((at - axisStart).toDouble() / (axisEnd - axisStart)).toFloat()
    fun at(fraction: Float): Long? = if (start == null || end == null || axisStart == null || axisEnd == null) null else
        (axisStart + ((axisEnd - axisStart) * fraction.coerceIn(0f, 1f)).toLong()).coerceIn(start!!, end!! - 1)

    companion object {
        /** Read the existing committed originals, off the UI thread; no secondary health store. */
        fun read(database: File, date: LocalDate): SleepDay {
            require(date >= LocalDate.of(1970, 1, 1) && date <= LocalDate.now())
            val zone = ZoneId.systemDefault()
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            return HealthRecordStore(database).use { store ->
                store.beginRead()
                try {
                val rows = JSONArray()
                store.window(start, end).use { cursor -> while (cursor.moveToNext()) {
                    val row = JSONObject(cursor.getString(0))
                    if (row.getString("type") == "sleep") rows.put(store.hydrate(row))
                } }
                val day = NativeHealthProjection.read(JSONObject().put("schema", 1).put("rows", rows), date)
                val first = store.metadata().optLong("firstRecord", start)
                require(first >= 0)
                SleepDay(date, day.nights, minOf(date, Instant.ofEpochMilli(first).atZone(zone).toLocalDate()).coerceAtLeast(LocalDate.of(1970, 1, 1)))
                } finally { store.endRead() }
            }
        }
    }
}

internal fun sleepStageLabel(stage: String) = when (stage) {
    "awake" -> "Awake"; "rem" -> "REM"; "light" -> "Light"; "deep" -> "Deep"
    "sleeping" -> "Sleep"; "unrecorded" -> "Not recorded"; else -> "Unknown"
}
