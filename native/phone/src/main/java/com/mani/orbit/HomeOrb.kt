package com.mani.orbit

import androidx.compose.animation.core.animate
import androidx.compose.animation.togetherWith
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

/** Radians the storm turns for each dp a finger drags it. */
private const val DragTurn = .009f

/**
 * The Home ring: the owner's storm rolling round the number (see [Storm]). A swipe spins it to the next
 * metric, and a tap nudges it round and cycles the period.
 */
@Composable
internal fun HomeOrb(summary: HomeSummary, suspended: Boolean, reduced: Boolean, modifier: Modifier,
                     deckProgress: () -> Float, deckTravel: Dp,
                     chooseMetric: (HomeMetric) -> Unit, choosePeriod: () -> Unit) {
    // The storm, read from the app's assets off the main thread; the ring draws nothing until it is in.
    val assets = androidx.compose.ui.platform.LocalContext.current.assets
    val storm by produceState<Storm?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Storm.load(assets).also { it.prepare(0.0); it.show() }
        }
    }
    // Counts frames the storm has put on screen; the drawing reads it to know a new one is in.
    var frames by remember { mutableIntStateOf(0) }
    val building = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    // The flow the ring was last built for, and the ring itself, recorded once per step of the flow. A
    // fold only redraws that recording at its new size: re-recording the ring on every frame of a deck
    // swipe copied all of it every time.
    val built = remember { FloatArray(2) { Float.NaN } }
    val picture = remember { android.graphics.Picture() }
    // Flow time runs whether or not a finger is turning the ring; only a suspended or reduced ring
    // holds still.
    var time by remember { mutableFloatStateOf(0f) }
    // Glow, dots and lightning all add their light to the dark page.
    val glowPaint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
        blendMode = android.graphics.BlendMode.PLUS } }
    // Made once the storm is in: the draw block is made once and must not hold a shader from before.
    val glowShaders = remember { arrayOfNulls<android.graphics.BitmapShader>(1) }
    val glowTurn = remember { android.graphics.Matrix() }
    val lightColours = remember { IntArray(LightSteps + 1) }
    val lightStops = remember { FloatArray(LightSteps + 1) { it / LightSteps.toFloat() } }
    val dotPaint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
        shader = android.graphics.BitmapShader(Storm.dotTexture(), android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        blendMode = android.graphics.BlendMode.PLUS } }
    val flashPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        blendMode = android.graphics.BlendMode.PLUS } }
    // How far a swipe has turned the storm. It has no set positions to return to: a flick spins it and
    // it coasts to a stop, the way stirred cloud does, and a metric is never a place on the ring.
    var angle by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf(false) }
    // Which way the last swipe went, so the new figure arrives from that side.
    var direction by remember { mutableIntStateOf(1) }
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
    fun coast(speed: Float) {
        animation?.cancel()
        if (reduced) { velocity = 0f; settling = false; return }
        settling = true
        animation = scope.launch {
            androidx.compose.animation.core.animateDecay(angle, speed,
                androidx.compose.animation.core.FloatExponentialDecaySpec(frictionMultiplier = 1.4f)) { x, v -> angle = x; velocity = v }
            settling = false; velocity = 0f
        }
    }
    /** Lightning where the storm was touched: [screen] is the angle on screen, radians from the right. */
    fun strikeAt(screen: Float) { storm?.strike(time.toDouble(), (screen - angle).toDouble()) }
    fun switch(delta: Int, speed: Float = -delta * 2.6f, at: Float = if (delta > 0) PI.toFloat() else 0f) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        direction = delta
        // At least a firm spin the way the finger went, however gently it let go.
        coast(if (abs(speed) < 2.2f) -delta * 2.2f else speed)
        strikeAt(at)
        select(HomeMetric.entries[Math.floorMod(current.ordinal + delta, HomeMetric.entries.size)])
    }
    fun cycle() { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); coast(-1.8f); period() }
    // The storm's clock: its particles stream and its form turns by themselves, so nothing else turns
    // the ring at rest.
    LaunchedEffect(suspended, reduced, lifecycle) {
        if (!suspended && !reduced) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous = withInfiniteAnimationFrameNanos { it }
            while (true) {
                val now = withInfiniteAnimationFrameNanos { it }
                val elapsed = (now - previous) / 1_000_000f
                // Sixty steps a second on any display, so particles glide rather than hop: a frame early
                // by a hair still counts, or a screen whose frames come a shade short waits for the next.
                if (elapsed >= 1000f / 60 - 2f) {
                    val step = min(elapsed, 60f)
                    time += step / 1000f
                    previous = now
                    // The next storm is built off the main thread; a frame still building is not waited on.
                    val ring = storm
                    if (ring != null && building.compareAndSet(false, true)) {
                        val at = time.toDouble()
                        launch(kotlinx.coroutines.Dispatchers.Default) {
                            try {
                                ring.prepare(at)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { ring.show(); frames++ }
                            } finally { building.set(false) }
                        }
                    }
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
                            val spin = (speed * 1000 * DragTurn).coerceIn(-12f, 12f)
                            val lift = change.position - androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                            if (turn != 0) switch(turn, spin, atan2(lift.y, lift.x)) else coast(spin)
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
                        if (!reduced) angle = start + delta.x / density.density * DragTurn
                        change.consume()
                    }
                }
            } finally { dragging = false; if (!completed) coast(0f) }
        }
    }.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { cycle() }, contentAlignment = Alignment.Center) {
        // The ring takes the whole hero area and fits itself to it, so its outer sheets have room.
        // One stable draw block. Handing the Canvas a fresh lambda on every recomposition made each
        // one — the start and end of every swipe among them — rebuild and re-render the whole ring.
        val drawRing: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = remember { {
            // A little room round the storm, so its outer glow and dust never meet the screen's edge.
            val scale = min(size.width / RingWidth.toFloat(), size.height / RingHeight.toFloat()) * .94f
            val ring = storm
            frames
            val shown = ring?.shown
            if (ring != null && shown != null && built[0] != shown.time.toFloat()) {
                val recording = picture.beginRecording(RingWidth.toInt(), RingHeight.toInt())
                // The cloud's purple light comes and goes with the storm: the glow turns with the form,
                // lit round the ring by how much light the storm has there this moment.
                val glowShader = glowShaders[0] ?: android.graphics.BitmapShader(ring.glow, android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP).also { glowShaders[0] = it }
                glowTurn.setRotate(Math.toDegrees(shown.formTurn).toFloat(), RingCentreX.toFloat(), RingCentreY.toFloat())
                glowShader.setLocalMatrix(glowTurn)
                for (k in 0 until LightSteps) {
                    val level = (255 * (.3f + .7f * min(1f, shown.light[k]))).toInt()
                    lightColours[k] = (0xFF shl 24) or (level shl 16) or (level shl 8) or level
                }
                lightColours[LightSteps] = lightColours[0]
                glowPaint.shader = android.graphics.ComposeShader(glowShader,
                    android.graphics.SweepGradient(RingCentreX.toFloat(), RingCentreY.toFloat(), lightColours, lightStops),
                    android.graphics.BlendMode.MODULATE)
                recording.drawCircle(RingCentreX.toFloat(), RingCentreY.toFloat(), 241f, glowPaint)
                recording.drawVertices(android.graphics.Canvas.VertexMode.TRIANGLES, ring.count * 8, shown.dotMesh, 0,
                    ring.dotTexture, 0, shown.dotTint, 0, ring.dotOrder, 0, ring.count * 6, dotPaint)
                // Lightning lights the cloud round the strike from inside.
                val flash = shown.flash
                if (flash[0] > .02) {
                    val light = (min(1.0, flash[0]) * 110).toInt()
                    flashPaint.shader = android.graphics.RadialGradient(flash[1].toFloat(), flash[2].toFloat(), 120f,
                        intArrayOf((light shl 24) or 0x9E96D8, ((light / 3) shl 24) or 0x7C72C0, 0x007C72C0), floatArrayOf(0f, .45f, 1f),
                        android.graphics.Shader.TileMode.CLAMP)
                    recording.drawCircle(flash[1].toFloat(), flash[2].toFloat(), 120f, flashPaint)
                }
                picture.endRecording()
                built[0] = shown.time.toFloat()
            }
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                // The fold, applied here rather than to a stored picture of the ring, so the ring is
                // rendered sharp at whatever size the deck gives it. It matches the number's transform.
                val fold = 1f - .30f * deckProgress()
                native.scale(fold, fold, size.width / 2, size.height / 2)
                native.translate((size.width - RingWidth.toFloat() * scale) / 2, (size.height - RingHeight.toFloat() * scale) / 2)
                native.scale(scale, scale)
                // A swipe or a tap turns the whole storm; it is drawn turned, never rebuilt for it.
                native.rotate(Math.toDegrees(angle.toDouble()).toFloat(), RingCentreX.toFloat(), RingCentreY.toFloat())
                native.drawPicture(picture)
                native.restore()
            }
        } }
        // The ring and the number shrink as one piece when the deck opens, by the same amount about the
        // same point. Scaling them differently slid the number into the ribbons, and scaling a stored
        // picture of the ring blurred it into a noisy blob, so the ring applies the fold in its own
        // drawing and is rendered sharp at every size. Its own layer means the glass sampling it
        // composites one texture rather than redrawing every grain.
        // The fold lifts the ring's whole layer: moving it inside its own layer cut off its top.
        Spacer(Modifier.fillMaxSize().graphicsLayer {
            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
            translationY = -deckTravel.toPx() * deckProgress() / 2
        }.drawBehind(drawRing))
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationY = -deckTravel.toPx() * deckProgress() / 2
            scaleX = 1f - .30f * deckProgress(); scaleY = scaleX
        }, contentAlignment = Alignment.Center) {
        androidx.compose.animation.AnimatedContent(summary, contentKey = { it.metric }, label = "Home figure",
            transitionSpec = {
                val from = direction
                if (reduced) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                else (androidx.compose.animation.slideInHorizontally(spring(1f, 380f)) { it / 3 * from } +
                    androidx.compose.animation.fadeIn(spring(1f, 380f))) togetherWith
                    (androidx.compose.animation.slideOutHorizontally(spring(1f, 380f)) { -it / 3 * from } +
                        androidx.compose.animation.fadeOut(spring(1f, 500f))) using
                    androidx.compose.animation.SizeTransform(clip = false)
            }) { shown ->
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(shown.label, color = HomeMuted, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 1.5.sp)
            // Sized to the storm's hole, as the reference's figure is: a long number steps its size down.
            OrbitDottedText(shown.primary, Modifier.fillMaxWidth(.52f).height(68.dp), HomeWhite)
            Text(shown.caption, color = HomeMuted, fontSize = 11.sp, lineHeight = 16.sp)
            Text(if (shown.period == 1) "Daily view" else "${shown.period}-day view", color = HomeAccent, fontSize = 11.sp, lineHeight = 16.sp)
        }
        }
        }
    }
}
