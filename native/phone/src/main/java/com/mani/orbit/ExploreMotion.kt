package com.mani.orbit

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.mani.orbit.sync.*

/** One position/velocity survives every grab, reversal and cancellation. */
internal class ExploreMotion(private val scope: CoroutineScope, initial: Boolean) {
    var value by mutableFloatStateOf(if (initial) 1f else 0f)
        private set
    var velocity = 0f
        private set
    var dragging by mutableStateOf(false)
        private set
    private var animation: Job? = null
    private var operation = 0L
    var route = TraceRoute.OTHER
    var settledOperation by mutableLongStateOf(0)
        private set
    var phase by mutableStateOf(TraceGesture.IDLE)
        private set

    /**
     * How far the shell is squeezed out of its own footprint, 0..1.
     *
     * The morph is two phases: the shell compresses into a small pill, then springs from there to
     * its destination and fills out again. A cancelled compression never reaches its spring, so a
     * rapid reversal cannot resume a stale second phase.
     */
    var squeeze by mutableFloatStateOf(0f)
        private set

    fun engage() {
        NativeDiagnostics.mark(operation, TraceStage.SUPERSEDED)
        operation = NativeDiagnostics.begin(TraceFeature.EXPLORE, route)
    }
    fun dispose() { animation?.cancel(); NativeDiagnostics.mark(operation, TraceStage.CANCELLED) }
    fun cancel() { NativeDiagnostics.mark(operation, TraceStage.CANCELLED) }

    fun grab() { engage(); animation?.cancel(); dragging = true; squeeze = 0f; phase = TraceGesture.DRAG }
    fun snap(open: Boolean) {
        animation?.cancel(); dragging = false; value = if (open) 1f else 0f; velocity = 0f; squeeze = 0f
        phase = TraceGesture.IDLE; settledOperation = operation
    }
    fun move(position: Float, speed: Float) { value = position.coerceIn(0f, 1f); velocity = speed.coerceIn(-4f, 4f) }
    fun settle(open: Boolean, speed: Float = velocity, reduced: Boolean = false, morph: Boolean = false) {
        NativeDiagnostics.mark(operation, TraceStage.COMMAND_REQUESTED)
        if (reduced) { snap(open); return }
        animation?.cancel()
        dragging = false
        phase = TraceGesture.SETTLING
        val settling = operation
        animation = scope.launch {
            if (morph) {
                animate(squeeze, 1f, animationSpec = tween(100, easing = CubicBezierEasing(.4f, 0f, .2f, 1f))) { v, _ -> squeeze = v }
                launch { animate(squeeze, 0f, animationSpec = spring(if (open) .76f else .85f, 289f, .001f)) { v, _ -> squeeze = v } }
            }
            animate(value, if (open) 1f else 0f, initialVelocity = speed,
                animationSpec = spring(dampingRatio = if (morph) (if (open) .76f else .85f) else 1f,
                    stiffness = 289f, visibilityThreshold = .001f)) { pose, rate ->
                value = pose; velocity = rate
            }
            phase = TraceGesture.IDLE; settledOperation = settling
        }
    }
}

/** Direct finger tracking plus a decaying grab offset preserves an interrupted lens's pose. */
internal class ExploreContact(private val scope: CoroutineScope) {
    var position by mutableFloatStateOf(0f)
        private set
    private var velocity = 0f
    private var raw = 0f
    private var correction = 0f
    private var dragging = false
    private var animation: Job? = null
    fun press(slot: Float, visible: Boolean, reduced: Boolean = false) {
        animation?.cancel(); dragging = false
        if (!visible) { position = slot; velocity = 0f }
        settle(slot, velocity, reduced)
    }
    fun move(finger: Float, speed: Float, reduced: Boolean = false) {
        if (reduced) { animation?.cancel(); dragging = true; position = finger; raw = finger; correction = 0f; velocity = 0f; return }
        raw = finger
        if (!dragging) {
            animation?.cancel(); dragging = true
            correction = position - finger
            animation = scope.launch {
                animate(correction, 0f, animationSpec = spring(1f, 650f, visibilityThreshold = .001f)) { offset, _ ->
                    correction = offset; position = raw + correction
                }
            }
        }
        position = raw + correction; velocity = speed
    }
    fun settle(slot: Float, speed: Float = 0f, reduced: Boolean = false) {
        animation?.cancel(); dragging = false
        if (reduced) { position = slot; velocity = 0f; return }
        animation = scope.launch {
            animate(position, slot, initialVelocity = speed, animationSpec = spring(.72f, 320f, visibilityThreshold = .001f)) { x, v ->
                position = x; velocity = v
            }
        }
    }
}

internal fun exploreRelease(position: Float, velocity: Float, distance: Float, wasOpen: Boolean): Boolean =
    if (kotlin.math.abs(distance) < 24f) wasOpen else position + velocity.coerceIn(-4f, 4f) * .16f > .5f

internal fun reveal(value: Float, start: Float, end: Float): Float {
    val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}
