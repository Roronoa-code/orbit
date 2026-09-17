package com.mani.orbit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

@Composable internal fun MusicTransport(music: NativeMusicState, command: (String, String, Double) -> Unit) {
    val reduced = LocalOrbitReducedMotion.current
    val pause = animateFloatAsState(if (music.playing) 1f else 0f, tween(if (reduced) 0 else 180), label = "play pause shape")
    listOf("previous", "toggle", "next").forEach { name ->
        val enabled = when (name) { "previous" -> music.canPrevious; "next" -> music.canNext; else -> music.canToggle }
        val label = when (name) { "previous" -> "Previous track"; "next" -> "Next track"; else -> if (music.playing) "Pause music" else "Play music" }
        val interaction = remember { MutableInteractionSource() }
        val glyph = remember { Path() }
        val held by interaction.collectIsPressedAsState()
        val focused by interaction.collectIsFocusedAsState()
        val press = animateFloatAsState(if (held && !reduced) 1f else 0f, if (reduced) tween(0) else spring(.82f, 700f), label = "transport contact")
        val haptic = LocalHapticFeedback.current
        Box(Modifier.padding(horizontal = 10.dp).size(48.dp).testTag("music-$name")
            .semantics { contentDescription = label }.clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                command(music.id, if (name == "toggle") (if (music.playing) "pause" else "play") else name, 0.0)
            }, contentAlignment = Alignment.Center) {
            if (focused) Canvas(Modifier.fillMaxSize()) { drawCircle(WorkoutPurple, style = Stroke(1.dp.toPx())) }
            Canvas(Modifier.size(if (name == "toggle") 28.dp else 22.dp).graphicsLayer {
                alpha = (if (enabled) 1f else .3f) * (1f - press.value * .25f)
                translationX = (if (name == "previous") -3f else if (name == "next") 3f else 0f) * density * press.value
                rotationZ = if (name == "toggle") -8f * press.value else 0f
            }) {
                glyph.reset()
                fun polygon(a: Offset, b: Offset, c: Offset, d: Offset = c) {
                    glyph.moveTo(a.x * size.width, a.y * size.height)
                    glyph.lineTo(b.x * size.width, b.y * size.height); glyph.lineTo(c.x * size.width, c.y * size.height)
                    glyph.lineTo(d.x * size.width, d.y * size.height); glyph.close()
                }
                if (name == "toggle") {
                    val p = pause.value
                    fun mix(a: Offset, b: Offset) = a + (b - a) * p
                    polygon(mix(Offset(.22f, .12f), Offset(.2f, .13f)), mix(Offset(.52f, .3f), Offset(.42f, .13f)),
                        mix(Offset(.52f, .7f), Offset(.42f, .87f)), mix(Offset(.22f, .88f), Offset(.2f, .87f)))
                    polygon(mix(Offset(.52f, .3f), Offset(.58f, .13f)), mix(Offset(.84f, .5f), Offset(.8f, .13f)),
                        mix(Offset(.84f, .5f), Offset(.8f, .87f)), mix(Offset(.52f, .7f), Offset(.58f, .87f)))
                } else {
                    fun direction(x: Float, y: Float) = Offset(if (name == "previous") 1 - x else x, y)
                    polygon(direction(.13f, .15f), direction(.72f, .5f), direction(.13f, .85f))
                    polygon(direction(.74f, .15f), direction(.87f, .15f), direction(.87f, .85f), direction(.74f, .85f))
                }
                drawPath(glyph, WorkoutWhite)
            }
        }
    }
}

@Composable internal fun MusicTimeline(music: NativeMusicState, visible: Boolean, command: (String, String, Double) -> Unit) {
    var seek by remember(music.id) { mutableStateOf<Float?>(null) }
    val latest by rememberUpdatedState(music)
    val available by rememberUpdatedState(visible && music.canSeek && music.duration > 0)
    val position = seek?.toLong() ?: music.position
    val fraction = if (music.duration > 0) position.toFloat() / music.duration else 0f
    Column {
        Canvas(Modifier.fillMaxWidth().height(40.dp).testTag("music-seek")
            .semantics {
                contentDescription = "Track position"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
                if (available) setProgress { value ->
                    if (!value.isFinite()) false else { command(music.id, "seek", value.coerceIn(0f, 1f) * music.duration.toDouble()); true }
                } else disabled()
            // While the player is collapsed this strip sits over the track text; an invisible
            // control must not take that touch away from the heading.
            }.then(if (!visible) Modifier else Modifier.pointerInput(music.id) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!available) return@awaitEachGesture
                    val id = latest.id; val duration = latest.duration
                    var claimed = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val point = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (point.isConsumed || event.changes.count { it.pressed } > 1 || !available || latest.id != id) break
                            val dx = point.position.x - down.position.x; val dy = point.position.y - down.position.y
                            if (!claimed && abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx)) break
                            if (abs(dx) > viewConfiguration.touchSlop || !point.pressed) claimed = true
                            if (claimed) {
                                seek = (point.position.x / size.width).coerceIn(0f, 1f) * duration
                                point.consume()
                            }
                            if (!point.pressed) {
                                if (claimed && latest.id == id) command(id, "seek", seek!!.toDouble())
                                break
                            }
                        }
                    } finally { seek = null }
                }
            })) {
            val alpha = if (music.canSeek) 1f else .4f
            drawLine(WorkoutWhite.copy(alpha = .2f * alpha), Offset(0f, center.y), Offset(size.width, center.y), 3.dp.toPx(), StrokeCap.Round)
            val x = size.width * fraction.coerceIn(0f, 1f)
            drawLine(WorkoutWhite.copy(alpha = alpha), Offset(0f, center.y), Offset(x, center.y), 3.dp.toPx(), StrokeCap.Round)
            drawCircle(WorkoutWhite.copy(alpha = alpha), 4.dp.toPx(), Offset(x, center.y))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(workoutClock(position), color = WorkoutMuted, fontSize = 10.sp)
            Text(if (music.buffering) "Buffering" else music.source, color = WorkoutMuted, fontSize = 10.sp,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(if (music.duration > 0) workoutClock(music.duration) else "Live", color = WorkoutMuted, fontSize = 10.sp)
        }
    }
}
