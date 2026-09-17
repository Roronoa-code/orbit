package com.mani.orbit.sync

import org.json.JSONArray
import org.json.JSONObject

/** Adapted from the existing Health App MeasurementSet: keep every SDK result together. */
data class MeasurementValue(val metric: String, val value: Double, val unit: String) {
    init { require(value.isFinite()) }
    override fun toString() = "MeasurementValue(metric=$metric, unit=$unit)"
}

class WatchMeasurement(val batch: ReadingBatch, val tracker: String, values: List<MeasurementValue>, val profile: MeasurementProfile? = null) {
    val values: List<MeasurementValue> = java.util.Collections.unmodifiableList(ArrayList(values))
    val primary: MeasurementValue get() = values.first { it.metric == if (tracker == "BIA") "BODY_FAT" else tracker }
    init {
        val expected = when (tracker) {
            "BIA" -> mapOf("BODY_FAT" to "%", "BODY_FAT_MASS" to "kg", "BODY_WATER" to "L",
                "SKELETAL_MUSCLE_MASS" to "kg", "FAT_FREE_MASS" to "kg", "BASAL_METABOLIC_RATE" to "kcal")
            "SPO2" -> mapOf("SPO2" to "%")
            "SKIN_TEMPERATURE" -> mapOf("SKIN_TEMPERATURE" to "°C", "AMBIENT_TEMPERATURE" to "°C")
            else -> throw IllegalArgumentException("Unsupported measurement")
        }
        require(this.values.size == expected.size && this.values.associate { it.metric to it.unit } == expected)
        require(tracker == "BIA" || profile == null)
        if (tracker == "BIA") require(profile?.sex != null && profile.birth != null && profile.weightKg != null && profile.heightCm != null)
        for (value in this.values) {
            when (value.metric) {
                "BODY_FAT", "SPO2" -> require(value.value in 0.0..100.0)
                "BASAL_METABOLIC_RATE" -> require(value.value in 0.0..12000.0)
                "SKIN_TEMPERATURE", "AMBIENT_TEMPERATURE" -> Unit // finite temperature, no invented physiological cutoff
                "BODY_FAT_MASS" -> require(value.value in 2.0..(requireNotNull(profile?.weightKg) - 2.0))
                else -> require(value.value in 2.0..requireNotNull(profile?.weightKg))
            }
        }
        val projections = this.values.mapNotNull { value -> PROJECTIONS[value.metric]?.let { it to value.value } }.toMap()
        require(batch.readings.size == projections.size && batch.readings.associate { it.metric to it.value } == projections)
        require(batch.readings.all { it.source == "samsung_sensor" && it.quality == "valid" && it.semantics == "instant" && it.start == it.end })
        require(batch.readings.map { Triple(it.boot, it.elapsedMs, it.start) }.distinct().size == 1)
    }
    override fun toString() = "WatchMeasurement(tracker=$tracker, values=${values.size})"
    companion object {
        val PROJECTIONS = mapOf("BODY_FAT" to "fat", "SKELETAL_MUSCLE_MASS" to "muscle", "FAT_FREE_MASS" to "lean",
            "SPO2" to "oxygen", "SKIN_TEMPERATURE" to "skinTemperature")
    }
}

/** Independent capability/path protects older receivers; ordinary reading schemas stay unchanged. */
object MeasurementWire {
    const val PATH = "/orbit/v1/measurements"
    fun encode(value: WatchMeasurement): ByteArray = JSONObject(ReadingWire.encode(value.batch).toString(Charsets.UTF_8))
        .put("measurement", JSONObject().put("tracker", value.tracker).put("sdk", "1.4.1").put("status", "complete")
            .put("values", JSONArray(value.values.map { JSONObject().put("metric", it.metric).put("value", it.value).put("unit", it.unit) }))
            .put("profile", value.profile?.let { JSONObject(MeasurementProfileWire.encode(it).toString(Charsets.UTF_8)) } ?: JSONObject.NULL))
        .toString().toByteArray(Charsets.UTF_8).also { require(it.size <= ReadingWire.MAX_BYTES) }
    fun decode(bytes: ByteArray): WatchMeasurement {
        val batch = ReadingWire.decode(bytes)
        val row = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONObject("measurement")
        require(row.get("sdk") == "1.4.1" && row.get("status") == "complete")
        val values = row.getJSONArray("values")
        require(values.length() in 1..16)
        return WatchMeasurement(batch, row.get("tracker") as String, List(values.length()) { index ->
            val value = values.getJSONObject(index)
            MeasurementValue(value.get("metric") as String, (value.get("value") as Number).toDouble(), value.get("unit") as String)
        }, if (row.isNull("profile")) null else MeasurementProfileWire.decode(row.getJSONObject("profile").toString().toByteArray(Charsets.UTF_8)))
    }
}
