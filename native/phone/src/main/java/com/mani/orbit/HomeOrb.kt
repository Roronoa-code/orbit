package com.mani.orbit

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.*

/** Same 646-point projection as signal-orb.js, batched into sixteen native paths. */
@Composable
internal fun HomeOrb(summary: HomeSummary, suspended: Boolean, reduced: Boolean, modifier: Modifier,
                     deckProgress: () -> Float, deckTravel: Dp,
                     chooseMetric: (HomeMetric) -> Unit, choosePeriod: () -> Unit) {
    val points = remember { Array(646) { i ->
        val lat = (i / 34 / 18.0 - .5) * PI; val lon = i % 34 / 34.0 * PI * 2
        doubleArrayOf(cos(lat) * cos(lon), sin(lat), cos(lat) * sin(lon))
    } }
    val bands = remember { Array(16) { android.graphics.Path() } }
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8E8EE.toInt() } }
    var angle by remember { mutableFloatStateOf(-summary.metric.ordinal * PI.toFloat() / 2) }
    var rest by remember { mutableFloatStateOf(angle) }
    var breath by remember { mutableFloatStateOf(0f) }
    var breathVelocity by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var velocity by remember { mutableFloatStateOf(0f) }
    var animation by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current by rememberUpdatedState(summary.metric)
    val select by rememberUpdatedState(chooseMetric)
    val period by rememberUpdatedState(choosePeriod)
    fun settle(target: Float, speed: Float = velocity) {
        animation?.cancel(); rest = target
        if (reduced) { angle = target; breath = 0f; breathVelocity = 0f; velocity = 0f; settling = false; return }
        settling = true
        animation = scope.launch {
            val breathing = launch {
                animate(breath, 0f, initialVelocity = breathVelocity, animationSpec = spring(1f, 169f, visibilityThreshold = .0005f)) { x, v -> breath = x; breathVelocity = v }
            }
            animate(angle, target, initialVelocity = speed, animationSpec = spring(1f, 144f, visibilityThreshold = .0005f)) { x, v -> angle = x; velocity = v }
            breathing.join()
            settling = false; velocity = 0f
        }
    }
    fun switch(delta: Int) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        breathVelocity -= 1.1f
        settle(rest - delta * PI.toFloat() / 2)
        select(HomeMetric.entries[Math.floorMod(current.ordinal + delta, HomeMetric.entries.size)])
    }
    fun cycle() { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); breathVelocity -= 1.35f; settle(rest - .72f); period() }
    LaunchedEffect(suspended, reduced, dragging, settling, lifecycle) {
        if (!suspended && !reduced && !dragging && !settling) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous = withInfiniteAnimationFrameNanos { it }
            while (true) {
                val now = withInfiniteAnimationFrameNanos { it }
                val elapsed = (now - previous) / 1_000_000f
                if (elapsed >= 1000f / 30) { rest += min(elapsed, 60f) / 90000f * PI.toFloat() * 2; angle = rest; previous = now }
            }
        }
    }
    Box(modifier.testTag("home-orb").semantics {
        contentDescription = "${summary.metric.title}, ${summary.period}-day view"
        customActions = listOf(CustomAccessibilityAction("Next metric") { switch(1); true }, CustomAccessibilityAction("Previous metric") { switch(-1); true })
    }.onKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key in listOf(Key.DirectionLeft, Key.DirectionRight)) { switch(if (it.key == Key.DirectionRight) 1 else -1); true } else false
    }.pointerInput(reduced) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            animation?.cancel(); settling = false; dragging = true
            val start = angle
            val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            var owned = false
            var completed = false
            try {
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed || change.id != down.id) break
                    val delta = change.position - down.position
                    if (!change.pressed) {
                        if (owned) {
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val speed = tracker.calculateVelocity().x / density.density / 1000
                            val dx = delta.x / density.density
                            val turn = if (abs(dx) >= 45 || abs(dx) >= 12 && abs(speed) > .45 && sign(dx) == sign(speed)) if (dx < 0) 1 else -1 else 0
                            velocity = (speed * 13).coerceIn(-15f, 15f)
                            if (turn != 0) switch(turn) else settle(rest)
                            change.consume(); completed = true
                        }
                        break
                    }
                    if (!owned && abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > abs(delta.x)) break
                    if (!owned && abs(delta.x) > viewConfiguration.touchSlop && abs(delta.x) > abs(delta.y) * 1.2) {
                        owned = true; animation?.cancel(); settling = false; dragging = true
                    }
                    if (owned) {
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!reduced) angle = start + (delta.x / density.density * .013f).coerceIn(-1.7f, 1.7f)
                        change.consume()
                    }
                }
            } finally { dragging = false; if (!completed) settle(rest, 0f) }
        }
    }.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { cycle() }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(290.dp).graphicsLayer {
            translationY = -deckTravel.toPx() * deckProgress() / 2
            scaleX = 1f - .24f * deckProgress(); scaleY = scaleX
        }) {
            val scale = min(size.width / 340, size.height / 260)
            val cosine = cos(angle.toDouble()); val sine = sin(angle.toDouble())
            for (path in bands) path.rewind()
            for (point in points) {
                val x = point[0] * cosine + point[2] * sine
                val depth = -point[0] * sine + point[2] * cosine
                val perspective = 1 / (1 - depth * .12)
                val band = floor((depth + 1) / 2 * bands.size).toInt().coerceIn(bands.indices)
                bands[band].addCircle((170 + x * 120 * perspective * (1 + breath)).toFloat(), (130 + point[1] * 111 * perspective * (1 + breath)).toFloat(),
                    (.65 + (depth + 1) * .5).toFloat(), android.graphics.Path.Direction.CW)
            }
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save(); native.translate((size.width - 340 * scale) / 2, (size.height - 260 * scale) / 2); native.scale(scale, scale)
                for (i in bands.indices) { paint.alpha = ((.1 + (i + .5) / 16 * .66) * 255).roundToInt(); native.drawPath(bands[i], paint) }
                native.restore()
            }
        }
        Column(Modifier.graphicsLayer {
            translationY = -deckTravel.toPx() * deckProgress() / 2
            scaleX = 1f - .14f * deckProgress(); scaleY = scaleX
        }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(summary.label, color = HomeMuted, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 1.5.sp)
            if (summary.metric == HomeMetric.Sleep) Text(summary.primary, color = HomeWhite, fontSize = 48.sp)
            else OrbitDottedText(summary.primary, Modifier.fillMaxWidth(.82f).height(68.dp), HomeWhite)
            Text(summary.caption, color = HomeMuted, fontSize = 11.sp, lineHeight = 16.sp)
            Text(if (summary.period == 1) "Daily view" else "${summary.period}-day view", color = HomeAccent, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}
