package com.mani.orbit

import com.mani.orbit.sync.TraceQualityReason
import com.mani.orbit.sync.TraceTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-only policy. Frame deadlines come from Android, never a presumed display refresh rate. */
internal class GlassQualityPolicy(private val opticalSupported: Boolean, private val frostSupported: Boolean = true) {
    // Open at the best tier the platform supports and let evidence lower it. Starting cheap and
    // climbing after five seconds means the owner watches the material change under their hands.
    private val initial = if (!frostSupported) GlassQuality.READABILITY
        else if (opticalSupported) GlassQuality.OPTICAL else GlassQuality.FROST
    private val value = MutableStateFlow(GlassQualityState(initial, TraceQualityReason.OBSERVING))
    val state = value.asStateFlow()
    private var maximum = initial
    private var boundaryReason = TraceQualityReason.OBSERVING
    private var held = false
    private var pending: GlassQualityState? = null
    private var lastFrame = 0L
    private var goodSince = 0L
    private var goodFrames = 0
    private var missedRun = 0

    /**
     * The material is the app. Nothing measured here may replace it with a flat fill.
     *
     * READABILITY is the accessibility and unsupported-platform tier: the owner's own reduce
     * transparency preference, a platform with no blur, or their own battery saver. Heat and frame
     * pressure cost the refraction and leave the glass, because a surface that silently turns into
     * a painted rectangle is the inconsistency, not a saving.
     *
     * Thermal headroom is a forecast, not the signal. A device that reports its thermal status but
     * no headroom (Samsung among them) still carries enough evidence for full optics; without the
     * forecast the policy simply relies on the status and on the frame evidence below.
     */
    @Synchronized fun conditions(thermalStatus: Int?, headroomKnown: Boolean, powerSaver: Boolean) {
        val cap: GlassQuality
        val reason: TraceQualityReason
        when {
            !frostSupported -> { cap = GlassQuality.READABILITY; reason = TraceQualityReason.UNSUPPORTED }
            powerSaver -> { cap = GlassQuality.READABILITY; reason = TraceQualityReason.POWER_SAVER }
            !opticalSupported -> { cap = GlassQuality.FROST; reason = TraceQualityReason.UNSUPPORTED }
            thermalStatus != null && thermalStatus >= 1 -> { cap = GlassQuality.FROST; reason = TraceQualityReason.THERMAL }
            thermalStatus == null -> { cap = GlassQuality.FROST; reason = TraceQualityReason.THERMAL_UNKNOWN }
            else -> { cap = GlassQuality.OPTICAL; reason = if (headroomKnown) TraceQualityReason.MEASURED else TraceQualityReason.THERMAL_UNKNOWN }
        }
        if (cap != maximum || reason != boundaryReason) { maximum = cap; boundaryReason = reason; clearEvidence(); pending = null }
        if (value.value.quality.ordinal < cap.ordinal) request(cap, reason, urgent = cap == GlassQuality.READABILITY)
        else if (value.value.quality == cap && reason != TraceQualityReason.MEASURED) request(cap, reason)
    }

    @Synchronized fun touch(active: Boolean) {
        held = active
        if (!active) { pending?.let { publish(it.quality, it.reason) }; pending = null }
    }

    @Synchronized fun pause() { held = false; pending = null; clearEvidence(); lastFrame = 0L }

    @Synchronized fun frame(start: Long, total: Long, deadline: Long, rendered: TraceTier) {
        if (start <= lastFrame || total < 0 || deadline <= 0 || total > 60_000_000_000L || deadline > 60_000_000_000L) return
        // Do not promote based on an opaque accessibility override or a different renderer's cheap frames.
        if (rendered != value.value.quality.traceTier) { clearEvidence(); lastFrame = start; return }
        if (lastFrame > 0 && start - lastFrame > 500_000_000L) clearEvidence()
        lastFrame = start
        if (total >= deadline) {
            goodSince = 0; goodFrames = 0; missedRun++
            // Frame pressure costs the refraction and stops there. Dropping the blur as well would
            // swap the material for a painted rectangle mid-gesture, which is worse than a late frame.
            if (missedRun >= 3) {
                if (value.value.quality == GlassQuality.OPTICAL) request(GlassQuality.FROST, TraceQualityReason.FRAME_PRESSURE)
                missedRun = 0
            }
        } else {
            missedRun = 0
            if (goodSince == 0L) goodSince = start
            goodFrames = (goodFrames + 1).coerceAtMost(30)
            // Deliberately asymmetric: a sustained missed-deadline run lowers fidelity; recovery needs
            // five seconds of timely rendered frames, not five seconds spent idle without evidence.
            if (start - goodSince >= 5_000_000_000L && goodFrames >= 30 && value.value.quality.ordinal > maximum.ordinal) {
                val higher = if (value.value.quality == GlassQuality.READABILITY) GlassQuality.FROST else GlassQuality.OPTICAL
                request(higher, TraceQualityReason.MEASURED)
            }
        }
    }

    private fun request(quality: GlassQuality, reason: TraceQualityReason, urgent: Boolean = false) {
        if (held && !urgent) {
            val prior = pending
            if (prior == null || quality.ordinal >= prior.quality.ordinal) pending = GlassQualityState(quality, reason)
        } else publish(quality, reason)
    }
    private fun publish(quality: GlassQuality, reason: TraceQualityReason) {
        if (value.value.quality != quality || value.value.reason != reason) {
            value.value = GlassQualityState(quality, reason); clearEvidence()
        }
    }
    private fun clearEvidence() { goodSince = 0; goodFrames = 0; missedRun = 0 }
}
