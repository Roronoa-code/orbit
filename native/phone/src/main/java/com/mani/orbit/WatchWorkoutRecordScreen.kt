package com.mani.orbit
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mani.orbit.sync.ReadingJournal
import kotlinx.coroutines.*

@Composable
internal fun WatchWorkoutRecordScreen(record: WorkoutRecord) {
    val w = requireNotNull(record.watch)
    val control by WatchWorkoutControl.state.collectAsStateWithLifecycle()
    WatchControlPresentation(record, control)
    val context = LocalContext.current
    val scroll = rememberLazyListState()
    ObserveHeaderScroll(scroll)
    var points by remember(record.id) { mutableStateOf(emptyList<WorkoutPoint>()) }
    var error by remember(record.id) { mutableStateOf<String?>(null) }
    var chart by rememberSaveable(record.id) { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(record.id, w.revision, record.watchChange) {
        try {
            val delta = withContext(Dispatchers.IO) { ReadingJournal(context.getDatabasePath("watch-readings.db")).use {
                it.workoutPoints(requireNotNull(record.watchInstallation), w.id, points.lastOrNull()?.let { it.elapsed + w.startElapsed } ?: -1)
            } }.map { WorkoutPoint(it.lat, it.lon, it.elapsed - w.startElapsed, it.altitude, null, it.breakBefore) }
            if (delta.isNotEmpty()) points = points + delta
            error = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "The saved Watch route could not be loaded." }
    }
    LaunchedEffect(w.terminal) { if (!w.terminal) while (isActive) { now = System.currentTimeMillis(); delay(1000) } }
    // Allow the same two-second clock-capture tolerance used by the Watch reading source.
    val fresh = !w.timeUncertain && now - w.updatedAt in -2000..15_000
    LazyColumn(Modifier.fillMaxSize().testTag("watch-workout-record"), state = scroll,
        contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Watch · Health Services", color = WorkoutPurple, fontSize = 13.sp)
            Text(record.date().format(WorkoutDate), color = WorkoutMuted, fontSize = 12.sp)
            OrbitDotNumber(workoutClock(w.activeMs), Modifier.fillMaxWidth(.85f).height(67.dp), WorkoutWhite)
            Text(when { w.phase == "interrupted" -> "Recording interrupted · last confirmed time"; w.terminal -> "Active time"
                w.phase == "paused" -> "Paused on Watch"; fresh -> "Recording on Watch"; else -> "Waiting for Watch update" }, color = WorkoutMuted, fontSize = 14.sp)
        } }
        item { WorkoutFacts(listOf("Distance" to "${workoutNumber(w.distance?.div(1000), 2)} km",
            "Energy" to "${workoutNumber(w.energy)} kcal", "Steps" to workoutNumber(w.steps?.toDouble()),
            "Elevation gain" to "${workoutNumber(w.elevation)} m")) }
        if (!w.terminal) item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val heartFresh = fresh && w.heartQuality == "valid" && w.heartElapsed?.let {
                (now - w.updatedAt).coerceAtLeast(0) + w.updatedElapsed - it in -1000..15_000
            } == true
            Text("${workoutNumber(w.heart.takeIf { heartFresh })} bpm", color = WorkoutWhite, fontSize = 30.sp)
            Text(if (heartFresh) "Latest Watch heart rate" else "Waiting for a fresh heart reading", color = WorkoutMuted, fontSize = 12.sp)
            WatchWorkoutControls(record)
        } }
        item { WorkoutFacts(listOf("Active time" to workoutClock(w.activeMs), "Total time" to workoutClock(record.total))) }
        if (w.kind == "Running") item { PhoneSweatEstimate(requireNotNull(record.watchInstallation), w) }
        if (w.gps) item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                MeasurementSelector(listOf("Route", "Elevation"), chart, "watch-workout-chart") { chart = it }
                WorkoutChart(points, if (chart == 0) 0 else 2, record.total, "Recorded on your Watch")
                if (points.isEmpty()) Text("No accurate Watch route points received yet.", color = WorkoutMuted, fontSize = 12.sp)
            }
        }
        item { SettingsMessage(error) }
        item { Text("Recorded by your Watch. ${if (w.timeUncertain) "Its clock changed; recorded durations are preserved. " else ""}" +
            "Last update ${workoutTime(w.updatedAt)}. Missing measurements stay empty.", color = WorkoutMuted, fontSize = 12.sp, lineHeight = 18.sp) }
    }
}
