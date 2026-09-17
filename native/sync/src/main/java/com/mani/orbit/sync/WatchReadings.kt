package com.mani.orbit.sync

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID

/** Source identity and capture time survive retries; a revised reading keeps the same id. */
data class WatchReading(
    val id: String,
    val revision: Long,
    val start: Long,
    val end: Long,
    val offsetSeconds: Int,
    val metric: String,
    val value: Double?,
    val unit: String,
    val quality: String,
    val semantics: String,
    val boot: String,
    val elapsedMs: Long,
    val source: String = "health_services",
    val capturedAt: Long = end,
    val anchorElapsedMs: Long = elapsedMs,
    val uncertaintyMs: Long = 0,
    val timeUncertain: Boolean = false,
    val bootCount: Int? = null,
) {
    init {
        uuid(id); uuid(boot)
        require(bootCount == null || bootCount >= 0)
        require(revision > 0 && start >= 0 && end >= start && elapsedMs >= 0 && offsetSeconds in -64800..64800)
        require(UNITS[metric] == unit)
        require(source in setOf("health_services", "android_battery", "samsung_sensor"))
        require(capturedAt >= 0 && anchorElapsedMs >= 0 && uncertaintyMs in 0..60_000)
        require(elapsedMs <= anchorElapsedMs + 1000)
        require(quality in setOf("valid", "unreliable", "no_contact", "unavailable", "unknown"))
        require(value == null && quality != "valid" || value != null && value.isFinite())
        if (value != null) {
            if (metric != "skinTemperature") require(value >= 0)
            if (metric in setOf("oxygen", "fat", "battery")) require(value <= 100)
            if (metric == "heart" && quality == "valid") require(value > 0)
        }
        require(semantics in setOf("instant", "interval", "daily", "session"))
        if (metric !in setOf("steps", "distance", "energy", "floors")) require(semantics == "instant")
    }
    override fun toString() = "WatchReading(metric=$metric, quality=$quality, revision=$revision)"
    companion object {
        val UNITS = mapOf("heart" to "bpm", "steps" to "count", "distance" to "m", "energy" to "kcal",
            "floors" to "count", "oxygen" to "%", "weight" to "kg", "fat" to "%", "lean" to "kg",
            "muscle" to "kg", "skinTemperature" to "celsius", "battery" to "%", "ibi" to "ms")
    }
}

class ReadingBatch(val id: String, val installation: String, readings: List<WatchReading>) {
    val readings: List<WatchReading> = java.util.Collections.unmodifiableList(ArrayList(readings))
    init {
        uuid(id); uuid(installation)
        require(readings.size in 1..ReadingWire.MAX_READINGS && readings.map { it.id }.distinct().size == readings.size)
    }
    override fun toString() = "ReadingBatch(count=${readings.size})"
}

/** Small, bounded messages. Raw waveforms and workout state use their own payloads. */
object ReadingWire {
    const val PATH = "/orbit/v1/readings"
    const val ACK_PATH = "/orbit/v1/readings-ack"
    const val MAX_BYTES = 48 * 1024
    const val MAX_READINGS = 64
    const val PHONE_CAPABILITY = "orbit_phone_v1"
    const val WATCH_CAPABILITY = "orbit_watch_v1"

    fun encode(batch: ReadingBatch): ByteArray = JSONObject().put("version", 1).put("id", batch.id)
        .put("installation", batch.installation).put("readings", JSONArray(batch.readings.map(::json)))
        .toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }

    fun decode(bytes: ByteArray): ReadingBatch {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val tokens = JSONTokener(text)
        val root = tokens.nextValue() as? JSONObject ?: throw IllegalArgumentException("Expected batch object")
        require(tokens.nextClean() == '\u0000')
        requireWireVersion(root)
        val rows = root.getJSONArray("readings")
        require(rows.length() in 1..MAX_READINGS)
        return ReadingBatch(root.getString("id"), root.getString("installation"), List(rows.length()) { read(rows.getJSONObject(it)) })
    }

    fun json(value: WatchReading): JSONObject = JSONObject().put("id", value.id).put("revision", value.revision)
        .put("start", value.start).put("end", value.end).put("offset", value.offsetSeconds).put("metric", value.metric)
        .put("value", value.value ?: JSONObject.NULL).put("unit", value.unit).put("quality", value.quality)
        .put("semantics", value.semantics).put("boot", value.boot).put("elapsedMs", value.elapsedMs)
        .put("source", value.source).put("capturedAt", value.capturedAt).put("anchorElapsedMs", value.anchorElapsedMs)
        .put("uncertaintyMs", value.uncertaintyMs).put("timeUncertain", value.timeUncertain)
        .also { row -> value.bootCount?.let { row.put("bootCount", it) } }

    private fun read(row: JSONObject): WatchReading {
        for (key in listOf("revision", "start", "end", "offset", "elapsedMs", "capturedAt", "anchorElapsedMs", "uncertaintyMs")) {
            val n = row.get(key)
            require(n is Int || n is Long) { "Expected integer" }
        }
        require(row.isNull("value") || row.get("value") is Number)
        require(row.get("timeUncertain") is Boolean)
        val offset = row.getLong("offset")
        require(offset in -64800L..64800L)
        return WatchReading(row.getString("id"), row.getLong("revision"), row.getLong("start"), row.getLong("end"),
            offset.toInt(), row.getString("metric"), if (row.isNull("value")) null else row.getDouble("value"),
            row.getString("unit"), row.getString("quality"), row.getString("semantics"), row.getString("boot"), row.getLong("elapsedMs"),
            row.getString("source"), row.getLong("capturedAt"), row.getLong("anchorElapsedMs"), row.getLong("uncertaintyMs"), row.getBoolean("timeUncertain"), readingBootCount(row))
    }

    fun encodeReceipt(receipt: ReadingReceipt): ByteArray = JSONObject().put("version", 1)
        .put("batch", receipt.batch).put("hash", receipt.hash).toString().toByteArray(Charsets.UTF_8)

    fun decodeReceipt(bytes: ByteArray): ReadingReceipt {
        require(bytes.isNotEmpty() && bytes.size <= 512)
        val tokens = JSONTokener(Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString())
        val row = tokens.nextValue() as? JSONObject ?: throw IllegalArgumentException("Expected receipt")
        require(tokens.nextClean() == '\u0000' && row.get("version") == 1)
        val batch = row.getString("batch").also(::uuid)
        val hash = row.getString("hash").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        return ReadingReceipt(batch, hash)
    }
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

internal fun uuid(value: String) { require(value.length == 36 && UUID.fromString(value).toString() == value) }

/** Additive v1 metadata; absent on older senders, never guessed from an arrival or wall clock. */
internal fun readingBootCount(row: JSONObject): Int? {
    if (!row.has("bootCount")) return null
    val value = row.get("bootCount")
    require(value is Int || value is Long) { "Expected integer boot count" }
    val count = (value as Number).toLong()
    require(count in 0..Int.MAX_VALUE.toLong()) { "Invalid boot count" }
    return count.toInt()
}
