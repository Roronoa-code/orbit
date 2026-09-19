package com.mani.orbit

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

internal val WorkoutDate = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
internal fun workoutTime(at: Long): String = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm", Locale.UK))

@Composable
internal fun WorkoutHub(records: List<WorkoutRecord>, ready: Boolean, error: String?, setup: (String) -> Unit, open: (String) -> Unit) {
    var history by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val today = LocalDate.now()
    val reduced = LocalOrbitReducedMotion.current
    val date = selected?.let(LocalDate::parse) ?: records.firstOrNull()?.date()?.coerceAtMost(today) ?: today
    val scroll = rememberLazyListState()
    ObserveHeaderScroll(scroll)
    LaunchedEffect(history) { scroll.scrollToItem(0) }
    val start = workoutWeek(if (history) date else today)
    val grouped = remember(records) { records.filter { it.end != null }.groupBy { it.date() } }
    val week = remember(grouped, start) { (0L..6).flatMap { grouped[start.plusDays(it)].orEmpty() } }
    val selectedRecords = grouped[date].orEmpty()
    LazyColumn(state = scroll, contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxSize().testTag("workout-hub")) {
        item { MeasurementSelector(listOf("Train", "History"), if (history) 1 else 0, "workout-tabs") { history = it == 1 } }
        if (error != null) item { SettingsMessage(error) }
        items(records.filter { it.watch != null && it.end == null }, key = { it.id }) { record ->
            WorkoutButton({ open(record.id) }, Modifier.fillMaxWidth().testTag("watch-active-workout")) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${record.kind} · Watch", color = WorkoutPurple, fontSize = 18.sp)
                    Text("${workoutClock(record.elapsed)} · ${if (record.paused) "Paused" else "Last confirmed activity"}", color = WorkoutMuted, fontSize = 13.sp)
                }
            }
        }
        if (!history) {
            item { Text("Choose your workout", fontSize = 14.sp, color = WorkoutWhite, modifier = Modifier.padding(top = 6.dp)) }
            items(WorkoutKinds.chunked(2)) { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { kind -> WorkoutButton({ setup(kind) }, Modifier.weight(1f).testTag("choose-$kind"), ready) {
                    Column(Modifier.fillMaxWidth().heightIn(min = 124.dp).padding(18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        WorkoutIcon(kind, Modifier.size(34.dp)); Text(kind, color = WorkoutWhite, fontSize = 19.sp, lineHeight = 26.sp)
                    }
                } }
            } }
            item { WorkoutButton({ history = true }, Modifier.fillMaxWidth(), background = Color.Transparent) {
                Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("This week", fontSize = 12.sp, color = WorkoutMuted)
                        Text("${workoutDuration(week.sumOf { it.elapsed })} · ${week.size} sessions", fontSize = 20.sp, color = WorkoutWhite)
                    }
                    Text("History", fontSize = 12.sp, color = WorkoutPurple)
                }
            } }
            records.firstOrNull()?.let { last ->
                item { Text("Last workout", fontSize = 14.sp, color = WorkoutMuted) }
                item { WorkoutSessionCard(last) { open(last.id) } }
            }
        } else {
            item {
                Column(Modifier.orbitPanel(24.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(if (start == workoutWeek(today)) "This week" else "Training week", color = WorkoutWhite, fontSize = 16.sp)
                            Text("${start.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK))} – ${start.plusDays(6).format(WorkoutDate)}", color = WorkoutMuted, fontSize = 12.sp)
                        }
                        WorkoutArrow("Previous week", -1, start > workoutWeek(records.minOfOrNull { it.date() }?.coerceAtMost(today) ?: today)) { selected = date.minusWeeks(1).toString() }
                        WorkoutArrow("Next week", 1, start < workoutWeek(today)) { selected = date.plusWeeks(1).coerceAtMost(today).toString() }
                    }
                    WorkoutFacts(listOf("Recorded time" to workoutDuration(week.sumOf { it.elapsed }), "Sessions" to week.size.toString()))
                    WorkoutDays(start, date, today, grouped) { selected = it.toString() }
                }
            }
            item { Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(date.format(DateTimeFormatter.ofPattern("EEEE d MMM", Locale.UK)), color = WorkoutWhite, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("${selectedRecords.size} ${if (selectedRecords.size == 1) "workout" else "workouts"}", fontSize = 12.sp, color = WorkoutMuted)
            } }
            if (selectedRecords.isEmpty()) item { Column(Modifier.fillMaxWidth().orbitPanel(20.dp).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (ready) "No workouts this day" else "Loading saved workouts…", color = WorkoutWhite, fontSize = 14.sp)
                Text("Choose a marked date to see a session.", color = WorkoutMuted, fontSize = 12.sp)
            } }
            items(selectedRecords, key = { it.id }) { record ->
                Box(if (reduced) Modifier else Modifier.animateItem(fadeInSpec = tween(180), fadeOutSpec = tween(120), placementSpec = spring(.9f, 500f))) {
                    WorkoutSessionCard(record) { open(record.id) }
                }
            }
        }
    }
}

