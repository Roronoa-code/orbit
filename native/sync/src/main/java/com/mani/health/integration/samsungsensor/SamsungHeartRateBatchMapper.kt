package com.mani.health.integration.samsungsensor

import com.mani.health.core.model.heart.HeartBeatBatch
import com.mani.health.core.model.heart.HeartBeatField
import com.mani.health.core.model.heart.HeartBeatIssue
import com.mani.health.core.model.heart.HeartBeatPoint
import com.mani.health.core.model.heart.HeartRateStatus
import com.mani.health.core.model.heart.HeartRateStatusMeaning
import com.mani.health.core.model.heart.IbiStatus
import com.mani.health.core.model.heart.IbiStatusMeaning
import java.time.Instant

sealed interface SamsungFieldRead<out T> {
    data class Value<T>(val value: T) : SamsungFieldRead<T>

    data class Failure(val errorClass: String) : SamsungFieldRead<Nothing> {
        init {
            require(errorClass.isNotBlank()) { "Error class must not be blank" }
        }
    }
}

inline fun <T> readSamsungField(block: () -> T): SamsungFieldRead<T> =
    try {
        SamsungFieldRead.Value(block())
    } catch (error: Exception) {
        SamsungFieldRead.Failure(error.javaClass.name)
    }

data class SamsungHeartRatePointRead(
    val sensorTimestampEpochMillis: SamsungFieldRead<Long?>,
    val heartRateBpm: SamsungFieldRead<Int?>,
    val heartRateStatus: SamsungFieldRead<Int?>,
    val ibiMillis: SamsungFieldRead<List<Int>?>,
    val ibiStatuses: SamsungFieldRead<List<Int>?>,
)

fun mapSamsungHeartRateBatch(
    callbackSequence: Long,
    receivedAt: Instant,
    reads: List<SamsungHeartRatePointRead>,
): HeartBeatBatch {
    val issues = mutableListOf<HeartBeatIssue>()
    if (reads.isEmpty()) issues += HeartBeatIssue.EmptyBatch

    val points = reads.mapIndexed { pointIndex, read ->
        val rawTimestamp = read.sensorTimestampEpochMillis.valueOrIssue(
            pointIndex,
            HeartBeatField.SENSOR_TIMESTAMP,
            issues,
        )
        val timestamp = rawTimestamp?.let { epochMillis ->
            try {
                Instant.ofEpochMilli(epochMillis)
            } catch (error: Exception) {
                issues += HeartBeatIssue.ReadFailure(
                    pointIndex,
                    HeartBeatField.SENSOR_TIMESTAMP,
                    error.javaClass.name,
                )
                null
            }
        }
        val heartRate = read.heartRateBpm.valueOrIssue(
            pointIndex,
            HeartBeatField.HEART_RATE,
            issues,
        )
        val rawHeartRateStatus = read.heartRateStatus.valueOrIssue(
            pointIndex,
            HeartBeatField.HEART_RATE_STATUS,
            issues,
        )
        val ibi = read.ibiMillis.valueOrIssue(pointIndex, HeartBeatField.IBI, issues)
        val rawIbiStatuses = read.ibiStatuses.valueOrIssue(
            pointIndex,
            HeartBeatField.IBI_STATUS,
            issues,
        )

        rawTimestamp?.flagNonPositive(pointIndex, HeartBeatField.SENSOR_TIMESTAMP, issues)
        heartRate?.flagNonPositive(pointIndex, HeartBeatField.HEART_RATE, issues)
        ibi?.forEachIndexed { valueIndex, value ->
            value.flagNonPositive(pointIndex, HeartBeatField.IBI, issues, valueIndex)
        }

        if (read.ibiMillis is SamsungFieldRead.Value && read.ibiStatuses is SamsungFieldRead.Value) {
            val ibiCount = ibi?.size
            val statusCount = rawIbiStatuses?.size
            if (ibiCount != statusCount) {
                issues += HeartBeatIssue.IbiLengthMismatch(pointIndex, ibiCount, statusCount)
            }
        }
        if (pointIndex > 0 && (ibi != null || rawIbiStatuses != null)) {
            issues += HeartBeatIssue.UnexpectedLaterBatchIbi(pointIndex)
        }

        HeartBeatPoint(
            sensorTimestamp = timestamp,
            rawSensorTimestampEpochMillis = rawTimestamp,
            rawHeartRateBpm = heartRate,
            heartRateStatus = rawHeartRateStatus?.toHeartRateStatus(),
            rawIbiMillis = ibi,
            rawIbiStatuses = rawIbiStatuses?.map(Int::toIbiStatus),
        )
    }

    return HeartBeatBatch(callbackSequence, receivedAt, points, issues)
}

private fun <T> SamsungFieldRead<T>.valueOrIssue(
    pointIndex: Int,
    field: HeartBeatField,
    issues: MutableList<HeartBeatIssue>,
): T? = when (this) {
    is SamsungFieldRead.Value -> value
    is SamsungFieldRead.Failure -> {
        issues += HeartBeatIssue.ReadFailure(pointIndex, field, errorClass)
        null
    }
}

private fun Number.flagNonPositive(
    pointIndex: Int,
    field: HeartBeatField,
    issues: MutableList<HeartBeatIssue>,
    valueIndex: Int? = null,
) {
    val value = toLong()
    if (value <= 0) issues += HeartBeatIssue.NonPositiveValue(pointIndex, field, value, valueIndex)
}

private fun Int.toHeartRateStatus(): HeartRateStatus = when (this) {
    1 -> HeartRateStatus.Known(this, HeartRateStatusMeaning.SUCCESSFUL_MEASUREMENT)
    0 -> HeartRateStatus.Known(
        this,
        HeartRateStatusMeaning.INITIAL_MEASUREMENT_OR_HIGHER_PRIORITY_SENSOR,
    )
    -2 -> HeartRateStatus.Known(this, HeartRateStatusMeaning.WEARABLE_MOVEMENT_DETECTED)
    -3 -> HeartRateStatus.Known(this, HeartRateStatusMeaning.WEARABLE_DETACHED)
    -8 -> HeartRateStatus.Known(this, HeartRateStatusMeaning.WEAK_SIGNAL_OR_MOVEMENT)
    -10 -> HeartRateStatus.Known(
        this,
        HeartRateStatusMeaning.SIGNAL_TOO_WEAK_OR_EXCESSIVE_MOVEMENT,
    )
    -999 -> HeartRateStatus.Known(this, HeartRateStatusMeaning.HIGHER_PRIORITY_SENSOR_OPERATING)
    else -> HeartRateStatus.Unknown(this)
}

private fun Int.toIbiStatus(): IbiStatus = when (this) {
    0 -> IbiStatus.Known(this, IbiStatusMeaning.NORMAL)
    -1 -> IbiStatus.Known(this, IbiStatusMeaning.ERROR)
    else -> IbiStatus.Unknown(this)
}
