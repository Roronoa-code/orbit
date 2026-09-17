package com.mani.orbit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

private val SleepWhite = Color(0xFFF5F1FA)
private val SleepMuted = Color(0xFFB9B3C3)
private val SleepDate = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
internal fun sleepDuration(minutes: Double): String {
    val n = minutes.roundToInt(); return (if (n >= 60) "${n / 60}h " else "") + "${n % 60}min"
}

@Composable
internal fun SleepRoute(health: HealthScreenState, reduced: Boolean) {
    val database = LocalContext.current.getDatabasePath("samsung-health.db")
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var dateText by rememberSaveable { mutableStateOf(health.day.date.toString()) }
    val date = LocalDate.parse(dateText)
    var saved by remember { mutableStateOf<SleepDay?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var retry by remember { mutableIntStateOf(0) }
    var generation by remember { mutableIntStateOf(0) }
    val firstDate = saved?.firstDate ?: minOf(date, health.days.keys.minOrNull() ?: health.day.date)
    val cached = remember(health.days, health.day.nights, date, firstDate) {
        (if (date == health.day.date) health.day else health.days[date])?.let { SleepDay(date, it.nights, firstDate) }
    }
    LaunchedEffect(date, health.days, health.lastSync, retry, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val request = ++generation
            loading = true; error = null
            try { saved = withContext(Dispatchers.IO) { SleepDay.read(database, date) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (request == generation) error = "Sleep readings could not be loaded. Your saved records are unchanged." }
            finally { if (request == generation) loading = false }
            awaitCancellation()
        }
    }
    val day = saved?.takeIf { it.date == date } ?: cached ?: SleepDay(date, emptyList(), firstDate)
    SleepScreen(day, loading, error, reduced, { dateText = it.toString() }, { retry++ })
}

@Composable
internal fun SleepScreen(day: SleepDay, loading: Boolean, error: String?, reduced: Boolean,
    changeDate: (LocalDate) -> Unit, retry: () -> Unit) {
    var selectedAt by rememberSaveable(day.date) { mutableStateOf<Long?>(null) }
    val selected = selectedAt?.let(day::locate)
    val selection = rememberUpdatedState(selectedAt)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var offset by remember { mutableFloatStateOf(0f) }
    var settle: Job? by remember { mutableStateOf(null) }
    val currentDay by rememberUpdatedState(day)
    val changeNow by rememberUpdatedState(changeDate)
    fun moveDate(delta: Int) {
        val next = currentDay.date.plusDays(delta.toLong())
        if (next < currentDay.firstDate || next > LocalDate.now()) return
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); changeNow(next)
    }
    val moveNow by rememberUpdatedState(::moveDate)
    val inspect: (Long?) -> Unit = { at ->
        if (day.locate(at ?: day.start ?: 0)?.start != selected?.start && at != null)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        selectedAt = at
    }
    val fontScale = LocalDensity.current.fontScale
    val scroll = rememberScrollState()
    ObserveHeaderScroll(scroll)
    Column(Modifier.fillMaxSize().testTag("sleep-scroll").pointerInput(reduced) {
        awaitEachGesture {
            val down = awaitFirstDown()
            settle?.cancel()
            val origin = offset
            var owned = false; var complete = false; var dx = 0f; var dy = 0f
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val point = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (point.isConsumed || event.changes.count { it.pressed } > 1) break
                    dx = point.position.x - down.position.x; dy = point.position.y - down.position.y
                    if (!point.pressed) { complete = true; if (owned) point.consume(); break }
                    if (!owned && abs(dy) > viewConfiguration.touchSlop && abs(dy) >= abs(dx)) break
                    if (!owned && abs(dx) > viewConfiguration.touchSlop) owned = true
                    if (owned) { point.consume(); if (!reduced) offset = (origin + dx * .3f).coerceIn(-56.dp.toPx(), 56.dp.toPx()) }
                }
            } finally {
                val commit = complete && owned && abs(dx) >= 48.dp.toPx() && abs(dx) > abs(dy) * 1.2f
                if (commit) moveNow(if (dx < 0) 1 else -1)
                settle = scope.launch { if (reduced || commit) offset = 0f else animate(offset, 0f, animationSpec = tween(180, easing = CubicBezierEasing(.2f, .7f, .2f, 1f))) { value, _ -> offset = value } }
            }
        }
    }.verticalScroll(scroll).graphicsLayer { translationX = offset }
        .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFF19181E)).padding(horizontal = 16.dp)
            .testTag("sleep-summary")) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(day.date.format(SleepDate), color = SleepWhite, fontSize = 13.sp, modifier = Modifier.weight(1f))
                WorkoutArrow("Previous day", -1, day.date > day.firstDate) { moveDate(-1) }
                WorkoutArrow("Next day", 1, day.date < LocalDate.now()) { moveDate(1) }
            }
            HorizontalDivider(color = Color.White.copy(alpha = .05f))
            FlowRow(Modifier.fillMaxWidth().padding(top = 15.dp, bottom = 20.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Time asleep", fontSize = 12.sp, color = SleepMuted)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val asleep = day.asleep?.roundToInt()
                        Text(asleep?.div(60)?.toString() ?: "—", color = SleepWhite, fontSize = 34.sp, fontWeight = FontWeight.Medium)
                        if (asleep != null) {
                            Text("h", color = SleepMuted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 5.dp))
                            Text("${asleep % 60}", color = SleepWhite, fontSize = 34.sp, fontWeight = FontWeight.Medium)
                            Text("min", color = SleepMuted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 5.dp))
                        }
                    }
                }
                if (day.start != null) Column {
                    Text("Sleep window", fontSize = 12.sp, color = SleepMuted)
                    Text("${healthTime(day.start!!)} – ${healthTime(day.end!!)}", color = Color(0xFFE2DCE9), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).testTag("sleep-loading"), color = HealthAccent)
            }
        }
        if (error != null) Column {
            Text(error, color = SleepMuted, fontSize = 13.sp, lineHeight = 19.sp)
            TextButton(onClick = retry) { Text("Try again") }
        }
        if (day.segments.isEmpty()) {
            if (!loading && error == null) Text("No sleep recorded for this day.", color = SleepMuted, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 40.dp).testTag("sleep-empty"))
        } else {
            Column(Modifier.padding(horizontal = 6.dp)) {
                Column(Modifier.fillMaxWidth().height((48 * max(1f, fontScale)).dp).testTag("sleep-inspection"), verticalArrangement = Arrangement.Center) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (selected != null) Box(Modifier.size(14.dp, 4.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (selected.stage == "unrecorded") Color(0xFF66636E) else sleepStageColor(selected.stage)))
                        Text(selected?.let { sleepStageLabel(it.stage) } ?: "Sleep stages", color = SleepWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text(selected?.let { sleepDuration((it.end - it.start) / 60000.0) } ?: "5-min blocks", color = SleepMuted, fontSize = 11.sp)
                    }
                    if (selected != null) Text("${if (selected.stage == "unrecorded") "Gap" else "Recorded"} · ${healthTime(selected.start)} – ${healthTime(selected.end)}", color = SleepMuted, fontSize = 11.sp, lineHeight = 17.sp)
                }
                SleepChart(day, selection, inspect)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF19191C)).padding(6.dp).testTag("sleep-breakdown")) {
                day.lanes.forEach { stage ->
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val active = selected?.stage == stage
                    val enabled = (day.totals[stage] ?: 0.0) > 0
                    val color = sleepStageColor(stage)
                    val tint by animateColorAsState(color.copy(alpha = if (pressed) .22f else if (active) .12f else 0f), tween(if (reduced) 0 else 160), label = "stage selection")
                    Row(Modifier.fillMaxWidth().graphicsLayer { alpha = if (enabled) 1f else .5f }.clip(RoundedCornerShape(12.dp)).background(tint)
                        .clickable(enabled = enabled, interactionSource = interaction, indication = null) { inspect(day.segments.first { it.stage == stage }.start) }
                        .semantics { this.selected = active; role = Role.Button }.testTag("sleep-stage-$stage")
                        .padding(horizontal = 10.dp, vertical = 10.dp).heightIn(min = 29.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(16.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(color))
                        Text(sleepStageLabel(stage), color = SleepWhite, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(sleepDuration(day.totals[stage] ?: 0.0), color = Color(0xFFC4C0CB), fontSize = 13.sp)
                    }
                }
            }
            Text("Samsung Health · Totals use original readings" +
                (if ((day.totals["unrecorded"] ?: 0.0) > 0) "\nGaps are not recorded" else "") +
                (if ((day.totals["unknown"] ?: 0.0) > 0) "\nSome stage timings are unavailable" else ""), color = SleepMuted,
                fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 14.dp))
        }
    }
}

