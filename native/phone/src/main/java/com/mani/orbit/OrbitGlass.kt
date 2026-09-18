package com.mani.orbit

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
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
 *
 * There is one material and one control surface in this app, and every panel and every button in
 * every route uses them. A per-surface tint or opacity is what made the app read as two different
 * apps, so the knobs are not parameters any more: they are the constants below.
 */

/** The single glass shade. Every panel in every route carries exactly this tint and opacity. */
internal val GlassTint = Color(0xFF15141A)
private const val SURFACE_OPACITY = .42f

/**
 * How much of the backdrop's own brightness reaches the surface.
 *
 * This is the legibility rule, and it is multiplicative rather than a plate laid over the backdrop.
 * A bright page is tamed while a dark one is untouched, and the structure the refraction bends
 * survives either way, so what passes under a surface still reads through it. Orbit previously
 * bought the same legibility with an opaque backing under the floating bar's labels: that hides the
 * backdrop instead of dimming it, which is paint rather than glass, and it applied to one surface
 * and not the others.
 *
 * At this factor the muted caption keeps at least 5:1 against anything the surface can be over,
 * including pure white, while a chart passing beneath a bar still reads through it.
 */
private const val BACKDROP_DIM = .30f

/** Tuning. Blur and lens sizes are in surface pixels and are pre-scaled by [GlassResolution]. */
private const val VIBRANCY = .6f
private const val BLUR_RADIUS_DP = 10f
private const val LENS_HEIGHT_DP = 12.6f
private const val LENS_AMOUNT_DP = 10.1f

/**
 * Fraction of the surface resolution the backdrop is recorded and processed at.
 *
 * A third is nine times fewer pixels through the colour matrix, the blur and the refraction, on
 * every floating surface at once. The blur is what hides the upscale; pixel-sized effect parameters
 * are pre-multiplied by the same factor.
 */
internal const val GlassResolution = .33f

/**
 * How much fuller a lifted surface is than one at rest.
 *
 * The blur is not on this list, and that is measured rather than assumed. Blur models frosting, not
 * thickness: growing it while the tint thins washed the page out faster than the thinner tint let it
 * through, so picking a surface up showed *less* of what was behind it than leaving it at rest. Lift
 * says "thicker" through the refraction, the rim, the dispersion, the shadow and the tint instead.
 */
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

/** The one fill the material falls back to. Two fallback colours read as two different materials. */
internal val GlassOpaqueFill = Color(0xFF1C1B22)

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
    focus: () -> Offset = { Offset(.5f, .5f) }): Modifier {
    val density = LocalDensity.current
    val readability = LocalGlassReadability.current
    val reduced = LocalOrbitReducedMotion.current
    val rendering = LocalGlassQuality.current
    val quality = rendering.quality
    val view = androidx.compose.ui.platform.LocalView.current
    val opaque = readability.opaque || quality == GlassQuality.READABILITY || !BlurSupported
    val shape = remember(corner) { RoundedCornerShape(corner) }
    val optics = animateFloatAsState(
        if (quality == GlassQuality.OPTICAL && LensSupported) 1f else 0f,
        tween(if (reduced) 0 else 180), label = "Optical fidelity")

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
        return background(GlassOpaqueFill, shape).border(GlassEdgeWidth, GlassEdgeColor, shape).graphicsLayer()
    }

    val blurPx = with(density) { BLUR_RADIUS_DP.dp.toPx() } * GlassResolution
    val lensHeightPx = with(density) { LENS_HEIGHT_DP.dp.toPx() } * GlassResolution
    val lensAmountPx = with(density) { LENS_AMOUNT_DP.dp.toPx() } * GlassResolution
    // The dim above carries the default legibility. An explicit contrast preference asks for more
    // than legible, so it adds a backing on top of it; at the default setting there is none.
    val backing = readability.contrast * .7f

    // Two boundaries: the leading layer keeps this material's own redraws off whatever encloses it,
    // and the trailing one keeps a ticking foreground from redrawing the material.
    return graphicsLayer().drawBackdrop(
        backdrop = page.layer,
        shape = { shape },
        effects = {
            val lifted = lift(engagement, reduced)
            // brightness cancels the contrast term's mid-grey pivot, leaving a pure multiply.
            colorControls(brightness = -(1f - BACKDROP_DIM) / 2f, contrast = BACKDROP_DIM,
                saturation = 1f + .5f * VIBRANCY)
            blur(blurPx)
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
            drawRect(GlassTint.copy(alpha = SURFACE_OPACITY * (1f - LIFT_THINNING * lifted)))
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .05f), Color.White.copy(alpha = .01f))))
            if (backing > 0f) drawRect(GlassTint.copy(alpha = backing))
        },
        backdropScale = GlassResolution,
    // The final draw boundary isolates foreground invalidation from this material's own drawing:
    // a timer ticking inside the surface must not redraw the glass it sits on.
    ).graphicsLayer()
}

