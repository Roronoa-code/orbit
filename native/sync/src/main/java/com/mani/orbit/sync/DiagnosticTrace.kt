package com.mani.orbit.sync

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Deliberately closed vocabulary: no readings, source IDs, coordinates or exception text. */
enum class TraceRoute { OTHER, STEPS, HEALTH, MEASUREMENTS, SLEEP, WORKOUTS, SETTINGS, WATCH_HOME, WATCH_WORKOUT, WATCH_RECOVERY, WATCH_HISTORY, WATCH_MEASURE }
enum class TraceTier { PHONE_RETAINED_FROST, WATCH_NATIVE, PHONE_OPTICAL, PHONE_READABILITY }
enum class TraceQualityReason { DEFAULT, OBSERVING, MEASURED, FRAME_PRESSURE, THERMAL, THERMAL_UNKNOWN, POWER_SAVER, UNSUPPORTED, PREFERENCE }
enum class TraceGesture { IDLE, TOUCH, DRAG, SETTLING }
enum class TraceFeature { EXPLORE, WATCH_CONTROL, WATCH_WORKOUT }
enum class TraceStage { INPUT_ACCEPTED, COMMAND_REQUESTED, API_ACCEPTED, PLATFORM_CONFIRMED, DURABLE_COMMIT, DISPLAY_UPDATED, FAILED, CANCELLED, SUPERSEDED }

/** A process-local elapsedRealtimeNanos clock. Never compare offsets across phone/Watch runs. */
class TraceLedger(private val clock: () -> Long = SystemClock::elapsedRealtimeNanos) {
    private val origin = clock()
    private val run = UUID.randomUUID().toString() // Ephemeral diagnostic session, not an installation ID.
    private var next = 0L
    private var lost = 0L
    private data class Operation(val feature: TraceFeature, val route: TraceRoute, var stage: TraceStage)
    private data class Event(val time: Long, val op: Long, val feature: TraceFeature, val route: TraceRoute, val stage: TraceStage)
    private val operations = linkedMapOf<Long, Operation>()
    private val events = ArrayDeque<Event>()
    private val frames = LongArray(TraceRoute.entries.size * TraceTier.entries.size * TraceGesture.entries.size * 4)
    private var rendering: Pair<TraceTier, TraceQualityReason>? = null

    @Synchronized fun quality(tier: TraceTier, reason: TraceQualityReason) { rendering = tier to reason }

    @Synchronized fun begin(feature: TraceFeature, route: TraceRoute, stage: TraceStage = TraceStage.INPUT_ACCEPTED): Long {
        val op = ++next
        if (operations.size == MAX_OPERATIONS) operations.remove(operations.keys.first())
        operations[op] = Operation(feature, route, stage)
        append(op, operations.getValue(op))
        return op
    }
    @Synchronized fun mark(op: Long, stage: TraceStage): Boolean {
        val current = operations[op] ?: return false
        if (current.stage == stage || current.stage in TERMINAL) return false
        current.stage = stage
        append(op, current)
        return true
    }
    private fun append(op: Long, state: Operation) {
        if (events.size == MAX_EVENTS) { events.removeFirst(); lost++ }
        events.addLast(Event((clock() - origin).coerceAtLeast(0), op, state.feature, state.route, state.stage))
    }

    /** Four primitive counters per fixed state bucket; no retained FrameData or per-frame objects. */
    @Synchronized fun frame(route: TraceRoute, tier: TraceTier, gesture: TraceGesture, jank: Boolean, uiNanos: Long) {
        val index = ((route.ordinal * TraceTier.entries.size + tier.ordinal) * TraceGesture.entries.size + gesture.ordinal) * 4
        val duration = uiNanos.coerceIn(0, 60_000_000_000L)
        if (frames[index] == 1_000_000L) { // Bounded aggregate window, even for a long-lived process.
            for (i in 0..3) frames[index + i] = 0
        }
        frames[index]++
        if (jank) frames[index + 1]++
        frames[index + 2] += duration
        frames[index + 3] = maxOf(frames[index + 3], duration)
    }
    fun snapshot(): JSONObject {
        // Copy under the lock; JSON work must never hold up the frame callback or an accepted input.
        val saved = synchronized(this) { Triple(events.toList(), operations.mapValues { it.value.copy() }, frames.copyOf()) to lost }
        val (events, operations, frames) = saved.first
        val summaries = JSONArray()
        for (route in TraceRoute.entries) for (tier in TraceTier.entries) for (gesture in TraceGesture.entries) {
            val index = ((route.ordinal * TraceTier.entries.size + tier.ordinal) * TraceGesture.entries.size + gesture.ordinal) * 4
            if (frames[index] == 0L) continue
            summaries.put(JSONObject().put("route", route.name).put("tier", tier.name).put("gesture", gesture.name)
                .put("frames", frames[index]).put("janky", frames[index + 1])
                .put("uiTotalNanos", frames[index + 2]).put("uiMaxNanos", frames[index + 3]))
        }
        val material = synchronized(this) { rendering }
        return JSONObject().put("schema", 1).put("run", run).put("clock", "process-relative-elapsedRealtimeNanos")
            .put("rendering", material?.let { JSONObject().put("tier", it.first.name).put("reason", it.second.name) } ?: JSONObject.NULL)
            .put("droppedEvents", saved.second).put("events", JSONArray(events.map {
                JSONObject().put("atNanos", it.time).put("op", it.op).put("feature", it.feature.name)
                    .put("route", it.route.name).put("stage", it.stage.name)
            })).put("operations", JSONArray(operations.map { (id, state) ->
                JSONObject().put("op", id).put("feature", state.feature.name).put("lastStage", state.stage.name)
                    .put("complete", state.stage in TERMINAL)
            })).put("frames", summaries)
    }
    companion object {
        const val MAX_EVENTS = 128
        const val MAX_OPERATIONS = 32
        private val TERMINAL = setOf(TraceStage.DISPLAY_UPDATED, TraceStage.FAILED, TraceStage.CANCELLED, TraceStage.SUPERSEDED)
    }
}
