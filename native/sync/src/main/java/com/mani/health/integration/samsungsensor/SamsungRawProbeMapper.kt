package com.mani.health.integration.samsungsensor

import java.time.Instant

sealed interface SamsungRawPointRead {
    val sensorTimestampEpochMillis: SamsungFieldRead<Long?>
}

data class SamsungPpgPointRead(
    override val sensorTimestampEpochMillis: SamsungFieldRead<Long?>,
    val ppgGreen: SamsungFieldRead<Int?>,
    val greenStatus: SamsungFieldRead<Int?>,
    val ppgIr: SamsungFieldRead<Int?>,
    val irStatus: SamsungFieldRead<Int?>,
    val ppgRed: SamsungFieldRead<Int?>,
    val redStatus: SamsungFieldRead<Int?>,
) : SamsungRawPointRead {
    override fun toString(): String =
        "SamsungPpgPointRead(hasTimestampRead=true, fieldCount=6)"
}

data class SamsungAccelerometerPointRead(
    override val sensorTimestampEpochMillis: SamsungFieldRead<Long?>,
    val accelerometerX: SamsungFieldRead<Int?>,
    val accelerometerY: SamsungFieldRead<Int?>,
    val accelerometerZ: SamsungFieldRead<Int?>,
) : SamsungRawPointRead {
    override fun toString(): String =
        "SamsungAccelerometerPointRead(hasTimestampRead=true, fieldCount=3)"
}

data class SamsungSkinTemperaturePointRead(
    override val sensorTimestampEpochMillis: SamsungFieldRead<Long?>,
    val objectTemperature: SamsungFieldRead<Float?>,
    val ambientTemperature: SamsungFieldRead<Float?>,
    val status: SamsungFieldRead<Int?>,
) : SamsungRawPointRead {
    override fun toString(): String =
        "SamsungSkinTemperaturePointRead(hasTimestampRead=true, fieldCount=3)"
}

fun mapSamsungRawProbeChunk(
    probe: SensorRawProbe,
    callbackSequence: Long,
    receivedAt: Instant,
    reads: List<SamsungRawPointRead>,
    receivedElapsedRealtimeMillis: Long? = null,
): RawProbeChunk {
    val issues = mutableListOf<RawProbeIssue>()
    if (reads.isEmpty()) issues += RawProbeIssue.EmptyChunk

    val samples = reads.mapIndexed { sampleIndex, read ->
        when (probe) {
            SensorRawProbe.PPG_CONTINUOUS ->
                (read as? SamsungPpgPointRead)?.toSample(sampleIndex, issues)
                    ?: wrongReadType(probe, read)
            SensorRawProbe.ACCELEROMETER_CONTINUOUS ->
                (read as? SamsungAccelerometerPointRead)?.toSample(sampleIndex, issues)
                    ?: wrongReadType(probe, read)
            SensorRawProbe.SKIN_TEMPERATURE_CONTINUOUS ->
                (read as? SamsungSkinTemperaturePointRead)?.toSample(sampleIndex, issues)
                    ?: wrongReadType(probe, read)
        }
    }

    return RawProbeChunk(
        probe = probe,
        callbackSequence = callbackSequence,
        receivedAt = receivedAt,
        samples = samples,
        issues = issues,
        receivedElapsedRealtimeMillis = receivedElapsedRealtimeMillis,
    )
}

private fun SamsungPpgPointRead.toSample(
    sampleIndex: Int,
    issues: MutableList<RawProbeIssue>,
): PpgRawSample {
    val timestamp = timestamp(sampleIndex, issues)
    return PpgRawSample(
        sensorTimestamp = timestamp.instant,
        rawSensorTimestampEpochMillis = timestamp.epochMillis,
        rawPpgGreen = ppgGreen.valueOrIssue(sampleIndex, RawProbeField.PPG_GREEN, issues),
        rawGreenStatus = greenStatus.valueOrIssue(sampleIndex, RawProbeField.GREEN_STATUS, issues),
        rawPpgIr = ppgIr.valueOrIssue(sampleIndex, RawProbeField.PPG_IR, issues),
        rawIrStatus = irStatus.valueOrIssue(sampleIndex, RawProbeField.IR_STATUS, issues),
        rawPpgRed = ppgRed.valueOrIssue(sampleIndex, RawProbeField.PPG_RED, issues),
        rawRedStatus = redStatus.valueOrIssue(sampleIndex, RawProbeField.RED_STATUS, issues),
    )
}

private fun SamsungAccelerometerPointRead.toSample(
    sampleIndex: Int,
    issues: MutableList<RawProbeIssue>,
): AccelerometerRawSample {
    val timestamp = timestamp(sampleIndex, issues)
    return AccelerometerRawSample(
        sensorTimestamp = timestamp.instant,
        rawSensorTimestampEpochMillis = timestamp.epochMillis,
        rawAccelerometerX = accelerometerX.valueOrIssue(
            sampleIndex,
            RawProbeField.ACCELEROMETER_X,
            issues,
        ),
        rawAccelerometerY = accelerometerY.valueOrIssue(
            sampleIndex,
            RawProbeField.ACCELEROMETER_Y,
            issues,
        ),
        rawAccelerometerZ = accelerometerZ.valueOrIssue(
            sampleIndex,
            RawProbeField.ACCELEROMETER_Z,
            issues,
        ),
    )
}

private fun SamsungSkinTemperaturePointRead.toSample(
    sampleIndex: Int,
    issues: MutableList<RawProbeIssue>,
): SkinTemperatureRawSample {
    val timestamp = timestamp(sampleIndex, issues)
    return SkinTemperatureRawSample(
        sensorTimestamp = timestamp.instant,
        rawSensorTimestampEpochMillis = timestamp.epochMillis,
        rawObjectTemperatureCelsius = objectTemperature.valueOrIssue(
            sampleIndex,
            RawProbeField.OBJECT_TEMPERATURE,
            issues,
        ),
        rawAmbientTemperatureCelsius = ambientTemperature.valueOrIssue(
            sampleIndex,
            RawProbeField.AMBIENT_TEMPERATURE,
            issues,
        ),
        rawStatus = status.valueOrIssue(sampleIndex, RawProbeField.STATUS, issues),
    )
}

private fun SamsungRawPointRead.timestamp(
    sampleIndex: Int,
    issues: MutableList<RawProbeIssue>,
): TimestampRead {
    val epochMillis = sensorTimestampEpochMillis.valueOrIssue(
        sampleIndex,
        RawProbeField.SENSOR_TIMESTAMP,
        issues,
    )
    val instant = epochMillis?.let {
        try {
            Instant.ofEpochMilli(it)
        } catch (error: Exception) {
            issues += RawProbeIssue.ReadFailure(
                sampleIndex,
                RawProbeField.SENSOR_TIMESTAMP,
                error.javaClass.name,
            )
            null
        }
    }
    return TimestampRead(epochMillis, instant)
}

private fun <T> SamsungFieldRead<T>.valueOrIssue(
    sampleIndex: Int,
    field: RawProbeField,
    issues: MutableList<RawProbeIssue>,
): T? = when (this) {
    is SamsungFieldRead.Value -> value
    is SamsungFieldRead.Failure -> {
        issues += RawProbeIssue.ReadFailure(sampleIndex, field, errorClass)
        null
    }
}

private data class TimestampRead(
    val epochMillis: Long?,
    val instant: Instant?,
)

private fun wrongReadType(probe: SensorRawProbe, read: SamsungRawPointRead): Nothing =
    error("Read type ${read.javaClass.name} does not match $probe")
