package com.mani.health.integration.samsungsensor

import java.time.Instant
import java.util.Collections

/** Explicit continuous trackers; capability and permission are checked on the actual Watch. */
enum class SensorRawProbe(
    val nominalRateHz: Double?,
) {
    PPG_CONTINUOUS(25.0),
    ACCELEROMETER_CONTINUOUS(25.0),
    SKIN_TEMPERATURE_CONTINUOUS(null),
}

/** Official Samsung Sensor SDK 1.4.1 conversion from accelerometer counts to m/s². */
val ACCELEROMETER_COUNTS_TO_METERS_PER_SECOND_SQUARED: Double = 9.81 / (16383.75 / 4.0)

enum class RawProbeField {
    SENSOR_TIMESTAMP,
    PPG_GREEN,
    GREEN_STATUS,
    PPG_IR,
    IR_STATUS,
    PPG_RED,
    RED_STATUS,
    ACCELEROMETER_X,
    ACCELEROMETER_Y,
    ACCELEROMETER_Z,
    OBJECT_TEMPERATURE,
    AMBIENT_TEMPERATURE,
    STATUS,
}

sealed interface RawProbeIssue {
    val sampleIndex: Int?

    data object EmptyChunk : RawProbeIssue {
        override val sampleIndex: Int? = null
    }

    data class ReadFailure(
        override val sampleIndex: Int,
        val field: RawProbeField,
        val errorClass: String,
    ) : RawProbeIssue {
        init {
            require(sampleIndex >= 0) { "Sample index must not be negative" }
            require(errorClass.isNotBlank()) { "Error class must not be blank" }
        }

        override fun toString(): String =
            "ReadFailure(sampleIndex=$sampleIndex, field=$field, errorClassPresent=true)"
    }
}

enum class RawPpgStatusMeaning {
    NORMAL,
    HIGHER_PRIORITY_SENSOR,
}

sealed interface RawPpgStatus {
    val rawValue: Int

    data class Known(
        override val rawValue: Int,
        val meaning: RawPpgStatusMeaning,
    ) : RawPpgStatus {
        override fun toString(): String = "Known(meaning=$meaning)"
    }

    data class Unknown(
        override val rawValue: Int,
    ) : RawPpgStatus {
        override fun toString(): String = "Unknown(rawValuePresent=true)"
    }
}

enum class RawSkinTemperatureStatusMeaning {
    NORMAL,
    ERROR,
}

sealed interface RawSkinTemperatureStatus {
    val rawValue: Int

    data class Known(
        override val rawValue: Int,
        val meaning: RawSkinTemperatureStatusMeaning,
    ) : RawSkinTemperatureStatus {
        override fun toString(): String = "Known(meaning=$meaning)"
    }

    data class Unknown(
        override val rawValue: Int,
    ) : RawSkinTemperatureStatus {
        override fun toString(): String = "Unknown(rawValuePresent=true)"
    }
}

sealed interface RawProbeSample {
    val sensorTimestamp: Instant?
    val rawSensorTimestampEpochMillis: Long?
}

data class PpgRawSample(
    override val sensorTimestamp: Instant?,
    override val rawSensorTimestampEpochMillis: Long?,
    val rawPpgGreen: Int?,
    val rawGreenStatus: Int?,
    val rawPpgIr: Int?,
    val rawIrStatus: Int?,
    val rawPpgRed: Int?,
    val rawRedStatus: Int?,
) : RawProbeSample {
    init {
        requireMatchingTimestamp(sensorTimestamp, rawSensorTimestampEpochMillis)
    }

    val greenStatus: RawPpgStatus?
        get() = rawGreenStatus?.toRawPpgStatus()
    val irStatus: RawPpgStatus?
        get() = rawIrStatus?.toRawPpgStatus()
    val redStatus: RawPpgStatus?
        get() = rawRedStatus?.toRawPpgStatus()

    override fun toString(): String =
        "PpgRawSample(hasTimestamp=${sensorTimestamp != null}, " +
            "hasGreen=${rawPpgGreen != null}, hasGreenStatus=${rawGreenStatus != null}, " +
            "hasIr=${rawPpgIr != null}, hasIrStatus=${rawIrStatus != null}, " +
            "hasRed=${rawPpgRed != null}, hasRedStatus=${rawRedStatus != null})"
}

