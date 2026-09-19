package com.mani.orbit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlin.math.*

@Composable internal fun WorkoutLive(record: WorkoutRecord, busy: Boolean, error: String?, action: (String) -> Unit,
    details: () -> Unit, music: NativeMusicState, player: WorkoutPlayerMotion,
    command: (String, String, Double) -> Unit, connect: () -> Unit) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    var remaining by rememberSaveable(record.id) { mutableStateOf(false) }
    var pattern by rememberSaveable { mutableIntStateOf(0) }
    var actionsHeight by remember { mutableIntStateOf(0) }
    val expanded = player.expanded
    val phase = { player.motion.value.coerceIn(0f, 1f) }
    val toggle = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); player.target(!player.expanded) }
    DisposableEffect(player) { player.shown = true; onDispose { player.shown = false } }
    val scroll = rememberScrollState()
    ObserveHeaderScroll(scroll)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Small screens and large text retain a scrollable canvas, not clipped actions.
        val footerExtra = (with(density) { actionsHeight.toDp() } - 76.dp).coerceAtLeast(0.dp)
        val headingExtra = (56 * (density.fontScale - 1f).coerceAtLeast(0f)).dp
        val canvasHeight = maxHeight.coerceAtLeast((610 * max(1f, density.fontScale * .88f)).dp + footerExtra)
        val musicBottom = canvasHeight - footerExtra
        val height = with(density) { canvasHeight.toPx() }
        val dial = minOf(256.dp, canvasHeight * .37f)
        val ringY = 32.dp
        val clockStart = ringY + dial / 2 - 41.dp
        val headingEnd = musicBottom - 278.dp - headingExtra
        val clockEnd = headingEnd - 32.dp - if (record.target > 0) 137.dp else 82.dp
        val compactDetailsY = ringY + dial + 14.dp
        fun moving(start: Dp, end: Dp) = Modifier.offset { IntOffset(0, with(density) { (start.toPx() + (end - start).toPx() * phase()).roundToInt() }) }
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Box(Modifier.fillMaxWidth().height(canvasHeight).testTag("workout-live")
                .workoutPlayerDrag(player, height * .48f)) {
                Text(if (record.paused) "Paused" else "Recording", color = WorkoutMuted, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp).graphicsLayer { alpha = 1f - phase() })
                TimerRing(record, pattern, Modifier.align(Alignment.TopCenter).offset(y = ringY).size(dial)
                    .graphicsLayer { alpha = 1f - reveal(phase(), 0f, .55f) }, { pattern = (pattern + 1) % 3 })
                Box(Modifier.fillMaxWidth().then(moving(clockStart, clockEnd)).height(82.dp)
                    .semantics { role = Role.Button; contentDescription = if (expanded) "Show workout details" else "Focus on timer and music"
                        onClick { toggle(); true } }
                    .pointerInput(player) { detectTapGestures { toggle() } }, contentAlignment = Alignment.Center) {
                    OrbitDotNumber(workoutClock(if (remaining) (record.target - record.elapsed).coerceAtLeast(0) else record.elapsed),
                        Modifier.widthIn(max = 260.dp).fillMaxWidth(.76f).height(63.dp).testTag("workout-timer"), WorkoutWhite)
                }
                if (record.target > 0) Box(Modifier.align(Alignment.TopCenter).then(moving(clockStart + 89.dp, clockEnd + 87.dp))) {
                    GlassTrack(listOf("Elapsed", "Remaining"), if (remaining) 1 else 0, { remaining = it == 1 }, "workout-clock-mode",
                        Modifier.width(212.dp), slotHeight = 32.dp, labelSize = 12.sp)
                }
                Column(Modifier.fillMaxWidth().offset(y = compactDetailsY).padding(horizontal = 24.dp)
                    .graphicsLayer { alpha = 1f - reveal(phase(), 0f, .22f) }
                    .then(if (phase() > .22f) Modifier.clearAndSetSemantics {} else Modifier), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        LiveFact(if (record.tracking) "Distance" else "Energy estimate", if (record.tracking) "${workoutNumber(record.distance?.div(1000), 2)} km" else "${workoutNumber(record.energy)} kcal")
                        LiveFact(if (record.tracking) "Average pace" else "Started", if (record.tracking) "${workoutPace(record.averageSpeed)} /km" else workoutTime(record.start))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(workoutStatus(record), color = WorkoutMuted, fontSize = 12.sp, maxLines = 2, modifier = Modifier.weight(1f))
                        WorkoutButton(details, enabled = phase() < .1f, background = Color.Transparent) { Text("All details", color = WorkoutPurple, fontSize = 13.sp) }
                    }
                    if (record.tracking && record.status in listOf("permission", "unavailable", "error"))
                        WorkoutButton({ action("tracking") }, enabled = phase() < .1f) { Text("Enable GPS tracking", color = WorkoutPurple) }
                }
                if (music.ready) {
                    // This anchor never moves. The window-level cover owns the whole thumbnail-to-artwork path.
                    Spacer(Modifier.offset(x = 20.dp, y = musicBottom - 234.dp - headingExtra / 2).size(48.dp)
                        .onGloballyPositioned { player.thumb = androidx.compose.ui.geometry.Rect(it.positionInRoot(), androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())) }
                        .testTag("music-thumbnail"))
                    Column(Modifier.fillMaxWidth().then(moving(musicBottom - 234.dp - headingExtra, headingEnd))
                        .padding(start = 82.dp, end = 20.dp).height(56.dp + headingExtra).testTag("music-heading")
                        .semantics { role = Role.Button; onClick("Open music player") { toggle(); true } }
                        .pointerInput(player) { detectTapGestures { toggle() } }, verticalArrangement = Arrangement.Center) {
                        Text(music.title.ifBlank { "Unknown track" }, color = WorkoutWhite, fontSize = 18.sp, lineHeight = 23.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(music.artist.ifBlank { music.source }, color = WorkoutMuted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.fillMaxWidth().offset(y = musicBottom - 220.dp).padding(horizontal = 24.dp)
                        .graphicsLayer { alpha = reveal(phase(), .4f, .9f) }
                        .then(if (phase() < .9f) Modifier.clearAndSetSemantics {} else Modifier)) {
                        MusicTimeline(music, phase() > .9f, command)
                    }
                    Row(Modifier.fillMaxWidth().offset(y = musicBottom - 156.dp), horizontalArrangement = Arrangement.Center) {
                        MusicTransport(music, command)
                    }
                    if (music.canOpen) WorkoutButton({ command(music.id, "open", 0.0) },
                        Modifier.align(Alignment.TopStart).offset(x = 20.dp, y = headingEnd).size(48.dp)
                            .graphicsLayer { alpha = reveal(phase(), .55f, 1f) }
                            .then(if (phase() < .9f) Modifier.clearAndSetSemantics {} else Modifier), enabled = phase() > .9f, background = Color.Transparent) {
                        Text("↗", color = WorkoutWhite, fontSize = 23.sp, modifier = Modifier.semantics { contentDescription = "Open music app" })
                    }
                } else Column(Modifier.fillMaxWidth().offset(y = musicBottom - 216.dp).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(when (music.status) { "permission" -> "Music controls are off"; "error" -> "Music unavailable"; else -> "Play something in your music app" },
                        color = WorkoutMuted, fontSize = 14.sp)
                    if (music.status == "permission") SettingsAction("Connect music", action = connect)
                }
                Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).onSizeChanged { actionsHeight = it.height }
                    .padding(horizontal = 20.dp, vertical = 8.dp)) {
                    SettingsMessage(error ?: music.error)
                    WorkoutActions(record.paused, busy, action)
                }
            }
        }
    }
}