@Composable
internal fun WorkoutArrow(label: String, direction: Int, enabled: Boolean, action: () -> Unit) {
    WorkoutButton(action, Modifier.size(44.dp).semantics { contentDescription = label }, enabled) {
        Canvas(Modifier.size(18.dp)) {
            val path = Path().apply { moveTo(size.width * (.5f - direction * .16f), size.height * .2f)
                lineTo(size.width * (.5f + direction * .16f), size.height * .5f); lineTo(size.width * (.5f - direction * .16f), size.height * .8f) }
            drawPath(path, WorkoutPurple, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

/**
 * The week's days, on the same glass track as every other choice in the app: the chosen day is a
 * lavender disc that lifts into a lens as a finger holds or carries it across the week.
 */
@Composable
private fun WorkoutDays(start: LocalDate, selected: LocalDate, today: LocalDate, grouped: Map<LocalDate, List<WorkoutRecord>>, choose: (LocalDate) -> Unit) {
    val days = remember(start) { (0L..6).map { start.plusDays(it) } }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) { days.forEach { date ->
            Text(date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.titlecase() }, color = WorkoutMuted.copy(alpha = if (date <= today) 1f else .35f),
                fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).clearAndSetSemantics {})
        } }
        GlassTrack(days.map { it.dayOfMonth.toString() }, days.indexOf(selected),
            { choose(days[it]) }, "workout-days", Modifier.fillMaxWidth(), slotHeight = 48.dp, labelSize = 16.sp,
            style = TrackStyle(rail = false, flatFill = Color(0xFFD5C2F5), ink = WorkoutWhite, chosenInk = Color(0xFF261938),
                round = 40.dp, inset = 0.dp, labelWeight = FontWeight.Normal),
            allowed = { days[it] <= today },
            describe = { "${days[it].format(WorkoutDate)}, ${grouped[days[it]].orEmpty().size} workouts" },
            mark = { index ->
                if (!grouped[days[index]].isNullOrEmpty() && days[index] != selected)
                    Box(Modifier.size(34.dp).border(1.dp, WorkoutPurple.copy(alpha = .35f), CircleShape))
            })
    }
}

@Composable
internal fun WorkoutSessionCard(record: WorkoutRecord, open: () -> Unit) {
    WorkoutButton(open, Modifier.fillMaxWidth().testTag("workout-record-${record.id}")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WorkoutIcon(record.kind, Modifier.size(32.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(record.kind, color = WorkoutWhite, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(workoutTime(record.start) + when { record.watch != null -> " · Watch"; record.imported -> " · Samsung Health"; else -> "" }, color = WorkoutMuted, fontSize = 12.sp)
                }
            }
            val facts = listOf("Duration" to workoutDuration(record.elapsed)) + listOfNotNull(
                record.distance?.let { "Distance" to "${workoutNumber(it / 1000, 2)} km" },
                record.energy?.let { (if (record.imported || record.watch != null) "Energy" else "Energy est.") to "${workoutNumber(it)} kcal" })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                facts.forEach { (label, value) -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(value, color = WorkoutWhite, fontSize = 16.sp); Text(label, color = WorkoutMuted, fontSize = 12.sp)
                } }
            }
        }
    }
}
