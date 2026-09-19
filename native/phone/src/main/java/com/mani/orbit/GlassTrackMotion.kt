package com.mani.orbit

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * How the selection of every track in the app moves: the navigation bar's and every segmented
 * control's. It is the owner's US app's navigation (components/useBlobTrack.ts, lib/blob-travel.ts,
 * lib/glass-pickup.ts) as their BitChord app carries it into Compose (ui/components/blobtrack), and
 * it keeps BitChord's hold: the lens rises past the rail on every side instead of pinching in.
 *
 * One value, the pickup, says how far the selection has lifted off the rail into glass. It drives the
 * lens's size, its light, its shadow and its refraction, so they can never disagree. A flat pill at
 * rest, a lens while a finger holds or carries it, and a lens that descends as it travels and is flat
 * again on the frame it arrives.
 *
 * Everything runs on one frame clock, and nothing here recomposes: the box and the pickup are read
 * only while placing and drawing.
 */

/** A committed journey between slots, in seconds: the US app's Full timing. */
internal const val TrackTravelSeconds = .36f
/** A carried or held lens let go over its own slot. */
internal const val TrackSettleSeconds = .28f
/** A flat lean let go without going anywhere. */
internal const val TrackAnticipationSeconds = .14f

/** A press on a neighbouring slot leans the pill toward it by this much of its own width, and stays flat. */
private const val Pull = .05f
private const val StretchMax = .16f
/** Finger speed, px/s, at which a carried pill is stretched the most. */
private const val StretchAt = 2400f
private const val Squash = .45f
private const val StretchEasing = .25f
private const val SquashMax = .34f
private const val SquashBulge = .30f
/** How far a flick carries on after the finger leaves, in seconds of its speed. */
private const val Projection = .12f
/** How much the navigation rail draws in under a fully lifted lens. */
internal const val TrackRecede = .05f
/** The hold's own spring: BitChord's press, a touch of give and no wobble. */
private const val HoldStiffness = 260f
private const val HoldDamping = .78f

/** Fast, then slow, then slower: quadratic, so a quarter of the way is left for the second half. */
internal fun travelEase(progress: Float): Float {
    val t = progress.coerceIn(0f, 1f)
    return t * (2 - t)
}

private fun smoothstep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/**
 * The glass through a journey. A held lens descends from wherever it is; a tapped one gains glass
 * near departure and descends while still moving. Either way it is flat on the arrival frame, with no
 * second settle after it.
 */
internal fun travelPickup(progress: Float, initial: Float): Float {
    val t = progress.coerceIn(0f, 1f)
    val start = initial.coerceIn(0f, 1f)
    return (start + (1 - start) * smoothstep(t / .16f)) * (1 - smoothstep(t))
}

/**
 * One exact step of a critically damped spring toward [target], into [out] as value then velocity.
 * Velocity survives a change of mind, so a quick release and re-grab never restarts the glass.
 */
internal fun stepPickup(value: Float, velocity: Float, target: Float, seconds: Float, out: FloatArray) {
    val dt = seconds.coerceIn(0f, .064f)
    val omega = 30f
    val displacement = value - target
    val c = velocity + omega * displacement
    val decay = exp(-omega * dt)
    var next = target + (displacement + c * dt) * decay
    var speed = (velocity - omega * c * dt) * decay
    if (abs(next - target) < .0005f && abs(speed) < .02f) { next = target; speed = 0f }
    if (next < 0f || next > 1f) { next = next.coerceIn(0f, 1f); speed = 0f }
    out[0] = next; out[1] = speed
}

/** Resistance that grows with the overshoot and flattens out: a pill pushed past the end gives, never escapes. */
internal fun rubberband(overshoot: Float, dimension: Float, c: Float = .55f): Float =
    if (dimension <= 0f) 0f else overshoot * dimension * c / (dimension + c * abs(overshoot))

@Stable
internal class GlassTrackState(val count: Int, private val scope: CoroutineScope) {
    // Written by the track as it lays out and recomposes.
    private var size = IntSize.Zero
    private var inset = 0f
    private var gap = 0f
    /** How far a fully lifted lens reaches past its pill on every side, px. */
    private var reach = 0f
    private var dragStart = 1f
    /** Whether the lens is kept inside the rail: a control inside a page has nothing to show past it. */
    private var contained = false
    /** A round pill of this size centred in its slot, px, instead of one filling the slot; 0 fills it. */
    private var round = 0f
    /** Which slots can be chosen: a day still to come cannot. */
    internal var allowed: (Int) -> Boolean = { true }
    internal var reduced = false
    internal var tick: () -> Unit = {}
    internal var commit: (Int) -> Unit = {}
    /** A deliberate tap on the slot already chosen. */
    internal var again: (Int) -> Unit = {}

