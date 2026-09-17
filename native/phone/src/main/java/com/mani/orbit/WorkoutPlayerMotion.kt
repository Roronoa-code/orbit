package com.mani.orbit

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs
import kotlin.math.sign

internal class WorkoutPlayerMotion(val motion: ExploreMotion) {
    var expanded by mutableStateOf(false)
    var shown by mutableStateOf(false)
    var thumb by mutableStateOf(Rect.Zero)
    var reduced = false
    fun target(open: Boolean, velocity: Float = motion.velocity) {
        expanded = open; motion.settle(open, velocity, reduced)
    }
    fun back(): Boolean {
        if (!expanded && motion.value < .01f) return false
        target(false); return true
    }
}

@Composable internal fun rememberWorkoutPlayer(id: String?): WorkoutPlayerMotion {
    val scope = rememberCoroutineScope()
    var saved by rememberSaveable { mutableStateOf(false) }
    var lastSession by rememberSaveable { mutableStateOf(id) }
    val state = remember { WorkoutPlayerMotion(ExploreMotion(scope, saved)).apply { expanded = saved } }
    state.reduced = LocalOrbitReducedMotion.current
    LaunchedEffect(id) {
        if (id != null && id != lastSession) { state.expanded = false; state.motion.snap(false); lastSession = id }
    }
    LaunchedEffect(state.expanded) { saved = state.expanded }
    DisposableEffect(state) { onDispose { state.motion.snap(false) } }
    return state
}

/** The finger owns one material coordinate, including a grab during an unfinished release. */
internal fun Modifier.workoutPlayerDrag(player: WorkoutPlayerMotion, travel: Float): Modifier = pointerInput(player, travel) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val motion = player.motion
        val start = motion.value
        val wasOpen = player.expanded
        val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
        var claimed = false
        var released = false
        var lastMove = down.uptimeMillis
        // Stop a settling material at contact without changing its pose.
        motion.grab()
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (event.changes.count { it.pressed } > 1 || change.isConsumed) break
                val dy = change.position.y - down.position.y
                val dx = change.position.x - down.position.x
                if (!change.pressed) {
                    released = true
                    if (claimed) {
                        val velocity = if (change.uptimeMillis - lastMove > 100) 0f else -tracker.calculateVelocity().y / travel
                        player.target(motion.value + velocity.coerceIn(-4f, 4f) * .16f > .5f, velocity)
                        change.consume()
                    } else if (player.expanded == wasOpen) motion.settle(wasOpen, reduced = player.reduced)
                    break
                }
                if (!claimed && abs(dx) > viewConfiguration.touchSlop && abs(dx) > abs(dy)) break
                if (!claimed && abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx)) claimed = true
                if (claimed) {
                    tracker.addPosition(change.uptimeMillis, change.position)
                    if (change.position != change.previousPosition) lastMove = change.uptimeMillis
                    motion.move(start - (dy - sign(dy) * viewConfiguration.touchSlop) / travel, -tracker.calculateVelocity().y / travel)
                    change.consume()
                }
            }
        // A child tap (music text, timer) owns its own focus change; releasing this detector must not undo it.
        } finally { if (!released && player.expanded == wasOpen) motion.settle(wasOpen, 0f, player.reduced) }
    }
}
