package com.mani.orbit

import com.mani.orbit.sync.TraceQualityReason
import com.mani.orbit.sync.TraceTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-only policy. Frame deadlines come from Android, never a presumed display refresh rate. */
internal class GlassQualityPolicy(private val opticalSupported: Boolean, private val frostSupported: Boolean = true) {
    private val initial = if (frostSupported) GlassQuality.FROST else GlassQuality.READABILITY
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

    @Synchronized fun conditions(thermalStatus: Int?, headroomKnown: Boolean, powerSaver: Boolean) {
        val cap: GlassQuality
        val reason: TraceQualityReason
        when {
            !frostSupported -> { cap = GlassQuality.READABILITY; reason = TraceQualityReason.UNSUPPORTED }
            powerSaver -> { cap = GlassQuality.READABILITY; reason = TraceQualityReason.POWER_SAVER }
            thermalStatus != null && thermalStatus >= 3 -> { cap = GlassQuality.READABILITY; reason = TraceQualityReason.THERMAL }
            thermalStatus != null && thermalStatus >= 1 -> { cap = GlassQuality.FROST; reason = TraceQualityReason.THERMAL }
            !headroomKnown || thermalStatus == null -> { cap = GlassQuality.FROST; reason = TraceQualityReason.THERMAL_UNKNOWN }
            !opticalSupported -> { cap = GlassQuality.FROST; reason = TraceQualityReason.UNSUPPORTED }
            else -> { cap = GlassQuality.OPTICAL; reason = TraceQualityReason.MEASURED }
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
            if (missedRun >= 3) {
                val lower = when (value.value.quality) {
                    GlassQuality.OPTICAL -> GlassQuality.FROST
                    else -> GlassQuality.READABILITY
                }
                request(lower, TraceQualityReason.FRAME_PRESSURE)
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
