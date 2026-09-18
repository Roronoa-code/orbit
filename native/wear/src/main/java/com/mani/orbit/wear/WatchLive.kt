package com.mani.orbit.wear

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.HealthServices
import com.google.android.gms.wearable.Wearable
import com.mani.orbit.sync.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * The watch's half of live heart rate and steps: see [LiveWire].
 *
 * The phone's lease is kept in preferences rather than memory, because the request arrives in one
 * short-lived service and the readings it asks for are captured in another, possibly in a fresh process.
 */
internal object WatchLive {
    /** A live recording calls back about once a second; the phone needs no more than this. */
    private const val STREAM_PUSH_MS = 1_500L
    @Volatile private var lastPush = -STREAM_PUSH_MS
    @Volatile private var lastHeart: Pair<Double, Long>? = null
    @Volatile private var lastSteps: Pair<Double, Long>? = null

    /** How a live recording for the phone starts. Checks replace it; the sensor is not theirs to use. */
    @Volatile internal var startLive: (Context) -> Unit = { WatchHeartService.start(it, forPhone = true) }

    /** Where updates go. Production sends them over the Data Layer, fire and forget; checks capture them. */
    @Volatile internal var send: (Context, String, ByteArray) -> Unit = { context, node, bytes ->
        Wearable.getMessageClient(context).sendMessage(node, LiveWire.PATH, bytes)
            .addOnFailureListener { Log.w("OrbitWatch", "Live update not delivered: ${it.javaClass.simpleName}") }
    }

    fun lease(context: Context, node: String, request: LiveRequest, now: Long = System.currentTimeMillis()) {
        // A phone holds the watch for one lease at most, whatever it asks for.
        val until = if (request.release) 0L else min(request.until, now + LiveWire.LEASE_MS)
        check(prefs(context).edit().putString("node", node).putLong("until", until).commit()) { "Live lease could not be saved" }
    }

    /** The phone watching right now, if any. */
    fun leased(context: Context, now: Long = System.currentTimeMillis()): String? {
        val saved = prefs(context)
        return saved.getString("node", null)?.takeIf { saved.getLong("until", 0) > now }
    }

    fun state(context: Context): String = when {
        WatchHeartService.state.value.let { it.active && it.probe == null } -> "streaming"
        !WatchPermissions.granted(context, WatchPermissions.heart) || !WatchPermissions.granted(context, WatchPermissions.background) -> "needs_access"
        !WatchStore(context).enabled() -> "off"
        else -> "passive"
    }

    /** The wearer stopped the phone's stream from the watch; it stays stopped until the phone lets go. */
    fun decline(context: Context) { check(prefs(context).edit().putBoolean("declined", true).commit()) }
    fun declined(context: Context) = prefs(context).getBoolean("declined", false)

    /** The newest valid heart reading this watch holds, whichever sensor path captured it. */
    fun newest(context: Context): Pair<Double, Long>? = WatchStore(context).journal().use { it.latest("heart") }?.let { row ->
        val bpm = row.optDouble("value", Double.NaN)
        val at = row.optLong("end", 0)
        if (row.optString("quality") == "valid" && !row.optBoolean("timeUncertain") && bpm.isFinite() && bpm > 0 && bpm < 300 && at > 0)
            bpm to at else null
    }

    /**
     * The watch's own step count since today's midnight, and when it was counted. A daily count starts at
     * midnight, before any clock correction since boot, so it is flagged time-uncertain whenever the clock
     * has been nudged; its count and the moment it was taken are still sound, and that is all a live
     * total needs.
     */
    fun newestSteps(context: Context, today: LocalDate = LocalDate.now()): Pair<Double, Long>? =
        WatchStore(context).journal().use { it.latest("steps") }?.let { row ->
            val steps = row.optDouble("value", Double.NaN)
            val at = row.optLong("end", 0)
            if (row.optString("semantics") == "daily" && row.optString("quality") == "valid" &&
                steps.isFinite() && steps >= 0 && at > 0 && on(today, at)) steps to at else null
        }

    /**
     * Start streaming for the phone without anyone touching the watch, and answer at once with what the
     * watch has. Then ask its sensors for anything newer: a flush delivers whatever the watch has counted
     * or measured since its last batch, and [offer] pushes it on capture. A release needs no answer; a
     * stream the phone started sees its lease end and stops itself.
     */
    fun request(context: Context, node: String, request: LiveRequest) {
        lease(context, node, request)
        if (request.release) { check(prefs(context).edit().remove("declined").commit()); return }
        var state = state(context)
        if (state != "streaming" && state != "needs_access" && !declined(context)) state = try {
            startLive(context); "streaming"
        } catch (error: Exception) {
            // Android can refuse a background start; the passive readings still come.
            Log.w("OrbitWatch", "Live heart could not start for the phone: ${error.javaClass.simpleName}")
            state
        }
        push(context, node, state, newest(context), newestSteps(context))
        try {
            HealthServices.getClient(context).passiveMonitoringClient.flushAsync().get(5, TimeUnit.SECONDS)
        } catch (error: Exception) {
            Log.w("OrbitWatch", "Passive flush incomplete: ${error.javaClass.simpleName}")
        }
    }

    /**
     * After a capture: while a phone is watching, push the newest valid heart reading and step count
     * straight to it. A new step count always goes; a live recording's once-a-second heart is thinned.
     */
    fun offer(context: Context, readings: List<WatchReading>) {
        val heart = readings.filter { it.metric == "heart" && it.quality == "valid" && !it.timeUncertain && it.value != null }
            .maxByOrNull { it.end }?.takeIf { reading -> lastHeart.let { it == null || reading.end > it.second } }
        val today = LocalDate.now()
        // See [newestSteps] for why a daily count's time-uncertain flag does not keep it from the phone.
        val steps = readings.filter { it.metric == "steps" && it.semantics == "daily" && it.quality == "valid" &&
            it.value != null && on(today, it.end) }
            .maxByOrNull { it.end }?.takeIf { reading -> lastSteps.let { it == null || reading.end > it.second } }
        if (heart == null && steps == null) return
        val node = leased(context) ?: return
        val state = state(context)
        if (steps == null && state == "streaming" && SystemClock.elapsedRealtime() - lastPush < STREAM_PUSH_MS) return
        push(context, node, state, heart?.let { it.value!! to it.end } ?: lastHeart, steps?.let { it.value!! to it.end } ?: lastSteps)
    }

    private fun push(context: Context, node: String, state: String, heart: Pair<Double, Long>?, steps: Pair<Double, Long>?) {
        lastPush = SystemClock.elapsedRealtime()
        heart?.let { lastHeart = it }
        steps?.let { lastSteps = it }
        send(context, node, LiveWire.encode(LiveUpdate(state, heart?.first, heart?.second, System.currentTimeMillis(),
            steps?.first, steps?.second)))
    }

    private fun on(day: LocalDate, at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate() == day

    /** Checks start from a clean slate. */
    internal fun reset(context: Context) {
        lastPush = -STREAM_PUSH_MS; lastHeart = null; lastSteps = null
        check(prefs(context).edit().clear().commit())
    }

    private fun prefs(context: Context) = context.getSharedPreferences("watch-live", Context.MODE_PRIVATE)
}