@Composable private fun LiveFact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(value, color = WorkoutWhite, fontSize = 24.sp)
        Text(label, color = WorkoutMuted, fontSize = 12.sp)
    }
}

@Composable private fun TimerRing(record: WorkoutRecord, pattern: Int, modifier: Modifier, change: () -> Unit) {
    val reduced = LocalOrbitReducedMotion.current
    val style = animateFloatAsState(pattern.toFloat(), if (reduced) tween(0) else spring(1f, 280f), label = "timer dots")
    val points = remember { List(100) { i -> val a = (i / 100f * 2 * PI - PI / 2).toFloat(); Offset(cos(a), sin(a)) } }
    val haptic = LocalHapticFeedback.current
    Canvas(modifier.semantics { contentDescription = "Change dot pattern"; role = Role.Button; onClick { change(); true } }
        .pointerInput(Unit) { detectTapGestures { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); change() } }) {
        val radius = size.minDimension / 2 - 7.dp.toPx()
        points.forEachIndexed { i, point ->
            val wave = sin(i / 100f * PI * 12).toFloat() * 5.dp.toPx() * style.value / 2
            val filled = record.target == 0L || i / 100f <= record.elapsed.toFloat() / record.target
            drawCircle((if (filled) WorkoutPurple else WorkoutMuted).copy(alpha = if (filled) .8f else .18f),
                (if (i % 5 == 0) 2.1f else 1.4f).dp.toPx(), center + point * (radius + wave))
        }
    }
}
