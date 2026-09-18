package com.mani.orbit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun workoutStatus(record: WorkoutRecord): String = when (record.status) {
    "permission" -> "Precise location needed"; "searching" -> "Finding GPS"; "tracking" -> "Phone GPS"
    "paused" -> "GPS paused"; "unavailable" -> "GPS unavailable"; "error" -> "Tracking needs attention"
    "finished" -> "Phone GPS recording"; else -> "Time only"
}

@Composable
internal fun WorkoutRecordScreen(record: WorkoutRecord, busy: Boolean, error: String?, action: (String) -> Unit,
    detailsLoading: Boolean = false, detailsError: String? = null, retryDetails: () -> Unit = {}) {
    val scroll = rememberLazyListState()
    ObserveHeaderScroll(scroll)
    var chart by rememberSaveable(record.id) { mutableIntStateOf(0) }
    var laps by rememberSaveable(record.id) { mutableStateOf(false) }
    var method by rememberSaveable(record.id) { mutableStateOf(false) }
    val running = record.end == null
    LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = if (running) 24.dp else 112.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text((if (record.imported) "Samsung Health" else if (running) "In progress" else "Workout saved") + " · " + record.date().format(WorkoutDate),
                    fontSize = 12.sp, color = WorkoutMuted)
                val hero = if (!record.imported && record.distance != null) workoutNumber(record.distance / 1000, 2) else workoutClock(record.elapsed)
                OrbitDotNumber(hero, Modifier.fillMaxWidth(.85f).height(67.dp), WorkoutWhite)
                Text(if (record.imported) "Session duration" else if (record.distance != null) "Kilometres" else "Active time", fontSize = 13.sp, color = WorkoutMuted)
                Text(workoutTime(record.start) + (record.end?.let { " – ${workoutTime(it)}" } ?: "") + if (record.imported) "" else " · ${workoutStatus(record)}",
                    fontSize = 12.sp, color = WorkoutMuted)
                if (record.title.isNotBlank()) Text(record.title, color = WorkoutWhite, fontSize = 14.sp)
            }
        }
        if (record.imported) {
            if (detailsLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = WorkoutPurple) }
            if (detailsError != null) item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsMessage(detailsError); SettingsAction("Retry recorded details") { retryDetails() }
            } }
            item { WorkoutSection("Recorded during this session") {
                WorkoutFacts(listOf("Distance" to "${workoutNumber(record.distance?.div(1000), 2)} km", "Recorded energy" to "${workoutNumber(record.energy)} kcal",
                    "Steps" to workoutNumber(record.summary["steps"]), "Average heart rate" to "${workoutNumber(record.summary["heartAverage"])} bpm",
                    "Lowest heart rate" to "${workoutNumber(record.summary["heartLow"])} bpm", "Highest heart rate" to "${workoutNumber(record.summary["heartHigh"])} bpm"))
            } }
            if (record.points.isNotEmpty()) item { WorkoutSection("Recorded route") { WorkoutChart(record.points, 0, record.total, "Samsung Health route") } }
            if (record.laps.isNotEmpty()) {
                item { SettingsAction("${record.laps.size} ${if (record.laps.size == 1) "lap" else "laps"} ${if (laps) "−" else "+"}") { laps = !laps } }
                if (laps) itemsIndexed(record.laps) { index, lap -> WorkoutFacts(listOf("Lap ${index + 1}" to workoutDuration(lap.end - lap.start)) +
                    listOfNotNull(lap.distance?.let { "Distance" to "${workoutNumber(it)} m" })) }
            }
            if (record.notes.isNotBlank()) item { WorkoutSection("Notes") { Text(record.notes, color = WorkoutWhite, fontSize = 14.sp) } }
            item { Text((if (record.reportedDuration) "Duration and measurements reported by Samsung Health. " else "Samsung Health measurements within the recorded start and end times. Duration includes pauses. ") +
                when { record.detailsDeferred -> "Recorded details are loading or temporarily unavailable."; record.points.isNotEmpty() -> "Route gaps remain visible."; record.hasUnsharedRoute -> "A route exists in Samsung Health but has not been shared with Orbit."; else -> "No route shared." },
                color = WorkoutMuted, fontSize = 12.sp, lineHeight = 18.sp) }
        } else {
            item { WorkoutSection("Time") {
                WorkoutFacts(listOf("Active" to workoutClock(record.elapsed), "Total" to workoutClock(record.total),
                    "Paused" to workoutClock(record.total - record.elapsed), "Target" to if (record.target == 0L) "Open" else workoutDuration(record.target)))
                if (record.target > 0) {
                    LinearProgressIndicator(progress = { (record.elapsed.toFloat() / record.target).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(),
                        color = WorkoutPurple, trackColor = WorkoutPurple.copy(alpha = .12f))
                    Text(if (record.elapsed >= record.target) "Target reached" else "${workoutClock(record.target - record.elapsed)} to target", color = WorkoutMuted, fontSize = 12.sp)
                }
            } }
            if (record.tracking || record.points.isNotEmpty()) {
                item { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    MeasurementSelector(listOf("Route", "Speed", "Elevation"), chart, "workout-chart") { chart = it }
                    WorkoutChart(record.points, chart, record.elapsed)
                } }
                item { WorkoutSection("Movement") {
                    WorkoutFacts(listOf("Average speed" to "${workoutNumber(record.averageSpeed?.times(3.6), 1)} km/h", "Maximum speed" to "${workoutNumber(record.maxSpeed?.times(3.6), 1)} km/h",
                        "Average pace" to "${workoutPace(record.averageSpeed)} /km", "Best pace" to "${workoutPace(record.maxSpeed)} /km",
                        "Lowest elevation" to "${workoutNumber(record.altitudeLow)} m", "Highest elevation" to "${workoutNumber(record.altitudeHigh)} m"))
                } }
            }
            record.energy?.let { energy -> item { WorkoutSection("Energy estimate") {
                Text("${workoutNumber(energy)} kcal", fontSize = 30.sp, color = WorkoutWhite)
                SettingsAction("How it’s estimated ${if (method) "−" else "+"}") { method = !method }
                if (method) Text("Based on ${workoutNumber(record.weight, 1)} kg, active time and a general ${record.kind.lowercase()} intensity. Includes resting energy during active time. Actual energy varies with effort.",
                    fontSize = 12.sp, lineHeight = 18.sp, color = WorkoutMuted)
            } } }
            item { Text((if (record.energy == null) "No energy estimate: no saved weight was available when this workout began. " else "") +
                if (record.tracking) "Distance, pace and elevation use phone GPS. Poor or interrupted fixes are excluded." else "Only active time contributes to the energy estimate.",
                color = WorkoutMuted, fontSize = 12.sp, lineHeight = 18.sp) }
            if (running) { item { SettingsMessage(error) }; item { WorkoutActions(record.paused, busy, action) } }
        }
    }
}

@Composable
private fun WorkoutSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().orbitPanel(24.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(title, color = WorkoutWhite, fontSize = 16.sp); content()
    }
}
