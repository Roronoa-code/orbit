package com.mani.orbit

import java.util.TreeMap

/** Original intervals are merged once. Conflicts and missing time never become sleep. */
internal object SleepTimeline {
    fun merge(nights: List<SleepNight>): List<SleepInterval> {
        val names = listOf("unknown", "awake", "sleeping", "light", "deep", "rem")
        val events = TreeMap<Long, IntArray>()
        for (night in nights) for (interval in night.intervals) {
            val index = names.indexOf(interval.stage)
            require(index >= 0 && interval.end > interval.start)
            events.getOrPut(interval.start) { IntArray(names.size) }[index]++
            events.getOrPut(interval.end) { IntArray(names.size) }[index]--
        }
        val result = mutableListOf<SleepInterval>()
        val counts = IntArray(names.size)
        var previous: Long? = null
        for ((at, changes) in events) {
            if (previous != null) {
                val occupied = counts.indices.filter { counts[it] > 0 }
                val stage = when (occupied.size) { 0 -> "unrecorded"; 1 -> names[occupied.single()]; else -> "unknown" }
                append(result, SleepInterval(previous, at, stage))
            }
            for (i in counts.indices) counts[i] += changes[i]
            previous = at
        }
        return result
    }

    fun totals(nights: List<SleepNight>): Map<String, Double> = merge(nights)
        .filter { it.stage != "unrecorded" }.groupBy { it.stage }
        .mapValues { (_, rows) -> rows.sumOf { (it.end - it.start) / 60000.0 } }

    /** Five-minute majority blocks; unknown and unrecorded boundaries remain exact. */
    fun blocks(intervals: List<SleepInterval>): List<SleepInterval> {
        if (intervals.isEmpty()) return emptyList()
        val start = intervals.first().start; val end = intervals.last().end
        val edges = sortedSetOf(start, end)
        var at = Math.floorDiv(start, 300000L) * 300000L + 300000L
        while (at < end) { edges += at; at += 300000L }
        for (row in intervals) if (row.stage == "unknown" || row.stage == "unrecorded") {
            edges += row.start; edges += row.end
        }
        val result = mutableListOf<SleepInterval>()
        var cursor = 0
        for ((a, b) in edges.zipWithNext()) {
            while (cursor < intervals.size && intervals[cursor].end <= a) cursor++
            val weights = linkedMapOf<String, Long>()
            var i = cursor
            while (i < intervals.size && intervals[i].start < b) {
                val row = intervals[i++]
                weights[row.stage] = (weights[row.stage] ?: 0L) + (minOf(b, row.end) - maxOf(a, row.start)).coerceAtLeast(0)
            }
            val most = weights.values.maxOrNull() ?: continue
            val previous = result.lastOrNull()?.stage
            val winner = previous?.takeIf { weights[it] == most } ?: weights.entries.first { it.value == most }.key
            append(result, SleepInterval(a, b, winner))
        }
        return result
    }

    private fun append(rows: MutableList<SleepInterval>, next: SleepInterval) {
        val last = rows.lastOrNull()
        if (last?.stage == next.stage && last.end == next.start) rows[rows.lastIndex] = last.copy(end = next.end)
        else rows += next
    }
}