    /** The chosen slot, or -1 when none is: a route no section owns. Labels are coloured from it as they draw. */
    var selected by mutableIntStateOf(-1)
        private set

    // The pill's own box, px in the track, before its lift and its stretch.
    private var left by mutableFloatStateOf(0f)
    private var top by mutableFloatStateOf(0f)
    private var width by mutableFloatStateOf(0f)
    private var height by mutableFloatStateOf(0f)
    private var stretch by mutableFloatStateOf(0f)

    /**
     * A selection that exists only while a finger has it. With nothing chosen, as on Home in the
     * Explore island, the lens rises from nothing where the finger lands and sinks away if let go.
     */
    var transient by mutableStateOf(false)
        private set

    /** Whether there is a selection to draw at all. */
    val hasPill: Boolean get() = selected >= 0 || transient

    /** How far the selection has lifted into glass: 0 a flat pill, 1 a held lens. */
    var pickup by mutableFloatStateOf(0f)
        private set

    var dragging = false
        private set

    private enum class Mode { Rest, Hold, Drag, Travel }
    private var mode = Mode.Rest
    private val box = FloatArray(4)
    private val goal = FloatArray(4)
    private val speed = FloatArray(4)
    private val from = FloatArray(4)
    private val to = FloatArray(4)
    private val scratch = FloatArray(2)
    private var elapsed = 0f
    private var duration = 1f
    private var startPickup = 0f
    private var lifts = false
    private var pickupSpeed = 0f
    private var pickupGoal = 0f
    private var stretchGoal = 0f
    private var sinceMove = 0f
    private var grabbed = false
    private var grab = 0f
    /** A pill carried from away from the finger closes that gap on its own rather than jumping. */
    private var correction = 0f
    private var downX = 0f
    private var downY = 0f
    private var fingerX = 0f
    private var over = -1
    private var job: Job? = null

    fun slotWidth(): Float = if (count <= 0) 0f else ((size.width - 2 * inset - gap * (count - 1)) / count).coerceAtLeast(0f)
    fun slotLeft(index: Int): Float = inset + index * (slotWidth() + gap)
    private fun slotHeight(): Float = (size.height - 2 * inset).coerceAtLeast(0f)

    fun slotAt(x: Float): Int {
        val step = slotWidth() + gap
        if (count <= 0 || step <= 0f) return -1
        return ((x - inset + gap / 2) / step).toInt().coerceIn(0, count - 1)
    }

    private fun pillWidth(): Float = if (round > 0f) min(round, slotWidth()) else slotWidth()
    private fun pillHeight(): Float = if (round > 0f) min(round, slotHeight()) else slotHeight()

    private fun rest(index: Int, out: FloatArray) {
        out[2] = pillWidth(); out[3] = pillHeight()
        out[0] = slotLeft(index) + (slotWidth() - out[2]) / 2; out[1] = inset + (slotHeight() - out[3]) / 2
    }

    private fun show(values: FloatArray) {
        left = values[0]; top = values[1]; width = values[2]; height = values[3]
    }

    private fun current(out: FloatArray) {
        out[0] = left; out[1] = top; out[2] = width; out[3] = height
    }

    /**
     * The pill as it is drawn, into [out] as left, top, width, height: its box, stretched along its
     * travel and squashed across it, then grown on every side as it lifts.
     */
    fun presentation(out: FloatArray) {
        val w = width * (1 + stretch)
        val h = height * (1 - stretch * Squash)
        var l = left - (w - width) / 2
        var t = top + (height - h) / 2
        val grow = reach * pickup
        var pw = w + 2 * grow
        var ph = h + 2 * grow
        l -= grow; t -= grow
        if (contained && size != IntSize.Zero) {
            pw = pw.coerceAtMost(size.width.toFloat()); ph = ph.coerceAtMost(size.height.toFloat())
            l = l.coerceIn(0f, size.width - pw); t = t.coerceIn(0f, size.height - ph)
        }
        out[0] = l; out[1] = t; out[2] = pw; out[3] = ph
    }

    /** The lens's centre across the track, 0..1: where the rim light and the rail's recede pivot sit. */
    fun centre(): Float = if (size.width > 0) (pivot() / size.width).coerceIn(0f, 1f) else .5f

    /** The lens's centre, px in the track. */
    fun pivot(): Float = (left + width / 2).coerceIn(0f, size.width.toFloat())

    fun layout(size: IntSize, inset: Float, gap: Float, reach: Float, dragStart: Float, contained: Boolean, round: Float = 0f) {
        val moved = size != this.size || inset != this.inset || gap != this.gap || round != this.round
        this.size = size; this.inset = inset; this.gap = gap; this.reach = reach
        this.dragStart = dragStart; this.contained = contained; this.round = round
        if (moved && mode != Mode.Hold && mode != Mode.Drag) place(selected)
    }

