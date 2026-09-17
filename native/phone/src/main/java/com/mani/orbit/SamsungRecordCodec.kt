package com.mani.orbit

import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.request.DataType.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale

/** Samsung's original IDs, source/device and units survive the existing SQLite projection. */
internal object SamsungRecordCodec {
    private fun number(value: Number): Double = value.toDouble().also { require(it.isFinite() && it >= 0) }
    private fun base(point: HealthDataPoint, type: String, suffix: String = "", start: Instant = point.startTime,
                     end: Instant = point.endTime ?: start): JSONObject {
        require(point.uid.isNotBlank() && point.uid.length <= 400 && !end.isBefore(start) && start.toEpochMilli() >= 0)
        return JSONObject().put("type", type).put("id", "sdk:${point.uid}$suffix").put("source", "com.sec.android.app.shealth")
            .put("transport", "samsung_sdk").put("start", start.toEpochMilli()).put("end", end.toEpochMilli())
            .put("modified", point.updateTime?.toEpochMilli()).put("startOffset", point.zoneOffset?.totalSeconds)
            .put("originApp", point.dataSource?.appId).put("deviceId", point.dataSource?.deviceId)
    }

    fun encode(type: String, point: HealthDataPoint): List<JSONObject> = when (type) {
        "body" -> buildList {
            val fields = listOf("weight" to BodyCompositionType.WEIGHT, "fat" to BodyCompositionType.BODY_FAT,
                "lean" to BodyCompositionType.FAT_FREE_MASS, "muscle" to BodyCompositionType.SKELETAL_MUSCLE_MASS,
                "height" to BodyCompositionType.HEIGHT)
            for ((key, field) in fields) point.getValue(field)?.let { value ->
                val amount = number(value) / if (key == "height") 100 else 1
                require(key != "fat" || amount <= 100)
                add(base(point, key).put("value", amount))
            }
        }
        "heart" -> {
            val samples = JSONArray()
            val series = point.getValue(HeartRateType.SERIES_DATA).orEmpty()
            var previous = point.startTime
            for (entry in series) {
                require(entry.startTime >= previous && entry.endTime >= entry.startTime && !entry.endTime.isAfter(point.endTime ?: point.startTime))
                val value = number(entry.heartRate); require(value > 0)
                samples.put(sample(entry.startTime, value, entry.min, entry.max))
                previous = entry.startTime
            }
            if (series.isEmpty()) point.getValue(HeartRateType.HEART_RATE)?.let {
                val value = number(it); require(value > 0)
                samples.put(sample(point.startTime, value, point.getValue(HeartRateType.MIN_HEART_RATE), point.getValue(HeartRateType.MAX_HEART_RATE)))
            }
            listOf(base(point, type).put("samples", samples))
        }
        "oxygen" -> {
            val series = point.getValue(BloodOxygenType.SERIES_DATA).orEmpty()
            if (series.isEmpty()) point.getValue(BloodOxygenType.OXYGEN_SATURATION)?.let {
                val value = number(it); require(value <= 100)
                listOf(oxygen(base(point, type), value, point.getValue(BloodOxygenType.MIN_OXYGEN_SATURATION), point.getValue(BloodOxygenType.MAX_OXYGEN_SATURATION)))
            }.orEmpty() else series.map { entry ->
                val value = number(entry.oxygenSaturation); require(value <= 100)
                require(!entry.startTime.isBefore(point.startTime) && !entry.endTime.isAfter(point.endTime ?: point.startTime))
                oxygen(base(point, type, ":${entry.startTime.toEpochMilli()}", entry.startTime, entry.endTime), value, entry.min, entry.max)
            }
        }
        "sleep" -> {
            val sessions = point.getValue(SleepType.SESSIONS).orEmpty()
            val score = point.getValue(SleepType.SLEEP_SCORE)?.also { require(it in 0..100) }
            if (sessions.isEmpty()) listOf(base(point, type).put("stages", JSONArray()).put("sleepScore", score)
                .put("recordedWindowMs", point.getValue(SleepType.DURATION)?.toMillis()?.also {
                    require(it >= 0 && it <= java.time.Duration.between(point.startTime, point.endTime ?: point.startTime).toMillis())
                }))
            else sessions.map { session ->
                require(!session.startTime.isBefore(point.startTime) && !session.endTime.isAfter(point.endTime ?: session.endTime))
                var end = session.startTime
                val stages = JSONArray()
                for (stage in session.stages.orEmpty()) {
                    require(!stage.startTime.isBefore(end) && stage.endTime > stage.startTime && stage.endTime <= session.endTime)
                    val value = when (stage.stage) {
                        SleepType.StageType.AWAKE -> 1; SleepType.StageType.LIGHT -> 4
                        SleepType.StageType.DEEP -> 5; SleepType.StageType.REM -> 6; else -> 0
                    }
                    stages.put(JSONArray().put(stage.startTime.toEpochMilli()).put(stage.endTime.toEpochMilli()).put(value))
                    end = stage.endTime
                }
                base(point, type, ":${session.startTime.toEpochMilli()}", session.startTime, session.endTime)
                    .put("stages", stages).put("sleepScore", score).put("recordedWindowMs", session.duration.toMillis().also {
                        require(it >= 0 && it <= java.time.Duration.between(session.startTime, session.endTime).toMillis())
                    })
            }
        }
        "nutrition" -> listOf(base(point, type).apply {
            put("name", point.getValue(NutritionType.TITLE)?.take(300))
            put("mealType", when (point.getValue(NutritionType.MEAL_TYPE)?.name) { "BREAKFAST" -> 1; "LUNCH" -> 2; "DINNER" -> 3; "SNACK" -> 4; else -> 0 })
            for ((key, field) in listOf("calories" to NutritionType.CALORIES, "protein" to NutritionType.PROTEIN,
                "carbs" to NutritionType.CARBOHYDRATE, "fat" to NutritionType.TOTAL_FAT)) point.getValue(field)?.let { put(key, number(it)) }
            val nutrients = JSONObject()
            for ((key, field) in listOf("Protein" to NutritionType.PROTEIN, "TotalCarbohydrate" to NutritionType.CARBOHYDRATE,
                "TotalFat" to NutritionType.TOTAL_FAT, "SaturatedFat" to NutritionType.SATURATED_FAT,
                "PolyunsaturatedFat" to NutritionType.POLYSATURATED_FAT, "MonounsaturatedFat" to NutritionType.MONOSATURATED_FAT,
                "TransFat" to NutritionType.TRANS_FAT, "DietaryFiber" to NutritionType.DIETARY_FIBER, "Sugar" to NutritionType.SUGAR))
                point.getValue(field)?.let { nutrients.put(key, number(it)) }
            for ((key, field) in listOf("Cholesterol" to NutritionType.CHOLESTEROL, "Sodium" to NutritionType.SODIUM,
                "Potassium" to NutritionType.POTASSIUM, "VitaminC" to NutritionType.VITAMIN_C,
                "Calcium" to NutritionType.CALCIUM, "Iron" to NutritionType.IRON))
                point.getValue(field)?.let { nutrients.put(key, number(it) / 1000) }
            point.getValue(NutritionType.VITAMIN_A)?.let { nutrients.put("VitaminA", number(it) / 1_000_000) }
            put("nutrientsGrams", nutrients)
        })
        "water" -> point.getValue(WaterIntakeType.AMOUNT)?.let { listOf(base(point, type).put("value", number(it))) }.orEmpty()
        "energyScore" -> point.getValue(EnergyScoreType.ENERGY_SCORE)?.let {
            val score = number(it); require(score <= 100)
            listOf(base(point, type).put("value", score)
                .put("date", point.startTime.atOffset(requireNotNull(point.zoneOffset)).toLocalDate().toString()))
        }.orEmpty()
        "exercise" -> point.getValue(ExerciseType.SESSIONS).orEmpty().map { session ->
            require(session.startTime >= point.startTime && session.endTime > session.startTime && session.endTime <= (point.endTime ?: session.endTime))
            val duration = session.duration.toMillis().also { require(it in 0..java.time.Duration.between(session.startTime, session.endTime).toMillis()) }
            val kind = session.exerciseType.name.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
            val summary = JSONObject().put("energy", number(session.calories))
            for ((key, value) in listOf("distance" to session.distance, "heartAverage" to session.meanHeartRate,
                "heartLow" to session.minHeartRate, "heartHigh" to session.maxHeartRate, "elevation" to session.altitudeGain,
                "maxSpeed" to session.maxSpeed, "meanSpeed" to session.meanSpeed))
                value?.let { summary.put(key, number(it)) }
            val route = JSONArray()
            var previous = session.startTime
            for (location in session.route.orEmpty()) {
                require(location.timestamp >= previous && location.timestamp <= session.endTime)
                require(location.latitude.isFinite() && location.latitude in -90f..90f && location.longitude.isFinite() && location.longitude in -180f..180f)
                val altitude = location.altitude?.also { require(it.isFinite()) }
                val accuracy = location.accuracy?.also { number(it) }
                route.put(JSONObject().put("at", location.timestamp.toEpochMilli()).put("lat", location.latitude.toDouble())
                    .put("lon", location.longitude.toDouble()).put("altitude", altitude).put("accuracy", accuracy))
                previous = location.timestamp
            }
            val logs = JSONArray()
            previous = session.startTime
            for (entry in session.log.orEmpty()) {
                require(entry.timestamp >= previous && entry.timestamp <= session.endTime)
                logs.put(JSONObject().put("at", entry.timestamp.toEpochMilli()).apply {
                    for ((key, value) in listOf("heart" to entry.heartRate, "speed" to entry.speed, "cadence" to entry.cadence, "count" to entry.count, "power" to entry.power))
                        value?.let { put(key, number(it)) }
                })
                previous = entry.timestamp
            }
            base(point, type, ":${session.startTime.toEpochMilli()}", session.startTime, session.endTime)
                .put("kind", kind).put("title", session.customTitle?.take(300)).put("notes", session.comment?.take(2000))
                .put("durationMs", duration).put("hasRoute", route.length() > 0).put("route", route).put("logs", logs)
                .put("summary", summary).put("laps", JSONArray()).put("segments", JSONArray())
        }
        else -> error("Unsupported Samsung record: $type")
    }

    private fun sample(at: Instant, value: Double, low: Number?, high: Number?): JSONArray {
        val min = low?.let(::number)?.takeIf { it > 0 } ?: value
        val max = high?.let(::number)?.takeIf { it > 0 } ?: value
        require(value in min..max)
        return JSONArray().put(at.toEpochMilli()).put(value).put(min).put(max)
    }
    private fun oxygen(row: JSONObject, value: Double, low: Number?, high: Number?): JSONObject {
        val min = low?.let(::number) ?: value; val max = high?.let(::number) ?: value
        require(value in min..max && max <= 100)
        return row.put("value", value).put("low", min).put("high", max)
    }
}
