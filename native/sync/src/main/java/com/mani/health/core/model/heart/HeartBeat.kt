package com.mani.health.core.model.heart

import java.time.Instant
import java.util.Collections

class HeartBeatBatch(
    val callbackSequence: Long,
    val receivedAt: Instant,
    points: List<HeartBeatPoint>,
    issues: List<HeartBeatIssue> = emptyList(),
    val receivedElapsedRealtimeMillis: Long? = null,
) {
    val points: List<HeartBeatPoint> = points.snapshot()
    val issues: List<HeartBeatIssue> = issues.snapshot()

    init {
        require(callbackSequence >= 0) { "Callback sequence must not be negative" }
        require(receivedElapsedRealtimeMillis == null || receivedElapsedRealtimeMillis >= 0)
        require(this.issues.all { it.pointIndex == null || it.pointIndex in this.points.indices }) {
            "Issue point indices must refer to a point in this batch"
        }
        require(this.issues.any { it is HeartBeatIssue.EmptyBatch } == this.points.isEmpty()) {
            "An empty batch must have exactly one empty-batch state"
        }
        require(this.issues.count { it is HeartBeatIssue.EmptyBatch } <= 1) {
            "An empty batch must have exactly one empty-batch state"
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is HeartBeatBatch &&
            callbackSequence == other.callbackSequence &&
            receivedAt == other.receivedAt &&
            receivedElapsedRealtimeMillis == other.receivedElapsedRealtimeMillis &&
            points == other.points &&
            issues == other.issues

    override fun hashCode(): Int {
        var result = callbackSequence.hashCode()
        result = 31 * result + receivedAt.hashCode()
        result = 31 * result + (receivedElapsedRealtimeMillis?.hashCode() ?: 0)
        result = 31 * result + points.hashCode()
        result = 31 * result + issues.hashCode()
        return result
    }

    override fun toString(): String =
        "HeartBeatBatch(callbackSequence=$callbackSequence, " +
            "pointCount=${points.size}, issueCount=${issues.size})"
}

class HeartBeatPoint(
    val sensorTimestamp: Instant?,
    val rawSensorTimestampEpochMillis: Long?,
    val rawHeartRateBpm: Int?,
    val heartRateStatus: HeartRateStatus?,
    rawIbiMillis: List<Int>?,
    rawIbiStatuses: List<IbiStatus>?,
) {
    val rawIbiMillis: List<Int>? = rawIbiMillis?.snapshot()
    val rawIbiStatuses: List<IbiStatus>? = rawIbiStatuses?.snapshot()

    init {
        require(sensorTimestamp == null || rawSensorTimestampEpochMillis != null) {
            "A sensor timestamp must retain its raw epoch-millisecond value"
        }
        require(
            sensorTimestamp == null ||
                sensorTimestamp == Instant.ofEpochMilli(checkNotNull(rawSensorTimestampEpochMillis)),
        ) {
            "Sensor timestamp must match its raw epoch-millisecond value"
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is HeartBeatPoint &&
            sensorTimestamp == other.sensorTimestamp &&
            rawSensorTimestampEpochMillis == other.rawSensorTimestampEpochMillis &&
            rawHeartRateBpm == other.rawHeartRateBpm &&
            heartRateStatus == other.heartRateStatus &&
            rawIbiMillis == other.rawIbiMillis &&
            rawIbiStatuses == other.rawIbiStatuses

    override fun hashCode(): Int {
        var result = sensorTimestamp?.hashCode() ?: 0
        result = 31 * result + (rawSensorTimestampEpochMillis?.hashCode() ?: 0)
        result = 31 * result + (rawHeartRateBpm ?: 0)
        result = 31 * result + (heartRateStatus?.hashCode() ?: 0)
        result = 31 * result + (rawIbiMillis?.hashCode() ?: 0)
        result = 31 * result + (rawIbiStatuses?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "HeartBeatPoint(hasSensorTimestamp=${sensorTimestamp != null}, " +
            "hasRawSensorTimestamp=${rawSensorTimestampEpochMillis != null}, " +
            "hasRawHeartRate=${rawHeartRateBpm != null}, " +
            "hasHeartRateStatus=${heartRateStatus != null}, " +
            "ibiCount=${rawIbiMillis?.size}, ibiStatusCount=${rawIbiStatuses?.size})"
}

sealed interface HeartRateStatus {
    val rawValue: Int

    data class Known(
        override val rawValue: Int,
        val meaning: HeartRateStatusMeaning,
    ) : HeartRateStatus

    data class Unknown(
        override val rawValue: Int,
    ) : HeartRateStatus
}

enum class HeartRateStatusMeaning {
    SUCCESSFUL_MEASUREMENT,
    INITIAL_MEASUREMENT_OR_HIGHER_PRIORITY_SENSOR,
    WEARABLE_MOVEMENT_DETECTED,
    WEARABLE_DETACHED,
    WEAK_SIGNAL_OR_MOVEMENT,
    SIGNAL_TOO_WEAK_OR_EXCESSIVE_MOVEMENT,
    HIGHER_PRIORITY_SENSOR_OPERATING,
}

sealed interface IbiStatus {
    val rawValue: Int

    data class Known(
        override val rawValue: Int,
        val meaning: IbiStatusMeaning,
    ) : IbiStatus

    data class Unknown(
        override val rawValue: Int,
    ) : IbiStatus
}

enum class IbiStatusMeaning {
    NORMAL,
    ERROR,
}

enum class HeartBeatField {
    SENSOR_TIMESTAMP,
    HEART_RATE,
    HEART_RATE_STATUS,
    IBI,
    IBI_STATUS,
}

sealed interface HeartBeatIssue {
    val pointIndex: Int?

    data object EmptyBatch : HeartBeatIssue {
        override val pointIndex: Int? = null
    }

    data class ReadFailure(
        override val pointIndex: Int,
        val field: HeartBeatField,
        val errorClass: String,
    ) : HeartBeatIssue {
        init {
            require(pointIndex >= 0) { "Point index must not be negative" }
            require(errorClass.isNotBlank()) { "Error class must not be blank" }
        }
    }

    data class IbiLengthMismatch(
        override val pointIndex: Int,
        val ibiCount: Int?,
        val statusCount: Int?,
    ) : HeartBeatIssue {
        init {
            require(pointIndex >= 0) { "Point index must not be negative" }
            require(ibiCount == null || ibiCount >= 0) { "IBI count must not be negative" }
            require(statusCount == null || statusCount >= 0) { "IBI status count must not be negative" }
            require(ibiCount != statusCount) { "Length-mismatch counts must differ" }
        }
    }

    data class UnexpectedLaterBatchIbi(
        override val pointIndex: Int,
    ) : HeartBeatIssue {
        init {
            require(pointIndex > 0) { "Unexpected later-batch IBI must refer to a later point" }
        }
    }

    data class NonPositiveValue(
        override val pointIndex: Int,
        val field: HeartBeatField,
        val rawValue: Long,
        val valueIndex: Int? = null,
    ) : HeartBeatIssue {
        init {
            require(pointIndex >= 0) { "Point index must not be negative" }
            require(rawValue <= 0) { "Non-positive issue must retain a non-positive value" }
            require(valueIndex == null || valueIndex >= 0) { "Value index must not be negative" }
        }
    }

    data class ImpossibleValue(
        override val pointIndex: Int,
        val field: HeartBeatField,
        val rawValue: Long,
        val valueIndex: Int? = null,
    ) : HeartBeatIssue {
        init {
            require(pointIndex >= 0) { "Point index must not be negative" }
            require(valueIndex == null || valueIndex >= 0) { "Value index must not be negative" }
        }
    }
}

private fun <T> List<T>.snapshot(): List<T> = Collections.unmodifiableList(ArrayList(this))
