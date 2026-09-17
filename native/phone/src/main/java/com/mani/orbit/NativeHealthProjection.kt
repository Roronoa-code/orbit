package com.mani.orbit

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class HealthMeal(val at: Long, val name: String, val calories: Double?, val protein: Double?, val carbs: Double?, val fat: Double?)
data class SleepInterval(val start: Long, val end: Long, val stage: String)
data class SleepNight(val id: String, val start: Long, val end: Long, val intervals: List<SleepInterval>,
    val recordedWindowMs: Long? = null, val score: Double? = null)
internal data class NativeHealthSnapshot(val day: HealthDay, val days: Map<LocalDate, HealthDay>, val workouts: List<WorkoutRecord> = emptyList())

/** One bounded pass over Samsung's original projection; absent readings remain absent. */
internal object NativeHealthProjection {
    private const val SOURCE = "com.sec.android.app.shealth"
    private val stages = listOf("unknown", "awake", "sleeping", "awake", "light", "deep", "rem", "awake")
    private val asleepStages = setOf("sleeping", "light", "deep", "rem")
    fun read(data: JSONObject, date: LocalDate): HealthDay = project(data, date).day

    private class Day(val date: LocalDate) {
        val values = mutableMapOf<String, Double>()
        val hearts = mutableListOf<Reading>()
        val heartHours = mutableListOf<Reading>()
        var count = 0L
        var sum = 0.0
        var low: Double? = null
        var high: Double? = null
        val nights = mutableListOf<SleepNight>()
        val meals = mutableListOf<HealthMeal>()
        var oxygenAt = -1L
        var energyScoreAt = -1L
    }
    private fun number(value: Any): Double {
        require(value is Number) { "Expected a numeric reading" }
        return value.toDouble().also { require(it.isFinite() && it >= 0) { "Invalid reading" } }
    }
    private fun whole(value: Any): Long = number(value).also {
        require(it <= 9007199254740991.0 && it % 1.0 == 0.0) { "Expected an exact non-negative integer" }
    }.toLong()
    private fun JSONObject.amount(key: String): Double? = if (!has(key) || isNull(key)) null else number(get(key))
    private fun parsedDate(value: String): LocalDate = LocalDate.parse(value).also { require(it >= LocalDate.of(1970, 1, 1)) }