data class AccelerometerRawSample(
    override val sensorTimestamp: Instant?,
    override val rawSensorTimestampEpochMillis: Long?,
    val rawAccelerometerX: Int?,
    val rawAccelerometerY: Int?,
    val rawAccelerometerZ: Int?,
) : RawProbeSample {
    init {
        requireMatchingTimestamp(sensorTimestamp, rawSensorTimestampEpochMillis)
    }

    val xMetersPerSecondSquared: Double?
        get() = rawAccelerometerX?.toDouble()?.times(ACCELEROMETER_COUNTS_TO_METERS_PER_SECOND_SQUARED)
    val yMetersPerSecondSquared: Double?
        get() = rawAccelerometerY?.toDouble()?.times(ACCELEROMETER_COUNTS_TO_METERS_PER_SECOND_SQUARED)
    val zMetersPerSecondSquared: Double?
        get() = rawAccelerometerZ?.toDouble()?.times(ACCELEROMETER_COUNTS_TO_METERS_PER_SECOND_SQUARED)
    override fun toString(): String =
        "AccelerometerRawSample(hasTimestamp=${sensorTimestamp != null}, " +
            "hasX=${rawAccelerometerX != null}, hasY=${rawAccelerometerY != null}, " +
            "hasZ=${rawAccelerometerZ != null})"
}

data class SkinTemperatureRawSample(
    override val sensorTimestamp: Instant?,
    override val rawSensorTimestampEpochMillis: Long?,
    val rawObjectTemperatureCelsius: Float?,
    val rawAmbientTemperatureCelsius: Float?,
    val rawStatus: Int?,
) : RawProbeSample {
    init {
        requireMatchingTimestamp(sensorTimestamp, rawSensorTimestampEpochMillis)
    }

    val temperatureStatus: RawSkinTemperatureStatus?
        get() = rawStatus?.toRawSkinTemperatureStatus()

    override fun toString(): String =
        "SkinTemperatureRawSample(hasTimestamp=${sensorTimestamp != null}, " +
            "hasObjectTemperature=${rawObjectTemperatureCelsius != null}, " +
            "hasAmbientTemperature=${rawAmbientTemperatureCelsius != null}, " +
            "hasStatus=${rawStatus != null})"
}

data class RawProbeQualityEvidence(
    val nominalRateHz: Double?,
    val sampleCount: Int,
    val timestampedSampleCount: Int,
    val durationMillis: Long?,
    val observedRateHz: Double?,
    val gapCount: Int,
    val largestGapMillis: Long?,
    val timestampOrderViolationCount: Int,
) {
    init {
        require(nominalRateHz == null || nominalRateHz > 0.0) { "Nominal rate must be positive" }
        require(sampleCount >= 0) { "Sample count must not be negative" }
        require(timestampedSampleCount in 0..sampleCount) {
            "Timestamped sample count must be within the sample count"
        }
        require(durationMillis == null || durationMillis >= 0) { "Duration must not be negative" }
        require(observedRateHz == null || observedRateHz >= 0.0) { "Observed rate must not be negative" }
        require(gapCount >= 0) { "Gap count must not be negative" }
        require(largestGapMillis == null || largestGapMillis >= 0) { "Largest gap must not be negative" }
        require(timestampOrderViolationCount >= 0) { "Timestamp-order count must not be negative" }
    }

    companion object {
        internal fun from(probe: SensorRawProbe, samples: List<RawProbeSample>): RawProbeQualityEvidence {
            val timestamps = samples.mapNotNull { it.rawSensorTimestampEpochMillis }
            val pairs = timestamps.zipWithNext()
            val deltas = pairs.mapNotNull { (previous, current) ->
                try { Math.subtractExact(current, previous) } catch (_: ArithmeticException) { null }
            }
            val positiveDeltas = deltas.filter { it > 0L }
            val durationMillis = if (timestamps.size >= 2) {
                try { Math.subtractExact(timestamps.last(), timestamps.first()).takeIf { it >= 0L } }
                catch (_: ArithmeticException) { null }
            } else {
                null
            }
            val observedRateHz = durationMillis
                ?.takeIf { it > 0L }
                ?.let { (timestamps.size - 1) * 1_000.0 / it }
            val expectedPeriodMillis = probe.nominalRateHz?.let { 1_000.0 / it }
            // Timing evidence only: a gap is a delta more than 1.5 nominal periods.
            val gapThresholdMillis = expectedPeriodMillis?.times(1.5)
            val gapCount = gapThresholdMillis?.let { threshold ->
                positiveDeltas.count { it.toDouble() > threshold }
            } ?: 0
            return RawProbeQualityEvidence(
                nominalRateHz = probe.nominalRateHz,
                sampleCount = samples.size,
                timestampedSampleCount = timestamps.size,
                durationMillis = durationMillis,
                observedRateHz = observedRateHz,
                gapCount = gapCount,
                largestGapMillis = positiveDeltas.maxOrNull(),
                timestampOrderViolationCount = pairs.count { (previous, current) -> current <= previous },
            )
        }
    }
}