    private fun place(index: Int) {
        if (index < 0 || size == IntSize.Zero) return
        transient = false
        rest(index, box); show(box)
        pickup = 0f; pickupSpeed = 0f; stretch = 0f; mode = Mode.Rest
    }

    /** The app chose a slot: the pill travels there, lifting on the way, unless a finger has it. */
    fun sync(index: Int) {
        if (index == selected) return
        val was = selected
        selected = index
        if (index < 0 || size == IntSize.Zero || mode == Mode.Hold || mode == Mode.Drag) return
        if (was < 0 || width <= 0f || reduced) place(index) else travel(index, true, TrackTravelSeconds)
    }

    /** A slot chosen without a finger on the rail: the keyboard, or an accessibility action. */
    fun choose(index: Int) {
        if (index !in 0 until count || index == selected || !allowed(index)) return
        sync(index)
        commit(index)
    }

    fun press(x: Float, y: Float) {
        if (size == IntSize.Zero || count <= 0) return
        downX = x; downY = y; fingerX = x
        over = slotAt(x)
        dragging = false
        stretch = 0f
        grabbed = selected >= 0 && x >= left && x <= left + width
        grab = x - left
        correction = 0f
        if (reduced || over < 0) { mode = Mode.Rest; return }
        if (selected < 0) {
            // Nothing chosen: the lens rises under the finger, from nothing, ready to be carried.
            rest(over, box); show(box)
            transient = true; grabbed = true; grab = x - left
            pickup = 0f; pickupSpeed = 0f; pickupGoal = 1f
            box.copyInto(goal); speed.fill(0f)
            mode = Mode.Hold
            animate()
            return
        }
        current(box)
        if (grabbed || over == selected) {
            // The lens rises where it is, whatever it was doing when the finger landed.
            goal.indices.forEach { goal[it] = box[it] }
            pickupGoal = 1f
        } else {
            // A neighbour: the pill leans toward it, flat, the same small way however far it is.
            rest(selected, goal)
            val lean = (if (over > selected) 1f else -1f) * goal[2] * Pull
            if (over > selected) { goal[0] += lean * .15f; goal[2] += lean * .85f }
            else { goal[0] += lean; goal[2] += abs(lean) * .85f }
            pickupGoal = 0f
        }
        speed.fill(0f)
        mode = Mode.Hold
        animate()
    }

    fun move(x: Float, y: Float, velocityX: Float) {
        if (size == IntSize.Zero || count <= 0) return
        fingerX = x
        if (!dragging) {
            if (hypot(x - downX, y - downY) <= dragStart) return
            dragging = true
            if (hasPill && !reduced) {
                if (!grabbed) { grab = width / 2; correction = left - (x - grab) }
                pickupGoal = 1f
                mode = Mode.Drag
                animate()
            }
        }
        val slot = slotAt(x)
        if (slot != over) { over = slot; tick() }
        if (mode != Mode.Drag) return
        stretchGoal = (abs(velocityX) / StretchAt).coerceIn(0f, 1f) * StretchMax
        sinceMove = 0f
        carry()
        animate()
    }

    /** The finger carries the pill, at its full slot size, squashing against either end of the rail. */
    private fun carry() {
        val full = pillWidth()
        val tall = pillHeight()
        val rim = (slotWidth() - full) / 2
        val floor = inset + (slotHeight() - tall) / 2
        val ideal = fingerX - grab + correction
        val least = inset + rim
        val most = size.width - full - inset - rim
        var l = ideal; var w = full; var t = floor; var h = tall
        if (ideal < least) {
            val give = min(full * SquashMax, rubberband(least - ideal, full))
            l = least; w = full - give; h = tall + give * SquashBulge; t = floor - give * SquashBulge / 2
        } else if (ideal > most) {
            val give = min(full * SquashMax, rubberband(ideal - most, full))
            l = most + give; w = full - give; h = tall + give * SquashBulge; t = floor - give * SquashBulge / 2
        }
        left = l; top = t; width = w; height = h
    }

