package com.mani.orbit

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mani.orbit.backdrop.drawBackdrop
import com.mani.orbit.backdrop.effects.blur
import com.mani.orbit.backdrop.effects.colorControls
import com.mani.orbit.backdrop.effects.lens
import com.mani.orbit.backdrop.highlight.Highlight
import com.mani.orbit.backdrop.highlight.HighlightStyle
import com.mani.orbit.backdrop.shadow.Shadow

/*
 * Orbit's material is the Kyant0/backdrop pipeline vendored under [com.mani.orbit.backdrop]:
 * saturation, blur and a real rounded-rectangle refraction, with a rim highlight that carries the
 * light and a shadow the surface casts. The same library and the same arrangement of it are what
 * the owner's BitChord uses, and this is a deliberate match to it.
 */

/** Tuning. Blur and lens sizes are in surface pixels and are pre-scaled by [GlassResolution]. */
private const val VIBRANCY = .6f
private const val BLUR_RADIUS_DP = 10f
private const val LENS_HEIGHT_DP = 12.6f
private const val LENS_AMOUNT_DP = 10.1f
private const val SURFACE_OPACITY = .40f

/**
 * Fraction of the surface resolution the backdrop is recorded and processed at.
 *
 * A third is nine times fewer pixels through the colour matrix, the blur and the refraction, on
 * every floating surface at once. The blur is what hides the upscale; pixel-sized effect parameters
 * are pre-multiplied by the same factor.
 */
internal const val GlassResolution = .33f

/** How much fuller a lifted surface is than one at rest. */
private const val LIFT_BLUR = .5f
private const val LIFT_LENS_HEIGHT = 2.2f
private const val LIFT_LENS_AMOUNT = 2.6f
private const val LIFT_RIM_DP = 1f
private const val LIFT_SHADOW_DP = 14f
private const val LIFT_SHADOW_ALPHA = .20f
private const val LIFT_THINNING = .4f

/** Past this much lift the rim splits the light into colour, as a thick lens does. */
private const val LIFT_DISPERSION_FROM = .3f

/** The hairline that stands in for the glass rim wherever the glass itself is not drawn. */
internal val GlassEdgeWidth = .5.dp
internal val GlassEdgeColor = Color.White.copy(alpha = .10f)

/** Refraction needs a runtime shader; the blur alone needs only a render effect. */
internal val LensSupported: Boolean get() = Build.VERSION.SDK_INT >= 33
internal val BlurSupported: Boolean get() = Build.VERSION.SDK_INT >= 31

/**
 * Samples the page's retained display list, never the floating controls themselves.
 *
 * [engagement] is how far this surface has lifted off the page, 0..1, read at draw time so it can
 * be an animation. A lifted surface is a thicker piece of glass: it bends more of what is behind
 * it, deeper in, splits the light at its rim once it is well up, carries a wider rim and a deeper
 * shadow, and thins its own tint so more of the page shows through. [focus] is where the contact
 * is inside the surface; the rim's light turns with it, the way light moves around real glass as
 * the glass moves.
 */
