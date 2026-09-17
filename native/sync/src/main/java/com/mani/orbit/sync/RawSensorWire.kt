package com.mani.orbit.sync

import com.mani.health.integration.samsungsensor.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class RawSensorFrame(override val installation: String, val session: String, override val boot: String,
    override val bootCount: Int, override val receivedElapsed: Long, val offset: Int, val clockUncertain: Boolean,
    val chunk: RawProbeChunk) : SensorFrame {
    override val id get() = heartId("raw:$installation:$session:${chunk.callbackSequence}")
    override val at get() = chunk.receivedAt.toEpochMilli()
    init {
        uuid(installation); uuid(session); uuid(boot)
        require(bootCount >= 0 && receivedElapsed >= 0 && offset in -64800..64800 && at >= 0)
        require(chunk.samples.size <= 16384)
        require(chunk.receivedElapsedRealtimeMillis == null || chunk.receivedElapsedRealtimeMillis == receivedElapsed)
    }
    override fun readings(): List<WatchReading> = chunk.samples.mapIndexedNotNull { index, sample ->
        if (sample !is SkinTemperatureRawSample) return@mapIndexedNotNull null
        val timestamp = sample.sensorTimestamp?.toEpochMilli()?.takeIf { it >= 0 } ?: return@mapIndexedNotNull null
        val delta = timestamp - at
        if (delta > 1000 || delta < -receivedElapsed) return@mapIndexedNotNull null
        val temperature = sample.rawObjectTemperatureCelsius?.takeIf { it.isFinite() }?.toDouble()
        val quality = when { temperature == null -> "unavailable"; sample.rawStatus == 0 -> "valid"
            sample.rawStatus == -1 -> "unreliable"; else -> "unknown" }
        WatchReading(heartId("$id:point:$index"), 1, timestamp, timestamp, offset, "skinTemperature", temperature,
            "celsius", quality, "instant", boot, receivedElapsed + delta, "samsung_sensor", at, receivedElapsed,
            2000, clockUncertain, bootCount)
    }
    override fun toString() = "RawSensorFrame(probe=${chunk.probe}, samples=${chunk.samples.size})"
}

