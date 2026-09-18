package com.mani.orbit

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mani.orbit.backdrop.drawBackdrop
import com.mani.orbit.backdrop.effects.blur
import com.mani.orbit.backdrop.effects.colorControls

/** A feathered band samples the scene below it, never its own output. */
@Composable
internal fun HomeScrollFrost(scene: GlassBackdrop, opacity: () -> Float, modifier: Modifier, pinnedEdge: Boolean = false) {
    val density = LocalDensity.current
    val sampleBackdrop = LocalGlassQuality.current.quality != GlassQuality.READABILITY &&
        !LocalGlassReadability.current.opaque && BlurSupported
    val blurPx = with(density) { 16.dp.toPx() } * GlassResolution
    val ink = PageInk
    val tint = if (pinnedEdge) Brush.verticalGradient(0f to ink, .15f to ink.copy(alpha = .96f), .6f to ink.copy(alpha = .3f), 1f to Color.Transparent)
        else Brush.verticalGradient(0f to Color.Transparent, .286f to ink, .52f to ink.copy(alpha = .55f), 1f to Color.Transparent)
    val mask = if (pinnedEdge) Brush.verticalGradient(0f to Color.Black, .2f to Color.Black, .65f to Color.Black.copy(alpha = .35f), 1f to Color.Transparent)
        else Brush.verticalGradient(0f to Color.Transparent, .286f to Color.Black, .48f to Color.Black, 1f to Color.Transparent)
    // The feather masks the sampled band and its tint together, so it is applied outside both. A band
    // nothing has scrolled under is invisible and draws nothing at all: kept as a layer, it re-rendered
    // its blur of the page on every frame the Home ring moved and every frame of a deck swipe.
    Box(modifier
        .graphicsLayer {
            val shown = opacity()
            alpha = shown
            compositingStrategy = if (shown > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        }
        .drawWithContent { if (opacity() > 0f) { drawContent(); drawRect(mask, blendMode = BlendMode.DstIn) } }
        .then(
            if (sampleBackdrop) Modifier.drawBackdrop(
                backdrop = scene.layer,
                shape = { RectangleShape },
                effects = { colorControls(saturation = 1.1f); blur(blurPx) },
                highlight = null,
                shadow = null,
                onDrawSurface = { drawRect(tint) },
                backdropScale = GlassResolution,
            ) else Modifier.drawBehind { drawRect(tint) }
        ))
}
