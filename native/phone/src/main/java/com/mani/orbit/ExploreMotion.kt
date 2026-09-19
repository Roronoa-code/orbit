package com.mani.orbit

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

    /** A finger is on the shell and has not asked it to go anywhere yet. */
    var pressing by mutableStateOf(false)
        private set

    /**
     * How far the shell has gathered itself, 0..1.
     *
     * This is squash and stretch on a single travel, not a stage before it: the gather runs
     * alongside the journey, peaks early and fills out again as the shell arrives. Running it first
     * and the travel second is what made one gesture read as two states. A cancelled gather never
     * reaches its spring, so a rapid reversal cannot resume a stale one.
     */
    var squeeze by mutableFloatStateOf(0f)
        private set

    fun engage() {
        NativeDiagnostics.mark(operation, TraceStage.SUPERSEDED)
        operation = NativeDiagnostics.begin(TraceFeature.EXPLORE, route)
    }
    fun dispose() { animation?.cancel(); NativeDiagnostics.mark(operation, TraceStage.CANCELLED) }
    fun cancel() { NativeDiagnostics.mark(operation, TraceStage.CANCELLED) }

    /**
     * The shell gathers under the finger and waits there.
     *
     * A press loads the spring; the release lets it go. The travel then continues from the pose the
     * finger left rather than starting from rest, so one contact reads as one movement.
     */
    fun press() {
        if (dragging) return
        engage(); animation?.cancel(); pressing = true
        animation = scope.launch { animate(squeeze, 1f, animationSpec = tween(120, easing = OrbitPressEasing)) { v, _ -> squeeze = v } }
    }

    /** The finger left without moving the shell and without asking it to travel. */
    fun relax() {
        if (!pressing) return
        pressing = false
        animation?.cancel()
        animation = scope.launch { animate(squeeze, 0f, animationSpec = orbitSettle()) { v, _ -> squeeze = v } }
    }

    fun grab() { engage(); animation?.cancel(); pressing = false; dragging = true; squeeze = 0f; phase = TraceGesture.DRAG }
    fun snap(open: Boolean) {
        animation?.cancel(); pressing = false; dragging = false; value = if (open) 1f else 0f; velocity = 0f; squeeze = 0f
        phase = TraceGesture.IDLE; settledOperation = operation
    }
    fun move(position: Float, speed: Float) { value = position.coerceIn(0f, 1f); velocity = speed.coerceIn(-4f, 4f) }
    fun settle(open: Boolean, speed: Float = velocity, reduced: Boolean = false, morph: Boolean = false) {
        NativeDiagnostics.mark(operation, TraceStage.COMMAND_REQUESTED)
        if (reduced) { snap(open); return }
        animation?.cancel()
        pressing = false
        dragging = false
        phase = TraceGesture.SETTLING
        val settling = operation
        animation = scope.launch {
            if (morph) launch {
                animate(squeeze, 1f, animationSpec = tween(90, easing = OrbitPressEasing)) { v, _ -> squeeze = v }
                animate(squeeze, 0f, animationSpec = orbitSettle(open)) { v, _ -> squeeze = v }
            }
            animate(value, if (open) 1f else 0f, initialVelocity = speed,
                animationSpec = if (morph) orbitSettle(open) else spring(1f, 289f, .001f)) { pose, rate ->
                value = pose; velocity = rate
            }
            phase = TraceGesture.IDLE; settledOperation = settling
        }
    }
}

internal fun exploreRelease(position: Float, velocity: Float, distance: Float, wasOpen: Boolean): Boolean =
    if (kotlin.math.abs(distance) < 24f) wasOpen else position + velocity.coerceIn(-4f, 4f) * .16f > .5f

internal fun reveal(value: Float, start: Float, end: Float): Float {
    val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}
