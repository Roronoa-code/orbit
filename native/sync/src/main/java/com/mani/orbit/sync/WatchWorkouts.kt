package com.mani.orbit.sync

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** A Watch-owned session. Phone copies are projections, never a second exercise owner. */
data class WatchWorkout(
    val id: String, val revision: Long, val kind: String, val boot: String,
    val start: Long, val startElapsed: Long, val updatedAt: Long, val updatedElapsed: Long,
    val activeMs: Long, val phase: String, val gps: Boolean,
    val distance: Double? = null, val steps: Long? = null, val energy: Double? = null,
    val elevation: Double? = null, val heart: Double? = null, val heartElapsed: Long? = null,
    val heartQuality: String = "unavailable", val speed: Double? = null, val speedElapsed: Long? = null,
    val endReason: Int? = null, val timeUncertain: Boolean = false,
) {
    val terminal get() = phase == "ended" || phase == "interrupted"
    init {
        uuid(id); uuid(boot)
        require(revision > 0 && kind in KINDS && phase in PHASES)
        require(start >= 0 && updatedAt >= 0 && startElapsed >= 0 && updatedElapsed >= startElapsed)
        require(activeMs in 0..updatedElapsed - startElapsed + 2000)
        require(!gps || kind != "Strength")
        listOf(distance, energy, elevation).forEach { require(it == null || it.isFinite() && it >= 0) }
        require(steps == null || steps >= 0)
        require(heart == null || heart.isFinite() && heart in 1.0..350.0)
        require(speed == null || speed.isFinite() && speed in 0.0..100.0)
        require(heartQuality in setOf("valid", "unknown", "unreliable", "no_contact", "unavailable"))
        require(heart != null || heartQuality != "valid")
        require(heartElapsed == null || heartElapsed in startElapsed..updatedElapsed + 1000)
        require(speedElapsed == null || speedElapsed in startElapsed..updatedElapsed + 1000)
        require(heart == null || heartElapsed != null)
        require(speed == null || speedElapsed != null)
        require(endReason == null || terminal)
    }
    override fun toString() = "WatchWorkout(kind=$kind, phase=$phase, revision=$revision)"
    companion object {
        val KINDS = listOf("Walking", "Running", "Cycling", "Strength")
        val PHASES = setOf("starting", "active", "paused", "ended", "interrupted")
    }
}

data class WatchRoutePoint(val elapsed: Long, val lat: Double, val lon: Double, val accuracy: Double,
                           val altitude: Double?, val breakBefore: Boolean) {
    init {
        require(elapsed >= 0 && lat in -90.0..90.0 && lon in -180.0..180.0)
        require(accuracy.isFinite() && accuracy in 0.0..50.0)
        require(altitude == null || altitude in -12000.0..100000.0)
    }
    override fun toString() = "WatchRoutePoint(elapsed=$elapsed)"
}

class WorkoutPacket(val id: String, val installation: String, val workout: WatchWorkout, points: List<WatchRoutePoint>) {
    val points: List<WatchRoutePoint> = java.util.Collections.unmodifiableList(ArrayList(points))
    init {
        uuid(id); uuid(installation)
        require(points.size <= WorkoutWire.MAX_POINTS && (workout.gps || points.isEmpty()))
        require(points.zipWithNext().all { (a, b) -> a.elapsed < b.elapsed })
        require(points.all { it.elapsed in workout.startElapsed..workout.updatedElapsed + 1000 })
    }
}

/** Incremental route batches prevent re-sending the growing route on every metric update. */
object WorkoutWire {
    const val PATH = "/orbit/v1/workouts"
    const val MAX_POINTS = 64
    fun json(w: WatchWorkout): JSONObject = JSONObject().put("id", w.id).put("revision", w.revision)
        .put("kind", w.kind).put("boot", w.boot).put("start", w.start).put("startElapsed", w.startElapsed)
        .put("updatedAt", w.updatedAt).put("updatedElapsed", w.updatedElapsed).put("activeMs", w.activeMs)
        .put("phase", w.phase).put("gps", w.gps).put("distance", w.distance ?: JSONObject.NULL)
        .put("steps", w.steps ?: JSONObject.NULL).put("energy", w.energy ?: JSONObject.NULL)
        .put("elevation", w.elevation ?: JSONObject.NULL).put("heart", w.heart ?: JSONObject.NULL)
        .put("heartElapsed", w.heartElapsed ?: JSONObject.NULL).put("heartQuality", w.heartQuality)
        .put("speed", w.speed ?: JSONObject.NULL).put("speedElapsed", w.speedElapsed ?: JSONObject.NULL)
        .put("endReason", w.endReason ?: JSONObject.NULL).put("timeUncertain", w.timeUncertain)

    fun pointJson(p: WatchRoutePoint): JSONObject = JSONObject().put("elapsed", p.elapsed).put("lat", p.lat)
        .put("lon", p.lon).put("accuracy", p.accuracy).put("altitude", p.altitude ?: JSONObject.NULL).put("breakBefore", p.breakBefore)

    fun encode(p: WorkoutPacket): ByteArray = JSONObject().put("version", 1).put("id", p.id)
        .put("installation", p.installation).put("workout", json(p.workout)).put("points", JSONArray(p.points.map(::pointJson)))
        .toString().toByteArray(Charsets.UTF_8).also { require(it.size <= ReadingWire.MAX_BYTES) }

    fun decode(bytes: ByteArray): WorkoutPacket {
        require(bytes.isNotEmpty() && bytes.size <= ReadingWire.MAX_BYTES)
        val tokens = JSONTokener(Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString())
        val root = tokens.nextValue() as? JSONObject ?: error("Expected workout packet")
        require(tokens.nextClean() == '\u0000')
        requireWireVersion(root)
        val rows = root.getJSONArray("points"); require(rows.length() <= MAX_POINTS)
        return WorkoutPacket(root.text("id"), root.text("installation"), read(root.getJSONObject("workout")),
            List(rows.length()) { point(rows.getJSONObject(it)) })
    }

    fun read(r: JSONObject): WatchWorkout = WatchWorkout(r.text("id"), r.whole("revision"), r.text("kind"), r.text("boot"),
        r.whole("start"), r.whole("startElapsed"), r.whole("updatedAt"), r.whole("updatedElapsed"), r.whole("activeMs"),
        r.text("phase"), r.flag("gps"), r.amount("distance"), r.optionalWhole("steps"), r.amount("energy"), r.amount("elevation"),
        r.amount("heart"), r.optionalWhole("heartElapsed"), r.text("heartQuality"), r.amount("speed"), r.optionalWhole("speedElapsed"),
        r.optionalWhole("endReason")?.also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }?.toInt(), r.flag("timeUncertain"))
    fun point(r: JSONObject) = WatchRoutePoint(r.whole("elapsed"), requireNotNull(r.amount("lat")),
        requireNotNull(r.amount("lon")), requireNotNull(r.amount("accuracy")), r.amount("altitude"), r.flag("breakBefore"))

    private fun JSONObject.text(key: String) = get(key) as? String ?: error("Expected text")
    private fun JSONObject.flag(key: String) = get(key) as? Boolean ?: error("Expected boolean")
    private fun JSONObject.whole(key: String): Long = get(key).let { require(it is Long || it is Int); (it as Number).toLong() }
    private fun JSONObject.optionalWhole(key: String): Long? = if (get(key) == JSONObject.NULL) null else whole(key)
    private fun JSONObject.amount(key: String): Double? = if (get(key) == JSONObject.NULL) null else
        (get(key) as? Number)?.toDouble()?.also { require(it.isFinite()) } ?: error("Expected number")
}
