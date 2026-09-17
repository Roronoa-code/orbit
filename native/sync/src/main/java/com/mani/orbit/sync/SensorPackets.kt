package com.mani.orbit.sync

import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Common transport/clock boundary for original heart and raw callbacks. Wire formats stay versioned. */
interface SensorFrame {
    val installation: String
    val id: String
    val boot: String
    val bootCount: Int
    val receivedElapsed: Long
    val at: Long
    fun readings(): List<WatchReading>
}

class SensorPacket(val installation: String, val frame: String, val index: Int, val parts: Int,
    val digest: String, data: ByteArray) {
    private val payload = data.copyOf()
    val data get() = payload.copyOf()
    val id get() = heartId("$installation:$frame:part:$index")
    init {
        uuid(installation); uuid(frame)
        require(parts in 1..SensorPackets.MAX_PARTS && index in 0 until parts)
        require(digest.matches(Regex("[0-9a-f]{64}")))
        require(payload.size in 1..SensorPackets.PART_BYTES && (index == parts - 1 || payload.size == SensorPackets.PART_BYTES))
    }
    override fun toString() = "SensorPacket(index=$index, parts=$parts)"
}

object SensorPackets {
    const val PART_BYTES = 24 * 1024
    const val MAX_PARTS = 64
    // ponytail: 1.5 MiB per original callback; stop visibly above this ceiling, never truncate.
    const val MAX_FRAME_BYTES = PART_BYTES * MAX_PARTS
    fun split(installation: String, frame: String, bytes: ByteArray): List<SensorPacket> {
        require(bytes.size in 1..MAX_FRAME_BYTES)
        val digest = ReadingWire.digest(bytes); val parts = (bytes.size + PART_BYTES - 1) / PART_BYTES
        return List(parts) { i -> SensorPacket(installation, frame, i, parts, digest,
            bytes.copyOfRange(i * PART_BYTES, minOf(bytes.size, (i + 1) * PART_BYTES))) }
    }
    fun encode(packet: SensorPacket): ByteArray = JSONObject().put("version", 1)
        .put("installation", packet.installation).put("frame", packet.frame).put("index", packet.index)
        .put("parts", packet.parts).put("sha256", packet.digest)
        .put("data", Base64.getEncoder().encodeToString(packet.data)).toString().toByteArray(Charsets.UTF_8)
        .also { require(it.size <= ReadingWire.MAX_BYTES) }
    fun decode(bytes: ByteArray): SensorPacket {
        val root = sensorJson(bytes, ReadingWire.MAX_BYTES); requireWireVersion(root)
        return SensorPacket(root.get("installation") as String, root.get("frame") as String,
            sensorInt(root.get("index")), sensorInt(root.get("parts")), root.get("sha256") as String,
            Base64.getDecoder().decode(root.get("data") as String))
    }
}

internal fun sensorJson(bytes: ByteArray, limit: Int): JSONObject {
    require(bytes.isNotEmpty() && bytes.size <= limit)
    val tokens = JSONTokener(Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString())
    val root = tokens.nextValue() as? JSONObject ?: error("Expected sensor packet")
    require(tokens.nextClean() == '\u0000'); return root
}
internal fun sensorLong(raw: Any): Long { require(raw is Int || raw is Long); return (raw as Number).toLong() }
internal fun sensorInt(raw: Any): Int = sensorLong(raw).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