class RawProbeChunk(
    val probe: SensorRawProbe,
    val callbackSequence: Long,
    val receivedAt: Instant,
    samples: List<RawProbeSample>,
    issues: List<RawProbeIssue> = emptyList(),
    val receivedElapsedRealtimeMillis: Long? = null,
) {
    val samples: List<RawProbeSample> = samples.snapshot()
    val issues: List<RawProbeIssue> = issues.snapshot()
    val quality: RawProbeQualityEvidence = RawProbeQualityEvidence.from(probe, this.samples)

    init {
        require(callbackSequence >= 0) { "Callback sequence must not be negative" }
        require(receivedElapsedRealtimeMillis == null || receivedElapsedRealtimeMillis >= 0)
        require(this.samples.all { when (probe) {
            SensorRawProbe.PPG_CONTINUOUS -> it is PpgRawSample
            SensorRawProbe.ACCELEROMETER_CONTINUOUS -> it is AccelerometerRawSample
            SensorRawProbe.SKIN_TEMPERATURE_CONTINUOUS -> it is SkinTemperatureRawSample
        } })
        require(this.issues.all { it.sampleIndex == null || it.sampleIndex in this.samples.indices }) {
            "Issue sample indices must refer to a sample in this chunk"
        }
        require(this.issues.count { it is RawProbeIssue.EmptyChunk } <= 1) {
            "An empty chunk must have at most one empty-chunk state"
        }
        require(this.issues.any { it is RawProbeIssue.EmptyChunk } == this.samples.isEmpty()) {
            "An empty chunk must have exactly one empty-chunk state"
        }
        require(this.quality.sampleCount == this.samples.size) {
            "Quality sample count must match the chunk"
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is RawProbeChunk &&
            probe == other.probe &&
            callbackSequence == other.callbackSequence &&
            receivedAt == other.receivedAt &&
            receivedElapsedRealtimeMillis == other.receivedElapsedRealtimeMillis &&
            samples == other.samples &&
            issues == other.issues &&
            quality == other.quality

    override fun hashCode(): Int {
        var result = probe.hashCode()
        result = 31 * result + callbackSequence.hashCode()
        result = 31 * result + receivedAt.hashCode()
        result = 31 * result + (receivedElapsedRealtimeMillis?.hashCode() ?: 0)
        result = 31 * result + samples.hashCode()
        result = 31 * result + issues.hashCode()
        result = 31 * result + quality.hashCode()
        return result
    }

    override fun toString(): String =
        "RawProbeChunk(probe=$probe, callbackSequence=$callbackSequence, " +
            "receivedAtPresent=true, sampleCount=${samples.size}, issueCount=${issues.size})"
}

sealed class SensorSdkRawProbeException(message: String) : Exception(message) {
    class PermissionRequired : SensorSdkRawProbeException(
        "Raw-probe permission is required",
    )

    class TrackerUnsupported : SensorSdkRawProbeException(
        "Requested raw-probe tracker is not supported",
    )

    class SdkPolicyRejected : SensorSdkRawProbeException(
        "Samsung Health Sensor SDK policy rejected the raw-probe tracker",
    )

    class ServiceUnavailable : SensorSdkRawProbeException(
        "Samsung Health Sensor Service is unavailable",
    )

    class ServiceUpdateRequired : SensorSdkRawProbeException(
        "Samsung Health Sensor Service must be updated",
    )

    class SdkMissing : SensorSdkRawProbeException(
        "Samsung Health Sensor SDK binary is not installed",
    )

    class SessionAlreadyActive : SensorSdkRawProbeException(
        "A Samsung raw-probe session is already active",
    )

    class CallbackBufferOverflow : SensorSdkRawProbeException(
        "The Samsung raw-probe callback buffer is full",
    )

    class Unexpected(val errorClass: String) : SensorSdkRawProbeException(
        "Unexpected Samsung raw-probe source failure: $errorClass",
    ) {
        init {
            require(errorClass.isNotBlank()) { "Error class must not be blank" }
        }
    }
}

private fun Int.toRawPpgStatus(): RawPpgStatus = when (this) {
    0 -> RawPpgStatus.Known(this, RawPpgStatusMeaning.NORMAL)
    -1 -> RawPpgStatus.Known(this, RawPpgStatusMeaning.HIGHER_PRIORITY_SENSOR)
    else -> RawPpgStatus.Unknown(this)
}

private fun Int.toRawSkinTemperatureStatus(): RawSkinTemperatureStatus = when (this) {
    0 -> RawSkinTemperatureStatus.Known(this, RawSkinTemperatureStatusMeaning.NORMAL)
    -1 -> RawSkinTemperatureStatus.Known(this, RawSkinTemperatureStatusMeaning.ERROR)
    else -> RawSkinTemperatureStatus.Unknown(this)
}

private fun requireMatchingTimestamp(sensorTimestamp: Instant?, rawEpochMillis: Long?) {
    require(sensorTimestamp == null || rawEpochMillis != null) {
        "A sensor timestamp must retain its raw epoch-millisecond value"
    }
    require(sensorTimestamp == null || sensorTimestamp == Instant.ofEpochMilli(checkNotNull(rawEpochMillis))) {
        "Sensor timestamp must match its raw epoch-millisecond value"
    }
}

private fun <T> List<T>.snapshot(): List<T> = Collections.unmodifiableList(ArrayList(this))