    fun project(data: JSONObject, selected: LocalDate): NativeHealthSnapshot {
        require(whole(data.get("schema")) == 1L)
        val zone = ZoneId.systemDefault()
        fun date(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val days = sortedMapOf<LocalDate, Day>()
        fun day(value: LocalDate) = days.getOrPut(value) { Day(value) }
        val weights = mutableListOf<Reading>()
        val fat = mutableListOf<Reading>()
        val lean = mutableListOf<Reading>()
        val muscle = mutableListOf<Reading>()
        val seen = HashSet<String>()
        val rows = data.getJSONArray("rows")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            require(row.get("source") == SOURCE)
            val type = row.getString("type")
            if (type == "heartHour") {
                val rowDate = parsedDate(row.getString("date"))
                val hour = whole(row.get("hour")).also { require(it <= 23) }.toInt()
                val count = whole(row.get("count"))
                val sum = requireNotNull(row.amount("sum"))
                val low = requireNotNull(row.amount("low")); val high = requireNotNull(row.amount("high"))
                val latest = requireNotNull(row.amount("latest")); val at = whole(row.get("latestTime"))
                require(count > 0 && date(at) == rowDate && Instant.ofEpochMilli(at).atZone(zone).hour == hour)
                require(low > 0 && high >= low && latest in low..high && sum > 0)
                require(sum / count >= low - .00001 && sum / count <= high + .00001)
                require(seen.add("heart:$rowDate:$hour"))
                val d = day(rowDate)
                d.count = Math.addExact(d.count, count); d.sum += sum; require(d.sum.isFinite())
                d.low = minOf(d.low ?: low, low); d.high = maxOf(d.high ?: high, high)
                d.hearts += Reading(at, latest)
                d.heartHours += Reading(rowDate.atStartOfDay(zone).withHour(hour).toInstant().toEpochMilli(), sum / count)
                continue
            }
            val id = row.get("id").also { require(it is String) }.toString()
            val start = whole(row.get("start")); val end = whole(row.get("end"))
            require(id.isNotBlank() && start >= 0 && end >= start && seen.add("$type:$id"))
            val rowDate = if (type == "sleep") date(end) else
                row.optString("date").takeIf { it.isNotEmpty() }?.let(::parsedDate) ?: date(start)
            val d = day(rowDate)
            val value = row.amount("value")
            when (type) {
                "stepsDay", "distanceDay", "floorsDay", "energyDay", "totalEnergyDay" -> {
                    require(!d.values.containsKey(type)) { "Duplicate daily aggregate" }
                    d.values[type] = requireNotNull(value)
                }
                "weight", "fat", "lean", "muscle", "height" -> {
                    require(value != null && (type != "fat" || value <= 100))
                    if (rowDate <= selected) when (type) {
                        "weight" -> weights += Reading(start, value)
                        "fat" -> fat += Reading(start, value)
                        "lean" -> lean += Reading(start, value)
                        "muscle" -> muscle += Reading(start, value)
                    }
                }
                "energyScore" -> {
                    require(value != null && value <= 100)
                    if (start >= d.energyScoreAt) { d.values[type] = value; d.energyScoreAt = start }
                }
                "oxygen" -> {
                    require(value != null && value <= 100)
                    val low = row.amount("low") ?: value; val high = row.amount("high") ?: value
                    require(value in low..high && high <= 100)
                    d.values["oxygenLow"] = minOf(d.values["oxygenLow"] ?: low, low)
                    d.values["oxygenHigh"] = maxOf(d.values["oxygenHigh"] ?: high, high)
                    if (start >= d.oxygenAt) { d.values[type] = value; d.oxygenAt = start }
                }
                "water" -> d.values[type] = ((d.values[type] ?: 0.0) + requireNotNull(value)).also { require(it.isFinite()) }
                "nutrition" -> {
                    val name = row.optString("name").takeIf { it.isNotBlank() } ?: listOf("Meal", "Breakfast", "Lunch", "Dinner", "Snack").getOrElse(row.optInt("mealType")) { "Meal" }
                    d.meals += HealthMeal(start, name, row.amount("calories"), row.amount("protein"), row.amount("carbs"), row.amount("fat"))
                }
                "sleep" -> {
                    val intervals = mutableListOf<SleepInterval>()
                    val raw = row.getJSONArray("stages")
                    var cursor = start
                    for (j in 0 until raw.length()) {
                        val interval = raw.getJSONArray(j)
                        val a = whole(interval.get(0)); val b = whole(interval.get(1))
                        val kind = whole(interval.get(2)).also { require(it < stages.size) }.toInt()
                        require(a >= cursor && b > a && b <= end && kind in stages.indices)
                        if (a > cursor) intervals += SleepInterval(cursor, a, "unknown")
                        intervals += SleepInterval(a, b, stages[kind]); cursor = b
                    }
                    if (cursor < end) intervals += SleepInterval(cursor, end, "unknown")
                    val duration = if (row.has("recordedWindowMs") && !row.isNull("recordedWindowMs"))
                        whole(row.get("recordedWindowMs")).also { require(it <= end - start) } else null
                    val score = row.amount("sleepScore")?.also { require(it <= 100) }
                    d.nights += SleepNight(id, start, end, intervals, duration, score)
                }
                else -> error("Unsupported Samsung measurement: $type")
            }
        }
        val finished = days.mapValues { (_, d) ->
            val totals = SleepTimeline.totals(d.nights)
            val known = asleepStages.any(totals::containsKey)
            val complete = known && totals["sleeping"] == null && totals["unknown"] == null
            val latest = d.hearts.maxByOrNull { it.at }
            HealthDay(date = d.date, steps = d.values["stepsDay"], distance = d.values["distanceDay"], floors = d.values["floorsDay"],
                energy = d.values["energyDay"], heart = latest?.value, oxygen = d.values["oxygen"],
                oxygenLow = d.values["oxygenLow"], oxygenHigh = d.values["oxygenHigh"],
                nutrition = d.meals.mapNotNull { it.calories }.takeIf { it.isNotEmpty() }?.sum()?.also { require(it.isFinite()) },
                asleepMinutes = if (known) asleepStages.sumOf { totals[it] ?: 0.0 } else null,
                sleepScore = d.nights.filter { it.score != null }.maxByOrNull { it.end }?.score,
                energyScore = d.values["energyScore"], energyScoreAt = d.energyScoreAt.takeIf { it >= 0 },
                heartReadings = d.hearts.sortedBy { it.at }, heartCount = d.count, heartSum = d.sum,
                heartLow = d.low, heartHigh = d.high, heartAt = latest?.at, hourlyHeart = d.heartHours.sortedBy { it.at },
                nights = d.nights.sortedBy { it.start }, sleepStages = listOf("light", "deep", "rem", "awake").associateWith { totals[it] ?: if (complete) 0.0 else null },
                sleepIncomplete = totals["unknown"] != null, meals = d.meals.sortedBy { it.at }, water = d.values["water"])
        }
        val history = MeasurementHistory(weights.sortedBy { it.at }, fat.sortedBy { it.at }, muscle = muscle.sortedBy { it.at }, lean = lean.sortedBy { it.at })
        val latestWeight = history.weights.lastOrNull()
        val selectedDay = (finished[selected] ?: HealthDay(selected)).copy(weight = latestWeight?.value,
            weightDate = latestWeight?.let { date(it.at) }, measurements = history, hourlySteps = hours(data.optJSONArray("stepHours")))
        return NativeHealthSnapshot(selectedDay, finished, WorkoutData.imported(if (data.has("workouts")) data.getJSONArray("workouts") else null))
    }

    fun hours(rows: JSONArray?): List<Reading> = if (rows == null) emptyList() else List(rows.length()) { i ->
        val row = rows.getJSONObject(i)
        val at = whole(row.get("start")); val end = whole(row.get("end")); val value = number(row.get("value"))
        require(at >= 0 && end > at && value.isFinite() && value >= 0)
        Reading(at, value)
    }.sortedBy { it.at }.also { require(it.map(Reading::at).distinct().size == it.size) }
}
