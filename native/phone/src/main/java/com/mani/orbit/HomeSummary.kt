package com.mani.orbit

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

internal enum class HomeMetric(val title: String, val unit: String) {
    Steps("Steps", "steps"), Heart("Heart rate", "bpm"), Sleep("Sleep", "min"), Intake("Intake", "kcal");
    fun value(day: HealthDay): Double? = when (this) {
        Steps -> day.steps
        Heart -> if (day.heartCount > 0) day.heartSum / day.heartCount else null
        Sleep -> day.asleepMinutes
        Intake -> day.nutrition
    }
}
internal data class HomeFact(val label: String, val value: String, val unit: String = "")
internal data class HomePoint(val label: String, val value: Double?, val date: LocalDate? = null)
internal data class HomeComparison(val current: Double?, val previous: Double?, val currentDays: Int, val previousDays: Int) {
    val difference: Double? get() = current?.let { a -> previous?.let { b -> a - b } }
}
internal data class HomeSummary(
    val metric: HomeMetric, val period: Int, val date: LocalDate,
    val primary: String, val label: String, val caption: String,
    val facts: List<HomeFact>, val footer: List<HomeFact>, val movementHeading: String,
    val movement: List<HomePoint>, val week: List<HomePoint>, val comparison: HomeComparison,
) {
    companion object {
        fun from(state: HealthScreenState, metric: HomeMetric, period: Int, goal: Int?): HomeSummary {
            require(period in listOf(1, 7, 30))
            val selected = state.day
            fun day(at: LocalDate) = if (at == selected.date) selected else state.days[at] ?: HealthDay(at)
            fun window(count: Int, end: LocalDate = selected.date) = List(count) { day(end.minusDays((count - 1L) - it)) }
            val rows = window(period)
            val values = rows.map(metric::value)
            val meals = rows.flatMap { it.meals }
            val sum = { numbers: List<Double?> -> numbers.filterNotNull().takeIf { it.isNotEmpty() }?.sum() }
            val mean = { numbers: List<Double?> -> numbers.filterNotNull().takeIf { it.isNotEmpty() }?.average() }
            val count = rows.sumOf { it.heartCount }
            val heartMean = if (count > 0) rows.sumOf { it.heartSum } / count else null
            val primary = when (metric) {
                HomeMetric.Steps -> sum(values)
                HomeMetric.Heart -> if (period == 1) selected.heart else heartMean
                HomeMetric.Sleep -> mean(values)
                HomeMetric.Intake -> if (period == 30) mean(values) else sum(values)
            }
            val facts: List<HomeFact>
            val footer: List<HomeFact>
            val label: String
            val caption: String
            val heading: String
            when (metric) {
                HomeMetric.Steps -> {
                    val target = goal?.times(period)?.toDouble()
                    val remaining = primary?.let { value -> target?.minus(value) }
                    label = if (period == 1) "Steps" else "$period-day steps"
                    caption = if (primary == null) "No shared steps yet" else if (target == null) "Goal unavailable" else "of ${homeNumber(target)} steps"
                    facts = listOf(HomeFact("Distance", homeNumber(sum(rows.map { it.distance })?.div(1000), 2), "km"),
                        HomeFact("Estimated energy", homeNumber(sum(rows.map { it.energy })), "kcal"),
                        HomeFact("Floors climbed", homeNumber(sum(rows.map { it.floors }))),
                        HomeFact("Goal completion", primary?.let { total -> target?.let { homeNumber(total / it * 100) + "%" } } ?: "—"))
                    footer = listOf(HomeFact(if (remaining == null) "No shared readings" else if (remaining >= 0) "To your goal" else "Beyond your goal",
                        remaining?.let { homeNumber(abs(it)) + " steps" } ?: "—"),
                        HomeFact("With shared steps", "${values.count { it != null }} / $period ${if (period == 1) "day" else "days"}"))
                    heading = if (period == 1) "Movement through the day" else "Movement through the period"
                }
                HomeMetric.Heart -> {
                    label = if (period == 1) "Heart rate" else "Average heart rate"
                    caption = "bpm · " + if (period == 1) "latest reading" else "recorded average"
                    facts = listOf(HomeFact("Average", homeNumber(heartMean), "bpm"), HomeFact("Lowest", homeNumber(rows.mapNotNull { it.heartLow }.minOrNull()), "bpm"),
                        HomeFact("Highest", homeNumber(rows.mapNotNull { it.heartHigh }.maxOrNull()), "bpm"), HomeFact("Readings", homeNumber(count.toDouble()), "samples"))
                    footer = listOf(HomeFact("Recorded readings", "Samsung Health"), HomeFact("Latest recorded", selected.heartAt?.let(::homeTime) ?: "—"))
                    heading = if (period == 1) "Heart rate through the day" else "Daily average heart rate"
                }
                HomeMetric.Sleep -> {
                    label = if (period == 1) "Recorded sleep" else "Average sleep"
                    caption = if (rows.any { it.sleepIncomplete }) "Known sleep · gaps unrecorded" else "Recorded time asleep"
                    facts = listOf("light", "deep", "rem", "awake").map {
                        HomeFact(if (it == "rem") "REM" else it.replaceFirstChar(Char::uppercaseChar), homeDuration(mean(rows.map { d -> d.sleepStages[it] })))
                    }
                    val nights = rows.sumOf { it.nights.size }; val recorded = values.count { it != null }
                    footer = listOf(HomeFact("Recorded sleep", "$nights ${if (nights == 1) "session" else "sessions"}"),
                        HomeFact("Explore sleep", "$recorded ${if (recorded == 1) "day" else "days"}"))
                    heading = if (period == 1) "Recorded sleep stages" else "Time asleep each day"
                }
                HomeMetric.Intake -> {
                    label = if (period == 30) "Average intake" else "Intake"; caption = "kcal logged"
                    val divisor = if (period == 30) values.count { it != null }.coerceAtLeast(1) else 1
                    facts = listOf(HomeFact("Protein", homeNumber(sum(meals.map { it.protein })?.div(divisor)), "g"),
                        HomeFact("Carbs", homeNumber(sum(meals.map { it.carbs })?.div(divisor)), "g"),
                        HomeFact("Fat", homeNumber(sum(meals.map { it.fat })?.div(divisor)), "g"),
                        HomeFact("Water", homeNumber(sum(rows.map { it.water })?.div(1000 * divisor), 1), "L"))
                    footer = listOf(HomeFact("Logged in this period", "${meals.size} ${if (meals.size == 1) "entry" else "entries"}"), HomeFact("Average recorded day", homeNumber(mean(values)) + " kcal"))
                    heading = if (period == 1) "Intake through the day" else "Calories logged each day"
                }
            }
            val movement = if (period > 1) rows.map { HomePoint(homeDate(it.date), metric.value(it), it.date) } else when (metric) {
                HomeMetric.Steps, HomeMetric.Heart -> {
                    val zone = ZoneId.systemDefault()
                    val start = selected.date.atStartOfDay(zone)
                    val end = selected.date.plusDays(1).atStartOfDay(zone).toInstant()
                    val readings = (if (metric == HomeMetric.Steps) selected.hourlySteps else selected.hourlyHeart).associateBy { it.at }
                    generateSequence(start.toInstant()) { it.plusSeconds(3600) }.takeWhile { it < end }.map { at ->
                        val value = readings[at.toEpochMilli()]?.value ?: if (metric == HomeMetric.Steps && state.liveStepsAt?.let { at.toEpochMilli() <= it } == true) 0.0 else null
                        HomePoint(homeTime(at.toEpochMilli()), value)
                    }.toList()
                }
                HomeMetric.Sleep -> listOf("light", "deep", "rem", "awake").map { HomePoint(if (it == "rem") "REM" else it.replaceFirstChar(Char::uppercaseChar), selected.sleepStages[it]) }
                HomeMetric.Intake -> selected.meals.map { HomePoint(it.name, it.calories) }.ifEmpty { listOf(HomePoint("No food entries", null)) }
            }
            val comparisonDays = if (period == 30) 30 else 7
            val recent = window(comparisonDays).map { HomePoint(homeDate(it.date), metric.value(it), it.date) }
            val prior = window(comparisonDays, selected.date.minusDays(comparisonDays.toLong())).map(metric::value)
            fun rounded(values: List<Double?>) = mean(values)?.let { floor(it + .5) }
            val comparison = HomeComparison(rounded(recent.map { it.value }), rounded(prior), recent.count { it.value != null }, prior.count { it != null })
            return HomeSummary(metric, period, selected.date, if (metric == HomeMetric.Sleep) homeDuration(primary) else homeNumber(primary),
                label, caption, facts, footer, heading, movement, recent, comparison)
        }
    }
}

internal fun homeNumber(value: Double?, digits: Int = 0) = value?.let { String.format(Locale.UK, "%,.${digits}f", it) } ?: "—"
internal fun homeDuration(value: Double?): String = value?.let { floor(it + .5).toInt().let { n -> if (n >= 60) "${n / 60}h ${n % 60}m" else "${n}m" } } ?: "—"
internal fun homeDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK))
internal fun homeTime(at: Long): String = DateTimeFormatter.ofPattern("HH:mm", Locale.UK).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))
