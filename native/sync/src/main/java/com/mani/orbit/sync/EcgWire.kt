package com.mani.orbit.sync

import com.mani.health.core.protocol.EcgChunk
import com.mani.health.core.protocol.EcgChunkCodec
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** The raw Samsung callback format is reused unchanged inside Orbit's authenticated transport. */
class EcgPacket(val id: String, val installation: String, val recording: String, val boot: String,
    val start: Long, val index: Int, val chunk: EcgChunk? = null, val phase: String = "recording",
    val expectedChunks: Int? = null, val expectedSamples: Int? = null,
    val startedElapsedMs: Long? = null, val bootCount: Int? = null) {
    init {
        listOf(id, installation, recording, boot).forEach(::uuid); require(start >= 0)
        require(startedElapsedMs == null || startedElapsedMs >= 0)
        require(bootCount == null || bootCount >= 0)
        if (chunk != null) {
            require(index in 0..31 && phase == "recording" && expectedChunks == null && expectedSamples == null)
            require(chunk.requestId == recording && chunk.bootId == boot)
            require(startedElapsedMs == null || chunk.callbacks.all { startedElapsedMs <= it.receivedElapsedNanos / 1_000_000 })
        } else {
            require(index == -1 && phase in setOf("complete", "cancelled", "failed", "interrupted"))
            require(expectedChunks != null && expectedChunks in 0..32 && expectedSamples != null && expectedSamples in 0..16000)
            require(expectedSamples in expectedChunks..expectedChunks * EcgChunkCodec.MAX_POINTS)
            require(phase != "complete" || expectedSamples > 0)
        }
    }
    override fun toString() = "EcgPacket(index=$index, phase=$phase)"
}

object EcgWire {
    const val PATH = "/orbit/v1/ecg"
    fun encode(packet: EcgPacket): ByteArray = JSONObject().put("version", 1).put("source", "samsung_sensor")
        .put("id", packet.id).put("installation", packet.installation).put("recording", packet.recording)
        .put("boot", packet.boot).put("start", packet.start).put("index", packet.index).put("phase", packet.phase)
        .put("chunks", packet.expectedChunks ?: JSONObject.NULL).put("samples", packet.expectedSamples ?: JSONObject.NULL)
        .put("raw", packet.chunk?.let { Base64.getEncoder().encodeToString(EcgChunkCodec.encode(it)) } ?: JSONObject.NULL)
        .also { row -> packet.startedElapsedMs?.let { row.put("startedElapsedMs", it) }; packet.bootCount?.let { row.put("bootCount", it) } }
        .toString().toByteArray(Charsets.UTF_8).also { require(it.size <= ReadingWire.MAX_BYTES) }

    fun decode(bytes: ByteArray): EcgPacket {
        require(bytes.isNotEmpty() && bytes.size <= ReadingWire.MAX_BYTES)
        val tokens = JSONTokener(Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString())
        val root = tokens.nextValue() as? JSONObject ?: error("Expected ECG packet")
        require(tokens.nextClean() == '\u0000'); requireWireVersion(root)
        require(root.get("source") == "samsung_sensor")
        fun integer(name: String): Long { val value = root.get(name); require(value is Int || value is Long); return (value as Number).toLong() }
        fun optional(name: String): Int? = if (root.isNull(name)) null else Math.toIntExact(integer(name))
        val raw = if (root.isNull("raw")) null else {
            val text = root.get("raw") as String
            val decoded = Base64.getDecoder().decode(text)
            require(Base64.getEncoder().encodeToString(decoded) == text)
            EcgChunkCodec.decode(decoded)
        }
        return EcgPacket(root.get("id") as String, root.get("installation") as String, root.get("recording") as String,
            root.get("boot") as String, integer("start"), Math.toIntExact(integer("index")), raw, root.get("phase") as String,
            optional("chunks"), optional("samples"), if (root.has("startedElapsedMs")) integer("startedElapsedMs") else null, readingBootCount(root))
    }
}
