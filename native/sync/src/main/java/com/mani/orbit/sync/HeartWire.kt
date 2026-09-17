package com.mani.orbit.sync

import com.mani.health.core.model.heart.*
import com.mani.health.integration.samsungsensor.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Original SDK callback boundaries and IBI/status arrays survive screen-off batching. */
data class HeartFrame(override val installation: String, val session: String, override val boot: String, override val bootCount: Int,
    override val receivedElapsed: Long, val offset: Int, val clockUncertain: Boolean, val batch: HeartBeatBatch) : SensorFrame {
    override val at get() = batch.receivedAt.toEpochMilli()
    override val id: String get() = heartId("$installation:$session:${batch.callbackSequence}")
    init {
        uuid(installation); uuid(session); uuid(boot)
        require(bootCount >= 0 && receivedElapsed >= 0 && offset in -64800..64800)
        require(batch.receivedAt.toEpochMilli() >= 0 && batch.points.size <= 4096)
        require(batch.receivedElapsedRealtimeMillis == null || batch.receivedElapsedRealtimeMillis == receivedElapsed)
        require(batch.points.all { (it.rawIbiMillis?.size ?: 0) <= 32768 && (it.rawIbiStatuses?.size ?: 0) <= 32768 })
    }
    override fun readings(): List<WatchReading> = batch.points.mapIndexedNotNull { index, point ->
        // Samsung supplies epoch time, not a per-point boot clock. Ambiguous clocks stay qualified.
        val at = point.sensorTimestamp?.toEpochMilli()?.takeIf { it >= 0 } ?: return@mapIndexedNotNull null
        val delta = at - batch.receivedAt.toEpochMilli()
        if (delta > 1000 || delta < -receivedElapsed) return@mapIndexedNotNull null
        val status = (point.heartRateStatus as? HeartRateStatus.Known)?.meaning
        val quality = when {
            (point.rawHeartRateBpm ?: 0) <= 0 -> "unavailable"
            status == HeartRateStatusMeaning.SUCCESSFUL_MEASUREMENT -> "valid"
            status == HeartRateStatusMeaning.WEARABLE_DETACHED -> "no_contact"
            status == null -> "unknown"
            else -> "unreliable"
        }
        WatchReading(heartId("$id:point:$index"), 1, at, at, offset, "heart",
            point.rawHeartRateBpm?.takeIf { it > 0 }?.toDouble(), "bpm", quality, "instant", boot,
            receivedElapsed + delta, "samsung_sensor", batch.receivedAt.toEpochMilli(), receivedElapsed,
            2000, clockUncertain, bootCount)
    }
    override fun toString() = "HeartFrame(points=${batch.points.size}, issues=${batch.issues.size})"
}

/** Retained source name; packet bytes and identities are unchanged. */
typealias HeartPacket = SensorPacket

internal fun heartId(value: String) = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()

object HeartWire {
    const val PATH = "/orbit/v1/heart-batches"
    const val PART_BYTES = SensorPackets.PART_BYTES
    const val MAX_PARTS = SensorPackets.MAX_PARTS
    const val MAX_FRAME_BYTES = SensorPackets.MAX_FRAME_BYTES
    fun packets(frame: HeartFrame): List<HeartPacket> = SensorPackets.split(frame.installation, frame.id, encodeFrame(frame))
    fun encode(packet: HeartPacket): ByteArray = SensorPackets.encode(packet)
    fun decode(bytes: ByteArray): HeartPacket = SensorPackets.decode(bytes)

    fun encodeFrame(frame: HeartFrame): ByteArray {
        val batch = frame.batch
        val readFailures = batch.issues.filterIsInstance<HeartBeatIssue.ReadFailure>()
        val failures = readFailures.associateBy { it.pointIndex to it.field }
        require(failures.size == readFailures.size)
        val points = batch.points.mapIndexed { index, p ->
            fun field(name: HeartBeatField, value: Any?): Any = failures[index to name]?.let { JSONObject().put("readError", it.errorClass) }
                ?: value ?: JSONObject.NULL
            JSONObject().put("time", field(HeartBeatField.SENSOR_TIMESTAMP, p.rawSensorTimestampEpochMillis))
                .put("heart", field(HeartBeatField.HEART_RATE, p.rawHeartRateBpm))
                .put("status", field(HeartBeatField.HEART_RATE_STATUS, p.heartRateStatus?.rawValue))
                .put("ibi", field(HeartBeatField.IBI, p.rawIbiMillis?.let(::JSONArray)))
                .put("ibiStatus", field(HeartBeatField.IBI_STATUS, p.rawIbiStatuses?.map { it.rawValue }?.let(::JSONArray)))
        }
        val bytes = JSONObject().put("version", 1).put("sdk", "1.4.1").put("installation", frame.installation)
            .put("session", frame.session).put("boot", frame.boot).put("bootCount", frame.bootCount)
            .put("elapsed", frame.receivedElapsed).put("offset", frame.offset).put("clockUncertain", frame.clockUncertain)
            .put("sequence", batch.callbackSequence).put("receivedAt", batch.receivedAt.toString())
            .put("sourceElapsed", batch.receivedElapsedRealtimeMillis ?: JSONObject.NULL)
            .put("points", JSONArray(points)).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FRAME_BYTES) { "Sensor callback exceeds storage limit" }
        // Mapper-derived issues are canonical; unknown issues cannot silently disappear during serialization.
        require(decodeFrame(bytes).batch == batch) { "Unrepresentable sensor callback" }
        return bytes
    }

    fun decodeFrame(bytes: ByteArray): HeartFrame {
        val root = parse(bytes, MAX_FRAME_BYTES); requireWireVersion(root); require(root.get("sdk") == "1.4.1")
        val rows = root.getJSONArray("points"); require(rows.length() <= 4096)
        val reads = List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            fun <T> field(key: String, read: (Any) -> T): SamsungFieldRead<T?> {
                val raw = row.get(key)
                if (raw is JSONObject) return SamsungFieldRead.Failure((raw.get("readError") as String).also {
                    require(it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl))
                })
                return SamsungFieldRead.Value(if (raw == JSONObject.NULL) null else read(raw))
            }
            fun numbers(raw: Any): List<Int> {
                val values = raw as JSONArray; require(values.length() <= 32768)
                return List(values.length()) { integer(values.get(it)) }
            }
            SamsungHeartRatePointRead(field("time", ::long), field("heart", ::integer), field("status", ::integer),
                field("ibi", ::numbers), field("ibiStatus", ::numbers))
        }
        val mapped = mapSamsungHeartRateBatch(long(root.get("sequence")), Instant.parse(root.get("receivedAt") as String), reads)
        val batch = HeartBeatBatch(mapped.callbackSequence, mapped.receivedAt, mapped.points, mapped.issues,
            if (root.get("sourceElapsed") == JSONObject.NULL) null else long(root.get("sourceElapsed")))
        return HeartFrame(root.get("installation") as String, root.get("session") as String, root.get("boot") as String,
            integer(root.get("bootCount")), long(root.get("elapsed")), integer(root.get("offset")),
            root.get("clockUncertain") as Boolean, batch)
    }

    private fun parse(bytes: ByteArray, limit: Int) = sensorJson(bytes, limit)
    private fun long(raw: Any) = sensorLong(raw)
    private fun integer(raw: Any) = sensorInt(raw)
}