    /** [inside] is false when the finger slid well off the rail: that lets go without choosing. */
    fun release(x: Float, velocityX: Float, inside: Boolean) {
        val carried = dragging
        dragging = false
        if (count <= 0 || size == IntSize.Zero) return
        val target = when {
            !inside -> selected
            carried -> {
                val step = slotWidth() + gap
                val centre = left + width / 2 + velocityX * Projection
                if (step > 0f) ((centre - inset - slotWidth() / 2) / step).roundToInt().coerceIn(0, count - 1) else selected
            }
            else -> slotAt(x)
        }.let { if (it >= 0 && !allowed(it)) selected else it }
        // A stretched pill keeps its shape as the journey begins, then relaxes on the way.
        val w = width * (1 + stretch); val h = height * (1 - stretch * Squash)
        left -= (w - width) / 2; top += (height - h) / 2; width = w; height = h; stretch = 0f
        val lifted = grabbed || carried || pickup > .001f
        grabbed = false
        if (target >= 0 && target != selected) {
            val was = selected
            val floating = transient
            selected = target
            transient = false
            if (was < 0 && !floating || reduced) place(target) else travel(target, true, TrackTravelSeconds)
            commit(target)
        } else if (selected >= 0) {
            travel(selected, lifted, if (lifted) TrackSettleSeconds else TrackAnticipationSeconds)
            if (inside && !carried && target == selected) again(target)
        } else if (transient) sink()
    }

    /** A lens nothing was chosen with sinks back into the rail where it is, and is gone. */
    private fun sink() {
        current(box); box.copyInto(goal); speed.fill(0f)
        pickupGoal = 0f
        mode = Mode.Hold
        animate()
    }

    fun cancel() {
        val lifted = grabbed || dragging || pickup > .001f
        dragging = false; grabbed = false
        val w = width * (1 + stretch); val h = height * (1 - stretch * Squash)
        left -= (w - width) / 2; top += (height - h) / 2; width = w; height = h; stretch = 0f
        if (selected >= 0 && size != IntSize.Zero) travel(selected, lifted, if (lifted) TrackSettleSeconds else TrackAnticipationSeconds)
        else if (transient) sink()
    }

    private fun travel(index: Int, lifting: Boolean, seconds: Float) {
        rest(index, to)
        if (reduced) { place(index); return }
        current(from)
        elapsed = 0f; duration = seconds; startPickup = pickup; lifts = lifting
        pickupSpeed = 0f
        mode = Mode.Travel
        animate()
    }

    private fun animate() {
        if (job?.isActive == true) return
        job = scope.launch {
            var last = 0L
            while (true) {
                val now = withFrameNanos { it }
                // A slow frame makes the motion a little longer rather than skipping it: at most two
                // frames' worth of time pass per frame, so the first frames after a tap still show.
                val dt = if (last == 0L) 1f / 60 else ((now - last) / 1e9f).coerceIn(0f, 1f / 30)
                last = now
                if (!step(dt)) break
            }
        }
    }

    private fun step(dt: Float): Boolean = when (mode) {
        Mode.Rest -> false
        Mode.Hold -> {
            val settled = springToGoal(dt)
            stepPickup(pickup, pickupSpeed, pickupGoal, dt, scratch)
            pickup = scratch[0]; pickupSpeed = scratch[1]
            if (transient && pickupGoal == 0f && pickup == 0f && !dragging) { transient = false; mode = Mode.Rest }
            !(settled && pickup == pickupGoal)
        }
        Mode.Drag -> {
            correction *= exp(-dt / .04f)
            sinceMove += dt
            if (sinceMove > .06f) stretchGoal = 0f
            stretch += (stretchGoal - stretch) * (1 - (1 - StretchEasing).pow(dt * 60))
            stepPickup(pickup, pickupSpeed, pickupGoal, dt, scratch)
            pickup = scratch[0]; pickupSpeed = scratch[1]
            carry()
            // A finger holding still leaves nothing to animate: the clock sleeps until it moves again.
            !(abs(correction) < .5f && stretch < .0005f && stretchGoal == 0f && pickup == pickupGoal)
        }
        Mode.Travel -> {
            elapsed += dt
            val t = (elapsed / duration).coerceIn(0f, 1f)
            val e = travelEase(t)
            for (i in 0..3) box[i] = from[i] + (to[i] - from[i]) * e
            show(box)
            if (lifts) pickup = travelPickup(t, startPickup)
            if (t >= 1f) {
                show(to)
                if (lifts) pickup = 0f
                pickupSpeed = 0f
                mode = Mode.Rest
                false
            } else true
        }
    }

    /** One step of the hold's spring for the box; true once it has arrived. */
    private fun springToGoal(dt: Float): Boolean {
        current(box)
        val damping = 2 * HoldDamping * sqrt(HoldStiffness)
        var remaining = dt
        while (remaining > 0f) {
            val h = min(remaining, .004f)
            for (i in 0..3) {
                val a = -HoldStiffness * (box[i] - goal[i]) - damping * speed[i]
                speed[i] += a * h
                box[i] += speed[i] * h
            }
            remaining -= h
        }
        var settled = true
        for (i in 0..3) if (abs(box[i] - goal[i]) > .3f || abs(speed[i]) > 5f) settled = false
        if (settled) { goal.copyInto(box); speed.fill(0f) }
        show(box)
        return settled
    }
}
