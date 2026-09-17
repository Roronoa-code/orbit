package com.mani.orbit

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.round

internal enum class Measurement(val label: String) {
    Weight("Weight"), Fat("Fat"), Muscle("Muscle"), Lean("Lean mass")
}

internal enum class MeasurementPeriod(val label: String) {
    Week("7D"), Month("30D"), Quarter("3M"), Year("1Y");

    fun start(end: LocalDate): LocalDate = when (this) {
        Week -> end.minusDays(6)
        Month -> end.minusDays(29)
        Quarter -> end.minusMonths(3).plusDays(1)
        Year -> end.minusYears(1).plusDays(1)
    }
}

/** Original samples stay separate; composition shares need a weight from the same measurement. */
data class MeasurementHistory(
    val weights: List<Reading> = emptyList(),
    val fatPercent: List<Reading> = emptyList(),
    val muscle: List<Reading> = emptyList(),
    val lean: List<Reading> = emptyList(),
) {
    private fun weightAt(at: Long) = weights.minByOrNull { abs(it.at - at) }
        ?.takeIf { abs(it.at - at) <= 60_000 && it.value > 0 }

    internal fun readings(metric: Measurement): List<Reading> = when (metric) {
        Measurement.Weight -> weights
        Measurement.Fat -> fatPercent.mapNotNull { fat -> weightAt(fat.at)?.let { Reading(fat.at, it.value * fat.value / 100) } }
        Measurement.Muscle -> muscle
        Measurement.Lean -> lean
    }.sortedBy { it.at }

    internal fun share(metric: Measurement, reading: Reading?): Float? = reading?.let {
        if (metric == Measurement.Weight) 1f
        else weightAt(it.at)?.let { weight -> (it.value / weight.value).takeIf { n -> n in 0.0..1.0 }?.toFloat() }
    }
}

internal fun Reading.day(zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
internal fun Double.displayPrecision() = round(this * 10) / 10

/** Dates define x positions, including unequal gaps. A flat rounded series remains flat. */
internal data class MeasurementTrend(val readings: List<Reading>, val start: LocalDate, val end: LocalDate) {
    private val values = readings.map { it.value.displayPrecision() }
    val hasChart get() = readings.size >= 2
    val hasStatistics get() = readings.size >= 3 && values.distinct().size > 1
    val lowest get() = values.minOrNull()
    val highest get() = values.maxOrNull()
    val average get() = readings.takeIf { it.isNotEmpty() }?.map { it.value }?.average()

    fun x(index: Int): Float {
        val first = readings.first().at
        val span = (readings.last().at - first).coerceAtLeast(1)
        return ((readings[index].at - first).toDouble() / span).toFloat()
    }

    fun y(index: Int): Float {
        val low = lowest ?: return .5f
        val high = highest ?: return .5f
        val span = (high - low).coerceAtLeast(1.0)
        return (.5 - (values[index] - (high + low) / 2) / span * .64).toFloat()
    }

    fun nearest(fraction: Float): Int = readings.indices.minByOrNull { abs(x(it) - fraction.coerceIn(0f, 1f)) } ?: -1

    companion object {
        fun from(rows: List<Reading>, period: MeasurementPeriod, end: LocalDate): MeasurementTrend {
            val start = period.start(end)
            return MeasurementTrend(rows.filter { it.day() in start..end }.sortedBy { it.at }, start, end)
        }
    }
}