/**
 * Every in-page panel: it finds the page recording itself, so no route decides what a panel is.
 *
 * Where a route has no recording at all — a preview, a unit fixture — the panel falls back to the
 * same single fill the material uses, rather than to a colour of its own.
 */
@Composable
internal fun Modifier.orbitPanel(corner: Dp, engagement: () -> Float = { 0f },
    focus: () -> Offset = { Offset(.5f, .5f) }): Modifier {
    val backdrop = LocalPageBackdrop.current
    val shape = remember(corner) { RoundedCornerShape(corner) }
    return if (backdrop == null) background(GlassOpaqueFill, shape).border(GlassEdgeWidth, GlassEdgeColor, shape)
    else orbitFrost(backdrop, corner, engagement, focus)
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

/*
 * Controls are not glass, and that is the rule rather than an omission.
 *
 * A button sits on a panel. Glass samples the page behind the panel, so a glass button would show
 * what the panel is already hiding and read as a hole punched through it. Every control in the app
 * therefore shares one fill, one rim and one press: they sit on the material, and the material is
 * what floats.
 */
internal val ControlFill = Color.White.copy(alpha = .05f)

/** The one step up from a resting control: a selected tab, a carried thumb, an engaged chip. */
internal val ControlSelectedFill = Color.White.copy(alpha = .10f)
internal val ControlEdge = Color.White.copy(alpha = .09f)

/**
 * [fill] carries the control's role: the shared resting fill, [ControlSelectedFill] for a selected
 * one, an accent colour for a filled action, or [Color.Transparent] for a plain text control. The
 * press is the same in every case, so the whole app answers a finger identically.
 */
@Composable
internal fun Modifier.orbitControl(corner: Dp, interaction: MutableInteractionSource,
    enabled: Boolean = true, fill: Color = ControlFill): Modifier {
    val reduced = LocalOrbitReducedMotion.current
    val pressed by interaction.collectIsPressedAsState()
    val down = pressed && enabled
    val shape = remember(corner) { RoundedCornerShape(corner) }
    val plain = fill == Color.Transparent
    val edge = if (fill == ControlFill || fill == ControlSelectedFill) ControlEdge else fill
    val press = animateFloatAsState(if (down && !reduced) OrbitPressScale else 1f,
        if (reduced) tween(0) else tween(OrbitPressMillis, easing = OrbitPressEasing), label = "Control press")
    val contact = animateFloatAsState(if (down) 1f else 0f, orbitEngage(reduced), label = "Control contact")
    return graphicsLayer { scaleX = press.value; scaleY = press.value }
        .clip(shape)
        .then(if (plain) Modifier else Modifier.background(fill, shape).border(GlassEdgeWidth, edge, shape))
        .drawWithContent {
            drawRect(Color.White.copy(alpha = contact.value.coerceIn(0f, 1f) * .09f))
            drawContent()
        }
}