object RawSensorWire {
    const val PATH = "/orbit/v1/raw-sensors"
    fun packets(frame: RawSensorFrame) = SensorPackets.split(frame.installation, frame.id, encodeFrame(frame))
    fun encode(packet: SensorPacket) = SensorPackets.encode(packet)
    fun decode(bytes: ByteArray) = SensorPackets.decode(bytes)
    fun encodeFrame(frame: RawSensorFrame): ByteArray {
        val chunk = frame.chunk
        val failures = chunk.issues.filterIsInstance<RawProbeIssue.ReadFailure>().associateBy { it.sampleIndex to it.field }
        require(failures.size == chunk.issues.count { it is RawProbeIssue.ReadFailure })
        val rows = chunk.samples.mapIndexed { index, sample ->
            fun field(key: RawProbeField, value: Any?): Any = failures[index to key]?.let {
                JSONObject().put("readError", it.errorClass)
            } ?: value ?: JSONObject.NULL
            val row = JSONObject().put("time", field(RawProbeField.SENSOR_TIMESTAMP, sample.rawSensorTimestampEpochMillis))
            when (sample) {
                is PpgRawSample -> row.put("green", field(RawProbeField.PPG_GREEN, sample.rawPpgGreen))
                    .put("greenStatus", field(RawProbeField.GREEN_STATUS, sample.rawGreenStatus))
                    .put("ir", field(RawProbeField.PPG_IR, sample.rawPpgIr)).put("irStatus", field(RawProbeField.IR_STATUS, sample.rawIrStatus))
                    .put("red", field(RawProbeField.PPG_RED, sample.rawPpgRed)).put("redStatus", field(RawProbeField.RED_STATUS, sample.rawRedStatus))
                is AccelerometerRawSample -> row.put("x", field(RawProbeField.ACCELEROMETER_X, sample.rawAccelerometerX))
                    .put("y", field(RawProbeField.ACCELEROMETER_Y, sample.rawAccelerometerY)).put("z", field(RawProbeField.ACCELEROMETER_Z, sample.rawAccelerometerZ))
                // Preserve every float bit, including non-finite vendor values; projections qualify separately.
                is SkinTemperatureRawSample -> row.put("objectBits", field(RawProbeField.OBJECT_TEMPERATURE, sample.rawObjectTemperatureCelsius?.toRawBits()))
                    .put("ambientBits", field(RawProbeField.AMBIENT_TEMPERATURE, sample.rawAmbientTemperatureCelsius?.toRawBits()))
                    .put("status", field(RawProbeField.STATUS, sample.rawStatus))
            }
        }
        val bytes = JSONObject().put("version", 1).put("sdk", "1.4.1").put("installation", frame.installation)
            .put("session", frame.session).put("boot", frame.boot).put("bootCount", frame.bootCount)
            .put("elapsed", frame.receivedElapsed).put("offset", frame.offset).put("clockUncertain", frame.clockUncertain)
            .put("probe", chunk.probe.name).put("sequence", chunk.callbackSequence).put("receivedAt", chunk.receivedAt.toString())
            .put("sourceElapsed", chunk.receivedElapsedRealtimeMillis ?: JSONObject.NULL).put("samples", JSONArray(rows))
            .toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= SensorPackets.MAX_FRAME_BYTES) { "Sensor callback exceeds storage limit" }
        require(decodeFrame(bytes).chunk == chunk) { "Unrepresentable sensor callback" }
        return bytes
    }
    fun decodeFrame(bytes: ByteArray): RawSensorFrame {
        val r = sensorJson(bytes, SensorPackets.MAX_FRAME_BYTES); requireWireVersion(r); require(r.get("sdk") == "1.4.1")
        val probe = SensorRawProbe.valueOf(r.get("probe") as String)
        val rows = r.getJSONArray("samples"); require(rows.length() <= 16384)
        val reads = List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            fun <T> field(key: String, read: (Any) -> T): SamsungFieldRead<T?> {
                val raw = row.get(key)
                if (raw is JSONObject) return SamsungFieldRead.Failure((raw.get("readError") as String).also {
                    require(it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl))
                })
                return SamsungFieldRead.Value(if (raw == JSONObject.NULL) null else read(raw))
            }
            val timestamp = field("time", ::sensorLong)
            when (probe) {
                SensorRawProbe.PPG_CONTINUOUS -> SamsungPpgPointRead(timestamp, field("green", ::sensorInt), field("greenStatus", ::sensorInt),
                    field("ir", ::sensorInt), field("irStatus", ::sensorInt), field("red", ::sensorInt), field("redStatus", ::sensorInt))
                SensorRawProbe.ACCELEROMETER_CONTINUOUS -> SamsungAccelerometerPointRead(timestamp, field("x", ::sensorInt), field("y", ::sensorInt), field("z", ::sensorInt))
                SensorRawProbe.SKIN_TEMPERATURE_CONTINUOUS -> SamsungSkinTemperaturePointRead(timestamp,
                    field("objectBits") { Float.fromBits(sensorInt(it)) }, field("ambientBits") { Float.fromBits(sensorInt(it)) }, field("status", ::sensorInt))
            }
        }
        val chunk = mapSamsungRawProbeChunk(probe, sensorLong(r.get("sequence")), Instant.parse(r.get("receivedAt") as String), reads,
            if (r.get("sourceElapsed") == JSONObject.NULL) null else sensorLong(r.get("sourceElapsed")))
        return RawSensorFrame(r.get("installation") as String, r.get("session") as String, r.get("boot") as String,
            sensorInt(r.get("bootCount")), sensorLong(r.get("elapsed")), sensorInt(r.get("offset")), r.get("clockUncertain") as Boolean, chunk)
    }
}
