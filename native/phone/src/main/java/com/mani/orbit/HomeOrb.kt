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
import androidx.compose.ui.draw.drawBehind
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

/** Colour steps by depth, looked up per grain rather than computed. */
private const val RingShades = 64

/**
 * The Home ring: twisted particle ribbons flowing round the number. A swipe spins it to the next
 * metric, a tap cycles the period, and a change of number is a gust that swells it and lets it settle.
 */
@Composable
internal fun HomeOrb(summary: HomeSummary, suspended: Boolean, reduced: Boolean, modifier: Modifier,
                     deckProgress: () -> Float, deckTravel: Dp,
                     chooseMetric: (HomeMetric) -> Unit, choosePeriod: () -> Unit) {
    val section = remember { DoubleArray(12) }
    // The flow the ring was last built for, and the ring itself, recorded once per step of the flow. A
    // fold only redraws that recording at its new size: re-recording the mesh copied its 2.5 MB of
    // vertices on every frame of every deck swipe.
    val built = remember { FloatArray(2) { Float.NaN } }
    val picture = remember { android.graphics.Picture() }
    val grain = remember { DoubleArray(4) }
    // Flow time runs whether or not a finger is turning the ring; only a suspended or reduced ring
    // holds still.
    var time by remember { mutableFloatStateOf(0f) }
    // Every grain is one tiny quad in a single triangle mesh: one draw call a frame. Drawn as points,
    // the renderer prepared each of tens of thousands of grains as its own shape, which cost about
    // 35ms of rendering on every animated frame and held up every touch behind it.
    val grains = SheetCount * SheetSteps * SheetAcross
    val mesh = remember { FloatArray(grains * 12) }
    val tints = remember { IntArray(grains * 6) }
    // Colour and strength by how much light a grain catches: dark indigo mesh, bright lavender folds.
    val shades = remember { IntArray(RingShades) { ringColour(it / (RingShades - 1f)) } }
    // Grains add their light: where a sheet folds edge-on they pile up and burn toward white.
    val paint = remember { android.graphics.Paint().apply { blendMode = android.graphics.BlendMode.PLUS } }
    // A violet haze through the band, so the ring glows rather than sitting on black.
    val glow = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.RadialGradient(RingCentreX.toFloat(), RingCentreY.toFloat(), 170f,
            intArrayOf(0x006A55C8, 0x006A55C8, 0x426A55C8, 0x1A6A55C8, 0x006A55C8), floatArrayOf(0f, .38f, .64f, .85f, 1f),
            android.graphics.Shader.TileMode.CLAMP)
    } }
    var angle by remember { mutableFloatStateOf(-summary.metric.ordinal * PI.toFloat() / 2) }
    var rest by remember { mutableFloatStateOf(angle) }
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
        if (reduced) { angle = target; velocity = 0f; settling = false; return }
        settling = true
        animation = scope.launch {
            animate(angle, target, initialVelocity = speed, animationSpec = spring(1f, 144f, visibilityThreshold = .0005f)) { x, v -> angle = x; velocity = v }
            settling = false; velocity = 0f
        }
    }
    fun switch(delta: Int) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        settle(rest - delta * PI.toFloat() / 2)
        select(HomeMetric.entries[Math.floorMod(current.ordinal + delta, HomeMetric.entries.size)])
    }
    fun cycle() { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); settle(rest - .72f); period() }
    // One clock for the flow and for the slow turn at rest (a full turn every ninety seconds), so both
    // move on the same frame and the ring is rebuilt once per step rather than once for each.
    LaunchedEffect(suspended, reduced, lifecycle) {
        if (!suspended && !reduced) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous = withInfiniteAnimationFrameNanos { it }
            while (true) {
                val now = withInfiniteAnimationFrameNanos { it }
                val elapsed = (now - previous) / 1_000_000f
                // Thirty steps a second on any display: a frame early by a hair still counts, or a 60 Hz
                // screen, whose two frames come to a shade under 1/30 s, waits for a third and gets 20.
                if (elapsed >= 1000f / 30 - 3f) {
                    val step = min(elapsed, 60f)
                    time += step / 1000f
                    if (!dragging && !settling) { rest += step / 90000f * PI.toFloat() * 2; angle = rest }
                    previous = now
                }
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
        // The ring takes the whole hero area and fits itself to it, so its outer sheets have room.
        // One stable draw block. Handing the Canvas a fresh lambda on every recomposition made each
        // one — the start and end of every swipe among them — rebuild and re-render the whole ring.
        val drawRing: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = remember { {
            val scale = min(size.width / RingWidth.toFloat(), size.height / RingHeight.toFloat())
            val seconds = time.toDouble()
            if (built[0] != time || built[1] != angle) {
            var n = 0
            for (k in 0 until SheetCount) for (i in 0 until SheetSteps) {
                sheetSection(k, i * (2 * PI / SheetSteps), seconds, angle.toDouble(), 0.0, section)
                for (j in 0 until SheetAcross) {
                    sheetGrain(section, -1.0 + 2.0 * j / (SheetAcross - 1), grain)
                    val light = grain[2]
                    // Lit grains are larger as well as brighter; a glint is a bright point on a lit fold.
                    val glint = glints(k, i, j, light)
                    val h = if (glint) .62f else (.20 + light * .30).toFloat()
                    val tint = if (glint) 0xE6F6F2FF.toInt() else shades[(light * (RingShades - 1)).toInt().coerceIn(0, RingShades - 1)]
                    val x = grain[0].toFloat(); val y = grain[1].toFloat()
                    val m = n * 12
                    mesh[m] = x - h; mesh[m + 1] = y - h; mesh[m + 2] = x + h; mesh[m + 3] = y - h; mesh[m + 4] = x + h; mesh[m + 5] = y + h
                    mesh[m + 6] = x - h; mesh[m + 7] = y - h; mesh[m + 8] = x + h; mesh[m + 9] = y + h; mesh[m + 10] = x - h; mesh[m + 11] = y + h
                    val c = n * 6
                    tints[c] = tint; tints[c + 1] = tint; tints[c + 2] = tint; tints[c + 3] = tint; tints[c + 4] = tint; tints[c + 5] = tint
                    n++
                }
            }
            val recording = picture.beginRecording(RingWidth.toInt(), RingHeight.toInt())
            recording.drawCircle(RingCentreX.toFloat(), RingCentreY.toFloat(), 170f, glow)
            recording.drawVertices(android.graphics.Canvas.VertexMode.TRIANGLES, n * 12, mesh, 0, null, 0,
                tints, 0, null, 0, 0, paint)
            picture.endRecording()
            built[0] = time; built[1] = angle
            }
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                // The fold, applied here rather than to a stored picture of the ring, so the ring is
                // rendered sharp at whatever size the deck gives it. It matches the number's transform.
                val fold = 1f - .30f * deckProgress()
                native.translate(0f, -deckTravel.toPx() * deckProgress() / 2)
                native.scale(fold, fold, size.width / 2, size.height / 2)
                native.translate((size.width - RingWidth.toFloat() * scale) / 2, (size.height - RingHeight.toFloat() * scale) / 2)
                native.scale(scale, scale)
                native.drawPicture(picture)
                native.restore()
            }
        } }
        // The ring and the number shrink as one piece when the deck opens, by the same amount about the
        // same point. Scaling them differently slid the number into the ribbons, and scaling a stored
        // picture of the ring blurred it into a noisy blob, so the ring applies the fold in its own
        // drawing and is rendered sharp at every size. Its own layer means the glass sampling it
        // composites one texture rather than redrawing every grain.
        Spacer(Modifier.fillMaxSize().graphicsLayer {
            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
        }.drawBehind(drawRing))
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationY = -deckTravel.toPx() * deckProgress() / 2
            scaleX = 1f - .30f * deckProgress(); scaleY = scaleX
        }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(summary.label, color = HomeMuted, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 1.5.sp)
            if (summary.metric == HomeMetric.Sleep) Text(summary.primary, color = HomeWhite, fontSize = 48.sp)
            else OrbitDottedText(summary.primary, Modifier.fillMaxWidth(.82f).height(68.dp), HomeWhite)
            Text(summary.caption, color = HomeMuted, fontSize = 11.sp, lineHeight = 16.sp)
            Text(if (summary.period == 1) "Daily view" else "${summary.period}-day view", color = HomeAccent, fontSize = 11.sp, lineHeight = 16.sp)
        }
        }
    }
}
