package com.mani.orbit

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** A feathered band samples the scene below it, never its own output. */
@Composable
internal fun HomeScrollFrost(scene: GlassBackdrop, opacity: () -> Float, modifier: Modifier, pinnedEdge: Boolean = false) {
    val sample = rememberGraphicsLayer()
    val coordinates = remember { GlassCoordinates() }
    val density = LocalDensity.current
    val sampleBackdrop = LocalGlassQuality.current.quality != GlassQuality.READABILITY && !LocalGlassReadability.current.opaque
    val effect = remember(density) {
        if (Build.VERSION.SDK_INT >= 31) {
            val radius = with(density) { 16.dp.toPx() }
            val saturation = android.graphics.ColorMatrix().apply { setSaturation(1.1f) }
            android.graphics.RenderEffect.createColorFilterEffect(android.graphics.ColorMatrixColorFilter(saturation),
                android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP)).asComposeRenderEffect()
        } else null
    }
    SideEffect { sample.renderEffect = effect }
    Box(modifier.onPlaced(coordinates::placed).graphicsLayer {
        alpha = opacity(); compositingStrategy = CompositingStrategy.Offscreen
    }.drawWithCache {
        val matrix = Matrix()
        val bleed = 48.dp.toPx()
        val ink = Color(0xFF0B0A0F)
        val tint = if (pinnedEdge) Brush.verticalGradient(0f to ink, .15f to ink.copy(alpha = .96f), .6f to ink.copy(alpha = .3f), 1f to Color.Transparent)
            else Brush.verticalGradient(0f to Color.Transparent, .286f to ink, .52f to ink.copy(alpha = .55f), 1f to Color.Transparent)
        val mask = if (pinnedEdge) Brush.verticalGradient(0f to Color.Black, .2f to Color.Black, .65f to Color.Black.copy(alpha = .35f), 1f to Color.Transparent)
            else Brush.verticalGradient(0f to Color.Transparent, .286f to Color.Black, .48f to Color.Black, 1f to Color.Transparent)
        onDrawBehind {
            if (opacity() > 0f) {
                if (effect != null && sampleBackdrop && scene.coordinates.transformTo(coordinates, matrix)) {
                    sample.record(size = IntSize(ceil(size.width + 2 * bleed).toInt(), ceil(size.height + 2 * bleed).toInt())) {
                        withTransform({ translate(bleed, bleed); transform(matrix) }) { drawLayer(scene.layer) }
                    }
                    translate(-bleed, -bleed) { drawLayer(sample) }
                }
                drawRect(tint)
                drawRect(mask, blendMode = BlendMode.DstIn)
            }
        }
    })
}
