package com.mani.orbit

import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** Samples the page's retained display list, never the floating controls themselves. */
@Composable
internal fun Modifier.orbitFrost(page: GlassBackdrop, corner: Dp, engagement: () -> Float = { 0f },
    focus: () -> Offset = { Offset(.5f, .5f) }): Modifier {
    val sample = rememberGraphicsLayer()
    val coordinates = remember { GlassCoordinates() }
    val density = LocalDensity.current
    val readability = LocalGlassReadability.current
    val reduced = LocalOrbitReducedMotion.current
    val rendering = LocalGlassQuality.current
    val quality = rendering.quality
    val view = androidx.compose.ui.platform.LocalView.current
    val opaque = readability.opaque || quality == GlassQuality.READABILITY
    val optics = androidx.compose.animation.core.animateFloatAsState(if (quality == GlassQuality.OPTICAL) 1f else 0f,
        androidx.compose.animation.core.tween(if (reduced) 0 else 180), label = "Optical fidelity")
    val lens = remember(density.density) { GlassLens(density.density) }
    // The final draw boundary isolates foreground invalidation from this material's own drawing.
    return onPlaced(coordinates::placed).drawWithCache {
        val matrix = Matrix()
        val bleed = lens.bleed
        val radius = corner.toPx()
        val tint = Brush.verticalGradient(listOf(Color.White.copy(alpha = .05f), Color.White.copy(alpha = .01f)))
        // Stable local backing, independent of sampled brightness. No dark/light style threshold can flutter.
        // The 8dp edge feather stays fixed as Explore expands; every label lies inside the plateau.
        val feather = (8.dp.toPx() / size.height.coerceAtLeast(1f)).coerceAtMost(.5f)
        val shieldAlpha = if (opaque) readability.contrast * .45f else .70f + readability.contrast * .20f
        val shield = Color(0xFF121212).copy(alpha = shieldAlpha)
        val backing = Brush.verticalGradient(0f to Color.Transparent, feather to shield,
            (1f - feather) to shield, 1f to Color.Transparent)
        onDrawBehind {
            val sampling = Build.VERSION.SDK_INT >= 31 && !opaque && page.coordinates.transformTo(coordinates, matrix)
            val actual = when {
                !sampling -> GlassQuality.READABILITY
                !lens.supported || optics.value == 0f -> GlassQuality.FROST
                else -> GlassQuality.OPTICAL
            }
            val reason = when {
                readability.opaque -> com.mani.orbit.sync.TraceQualityReason.PREFERENCE
                Build.VERSION.SDK_INT < 31 || sampling && !lens.supported -> com.mani.orbit.sync.TraceQualityReason.UNSUPPORTED
                else -> rendering.reason
            }
            com.mani.orbit.sync.DiagnosticApplication.quality(view, actual.traceTier, reason)
            if (sampling) {
                sample.renderEffect = lens.effect(size.width, size.height, radius, if (reduced) 0f else engagement(), focus(), optics.value)
                // Padding supplies real pixels beyond every edge before the rounded foreground clip.
                sample.record(size = IntSize(ceil(size.width + bleed * 2).toInt(), ceil(size.height + bleed * 2).toInt())) {
                    withTransform({ translate(bleed, bleed); transform(matrix) }) { drawLayer(page.layer) }
                }
                translate(-bleed, -bleed) { drawLayer(sample) }
                drawRect(Color(0xFF121212).copy(alpha = .40f))
            } else drawRect(Color(0xFF28262E))
            drawRect(tint)
            drawRect(backing)
        }
    }.graphicsLayer()
}