@Composable
internal fun Modifier.orbitFrost(page: GlassBackdrop, corner: Dp, engagement: () -> Float = { 0f },
    focus: () -> Offset = { Offset(.5f, .5f) }, tint: Color = Color(0xFF121212),
    shield: Boolean = true, opacity: Float = SURFACE_OPACITY): Modifier {
    val density = LocalDensity.current
    val readability = LocalGlassReadability.current
    val reduced = LocalOrbitReducedMotion.current
    val rendering = LocalGlassQuality.current
    val quality = rendering.quality
    val view = androidx.compose.ui.platform.LocalView.current
    val opaque = readability.opaque || quality == GlassQuality.READABILITY || !BlurSupported
    val shape = remember(corner) { RoundedCornerShape(corner) }
    val optics = androidx.compose.animation.core.animateFloatAsState(
        if (quality == GlassQuality.OPTICAL && LensSupported) 1f else 0f,
        androidx.compose.animation.core.tween(if (reduced) 0 else 180), label = "Optical fidelity")

    val reason = when {
        readability.opaque -> com.mani.orbit.sync.TraceQualityReason.PREFERENCE
        !BlurSupported || quality == GlassQuality.OPTICAL && !LensSupported ->
            com.mani.orbit.sync.TraceQualityReason.UNSUPPORTED
        else -> rendering.reason
    }
    if (opaque) {
        com.mani.orbit.sync.DiagnosticApplication.quality(view, GlassQuality.READABILITY.traceTier, reason)
        // The readability fill needs the same draw boundary as the glass: a ticking foreground
        // must not redraw the surface it sits on in any tier.
        return background(if (tint == Color(0xFF121212)) Color(0xFF28262E) else tint, shape)
            .border(GlassEdgeWidth, GlassEdgeColor, shape).graphicsLayer()
    }

    val blurPx = with(density) { BLUR_RADIUS_DP.dp.toPx() } * GlassResolution
    val lensHeightPx = with(density) { LENS_HEIGHT_DP.dp.toPx() } * GlassResolution
    val lensAmountPx = with(density) { LENS_AMOUNT_DP.dp.toPx() } * GlassResolution
    // Stable local backing, independent of sampled brightness. No dark/light threshold can flutter,
    // and every label lies inside the plateau between the two 8dp feathers.
    val shieldAlpha = .70f + readability.contrast * .20f
    val shieldColor = tint.copy(alpha = shieldAlpha)
    val featherPx = with(density) { 8.dp.toPx() }

    // Two boundaries: the leading layer keeps this material's own redraws off whatever encloses it,
    // and the trailing one keeps a ticking foreground from redrawing the material.
    return graphicsLayer().drawBackdrop(
        backdrop = page.layer,
        shape = { shape },
        effects = {
            val lifted = lift(engagement, reduced)
            colorControls(saturation = 1f + .5f * VIBRANCY)
            blur(blurPx * (1f + LIFT_BLUR * lifted))
            if (optics.value > 0f) {
                lens(
                    refractionHeight = lensHeightPx * optics.value * (1f + LIFT_LENS_HEIGHT * lifted),
                    refractionAmount = lensAmountPx * optics.value * (1f + LIFT_LENS_AMOUNT * lifted),
                    depthEffect = true,
                    chromaticAberration = lifted >= LIFT_DISPERSION_FROM,
                )
            }
        },
        highlight = {
            val lifted = lift(engagement, reduced)
            Highlight(
                width = (GlassEdgeWidth.value + LIFT_RIM_DP * lifted).dp,
                style = HighlightStyle.Default(angle = lightAngle(focus, lifted)),
            )
        },
        shadow = {
            val lifted = lift(engagement, reduced)
            Shadow(radius = (24f + LIFT_SHADOW_DP * lifted).dp,
                color = Color.Black.copy(alpha = .10f + LIFT_SHADOW_ALPHA * lifted))
        },
        onDrawSurface = {
            val lifted = lift(engagement, reduced)
            val tier = if (optics.value > 0f) GlassQuality.OPTICAL else GlassQuality.FROST
            com.mani.orbit.sync.DiagnosticApplication.quality(view, tier.traceTier, reason)
            drawRect(tint.copy(alpha = opacity * (1f - LIFT_THINNING * lifted)))
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .05f), Color.White.copy(alpha = .01f))))
            if (shield) {
                val feather = (featherPx / size.height.coerceAtLeast(1f)).coerceAtMost(.5f)
                drawRect(Brush.verticalGradient(0f to Color.Transparent, feather to shieldColor,
                    (1f - feather) to shieldColor, 1f to Color.Transparent))
            }
        },
        backdropScale = GlassResolution,
    // The final draw boundary isolates foreground invalidation from this material's own drawing:
    // a timer ticking inside the surface must not redraw the glass it sits on.
    ).graphicsLayer()
}

/** Manual motion control owns this: a paused material sits at rest rather than tracking a finger. */
private fun lift(engagement: () -> Float, reduced: Boolean): Float =
    if (reduced) 0f else engagement().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f

/**
 * The rim light turns with the contact rather than staying pinned to one corner.
 *
 * The deflection scales with the lift, so a surface at rest always carries the same rim: a released
 * material returns to exactly the pose it had before the contact, wherever the finger left it.
 */
private fun lightAngle(focus: () -> Offset, lifted: Float): Float {
    val point = focus()
    val x = point.x.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .5f
    return 45f + (x - .5f) * 60f * lifted
}
