package com.mani.orbit.sync

import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Samsung's running prediction, linked to the original workout. Never a hydration measurement. */
data class SweatEstimate(
    val installation: String, val workout: String, val boot: String, val startElapsed: Long,
    val revision: Long, val phase: String, val at: Long, val elapsed: Long,
    val profile: MeasurementProfile?, val status: Int? = null, val rawMl: Double? = null,
    val reason: String? = null, val timeUncertain: Boolean = false, val sensorAt: Long? = null,
) {
    val terminal get() = phase == "complete" || phase == "unavailable"
    val millilitres get() = rawMl.takeIf { phase == "complete" && status == 0 }
    val id get() = UUID.nameUUIDFromBytes("sweat:$installation:$workout:$revision".toByteArray(Charsets.UTF_8)).toString()
    init {
        uuid(installation); uuid(workout); uuid(boot)
        require(startElapsed >= 0 && elapsed >= startElapsed && at >= 0 && revision in 1..3)
        require(phase in setOf("tracking", "pending", "complete", "unavailable"))
        require(phase != "tracking" || revision == 1L)
        require(phase != "pending" || revision == 2L)
        require(phase != "complete" || revision == 3L)
        require(rawMl == null || rawMl.isFinite() && rawMl >= 0)
        require(reason == null || reason.matches(Regex("[A-Z0-9_]{1,64}")))
        require(phase == "unavailable" || profile?.let { it.birth != null && it.sex != null && it.heightCm != null && it.weightKg != null } == true)
        when (phase) {
            "complete" -> require(status == 0 && rawMl != null && reason == null && !timeUncertain)
            "unavailable" -> require(reason != null && (status == null || reason == "SDK_STATUS"))
            else -> require(status == null && rawMl == null && reason == null)
        }
        require(status != 0 || phase == "complete")
        require(rawMl == null || status != null)
        require((status == null) == (sensorAt == null))
    }
    override fun toString() = "SweatEstimate(phase=$phase, revision=$revision)"
}

object SweatWire {
    const val PATH = "/orbit/v1/workout-sweat"
    fun encode(r: SweatEstimate): ByteArray = JSONObject().put("version", 1).put("source", "samsung_sensor")
        .put("installation", r.installation).put("workout", r.workout).put("boot", r.boot).put("startElapsed", r.startElapsed)
        .put("revision", r.revision).put("phase", r.phase).put("at", r.at).put("elapsed", r.elapsed)
        .put("profile", r.profile?.let { JSONObject(String(MeasurementProfileWire.encode(it), Charsets.UTF_8)) } ?: JSONObject.NULL)
        .put("status", r.status ?: JSONObject.NULL).put("rawMl", r.rawMl ?: JSONObject.NULL)
        .put("reason", r.reason ?: JSONObject.NULL).put("timeUncertain", r.timeUncertain)
        .put("sensorAt", r.sensorAt ?: JSONObject.NULL)
        .toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): SweatEstimate {
        require(bytes.isNotEmpty() && bytes.size <= 4096)
        val tokens = JSONTokener(Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString())
        val r = tokens.nextValue() as? JSONObject ?: error("Expected sweat estimate")
        require(tokens.nextClean() == '\u0000'); requireWireVersion(r)
        require(r.get("source") == "samsung_sensor")
        fun whole(key: String) = r.get(key).let { require(it is Long || it is Int); (it as Number).toLong() }
        fun text(key: String) = r.get(key) as String
        val status = if (r.get("status") == JSONObject.NULL) null else whole("status").also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
        return SweatEstimate(text("installation"), text("workout"), text("boot"), whole("startElapsed"), whole("revision"),
            text("phase"), whole("at"), whole("elapsed"), if (r.get("profile") == JSONObject.NULL) null else
                MeasurementProfileWire.decode(r.getJSONObject("profile").toString().toByteArray(Charsets.UTF_8)), status,
            if (r.get("rawMl") == JSONObject.NULL) null else (r.get("rawMl") as Number).toDouble(),
            if (r.get("reason") == JSONObject.NULL) null else text("reason"), r.get("timeUncertain") as Boolean,
            if (r.get("sensorAt") == JSONObject.NULL) null else whole("sensorAt"))
    }
}

/** Shared qualification wording; neither app turns Samsung's failed zero into a result. */
fun SweatEstimate.description(): String = when {
    phase == "tracking" -> "Available after your run"
    phase == "pending" -> "Waiting for a final estimate"
    millilitres != null -> "Samsung running estimate · not fluid intake"
    reason == "PROFILE_REQUIRED" -> "Complete your profile on your phone for future runs"
    reason == "PROFILE_UNAVAILABLE" -> "Your saved profile could not be read for this run"
    reason == "PERMISSION_REQUIRED" -> "Allow sensor access for future runs"
    reason == "UNSUPPORTED" || reason == "CADENCE_UNSUPPORTED" -> "Not supported on this Watch"
    reason == "INTERRUPTED" -> "Recording was interrupted; no final estimate"
    reason == "CLOCK_CHANGED" -> "The clock changed; no reliable estimate"
    reason == "SDK_POLICY_REJECTED" -> "Samsung sensor access is not authorized"
    reason == "SDK_STATUS" && status != null && status in 1..127 -> buildList {
        if (status and 1 != 0) add("run under 5 min")
        if (status and 2 != 0) add("distance or energy too low")
        if (status and 4 != 0) add("estimate below 100 ml")
        if (status and 8 != 0) add("heart-rate gaps")
        if (status and 16 != 0) add("cadence gaps")
        if (status and 32 != 0) add("estimator did not initialize")
        if (status and 64 != 0) add("cadence too low")
    }.joinToString(" · ")
    reason == "SDK_STATUS" -> "Samsung could not estimate this run (status $status)"
    else -> "No final estimate from Samsung"
}
