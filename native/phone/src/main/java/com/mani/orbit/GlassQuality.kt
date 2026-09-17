package com.mani.orbit

import androidx.compose.runtime.staticCompositionLocalOf
import com.mani.orbit.sync.TraceQualityReason
import com.mani.orbit.sync.TraceTier

/** Only decorative rendering may change. Data, geometry and input ownership never depend on this. */
internal enum class GlassQuality { OPTICAL, FROST, READABILITY }
internal data class GlassQualityState(val quality: GlassQuality, val reason: TraceQualityReason = TraceQualityReason.DEFAULT)
internal val LocalGlassQuality = staticCompositionLocalOf { GlassQualityState(GlassQuality.OPTICAL) }
internal val GlassQuality.traceTier get() = when (this) {
    GlassQuality.OPTICAL -> TraceTier.PHONE_OPTICAL
    GlassQuality.FROST -> TraceTier.PHONE_RETAINED_FROST
    GlassQuality.READABILITY -> TraceTier.PHONE_READABILITY
}
