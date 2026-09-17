package com.mani.orbit

import com.mani.orbit.sync.ReadingJournal
import com.mani.orbit.sync.WatchWorkout

/** Incremental committed changes: clock ticks never decode the full workout history or route. */
internal class WatchWorkoutProjection {
    private var sequence = 0L
    private val records = LinkedHashMap<String, WorkoutRecord>()
    fun refresh(journal: ReadingJournal): List<WorkoutRecord>? {
        val changes = journal.workoutChanges(sequence)
        if (changes.isEmpty()) return null
        val next = changes.map { (seq, installation, workout) -> seq to record(installation, workout, seq) }
        next.forEach { (_, record) -> records[record.id] = record }
        sequence = next.last().first
        return records.values.sortedByDescending { it.start }
    }
    companion object {
        fun record(installation: String, w: WatchWorkout, change: Long = 0) = WorkoutRecord("watch:$installation:${w.id}", w.kind, w.start,
            if (w.terminal) w.updatedAt else null, w.activeMs, maxOf(w.activeMs, w.updatedElapsed - w.startElapsed),
            tracking = w.gps, status = w.phase, paused = w.phase == "paused", distance = w.distance,
            summary = buildMap { w.steps?.let { put("steps", it.toDouble()) }; w.energy?.let { put("energy", it) } },
            watch = w, watchInstallation = installation, watchChange = change)
    }
}
