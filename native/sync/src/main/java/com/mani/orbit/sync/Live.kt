package com.mani.orbit.sync

import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Live heart rate and steps while Orbit is open on the phone.
 *
 * The phone holds a short lease on the watch's attention and renews it while it is on screen. While the
 * lease holds, the watch streams heart rate and pushes each new heart reading and step count the moment
 * it is captured, instead of waiting for the next queued transfer. Live updates are display-only and
 * best effort: every reading still reaches the phone's journal through the acknowledged readings path,
 * so a lost update costs freshness, never data.
 */
data class LiveRequest(val until: Long) {
    init { require(until >= 0) }
    /** A lease of zero releases the watch at once. */
    val release get() = until == 0L
}

/**
 * What the watch can offer right now, its newest valid heart reading, and its own step count since
 * midnight with the time it was counted. Either reading may be missing.
 */
data class LiveUpdate(val state: String, val bpm: Double?, val at: Long?, val sent: Long,
                      val steps: Double? = null, val stepsAt: Long? = null) {
    init {
        require(state in STATES)
        require((bpm == null) == (at == null))
        bpm?.let { require(it.isFinite() && it > 0 && it < 300) }
        at?.let { require(it > 0) }
        require((steps == null) == (stepsAt == null))
        steps?.let { require(it.isFinite() && it >= 0 && it < 1_000_000) }
        stepsAt?.let { require(it > 0) }
        require(sent > 0)
    }
    companion object {
        /**
         * streaming: a live heart recording is running on the watch; a reading every second or two.
         * passive: background collection is on; a reading whenever the watch measures.
         * needs_access: Orbit on the watch has not been allowed heart or background health access.
         * off: background collection is switched off in Orbit on the watch.
         */
        val STATES = setOf("streaming", "passive", "needs_access", "off")
    }
}

object LiveWire {
    const val PATH = "/orbit/v1/live"
    const val MAX_BYTES = 512
    /** How long one request holds the watch, and how often the phone renews it while it is on screen. */
    const val LEASE_MS = 45_000L
    const val RENEW_MS = 20_000L

    fun encode(request: LiveRequest): ByteArray = JSONObject().put("version", 1).put("kind", "request")
        .put("until", request.until).toString().toByteArray(Charsets.UTF_8)

    fun encode(update: LiveUpdate): ByteArray = JSONObject().put("version", 1).put("kind", "update")
        .put("state", update.state).put("bpm", update.bpm ?: JSONObject.NULL).put("at", update.at ?: JSONObject.NULL)
        .put("steps", update.steps ?: JSONObject.NULL).put("stepsAt", update.stepsAt ?: JSONObject.NULL)
        .put("sent", update.sent).toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }

    fun decodeRequest(bytes: ByteArray): LiveRequest {
        val root = parse(bytes, "request")
        return LiveRequest(long(root.get("until")))
    }

    fun decodeUpdate(bytes: ByteArray): LiveUpdate {
        val root = parse(bytes, "update")
        val state = root.get("state") as? String ?: error("Expected state")
        // A state this build does not know is a newer watch, not a malformed message.
        if (state !in LiveUpdate.STATES) throw UnsupportedWire("command")
        fun number(key: String) = root.get(key).let { if (it == JSONObject.NULL) null else (it as? Number)?.toDouble() ?: error("Expected $key") }
        fun time(key: String) = root.get(key).let { if (it == JSONObject.NULL) null else long(it) }
        // Steps came later in this family: a watch that sends none simply has none to offer.
        val steps = if (root.has("steps")) number("steps") else null
        val stepsAt = if (root.has("stepsAt")) time("stepsAt") else null
        return LiveUpdate(state, number("bpm"), time("at"), long(root.get("sent")), steps, stepsAt)
    }

    private fun parse(bytes: ByteArray, kind: String): JSONObject {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        val tokens = JSONTokener(Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString())
        val root = tokens.nextValue() as? JSONObject ?: error("Expected a live message")
        require(tokens.nextClean() == '\u0000')
        requireWireVersion(root)
        require(root.get("kind") == kind) { "Expected a live $kind" }
        return root
    }

    private fun long(raw: Any): Long = when (raw) {
        is Int -> raw.toLong()
        is Long -> raw
        else -> error("Expected a whole number")
    }
}
