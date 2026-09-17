package com.mani.orbit.sync

import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

data class WorkoutControl(val id: String, val installation: String, val workout: String, val boot: String,
                          val phase: String, val action: String, val stage: String = "request", val token: String? = null) {
    init {
        listOf(id, installation, workout, boot).forEach { uuid(it) }
        require(phase in setOf("active", "paused"))
        require(action == "finish" || action == if (phase == "active") "pause" else "resume")
        require(stage in setOf("request", "ready", "commit", "accepted", "rejected"))
        require((token != null) == (stage in setOf("ready", "commit", "accepted")))
        token?.let { uuid(it) }
    }
    fun sameRequest(other: WorkoutControl) = copy(stage = "request", token = null) == other.copy(stage = "request", token = null)
    val desiredPhase get() = when (action) { "pause" -> "paused"; "resume" -> "active"; else -> "ended" }
}

object WorkoutControlWire {
    const val PATH = "/orbit/v1/workout-control"
    const val MAX_BYTES = 2048
    const val OFFER_MS = 8_000L
    const val REQUEST_MS = 20_000L
    fun encode(c: WorkoutControl): ByteArray = JSONObject().put("version", 1).put("id", c.id)
        .put("installation", c.installation).put("workout", c.workout).put("boot", c.boot)
        .put("phase", c.phase).put("action", c.action).put("stage", c.stage).put("token", c.token ?: JSONObject.NULL)
        .toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
    fun decode(bytes: ByteArray): WorkoutControl {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        val tokens = JSONTokener(Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString())
        val root = tokens.nextValue() as? JSONObject ?: error("Expected control")
        require(tokens.nextClean() == '\u0000')
        requireWireVersion(root)
        fun text(key: String) = root.get(key) as? String ?: error("Expected text")
        if (text("action") !in setOf("pause", "resume", "finish") ||
            text("stage") !in setOf("request", "ready", "commit", "accepted", "rejected")) throw UnsupportedWire("command")
        return WorkoutControl(text("id"), text("installation"), text("workout"), text("boot"), text("phase"),
            text("action"), text("stage"), if (root.get("token") == JSONObject.NULL) null else text("token"))
    }
}

/** One live, single-use offer. Process death revokes it; nothing can replay from disk after reconnect. */
class WorkoutControlGate {
    private var offer: WorkoutControl? = null
    private var issued = 0L
    fun invalidate() { offer = null }
    private fun validate(c: WorkoutControl, w: WatchWorkout, installation: String, boot: String) {
        require(c.installation == installation && c.workout == w.id && c.boot == boot && c.boot == w.boot)
        require(!w.terminal && c.phase == w.phase)
    }
    fun prepare(c: WorkoutControl, w: WatchWorkout, installation: String, boot: String, now: Long): WorkoutControl {
        require(c.stage == "request" && now >= 0)
        validate(c, w, installation, boot)
        return c.copy(stage = "ready", token = UUID.randomUUID().toString()).also { offer = it; issued = now }
    }
    fun consume(c: WorkoutControl, w: WatchWorkout, installation: String, boot: String, now: Long) {
        val ready = requireNotNull(offer) { "Control expired" }
        require(c.stage == "commit" && c.sameRequest(ready) && c.token == ready.token)
        invalidate()
        require(now >= issued && now - issued <= WorkoutControlWire.OFFER_MS) { "Control expired" }
        validate(c, w, installation, boot)
    }
}
